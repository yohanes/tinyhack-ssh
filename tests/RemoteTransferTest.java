import com.tinyhack.ssh.files.RemoteTransfer;
import com.tinyhack.ssh.ssh.SshTarget;

import java.util.Arrays;
import java.util.List;

public class RemoteTransferTest {
    private static void eq(Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    private static SshTarget target(String destination, String... options) {
        SshTarget t = new SshTarget();
        t.destination = destination;
        t.sshPath = "/app/lib/libssh.so";
        t.options.addAll(Arrays.asList(options));
        return t;
    }

    public static void main(String[] args) {
        // Extra profile ssh args: keep what scp/sftp understand, drop the rest.
        eq(SshTarget.filterTransferSshArgs(
                "-L 8080:localhost:80 -t -A -o StrictHostKeyChecking=accept-new -p 2200 -l bob -J jump -4Cv -N -R 9:x:9 -oCompression=yes -ikey".split(" ")),
                Arrays.asList("-o", "StrictHostKeyChecking=accept-new", "-o", "Port=2200", "-o", "User=bob",
                        "-J", "jump", "-4", "-C", "-v", "-o", "Compression=yes", "-i", "key"),
                "filterTransferSshArgs");
        eq(SshTarget.filterTransferSshArgs(new String[]{"tmux", "attach", "-o"}), List.of(), "positional + dangling");
        eq(SshTarget.filterTransferSshArgs(new String[]{"-tp", "2022"}), List.of("-o", "Port=2022"), "cluster ending in arg flag");

        SshTarget t = target("me@host", "-i", "/k", "-o", "Port=2222");
        eq(RemoteTransfer.upload(RemoteTransfer.Method.SCP, t, List.of("/s/a b.txt", "/s/dir"), "uploads"),
                List.of("scp", "-r", "-S", "/app/lib/libssh.so", "-i", "/k", "-o", "Port=2222",
                        "/s/a b.txt", "/s/dir", "me@host:uploads/"),
                "scp upload");
        eq(RemoteTransfer.upload(RemoteTransfer.Method.SCP, t, List.of("/s/x"), "  "),
                List.of("scp", "-r", "-S", "/app/lib/libssh.so", "-i", "/k", "-o", "Port=2222", "/s/x", "me@host:"),
                "scp upload to home");
        eq(RemoteTransfer.download(RemoteTransfer.Method.SCP, t, "my folder", "/storage/emulated/0/Download"),
                List.of("scp", "-r", "-S", "/app/lib/libssh.so", "-i", "/k", "-o", "Port=2222",
                        "me@host:my folder", "/storage/emulated/0/Download/"),
                "scp download");
        eq(RemoteTransfer.sftp(t, ""),
                List.of("sftp", "-S", "/app/lib/libssh.so", "-i", "/k", "-o", "Port=2222", "me@host"),
                "sftp home");

        // rsync -e is re-split by rsync itself: quote words with spaces/quotes.
        SshTarget cf = target("fe80::1", "-o", "ProxyCommand='/lib/libcloudflared.so' access ssh --hostname 'a.example'",
                "-i", "/home/key \"x\"");
        List<String> rs = RemoteTransfer.upload(RemoteTransfer.Method.RSYNC, cf, List.of("/s/f"), "/srv/in/");
        eq(rs, List.of("rsync", "-rthP", "-e",
                "/app/lib/libssh.so -o \"ProxyCommand='/lib/libcloudflared.so' access ssh --hostname 'a.example'\" -i \"/home/key \"\"x\"\"\"",
                "/s/f", "[fe80::1]:/srv/in/"), "rsync upload with IPv6 + ProxyCommand");

        eq(RemoteTransfer.download(RemoteTransfer.Method.RSYNC, target("h"), List.of("/a b", "/c"), "/dl"),
                List.of("rsync", "-rthP", "-e", "/app/lib/libssh.so", "h:/a b", "h:/c", "/dl/"),
                "rsync multi download");
        eq(RemoteTransfer.shellAt(t, "/srv/it's here"),
                List.of("/app/lib/libssh.so", "-i", "/k", "-o", "Port=2222", "-t", "me@host",
                        "cd '/srv/it'\\''s here' && exec \"${SHELL:-/bin/sh}\" -l"),
                "remote shell in folder");

        System.out.println("Remote transfer command tests passed");
    }
}
