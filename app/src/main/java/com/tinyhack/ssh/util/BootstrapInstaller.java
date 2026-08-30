package com.tinyhack.ssh.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class BootstrapInstaller {
    private static final String TAG = "BootstrapInstaller";

    public static void installIfNeeded(Context context) {
        File filesDir = context.getFilesDir();
        File homeDir = new File(filesDir, "home");
        File sshDir = new File(homeDir, ".ssh");
        File usrDir = new File(filesDir, "usr");
        File binDir = new File(usrDir, "bin");
        File etcSshDir = new File(usrDir, "etc/ssh");
        File tmpDir = new File(usrDir, "tmp");

        homeDir.mkdirs();
        sshDir.mkdirs();
        binDir.mkdirs();
        etcSshDir.mkdirs();
        tmpDir.mkdirs();

        try {
            Os.chmod(homeDir.getAbsolutePath(), 0700);
            Os.chmod(sshDir.getAbsolutePath(), 0700);
            File[] sshFiles = sshDir.listFiles();
            if (sshFiles != null) {
                for (File f : sshFiles) {
                    if (f.getName().endsWith(".pub") || f.getName().equals("known_hosts") || f.getName().equals("known_hosts2") || f.getName().equals("config")) {
                        Os.chmod(f.getAbsolutePath(), 0644);
                    } else if (f.isFile()) {
                        Os.chmod(f.getAbsolutePath(), 0600);
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            copyAssets(context, "usr", usrDir);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy assets", e);
        }

        File bashrc = new File(homeDir, ".bashrc");
        if (!bashrc.exists()) {
            try (FileOutputStream fos = new FileOutputStream(bashrc)) {
                String content = "export PS1='\\[\\033[01;32m\\]tinyhack@android\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '\n"
                        + "export HISTSIZE=5000\n"
                        + "export HISTFILESIZE=10000\n"
                        + "export HISTCONTROL=ignoreboth:erasedups\n"
                        + "shopt -s histappend 2>/dev/null || true\n"
                        + "alias ls='ls --color=auto'\n"
                        + "alias ll='ls -la'\n"
                        + "alias la='ls -A'\n";
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        }
        ensureBashIntegration(homeDir);

        File bashProfile = new File(homeDir, ".bash_profile");
        if (!bashProfile.exists()) {
            try (FileOutputStream fos = new FileOutputStream(bashProfile)) {
                String content = "if [ -f ~/.bashrc ]; then\n  . ~/.bashrc\nfi\n";
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        }

        // Link native library executables into files/usr/bin
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        String[] binaries = {"busybox", "bash", "ssh", "ssh-keygen", "ssh-keyscan", "scp", "sftp", "rsync", "ssh-agent", "ssh-add", "mosh", "mosh-client", "cloudflared"};
        java.util.Set<String> standaloneBinaries = new java.util.HashSet<>(java.util.Arrays.asList(binaries));
        standaloneBinaries.add("sh");

        for (String b : binaries) {
            File link = new File(binDir, b);
            File target = new File(nativeLibDir, "lib" + b + ".so");
            if (target.exists()) {
                try {
                    try {
                        Os.remove(link.getAbsolutePath());
                    } catch (Exception ignored) {}
                    link.delete();
                    Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
                    Log.d(TAG, "Symlinked " + link + " -> " + target);
                } catch (Exception e) {
                    try (FileOutputStream fos = new FileOutputStream(link)) {
                        String script = "#!/system/bin/sh\nexec \"" + target.getAbsolutePath() + "\" \"$@\"\n";
                        fos.write(script.getBytes(StandardCharsets.UTF_8));
                        link.setExecutable(true, false);
                    } catch (IOException ignored) {}
                }
            }
        }

        // Symlink sh -> bash
        File shLink = new File(binDir, "sh");
        File bashTarget = new File(nativeLibDir, "libbash.so");
        if (bashTarget.exists()) {
            try {
                try { Os.remove(shLink.getAbsolutePath()); } catch (Exception ignored) {}
                shLink.delete();
                Os.symlink(bashTarget.getAbsolutePath(), shLink.getAbsolutePath());
            } catch (Exception ignored) {}
        }

        // Link all BusyBox applets
        File busyboxTarget = new File(nativeLibDir, "libbusybox.so");
        if (busyboxTarget.exists()) {
            String[] busyboxApplets = {
                "[", "[[", "ar", "arch", "ascii", "awk", "base32", "base64", "basename", "bbconfig",
                "bc", "beep", "bootchartd", "bunzip2", "bzcat", "bzip2", "cal", "cat", "chat", "chattr",
                "chgrp", "chmod", "chown", "chpst", "chrt", "chvt", "cksum", "clear", "cmp", "comm",
                "cp", "cpio", "crc32", "crond", "crontab", "cttyhack", "cut", "dc", "dd", "deallocvt",
                "devmem", "diff", "dirname", "dmesg", "dnsd", "dnsdomainname", "dos2unix", "dpkg", "dpkg-deb", "du",
                "dumpkmap", "echo", "ed", "egrep", "env", "envdir", "envuidgid", "expand", "expr", "factor",
                "fakeidentd", "fallocate", "false", "fatattr", "fbsplash", "fgconsole", "fgrep", "find", "findfs", "flash_lock",
                "flash_unlock", "flashcp", "flock", "fold", "free", "fsfreeze", "fsync", "ftpd", "ftpget", "ftpput",
                "fuser", "getfattr", "getopt", "grep", "gunzip", "gzip", "hd", "hdparm", "head", "hexdump",
                "hexedit", "httpd", "hwclock", "i2cdetect", "i2cdump", "i2cget", "i2cset", "i2ctransfer", "inotifyd", "install",
                "iostat", "ipcalc", "kill", "killall", "killall5", "less", "link", "linux32", "linux64", "linuxrc",
                "ln", "logger", "lpd", "lpq", "lpr", "ls", "lsattr", "lsblk", "lsof", "lspci",
                "lsscsi", "lsusb", "lzcat", "lzma", "lzop", "makedevs", "makemime", "man", "md5sum", "mesg",
                "mim", "mkdir", "mkdosfs", "mke2fs", "mkfifo", "mktemp", "more", "mpstat", "mv", "nanddump",
                "nc", "nice", "nl", "nmeter", "nohup", "nologin", "nproc", "od", "openvt", "partprobe",
                "paste", "patch", "pgrep", "pidof", "pipe_progress", "pkill", "pmap", "popmaildir", "powertop", "printenv",
                "printf", "ps", "pscan", "pstree", "pwd", "pwdx", "raidautorun", "rdev", "readlink", "readprofile",
                "realpath", "reformime", "renice", "reset", "resize", "resume", "rev", "rm", "rmdir", "rpm",
                "rpm2cpio", "rtcwake", "run-init", "run-parts", "runsv", "runsvdir", "rx", "script", "scriptreplay", "sed",
                "seedrng", "sendmail", "seq", "setconsole", "setfattr", "setlogcons", "setserial", "setsid", "setuidgid",
                "sha1sum", "sha256sum", "sha384sum", "sha3sum", "sha512sum", "shred", "shuf", "sleep", "smemcap", "softlimit",
                "sort", "split", "ssl_client", "ssl_server", "start-stop-daemon", "strings", "stty", "sum", "sv", "svc",
                "svlogd", "svok", "switch_root", "sync", "sysctl", "tac", "tail", "tar", "tcpsvd", "tee",
                "telnet", "telnetd", "test", "tftp", "tftpd", "time", "timeout", "touch", "tr",
                "tree", "true", "truncate", "ts", "tsort", "ttysize", "tune2fs", "udpsvd", "uevent", "uname",
                "uncompress", "unexpand", "uniq", "unix2dos", "unlink", "unlzma", "unxz", "unzip", "uptime", "usleep",
                "uudecode", "uuencode", "uuidgen", "vi", "vmstat", "volname", "watch", "wc", "wget", "which",
                "whoami", "whois", "xargs", "xxd", "xz", "xzcat", "yes", "zcat"
            };

            for (String applet : busyboxApplets) {
                if (standaloneBinaries.contains(applet)) continue;
                File appletLink = new File(binDir, applet);
                try {
                    try { Os.remove(appletLink.getAbsolutePath()); } catch (Exception ignored) {}
                    appletLink.delete();
                    Os.symlink(busyboxTarget.getAbsolutePath(), appletLink.getAbsolutePath());
                } catch (Exception ignored) {}
            }
        }

        // busybox top hard-fails reading /proc/stat ("can't open 'stat':
        // Permission denied") — SELinux denies untrusted_app access to proc_stat.
        // The system toybox top degrades gracefully, so prefer it when present.
        // (vmstat stays on busybox: toybox vmstat also fails — /proc/uptime is
        // equally SELinux-denied, so no implementation can work for apps.)
        {
            File topLink = new File(binDir, "top");
            File sysTop = new File("/system/bin/top");
            File target = sysTop.exists() ? sysTop : new File(nativeLibDir, "libbusybox.so");
            if (target.exists()) {
                try {
                    try { Os.remove(topLink.getAbsolutePath()); } catch (Exception ignored) {}
                    topLink.delete();
                    Os.symlink(target.getAbsolutePath(), topLink.getAbsolutePath());
                    Log.d(TAG, "Symlinked " + topLink + " -> " + target);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void ensureBashIntegration(File homeDir) {
        File bashrc = new File(homeDir, ".bashrc");
        if (!bashrc.exists()) return;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8);
            if (content.contains("Tinyhack SSH OSC 133") || content.contains("__tinyhack_ssh_precmd")) return;
            String snippet = "\n# Tinyhack SSH shell integration - semantic prompts (OSC 133)\n"
                    + "# Enables jump-to-prompt, copy output, and smart resize in Ghostty.\n"
                    + "if [[ -n \"$BASH_VERSION\" ]]; then\n"
                    + "  __tinyhack_ssh_precmd() {\n"
                    + "    local ret=$?\n"
                    + "    printf '\\033]133;D;%s\\007' \"$ret\"\n"
                    + "    printf '\\033]133;A\\007'\n"
                    + "  }\n"
                    + "  __tinyhack_ssh_preexec() { printf '\\033]133;C\\007'; }\n"
                    + "  if [[ \"$PS1\" != *\"133;\"* ]]; then\n"
                    + "    PS1='\\[\\033]133;A\\007\\]'\"$PS1\"'\\[\\033]133;B\\007\\]'\n"
                    + "    PS0='\\[\\033]133;C\\007\\]'\n"
                    + "    if [[ -z \"${PROMPT_COMMAND:-}\" ]]; then\n"
                    + "      PROMPT_COMMAND=\"__tinyhack_ssh_precmd\"\n"
                    + "    elif [[ \"$PROMPT_COMMAND\" != *\"__tinyhack_ssh_precmd\"* ]]; then\n"
                    + "      PROMPT_COMMAND=\"__tinyhack_ssh_precmd;$PROMPT_COMMAND\"\n"
                    + "    fi\n"
                    + "  fi\n"
                    + "fi\n";
            try (FileOutputStream fos = new FileOutputStream(bashrc, true)) {
                fos.write(snippet.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static void copyAssets(Context context, String assetPath, File targetDir) throws IOException {
        AssetManager assetManager = context.getAssets();
        String[] assets = assetManager.list(assetPath);
        if (assets == null || assets.length == 0) {
            copyAssetFile(context, assetPath, targetDir);
        } else {
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            for (String asset : assets) {
                String subAssetPath = assetPath + "/" + asset;
                File subTargetDir = new File(targetDir, asset);
                String[] subAssets = assetManager.list(subAssetPath);
                if (subAssets != null && subAssets.length > 0) {
                    copyAssets(context, subAssetPath, subTargetDir);
                } else {
                    copyAssetFile(context, subAssetPath, subTargetDir);
                }
            }
        }
    }

    private static void copyAssetFile(Context context, String assetPath, File targetFile) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
