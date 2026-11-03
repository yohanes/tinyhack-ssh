package com.tinyhack.ssh.files;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal SFTP version 3 client (draft-ietf-secsh-filexfer-02, the version
 * OpenSSH speaks) over the stdin/stdout of {@code ssh -s host sftp}.
 *
 * <p>Pure Java so it can be tested off-device. Requests are sent one at a
 * time; every public method is synchronized and blocks, so call it from a
 * worker thread.
 */
public final class SftpClient implements AutoCloseable {

    // Packet types
    private static final int FXP_INIT = 1;
    private static final int FXP_VERSION = 2;
    private static final int FXP_OPEN = 3;
    private static final int FXP_CLOSE = 4;
    private static final int FXP_READ = 5;
    private static final int FXP_LSTAT = 7;
    private static final int FXP_OPENDIR = 11;
    private static final int FXP_READDIR = 12;
    private static final int FXP_REMOVE = 13;
    private static final int FXP_MKDIR = 14;
    private static final int FXP_RMDIR = 15;
    private static final int FXP_REALPATH = 16;
    private static final int FXP_STAT = 17;
    private static final int FXP_RENAME = 18;
    private static final int FXP_READLINK = 19;
    private static final int FXP_STATUS = 101;
    private static final int FXP_HANDLE = 102;
    private static final int FXP_DATA = 103;
    private static final int FXP_NAME = 104;
    private static final int FXP_ATTRS = 105;

    // Status codes
    public static final int FX_OK = 0;
    public static final int FX_EOF = 1;
    public static final int FX_NO_SUCH_FILE = 2;
    public static final int FX_PERMISSION_DENIED = 3;
    public static final int FX_FAILURE = 4;

    // Attribute flags
    private static final int ATTR_SIZE = 0x1;
    private static final int ATTR_UIDGID = 0x2;
    private static final int ATTR_PERMISSIONS = 0x4;
    private static final int ATTR_ACMODTIME = 0x8;
    private static final int ATTR_EXTENDED = 0x80000000;

    private static final int FXF_READ = 0x1;

    private static final int S_IFMT = 0170000;
    private static final int S_IFDIR = 0040000;
    private static final int S_IFLNK = 0120000;
    private static final int S_IFREG = 0100000;

    /** Largest packet accepted from the server (OpenSSH's own limit is 256 KiB). */
    private static final int MAX_PACKET = 1024 * 1024;
    private static final int READ_CHUNK = 32 * 1024;

    /** Server error with its SFTP status code. */
    public static final class SftpException extends IOException {
        public final int status;
        public SftpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Attrs {
        public long size = -1;
        public int uid = -1;
        public int gid = -1;
        /** POSIX mode including file type bits, or -1 when not sent. */
        public int permissions = -1;
        /** Seconds since the epoch, or -1. */
        public long mtime = -1;

        public boolean hasType() { return permissions != -1 && (permissions & S_IFMT) != 0; }
        public boolean isDirectory() { return permissions != -1 && (permissions & S_IFMT) == S_IFDIR; }
        public boolean isSymlink() { return permissions != -1 && (permissions & S_IFMT) == S_IFLNK; }
        public boolean isRegular() { return permissions != -1 && (permissions & S_IFMT) == S_IFREG; }

        /** ls-style "drwxr-xr-x"; "?" when the server sent no mode. */
        public String modeString() {
            if (permissions == -1) return "?";
            int type = permissions & S_IFMT;
            char t = type == S_IFDIR ? 'd' : type == S_IFLNK ? 'l' : '-';
            StringBuilder sb = new StringBuilder().append(t);
            String rwx = "rwxrwxrwx";
            for (int i = 0; i < 9; i++) sb.append((permissions & (1 << (8 - i))) != 0 ? rwx.charAt(i) : '-');
            return sb.toString();
        }
    }

    public static final class Name {
        public final String filename;
        public final String longname;
        public final Attrs attrs;
        Name(String filename, String longname, Attrs attrs) {
            this.filename = filename;
            this.longname = longname;
            this.attrs = attrs;
        }
    }

    public interface ReadProgress {
        void onBytes(long done, long total);
    }

    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;
    private int serverVersion;
    private volatile boolean closed;

    public SftpClient(InputStream in, OutputStream out) {
        this.in = new DataInputStream(new java.io.BufferedInputStream(in, 64 * 1024));
        this.out = new java.io.BufferedOutputStream(out, 64 * 1024);
    }

    /** Protocol handshake; must be the first call. */
    public synchronized void init() throws IOException {
        Buf b = new Buf().u8(FXP_INIT).u32(3);
        send(b);
        Packet p = receive();
        if (p.type != FXP_VERSION) throw new IOException("Not an SFTP server (got packet type " + p.type + ")");
        serverVersion = p.u32();
        if (serverVersion < 3) throw new IOException("SFTP version " + serverVersion + " is not supported");
    }

    public int getServerVersion() { return serverVersion; }

    // ------------------------------------------------------------ operations

