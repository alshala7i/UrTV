package sa.urtv.setup;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * One ADB connection over Wi-Fi (the TV's port 5555), the protocol adbd speaks: CNXN, AUTH, then one OPEN stream
 * per shell command. Only what the setup needs, one command at a time, like optimize/tv.py. Plain Java, so
 * tests/AdbTest.java runs it against a pretend TV (tests/fake_adbd.py).
 */
final class Adb implements Closeable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
        CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001, MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** The TV refused or never answered the «Allow USB debugging?» question. */
    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    /** The TV answered with STLS: it only takes Wireless debugging with a pairing code, which this app can't do yet. */
    static final class TlsOnlyException extends IOException {
        TlsOnlyException() { super("this TV only takes Wireless debugging with a pairing code (TLS), not port 5555 alone"); }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int maxData = 4096;
    private int nextId = 1;
    /** what the TV said about itself in CNXN, e.g. "device::ro.product.model=…;" */
    String banner = "";

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Connects and logs in with this phone's key. The first time, the TV shows «Allow USB debugging?»: that wait
     * is authMs (the person has to walk to the TV), everything else is ioMs.
     */
    static Adb connect(String host, int port, AdbKey key, int ioMs, int authMs) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        try {
            s.connect(new InetSocketAddress(host, port), Math.min(ioMs, 10000));
            s.setSoTimeout(ioMs);
            Adb a = new Adb(s);
            a.handshake(key, ioMs, authMs);
            return a;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void handshake(AdbKey key, int ioMs, int authMs) throws IOException {
        send(CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, offered = false;
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (SocketTimeoutException e) {
                if (offered) throw new AuthException("no answer to «Allow USB debugging?» on the TV");
                throw e;
            }
            if (m.cmd == CNXN) {
                maxData = Math.max(1024, Math.min(m.arg1, MAX_PAYLOAD));
                banner = new String(m.data, StandardCharsets.UTF_8);
                socket.setSoTimeout(ioMs);
                return;
            }
            if (m.cmd == STLS) throw new TlsOnlyException();
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) continue;
            try {
                if (!signed) {   // a key the TV already trusts: in at once
                    send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    signed = true;
                } else if (!offered) {   // a new key: the TV asks the person
                    send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKey("urtv@phone"));
                    offered = true;
                    socket.setSoTimeout(authMs);
                } else {
                    throw new AuthException("the TV refused this phone (Allow was not pressed)");
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("could not sign the TV's login token", e);
            }
        }
    }

    /** The longest message this TV takes: a command (with its upload piece) must fit in it. */
    int maxData() { return maxData; }

    /** Runs one command in the TV's shell and returns everything it printed (stdout and stderr together). */
    String shell(String cmd) throws IOException {
        return service("shell:" + cmd);
    }

    /** reboot: the connection drops as the TV goes down; that is expected and not an error. */
    void reboot() {
        try { service("reboot:"); } catch (IOException ignored) { }
    }

    private String service(String name) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, (name + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;   // a late message of an earlier stream
            if (m.cmd == OKAY) {
                remote = m.arg0;
            } else if (m.cmd == WRTE) {
                remote = m.arg0;
                buf.write(m.data);
                send(OKAY, local, remote, null);
            } else if (m.cmd == CLSE) {
                if (remote != 0) send(CLSE, local, remote, null);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Msg read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt();   // checksum: TCP already guards the bytes
        int magic = b.getInt();
        if (magic != (m.cmd ^ 0xffffffff) || len < 0 || len > 16 * 1024 * 1024)
            throw new IOException("not an ADB answer (is something else on this port?)");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        if (len > maxData && cmd != CNXN && cmd != AUTH) throw new IOException("command too long for this TV");
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;   // older adbd checks it
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        if (len > 0) b.put(data);
        out.write(b.array());
        out.flush();
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
