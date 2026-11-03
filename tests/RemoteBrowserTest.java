import com.tinyhack.ssh.files.RemoteConnection;
import com.tinyhack.ssh.files.SftpClient;
import com.tinyhack.ssh.ssh.SshTarget;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exercises SftpClient and RemoteConnection against a real OpenSSH server.
 * Args: sshPath port user keyFile knownHosts workDir (the server is this host,
 * so workDir is visible both locally and over SFTP).
 */
public class RemoteBrowserTest {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok  " + what);
    }

    private static void eq(Object actual, Object expected, String what) {
        check(expected.equals(actual), what + " (expected " + expected + ", got " + actual + ")");
    }

    public static void main(String[] args) throws Exception {
        String sshPath = args[0];
        String port = args[1];
        String user = args[2];
        String key = args[3];
        String known = args[4];
        File work = new File(args[5]).getCanonicalFile();

        // Fixture tree on "the server" (this machine).
        File root = new File(work, "remote root");
        File sub = new File(root, "sub dir");
        File keep = new File(work, "link target");
        sub.mkdirs();
        keep.mkdirs();
        Files.write(new File(keep, "precious.txt").toPath(), "keep me".getBytes());
        byte[] big = new byte[300_000];
        new Random(42).nextBytes(big);
        Files.write(new File(root, "big.bin").toPath(), big);
        Files.write(new File(sub, "héllo wörld.txt").toPath(), "hi".getBytes("UTF-8"));
        new File(root, "empty").mkdir();
        Files.createSymbolicLink(new File(root, "link").toPath(), keep.toPath());
        new File(root, ".hidden").createNewFile();

        SshTarget t = new SshTarget();
        t.sshPath = sshPath;
        t.destination = user + "@127.0.0.1";
        String[] opts = {"-F", "/dev/null", "-i", key, "-o", "Port=" + port,
                "-o", "UserKnownHostsFile=" + known, "-o", "StrictHostKeyChecking=accept-new",
                "-o", "IdentitiesOnly=yes", "-o", "ForwardAgent=no"};
        for (String o : opts) t.options.add(o);

        File cm = new File(work, "cm");
        cm.mkdirs();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TMPDIR", work.getAbsolutePath());
        env.remove("SSH_AUTH_SOCK");

        RemoteConnection rc = new RemoteConnection(t, cm, env);
        check(!rc.isMasterAlive(), "no master before connecting");
        String err = rc.tryStartMasterSilently();
        check(err == null, "silent master start (" + err + ")");
        check(rc.isMasterAlive(), "master alive");
        check(new File(rc.getControlPath()).exists(), "control socket created");

        SftpClient c = rc.openSftp();
        eq(c.getServerVersion(), 3, "server version");
        String home = c.realpath(".");
        check(home.startsWith("/"), "realpath . is absolute: " + home);
        String base = c.realpath(root.getAbsolutePath());
        eq(base, root.getAbsolutePath(), "realpath of fixture");

        List<SftpClient.Name> names = c.list(base);
        Map<String, SftpClient.Name> byName = new HashMap<>();
        for (SftpClient.Name n : names) byName.put(n.filename, n);
        eq(byName.keySet(), new java.util.HashSet<>(List.of("sub dir", "big.bin", "empty", "link", ".hidden")),
                "listing has no . / ..");
        check(byName.get("sub dir").attrs.isDirectory(), "folder type");
        check(byName.get("big.bin").attrs.isRegular(), "file type");
        eq(byName.get("big.bin").attrs.size, (long) big.length, "file size");
        check(Math.abs(byName.get("big.bin").attrs.mtime * 1000 - new File(root, "big.bin").lastModified()) < 2000,
                "mtime");
        check(byName.get("link").attrs.isSymlink(), "readdir does not follow links");
        check(c.stat(RemoteConnection.join(base, "link")).isDirectory(), "stat follows a link to a folder");
        eq(c.readlink(RemoteConnection.join(base, "link")), keep.getAbsolutePath(), "readlink");
        eq(c.list(RemoteConnection.join(base, "sub dir")).get(0).filename, "héllo wörld.txt", "UTF-8 names");
        eq(byName.get("big.bin").attrs.modeString().charAt(0), '-', "mode string");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long[] last = {0};
        c.download(RemoteConnection.join(base, "big.bin"), bos, (d, tot) -> last[0] = d, new AtomicBoolean());
        check(java.util.Arrays.equals(bos.toByteArray(), big), "download is byte-identical");
        eq(last[0], (long) big.length, "final progress");

        try {
            c.list(RemoteConnection.join(base, "missing"));
            check(false, "listing a missing folder fails");
        } catch (SftpClient.SftpException e) {
            eq(e.status, SftpClient.FX_NO_SUCH_FILE, "missing folder status");
        }

        c.mkdir(RemoteConnection.join(base, "new folder"));
        check(new File(root, "new folder").isDirectory(), "mkdir");
        try {
            c.mkdir(RemoteConnection.join(base, "new folder"));
            check(false, "mkdir over existing fails");
        } catch (SftpClient.SftpException e) {
            check(true, "mkdir over existing fails: " + e.getMessage());
        }
        c.rename(RemoteConnection.join(base, "new folder"), RemoteConnection.join(base, "renamed"));
        check(new File(root, "renamed").isDirectory() && !new File(root, "new folder").exists(), "rename");
        try {
            c.rename(RemoteConnection.join(base, "renamed"), RemoteConnection.join(base, "empty"));
            check(false, "rename onto existing fails");
        } catch (SftpClient.SftpException e) {
            check(new File(root, "renamed").isDirectory(), "rename onto existing is refused");
        }

        long[] m = RemoteConnection.measure(c, base, new AtomicBoolean());
        eq(m[0], (long) big.length + 2, "recursive size (links not followed)");
        eq(m[1], 4L, "recursive file count (big, héllo, link, .hidden)");

        // A transfer reusing the master: BatchMode proves no new login happens.
        SshTarget shared = rc.sharedTarget();
        File dl = new File(work, "dl.bin");
        List<String> scp = new ArrayList<>();
        scp.add("scp");
        scp.add("-S");
        scp.add(shared.sshPath);
        scp.addAll(shared.options);
        scp.add("-o");
        scp.add("BatchMode=yes");
        scp.add("-i");
        scp.add("/nonexistent"); // would break a fresh login
        scp.add(shared.destination + ":" + RemoteConnection.join(base, "big.bin"));
        scp.add(dl.getAbsolutePath());
        Process p = new ProcessBuilder(scp).inheritIO().start();
        eq(p.waitFor(), 0, "scp over the shared master");
        check(java.util.Arrays.equals(Files.readAllBytes(dl.toPath()), big), "scp copy is byte-identical");

        List<String> errors = new ArrayList<>();
        int deleted = RemoteConnection.deleteRecursive(c, base, new AtomicBoolean(), errors, null);
        eq(errors, List.of(), "recursive delete errors");
        check(!root.exists(), "tree deleted");
        check(new File(keep, "precious.txt").exists(), "link target survives delete");
        eq(deleted, 8, "deleted entry count");

        rc.closeSftp();
        check(rc.isMasterAlive(), "master persists after the channel closes");
        rc.openSftp();
        eq(rc.getSftp().realpath("."), home, "reopen channel");
        rc.disconnect();
        check(!rc.isMasterAlive(), "disconnect ends the master");

        // App exit: every master in the control folder is logged out.
        check(rc.tryStartMasterSilently() == null, "master restarts silently");
        check(rc.isMasterAlive(), "master alive again");
        RemoteConnection.exitAll(cm, sshPath, env);
        check(!rc.isMasterAlive(), "exitAll ends the master");
        eq(cm.list().length, 0, "exitAll removes the sockets");

        // A dead master's socket must not block a new one (ssh would
        // otherwise run without multiplexing and the browser could not attach).
        Files.createFile(new File(rc.getControlPath()).toPath());
        check(rc.tryStartMasterSilently() == null && rc.isMasterAlive(), "stale socket is replaced");
        rc.disconnect();

        eq(RemoteConnection.parent("/a/b"), "/a", "parent");
        eq(RemoteConnection.parent("/a"), "/", "parent of top level");
        check(RemoteConnection.parent("/") == null, "parent of /");
        eq(RemoteConnection.parent("/C:/Users"), "/C:", "windows-style parent");
        eq(RemoteConnection.name("/a/b c/"), "b c", "name");
        eq(RemoteConnection.join("/", "x"), "/x", "join at root");
        System.out.println("All remote browser tests passed");
    }
}