    /** Canonical absolute path; "." gives the login folder. */
    public synchronized String realpath(String path) throws IOException {
        int id = request(FXP_REALPATH, new Buf().str(path));
        List<Name> names = expectName(id);
        if (names.isEmpty()) throw new IOException("Empty REALPATH reply");
        return names.get(0).filename;
    }

    public synchronized Attrs stat(String path) throws IOException {
        return expectAttrs(request(FXP_STAT, new Buf().str(path)));
    }

    public synchronized Attrs lstat(String path) throws IOException {
        return expectAttrs(request(FXP_LSTAT, new Buf().str(path)));
    }

    public synchronized String readlink(String path) throws IOException {
        List<Name> names = expectName(request(FXP_READLINK, new Buf().str(path)));
        if (names.isEmpty()) throw new IOException("Empty READLINK reply");
        return names.get(0).filename;
    }

    /** Directory entries without "." and "..". Attributes are lstat-style. */
    public synchronized List<Name> list(String path) throws IOException {
        byte[] handle = expectHandle(request(FXP_OPENDIR, new Buf().str(path)));
        List<Name> out = new ArrayList<>();
        try {
            while (true) {
                int id = request(FXP_READDIR, new Buf().str(handle));
                Packet p = receiveFor(id);
                if (p.type == FXP_STATUS) {
                    int code = p.u32();
                    if (code == FX_EOF) break;
                    throw statusException(code, p);
                }
                if (p.type != FXP_NAME) throw unexpected(p);
                for (Name n : readNames(p)) {
                    if (n.filename.equals(".") || n.filename.equals("..")) continue;
                    out.add(n);
                }
            }
        } finally {
            closeHandle(handle);
        }
        return out;
    }

    public synchronized void mkdir(String path) throws IOException {
        expectOk(request(FXP_MKDIR, new Buf().str(path).u32(0)));
    }

    public synchronized void rmdir(String path) throws IOException {
        expectOk(request(FXP_RMDIR, new Buf().str(path)));
    }

    public synchronized void remove(String path) throws IOException {
        expectOk(request(FXP_REMOVE, new Buf().str(path)));
    }

    /** Fails (rather than overwrites) when {@code to} exists, per SFTP v3. */
    public synchronized void rename(String from, String to) throws IOException {
        expectOk(request(FXP_RENAME, new Buf().str(from).str(to)));
    }

    /** Copy a remote file into {@code dst}. */
    public synchronized void download(String path, OutputStream dst, ReadProgress progress,
                                      AtomicBoolean cancel) throws IOException {
        Attrs a = stat(path);
        long total = a.size;
        byte[] handle = expectHandle(request(FXP_OPEN, new Buf().str(path).u32(FXF_READ).u32(0)));
        long offset = 0;
        long lastReport = 0;
        try {
            while (true) {
                if (cancel != null && cancel.get()) throw new InterruptedIOException("cancelled");
                int id = request(FXP_READ, new Buf().str(handle).u64(offset).u32(READ_CHUNK));
                Packet p = receiveFor(id);
                if (p.type == FXP_STATUS) {
                    int code = p.u32();
                    if (code == FX_EOF) break;
                    throw statusException(code, p);
                }
                if (p.type != FXP_DATA) throw unexpected(p);
                int len = p.u32();
                if (len < 0 || len > p.remaining()) throw new IOException("Bad DATA packet");
                dst.write(p.data, p.pos, len);
                p.pos += len;
                offset += len;
                long now = System.nanoTime();
                if (progress != null && now - lastReport > 100_000_000L) {
                    lastReport = now;
                    progress.onBytes(offset, total);
                }
            }
        } finally {
            closeHandle(handle);
        }
        if (progress != null) progress.onBytes(offset, total);
    }

