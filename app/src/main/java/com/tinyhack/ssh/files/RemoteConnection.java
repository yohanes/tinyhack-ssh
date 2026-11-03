package com.tinyhack.ssh.files;

import com.tinyhack.ssh.ssh.SshTarget;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One server connection for the remote file browser.
 *
 * <p>Authentication happens once, in an OpenSSH control master
 * ({@code ssh -M -f -N}). When a key works without prompts the master is
 * started silently; otherwise it runs in a terminal session where password,
 * passphrase, host-key and Cloudflare prompts are interactive. The browser's
 * SFTP channel and any scp / rsync / sftp transfer then multiplex over the
 * master's socket, so the user never logs in twice.
 *
 * <p>Pure Java (no Android APIs) so it can be tested off-device.
 */
public final class RemoteConnection implements AutoCloseable {
    /** Idle seconds the master stays up after the last channel closes. */
    static final int PERSIST_SECONDS = 15 * 60;

    private final SshTarget target;
    private final String controlPath;
    private final Map<String, String> env;
    private Process sftpProcess;
    private SftpClient sftp;
    private final StringBuilder stderr = new StringBuilder();

    /**
     * @param controlDir private (0700) folder for control sockets; its path
     *        must stay short because Unix socket paths are limited to ~100 bytes.
     * @param env complete environment for spawned ssh processes.
     */
    public RemoteConnection(SshTarget target, File controlDir, Map<String, String> env) {
        this.target = target;
        this.controlPath = new File(controlDir, socketName(target)).getAbsolutePath();
        this.env = env;
    }

    public SshTarget getTarget() { return target; }

    public String getControlPath() { return controlPath; }

