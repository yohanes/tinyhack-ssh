package com.tinyhack.ssh.files;

import com.tinyhack.ssh.ssh.SshTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds scp / rsync / sftp command lines from a profile's {@link SshTarget}.
 * Commands run in a terminal session so authentication and host-key prompts
 * work exactly like an interactive ssh login.
 */
public final class RemoteTransfer {
    private RemoteTransfer() {}

    public enum Method {
        SCP("scp"), RSYNC("rsync");
        public final String label;
        Method(String label) { this.label = label; }
        public static Method fromName(String s) {
            for (Method m : values()) if (m.name().equals(s)) return m;
            return SCP;
        }
    }

    /** Upload local files/folders into {@code remoteDir} (empty = remote home). */
    public static List<String> upload(Method method, SshTarget t,
                                      List<String> localPaths, String remoteDir) {
        String dest = remoteSpec(t.destination, asDirectory(remoteDir));
        List<String> cmd = new ArrayList<>();
        if (method == Method.RSYNC) {
            addRsync(cmd, t);
        } else {
            addScp(cmd, t);
        }
        cmd.addAll(localPaths);
        cmd.add(dest);
        return cmd;
    }

    /** Download a remote file or folder into {@code localDir}. */
    public static List<String> download(Method method, SshTarget t,
                                        String remotePath, String localDir) {
        return download(method, t, java.util.Collections.singletonList(remotePath), localDir);
    }

    /** Download remote files / folders (all on the same server) into {@code localDir}. */
    public static List<String> download(Method method, SshTarget t,
                                        List<String> remotePaths, String localDir) {
        List<String> cmd = new ArrayList<>();
        if (method == Method.RSYNC) {
            addRsync(cmd, t);
        } else {
            addScp(cmd, t);
        }
        for (String p : remotePaths) cmd.add(remoteSpec(t.destination, p));
        cmd.add(localDir.endsWith("/") ? localDir : localDir + "/");
        return cmd;
    }

    /** Interactive login shell on the server, starting in {@code remoteDir}. */
    public static List<String> shellAt(SshTarget t, String remoteDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(t.sshPath);
        cmd.addAll(t.options);
        cmd.add("-t");
        cmd.add(t.destination);
        // ssh joins the remote command with spaces and runs it with the
        // user's shell, so the folder is quoted for a POSIX shell.
        cmd.add("cd " + posixQuote(remoteDir) + " && exec \"${SHELL:-/bin/sh}\" -l");
        return cmd;
    }

    static String posixQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Interactive sftp; the local working directory is the session cwd. */
    public static List<String> sftp(SshTarget t, String remoteDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add("sftp");
        // scp/sftp otherwise exec an ssh path fixed at build time.
        cmd.add("-S");
        cmd.add(t.sshPath);
        cmd.addAll(t.options);
        String dir = remoteDir == null ? "" : remoteDir.trim();
        cmd.add(dir.isEmpty() ? t.destination : remoteSpec(t.destination, dir));
        return cmd;
    }

    private static void addScp(List<String> cmd, SshTarget t) {
        cmd.add("scp");
        cmd.add("-r");
        cmd.add("-S");
        cmd.add(t.sshPath);
        cmd.addAll(t.options);
    }

    private static void addRsync(List<String> cmd, SshTarget t) {
        cmd.add("rsync");
        // Recursive, keep mtimes, resumable partial files, live progress.
        // Permissions/owners are not copied: shared-storage files have
        // synthetic modes that would be meaningless on the server.
        cmd.add("-rthP");
        cmd.add("-e");
        cmd.add(rsyncRemoteShell(t.sshPath, t.options));
    }

    /** "/path/ssh opt1 opt2 ..." quoted for rsync's own -e word splitting. */
    static String rsyncRemoteShell(String sshPath, List<String> sshOptions) {
        StringBuilder sb = new StringBuilder(rsyncQuote(sshPath));
        for (String o : sshOptions) sb.append(' ').append(rsyncQuote(o));
        return sb.toString();
    }

    /**
     * rsync splits -e on spaces and honours '...' and "..." quoting, where a
     * doubled quote character inside a quoted section yields one literal quote.
     */
    static String rsyncQuote(String s) {
        boolean plain = !s.isEmpty();
        for (int i = 0; i < s.length() && plain; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\'' || c == '"') plain = false;
        }
        if (plain) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** host:path, bracketing IPv6 literals as scp/rsync require. */
    static String remoteSpec(String destination, String path) {
        String userPart = "";
        String host = destination;
        int at = destination.lastIndexOf('@');
        if (at >= 0) {
            userPart = destination.substring(0, at + 1);
            host = destination.substring(at + 1);
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        return userPart + host + ":" + (path == null ? "" : path);
    }

    /**
     * A trailing slash makes scp fail on a missing folder instead of silently
     * renaming a single uploaded item to that name.
     */
    static String asDirectory(String remoteDir) {
        String d = remoteDir == null ? "" : remoteDir.trim();
        if (d.isEmpty() || d.endsWith("/")) return d;
        return d + "/";
    }
}
