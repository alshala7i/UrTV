#!/usr/bin/env python3
"""A pretend TV for tests/AdbTest.java: speaks adbd's side of the ADB protocol on 127.0.0.1 and runs each shell
command in this computer's sh (inside a scratch folder), so the phone app's ADB code is tested end to end.

    python3 tests/fake_adbd.py PORT [--drop N] [--maxdata BYTES]

The first login has to offer its public key («Allow USB debugging?», accepted here); later logins must sign the
token with that same key. --maxdata is what the TV takes per message (1 MB today, 4 KB
on old sets). --drop N cuts the connection on every Nth command, before answering, like Wi-Fi does.
"""
import base64
import os
import random
import socket
import struct
import subprocess
import sys
import threading

CNXN, AUTH, OPEN, OKAY, CLSE, WRTE = 0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257
SHA1_INFO = bytes([0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14])
trusted = []        # (n, e) of keys the person allowed
commands = [0]      # every OPEN, for --drop
DROP = int(sys.argv[sys.argv.index("--drop") + 1]) if "--drop" in sys.argv else 0
# FAKE_TV_BIN: a folder of pretend pm, settings, cmd, am … (tests/fake_tv) put first on the shell's PATH
SHELL_ENV = dict(os.environ, PATH=os.environ["FAKE_TV_BIN"] + os.pathsep + os.environ["PATH"]) \
    if os.environ.get("FAKE_TV_BIN") else None
MAXDATA = int(sys.argv[sys.argv.index("--maxdata") + 1]) if "--maxdata" in sys.argv else 1024 * 1024


def send(c, cmd, a0, a1, data=b""):
    c.sendall(struct.pack("<6I", cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data)


def recv(c):
    head = b""
    while len(head) < 24:
        part = c.recv(24 - len(head))
        if not part:
            raise EOFError
        head += part
    cmd, a0, a1, n, _, magic = struct.unpack("<6I", head)
    assert magic == cmd ^ 0xffffffff, "bad magic"
    data = b""
    while len(data) < n:
        part = c.recv(n - len(data))
        if not part:
            raise EOFError
        data += part
    return cmd, a0, a1, data


def parse_key(blob):
    raw = base64.b64decode(blob.split(b" ")[0])
    words = struct.unpack_from("<I", raw, 0)[0]
    n = sum(struct.unpack_from("<I", raw, 8 + 4 * i)[0] << (32 * i) for i in range(words))
    e = struct.unpack_from("<I", raw, 8 + 8 * words)[0]
    n0inv = struct.unpack_from("<I", raw, 4)[0]
    assert (n * n0inv) % (1 << 32) == (1 << 32) - 1, "n0inv is not -1/n mod 2^32"
    return n, e


def verifies(sig, token, key):
    n, e = key
    size = (n.bit_length() + 7) // 8
    want = b"\x00\x01" + b"\xff" * (size - 3 - len(SHA1_INFO) - len(token)) + b"\x00" + SHA1_INFO + token
    return pow(int.from_bytes(sig, "big"), e, n) == int.from_bytes(want, "big")


def serve(c, workdir):
    cmd, a0, a1, data = recv(c)
    assert cmd == CNXN and data.startswith(b"host::"), "expected CNXN"
    token = os.urandom(20)
    send(c, AUTH, 1, 0, token)
    while True:
        cmd, a0, a1, data = recv(c)
        assert cmd == AUTH, "expected AUTH"
        if a0 == 2 and any(verifies(data, token, k) for k in trusted):
            break
        if a0 == 2:
            token = os.urandom(20)
            send(c, AUTH, 1, 0, token)
        elif a0 == 3:
            assert data.endswith(b"\x00"), "public key must end in NUL"
            trusted.append(parse_key(data.rstrip(b"\x00")))
            break
    send(c, CNXN, 0x01000001, MAXDATA, b"device::ro.product.model=Fake;\x00")
    remote = 100
    while True:
        cmd, a0, a1, data = recv(c)
        if cmd != OPEN:
            continue
        assert len(data) <= MAXDATA, f"OPEN of {len(data)} bytes, more than the {MAXDATA} this TV takes"
        commands[0] += 1
        if DROP and commands[0] % DROP == 0:
            c.close()
            return
        remote += 1
        local = a0
        service = data.rstrip(b"\x00").decode()
        send(c, OKAY, remote, local)
        if service.startswith("shell:"):
            out = subprocess.run(["sh", "-c", service[6:]], cwd=workdir, capture_output=True, env=SHELL_ENV).stdout
        else:
            out = b""
        for i in range(0, len(out), 4000):   # in pieces, each acknowledged, as adbd does
            send(c, WRTE, remote, local, out[i:i + 4000])
            k = recv(c)
            assert k[0] == OKAY and k[1] == local and k[2] == remote, f"expected OKAY, got {k[:3]}"
        send(c, CLSE, remote, local)
        k = recv(c)
        assert k[0] == CLSE, "expected the client to close the stream too"


def main():
    port = int(sys.argv[1])
    workdir = os.environ.get("FAKE_TV_DIR", os.getcwd())
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(4)
    print("ready", flush=True)
    while True:
        c, _ = s.accept()
        threading.Thread(target=lambda: _run(c, workdir), daemon=True).start()


def _run(c, workdir):
    try:
        serve(c, workdir)
    except (EOFError, OSError):
        pass
    except AssertionError as e:
        print("PROTOCOL ERROR:", e, file=sys.stderr, flush=True)
        os._exit(3)
    finally:
        c.close()


if __name__ == "__main__":
    random.seed()
    main()
