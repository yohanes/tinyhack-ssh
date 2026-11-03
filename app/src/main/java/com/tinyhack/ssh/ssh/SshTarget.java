package com.tinyhack.ssh.ssh;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenSSH connection options for a connection profile. The options are
 * accepted identically by ssh, scp and sftp, so terminal sessions and file
 * transfers share the same identity, port, Cloudflare Access and extra-argument
 * handling. Built by {@code TerminalService.buildSshTarget}.
 */
public final class SshTarget {
    /** Options to place before the destination (never includes the program name). */
    public final List<String> options = new ArrayList<>();
    /** user@host, or null when the profile has no host. */
    public String destination;
    /** Extra environment (Cloudflare service token), or null. */
    public String[] envp;
    /** Non-null when the profile cannot be used; user-facing reason. */
    public String error;
    /** Absolute path of the bundled ssh client (for scp -S / sftp -S / rsync -e). */
    public String sshPath = "ssh";

    /** ssh flags that consume an argument (from ssh(1) usage). */
    private static final String SSH_FLAGS_WITH_ARG = "BbcDEeFIiJLlmOoPpQRSWw";

    /**
     * Keep only the extra ssh arguments that scp and sftp also understand
     * with the same meaning. Port forwards, TTY flags and remote commands are
     * dropped; -p/-l are rewritten as -o Port=/-o User= because scp/sftp
     * assign different meanings to those letters.
     */
    public static List<String> filterTransferSshArgs(String[] tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.length() < 2 || tok.charAt(0) != '-' || tok.startsWith("--")) continue;
            for (int j = 1; j < tok.length(); j++) {
                char c = tok.charAt(j);
                if (SSH_FLAGS_WITH_ARG.indexOf(c) >= 0) {
                    String val = j + 1 < tok.length() ? tok.substring(j + 1)
                            : (i + 1 < tokens.length ? tokens[++i] : null);
                    if (val == null || val.isEmpty()) break;
                    switch (c) {
                        case 'o': case 'i': case 'J': case 'F': case 'c':
                            out.add("-" + c);
                            out.add(val);
                            break;
                        case 'p':
                            out.add("-o");
                            out.add("Port=" + val);
                            break;
                        case 'l':
                            out.add("-o");
                            out.add("User=" + val);
                            break;
                        default:
                            break; // -L/-R/-D forwards, -b, -e, -m, -W ...: not for transfers
                    }
                    break;
                }
                if ("46Cv".indexOf(c) >= 0) out.add("-" + c);
            }
        }
        return out;
    }
}