    /** One socket per distinct destination + option set, so editing a profile starts fresh. */
    static String socketName(SshTarget t) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.valueOf(t.destination).getBytes(StandardCharsets.UTF_8));
            for (String o : t.options) {
                md.update((byte) 0);
                md.update(o.getBytes(StandardCharsets.UTF_8));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(t.destination).hashCode());
        }
    }

    // ------------------------------------------------------------- commands

    /** "-o ControlPath=..." first: OpenSSH keeps the first value of each option. */
    private List<String> controlOptions(String master) {
        List<String> o = new ArrayList<>();
        o.add("-o");
        o.add("ControlPath=" + controlPath.replace("%", "%%"));
        o.add("-o");
        o.add("ControlMaster=" + master);
        return o;
    }

    /** ssh argv (program first) that authenticates and backgrounds a master. */
    public List<String> masterCommand(boolean batch) {
        List<String> cmd = new ArrayList<>();
        cmd.add(target.sshPath);
        cmd.addAll(controlOptions("yes"));
        cmd.add("-o");
        cmd.add("ControlPersist=" + PERSIST_SECONDS);
        if (batch) {
            cmd.add("-o");
            cmd.add("BatchMode=yes");
            cmd.add("-o");
            cmd.add("ConnectTimeout=15");
        }
        cmd.add("-f");
        cmd.add("-N");
        cmd.addAll(target.options);
        cmd.add(target.destination);
        return cmd;
    }

    /** Same as {@link #masterCommand} but with "ssh" as argv[0] for a terminal session. */
    public List<String> interactiveMasterCommand() {
        List<String> cmd = masterCommand(false);
        cmd.set(0, "ssh");
        return cmd;
    }

    private List<String> controlCommand(String op) {
        List<String> cmd = new ArrayList<>();
        cmd.add(target.sshPath);
        cmd.addAll(controlOptions("no"));
        cmd.add("-O");
        cmd.add(op);
        cmd.add(target.destination);
        return cmd;
    }

    private List<String> sftpCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(target.sshPath);
        cmd.addAll(controlOptions("no"));
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.addAll(target.options);
        cmd.add("-s");
        cmd.add(target.destination);
        cmd.add("sftp");
        return cmd;
    }

    /**
     * A copy of the target whose ssh options reuse this master, for scp /
     * rsync / sftp transfers. Without a live master they simply connect (and
     * prompt) as usual.
     */
    public SshTarget sharedTarget() {
        SshTarget t = new SshTarget();
        t.sshPath = target.sshPath;
        t.destination = target.destination;
        t.envp = target.envp;
        t.error = target.error;
        t.options.addAll(controlOptions("no"));
        t.options.addAll(target.options);
        return t;
    }

    // ------------------------------------------------------------- lifecycle

    public boolean isMasterAlive() {
        return run(controlCommand("check"), 10).exitCode == 0;
    }

    /**
     * Try to start the master without any prompt (key auth, known host).
     * @return null on success, otherwise ssh's error text.
     */
    public String tryStartMasterSilently() {
        removeStaleSocket();
        Result r = runWith(masterCommand(true), env, 30, silentAttempt);
        if (r.exitCode == 0 && isMasterAlive()) return null;
        String msg = r.output.trim();
        return msg.isEmpty() ? "ssh exited with status " + r.exitCode : msg;
    }

    /**
     * A socket left by a master that was killed (e.g. with the app) makes a
     * new master silently run without multiplexing; remove it first.
     * Call only when {@link #isMasterAlive()} is false.
     */
    public void removeStaleSocket() {
        //noinspection ResultOfMethodCallIgnored
        new File(controlPath).delete();
    }

    /** Open the SFTP channel over the master. */
    public synchronized SftpClient openSftp() throws IOException {
        closeSftp();
        stderr.setLength(0);
        ProcessBuilder pb = new ProcessBuilder(sftpCommand());
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        sftpProcess = p;
        Thread drain = new Thread(() -> drainStderr(p.getErrorStream()), "sftp-stderr");
        drain.setDaemon(true);
        drain.start();
        SftpClient client = new SftpClient(p.getInputStream(), p.getOutputStream());
        try {
            client.init();
        } catch (IOException e) {
            client.close();
            p.destroy();
            sftpProcess = null;
            String err = waitForStderr(p);
            throw new IOException(err.isEmpty() ? e.getMessage() : err, e);
        }
        sftp = client;
        return client;
    }

    public synchronized SftpClient getSftp() { return sftp; }

    /** Close the browser's channel; the master lingers for {@link #PERSIST_SECONDS}. */
    public void closeSftp() {
        Process p;
        SftpClient c;
        synchronized (this) {
            p = sftpProcess;
            c = sftp;
            sftpProcess = null;
            sftp = null;
        }
        // Process first: a request blocked on the pipe then fails at once.
        if (p != null) p.destroy();
        if (c != null) c.close();
    }

    /** Close the channel and end the master too (log out). */
    public void disconnect() {
        closeSftp();
        run(controlCommand("exit"), 10);
    }

    @Override
    public void close() { closeSftp(); }

    /** Stop every master whose socket lives in {@code controlDir} (app exit). */
    public static void exitAll(File controlDir, String sshPath, Map<String, String> env) {
        File[] socks = controlDir.listFiles();
        if (socks == null) return;
        for (File s : socks) {
            List<String> cmd = new ArrayList<>();
            cmd.add(sshPath);
            cmd.add("-o");
            cmd.add("ControlPath=" + s.getAbsolutePath().replace("%", "%%"));
            cmd.add("-O");
            cmd.add("exit");
            cmd.add("localhost"); // required by the syntax; the socket picks the server
            runWith(cmd, env, 5, null);
            //noinspection ResultOfMethodCallIgnored
            s.delete();
        }
    }

    private void drainStderr(InputStream es) {
        byte[] buf = new byte[1024];
        try {
            int n;
            while ((n = es.read(buf)) > 0) {
                synchronized (stderr) {
                    if (stderr.length() < 8192) stderr.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {}
    }

    private String waitForStderr(Process p) {
        try { p.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        synchronized (stderr) {
            return stderr.toString().trim();
        }
    }

    // ------------------------------------------------------------ processes

    static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** Process of a running {@link #tryStartMasterSilently()}, so it can be cancelled. */
    private final java.util.concurrent.atomic.AtomicReference<Process> silentAttempt =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Abort a running silent login (it then returns an error). */
    public void cancelSilentAttempt() {
        Process p = silentAttempt.get();
        if (p != null) p.destroy();
    }

    private Result run(List<String> cmd, int timeoutSeconds) {
        return runWith(cmd, env, timeoutSeconds, null);
    }

    /**
     * Run to completion with stdin closed. Output goes to a temp file, not a
     * pipe: "ssh -f" leaves a background child holding inherited pipes open.
     */
    private static Result runWith(List<String> cmd, Map<String, String> env, int timeoutSeconds,
                                  java.util.concurrent.atomic.AtomicReference<Process> track) {
        File log = null;
        try {
            String tmp = env.get("TMPDIR");
            log = File.createTempFile("ssh-", ".log", tmp != null ? new File(tmp) : null);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.redirectErrorStream(true);
            pb.redirectOutput(log);
            Process p = pb.start();
            if (track != null) track.set(p);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
                return new Result(-1, "Timed out");
            }
            return new Result(p.exitValue(), readSmall(log));
        } catch (IOException e) {
            return new Result(-1, e.getMessage() != null ? e.getMessage() : e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Interrupted");
        } finally {
            if (track != null) track.set(null);
            //noinspection ResultOfMethodCallIgnored
            if (log != null) log.delete();
        }
    }

    private static String readSmall(File f) throws IOException {
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        int n = Math.min(b.length, 4096);
        return new String(b, b.length - n, n, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------- remote tree ops

    public interface ItemCallback {
        void onItem(String path, int done);
    }

    /**
     * Delete a remote file or folder tree. Symbolic links are removed as
     * links and never followed.
     * @return number of entries deleted
     */
    public static int deleteRecursive(SftpClient c, String path, java.util.concurrent.atomic.AtomicBoolean cancel,
                                      List<String> errors, ItemCallback cb) throws java.io.InterruptedIOException {
        int[] done = {0};
        deleteInto(c, path, cancel, errors, cb, done);
        return done[0];
    }

    private static void deleteInto(SftpClient c, String path, java.util.concurrent.atomic.AtomicBoolean cancel,
                                   List<String> errors, ItemCallback cb, int[] done) throws java.io.InterruptedIOException {
        if (cancel != null && cancel.get()) throw new java.io.InterruptedIOException("cancelled");
        try {
            SftpClient.Attrs a = c.lstat(path);
            if (a.isDirectory()) {
                for (SftpClient.Name n : c.list(path)) {
                    deleteInto(c, join(path, n.filename), cancel, errors, cb, done);
                }
                c.rmdir(path);
            } else {
                c.remove(path);
            }
            done[0]++;
            if (cb != null) cb.onItem(path, done[0]);
        } catch (java.io.InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            errors.add(name(path) + ": " + e.getMessage());
        }
    }

    /** Recursive size and file count of a remote tree (links not followed). */
    public static long[] measure(SftpClient c, String path, java.util.concurrent.atomic.AtomicBoolean cancel)
            throws IOException {
        long[] acc = new long[2];
        measureInto(c, path, c.lstat(path), cancel, acc);
        return acc;
    }

    private static void measureInto(SftpClient c, String path, SftpClient.Attrs a,
                                    java.util.concurrent.atomic.AtomicBoolean cancel, long[] acc) throws IOException {
        if (cancel != null && cancel.get()) throw new java.io.InterruptedIOException("cancelled");
        if (a.isDirectory()) {
            List<SftpClient.Name> kids;
            try {
                kids = c.list(path);
            } catch (SftpClient.SftpException e) {
                return; // unreadable folder: count what we can
            }
            for (SftpClient.Name n : kids) measureInto(c, join(path, n.filename), n.attrs, cancel, acc);
        } else {
            if (!a.isSymlink() && a.size > 0) acc[0] += a.size;
            acc[1]++;
        }
    }

    // ------------------------------------------------------- remote paths

    /** POSIX-style join for remote paths. */
    public static String join(String dir, String name) {
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    /** Parent of an absolute remote path, or null at "/". */
    public static String parent(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) return null;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        if (slash < 0) return null;
        if (slash == 0) return "/";
        return p.substring(0, slash);
    }

    public static String name(String path) {
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}
