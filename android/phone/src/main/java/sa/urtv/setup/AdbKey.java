package sa.urtv.setup;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * This phone's ADB key: made once and kept in the app's files, so the TV asks «Allow USB debugging?» only the
 * first time («Always allow»). Plain Java (no android.*), so tests/AdbTest.java runs it on a computer.
 */
final class AdbKey {
    // ASN.1 DigestInfo of a SHA-1 hash: adbd signs and checks the 20-byte token as if it were one
    private static final byte[] SHA1_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    };

    private final RSAPrivateKey priv;
    private final RSAPublicKey pub;

    private AdbKey(RSAPrivateKey priv, RSAPublicKey pub) { this.priv = priv; this.pub = pub; }

    /** The key in this folder, made on first use. */
    static AdbKey load(File dir) throws Exception {
        File privFile = new File(dir, "adbkey"), pubFile = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (!privFile.isFile() || !pubFile.isFile()) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            dir.mkdirs();
            Files.write(privFile.toPath(), kp.getPrivate().getEncoded());
            Files.write(pubFile.toPath(), kp.getPublic().getEncoded());
        }
        return new AdbKey(
            (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privFile.toPath()))),
            (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath()))));
    }

    /** The token signed the way adbd checks it: PKCS#1 v1.5 padding around SHA-1's DigestInfo, built by hand. */
    byte[] sign(byte[] token) throws Exception {
        int len = (priv.getModulus().bitLength() + 7) / 8;
        byte[] block = new byte[len];
        int pad = len - 3 - SHA1_INFO.length - token.length;
        block[0] = 0x00;
        block[1] = 0x01;
        for (int i = 0; i < pad; i++) block[2 + i] = (byte) 0xff;
        block[2 + pad] = 0x00;
        System.arraycopy(SHA1_INFO, 0, block, 3 + pad, SHA1_INFO.length);
        System.arraycopy(token, 0, block, 3 + pad + SHA1_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        return c.doFinal(block);
    }

    /** The public key as adbd stores it (Android's mincrypt layout, base64) with a name, NUL-terminated. */
    byte[] publicKey(String name) {
        BigInteger n = pub.getModulus();
        int words = n.bitLength() / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