    /**
     * Not synchronized: callable while another thread is blocked in a
     * request (which then fails). Kill the transport process first so a
     * blocked read returns.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { out.close(); } catch (IOException ignored) {}
        try { in.close(); } catch (IOException ignored) {}
    }

    public boolean isClosed() { return closed; }

    // ---------------------------------------------------------- reply helpers

    private void closeHandle(byte[] handle) {
        try {
            expectOk(request(FXP_CLOSE, new Buf().str(handle)));
        } catch (IOException ignored) {}
    }

    private void expectOk(int id) throws IOException {
        Packet p = receiveFor(id);
        if (p.type != FXP_STATUS) throw unexpected(p);
        int code = p.u32();
        if (code != FX_OK) throw statusException(code, p);
    }

    private byte[] expectHandle(int id) throws IOException {
        Packet p = receiveFor(id);
        if (p.type == FXP_STATUS) throw statusException(p.u32(), p);
        if (p.type != FXP_HANDLE) throw unexpected(p);
        return p.bytes();
    }

    private Attrs expectAttrs(int id) throws IOException {
        Packet p = receiveFor(id);
        if (p.type == FXP_STATUS) throw statusException(p.u32(), p);
        if (p.type != FXP_ATTRS) throw unexpected(p);
        return readAttrs(p);
    }

    private List<Name> expectName(int id) throws IOException {
        Packet p = receiveFor(id);
        if (p.type == FXP_STATUS) throw statusException(p.u32(), p);
        if (p.type != FXP_NAME) throw unexpected(p);
        return readNames(p);
    }

    private static List<Name> readNames(Packet p) throws IOException {
        int count = p.u32();
        if (count < 0 || count > 100000) throw new IOException("Bad NAME count");
        List<Name> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String filename = p.string();
            String longname = p.string();
            names.add(new Name(filename, longname, readAttrs(p)));
        }
        return names;
    }

    private static Attrs readAttrs(Packet p) throws IOException {
        Attrs a = new Attrs();
        int flags = p.u32();
        if ((flags & ATTR_SIZE) != 0) a.size = p.u64();
        if ((flags & ATTR_UIDGID) != 0) {
            a.uid = p.u32();
            a.gid = p.u32();
        }
        if ((flags & ATTR_PERMISSIONS) != 0) a.permissions = p.u32();
        if ((flags & ATTR_ACMODTIME) != 0) {
            p.u32(); // atime
            a.mtime = p.u32() & 0xFFFFFFFFL;
        }
        if ((flags & ATTR_EXTENDED) != 0) {
            int n = p.u32();
            for (int i = 0; i < n; i++) {
                p.bytes();
                p.bytes();
            }
        }
        return a;
    }

    private static SftpException statusException(int code, Packet p) {
        String msg = null;
        try {
            if (p.remaining() >= 4) msg = p.string();
        } catch (IOException ignored) {}
        if (msg == null || msg.isEmpty()) msg = describeStatus(code);
        return new SftpException(code, msg);
    }

    public static String describeStatus(int code) {
        switch (code) {
            case FX_EOF: return "End of file";
            case FX_NO_SUCH_FILE: return "No such file";
            case FX_PERMISSION_DENIED: return "Permission denied";
            case FX_FAILURE: return "Failure";
            case 5: return "Bad message";
            case 6: return "No connection";
            case 7: return "Connection lost";
            case 8: return "Operation unsupported";
            default: return "Error " + code;
        }
    }

    private static IOException unexpected(Packet p) {
        return new IOException("Unexpected SFTP reply type " + p.type);
    }

    // -------------------------------------------------------------- transport

    private int request(int type, Buf payload) throws IOException {
        int id = nextId++;
        Buf b = new Buf().u8(type).u32(id);
        b.raw(payload.toByteArray());
        send(b);
        return id;
    }

    private void send(Buf b) throws IOException {
        if (closed) throw new IOException("Connection closed");
        byte[] body = b.toByteArray();
        byte[] len = {(byte) (body.length >>> 24), (byte) (body.length >>> 16), (byte) (body.length >>> 8), (byte) body.length};
        out.write(len);
        out.write(body);
        out.flush();
    }

    private Packet receiveFor(int id) throws IOException {
        Packet p = receive();
        int got = p.u32();
        if (got != id) throw new IOException("SFTP reply out of order (" + got + " != " + id + ")");
        return p;
    }

    private Packet receive() throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            closed = true;
            throw new IOException("Connection closed by server");
        }
        if (len < 1 || len > MAX_PACKET) {
            closed = true;
            throw new IOException("Bad SFTP packet length " + len);
        }
        byte[] data = new byte[len];
        try {
            in.readFully(data);
        } catch (EOFException e) {
            closed = true;
            throw new IOException("Connection closed by server");
        }
        Packet p = new Packet(data);
        p.type = data[0] & 0xFF;
        p.pos = 1;
        return p;
    }

    private static final class Packet {
        final byte[] data;
        int pos;
        int type;
        Packet(byte[] data) { this.data = data; }

        int remaining() { return data.length - pos; }

        int u32() throws IOException {
            if (remaining() < 4) throw new IOException("Truncated SFTP packet");
            int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        long u64() throws IOException {
            long hi = u32() & 0xFFFFFFFFL;
            long lo = u32() & 0xFFFFFFFFL;
            return (hi << 32) | lo;
        }

        byte[] bytes() throws IOException {
            int n = u32();
            if (n < 0 || n > remaining()) throw new IOException("Truncated SFTP string");
            byte[] b = new byte[n];
            System.arraycopy(data, pos, b, 0, n);
            pos += n;
            return b;
        }

        String string() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class Buf {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        Buf u8(int v) { bos.write(v); return this; }

        Buf u32(int v) {
            bos.write(v >>> 24);
            bos.write(v >>> 16);
            bos.write(v >>> 8);
            bos.write(v);
            return this;
        }

        Buf u64(long v) {
            u32((int) (v >>> 32));
            return u32((int) v);
        }

        Buf str(String s) { return str(s.getBytes(StandardCharsets.UTF_8)); }

        Buf str(byte[] b) {
            u32(b.length);
            bos.write(b, 0, b.length);
            return this;
        }

        Buf raw(byte[] b) {
            bos.write(b, 0, b.length);
            return this;
        }

        byte[] toByteArray() { return bos.toByteArray(); }
    }
}
