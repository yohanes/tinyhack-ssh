package com.tinyhack.ssh.ssh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages embedded ssh-agent lifecycle and key operations.
 * Uses native libssh-agent.so / libssh-add.so (OpenSSH 10.5p1) built for Android arm64.
 * Socket at {@code filesDir/agent.sock} and env {@code SSH_AUTH_SOCK} is injected into
 * new terminal sessions via TerminalService.
 *
 * Lock is a manual pause: while locked the agent refuses all operations (enforced
 * server-side in SshAgentServer). There is no biometric gate for loading keys —
 * passphrase-less keys are auto-loaded at agent start; Keystore security keys keep
 * their per-use biometric enforcement via AndroidKeyStore.
 */
public class SshAgentManager {
    private static final String TAG = "SshAgentManager";
    private static final String PREFS = "ssh_agent_prefs";
    private static final String KEY_AUTOSTART = "agent_autostart";
    // Prefer Java server for reliability under untrusted_app SELinux
    private SshAgentServer javaServer;
    private static final String KEY_LOCKED = "agent_locked";
    private static final String KEY_SOCKET_PATH = "agent_socket_path";

    private static SshAgentManager sInstance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private String socketPath;
    private File socketFile;
    private String actualSocketPath;
    private Process agentProcess;
    private final Object lock = new Object();

    private SshAgentManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File sock = new File(new File(appContext.getFilesDir(), "tmp"), "agent.sock");
        String canonical = sock.getAbsolutePath();
        try { canonical = sock.getCanonicalPath(); } catch (Exception ignored) {}
        this.socketPath = canonical;
        this.socketFile = new File(socketPath);
        this.actualSocketPath = socketPath;
        // Ensure prefs have defaults
        if (!prefs.contains(KEY_AUTOSTART)) prefs.edit().putBoolean(KEY_AUTOSTART, true).apply();
        if (!prefs.contains(KEY_LOCKED)) prefs.edit().putBoolean(KEY_LOCKED, false).apply();
        // Legacy pref from the removed biometric load gate
        prefs.edit().remove("agent_use_biometric").apply();
    }

    public static synchronized SshAgentManager getInstance(Context ctx) {
        if (sInstance == null) sInstance = new SshAgentManager(ctx);
        return sInstance;
    }

    public String getSocketPath() {
        // If Java server is running and has actual path, return that
        if (javaServer != null && javaServer.isRunning()) {
            String actual = javaServer.getActualSocketPath();
            if (actual != null) return actual;
        }
        if (actualSocketPath != null) return actualSocketPath;
        return socketPath;
    }

    public String getAgentEnv() {
        return "SSH_AUTH_SOCK=" + getSocketPath();
    }

    public boolean isAgentRunning() {
        synchronized (lock) {
            // Prefer Java server
            if (javaServer != null && javaServer.isRunning()) {
                return true;
            }
            if (agentProcess != null) {
                try {
                    agentProcess.exitValue();
                    agentProcess = null;
                } catch (IllegalThreadStateException e) {
                    // still running
                }
            }
            if (!socketFile.exists()) {
                if (agentProcess != null) {
                    return true;
                }
                return false;
            }
            try {
                ExecResult r = execSshAdd(new String[]{"-l"}, 2000);
                return r.exitCode == 0 || r.exitCode == 1;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public boolean isLocked() {
        return prefs.getBoolean(KEY_LOCKED, false);
    }

    public void setLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_LOCKED, locked).apply();
        // Enforce on the running Java agent as well: while locked it refuses
        // identities/signing/adds at the protocol level (OpenSSH semantics).
        SshAgentServer server = javaServer;
        if (server != null && server.isRunning()) {
            server.setLocked(locked);
        }
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTOSTART, true);
    }

    public void setAutoStart(boolean auto) {
        prefs.edit().putBoolean(KEY_AUTOSTART, auto).apply();
    }

    private String getSshAgentBin() {
        String nativeLib = appContext.getApplicationInfo().nativeLibraryDir + "/libssh-agent.so";
        if (new File(nativeLib).exists()) return nativeLib;
        File fallback = new File(appContext.getFilesDir(), "usr/bin/ssh-agent");
        if (fallback.exists()) return fallback.getAbsolutePath();
        // Last resort: try system ssh-agent (unlikely on Android)
        return "ssh-agent";
    }

    private String getSshAddBin() {
        String nativeLib = appContext.getApplicationInfo().nativeLibraryDir + "/libssh-add.so";
        if (new File(nativeLib).exists()) return nativeLib;
        File fallback = new File(appContext.getFilesDir(), "usr/bin/ssh-add");
        if (fallback.exists()) return fallback.getAbsolutePath();
        return "ssh-add";
    }

    private File getHomeDir() {
        return new File(appContext.getFilesDir(), "home");
    }

    public synchronized boolean startAgent() {
        synchronized (lock) {
            if (isAgentRunning()) {
                Log.i(TAG, "Agent already running at " + socketPath);
                return true;
            }
            // Try Java server first (pure-Java, no native binary, works under untrusted_app)
            try {
                if (javaServer == null) javaServer = new SshAgentServer(appContext, socketPath);
                if (javaServer.start()) {
                    String actual = javaServer.getActualSocketPath();
                    Log.i(TAG, "Java SSH agent started at " + actual + " (requested " + socketPath + ")");
                    actualSocketPath = actual;
                    setLocked(false);
                    agentProcess = null;
                    scheduleAutoLoad();
                    return true;
                } else {
                    Log.w(TAG, "Java agent start returned false, trying native");
                }
            } catch (Exception e) {
                Log.w(TAG, "Java agent failed, falling back to native", e);
            }
            // Kill any stale daemon that may still hold socket (e.g., from previous manual run)
            try {
                ProcessBuilder kpb = new ProcessBuilder("/system/bin/sh", "-c", "pkill -9 ssh-agent 2>/dev/null; killall -9 ssh-agent 2>/dev/null; busybox pkill -9 ssh-agent 2>/dev/null; busybox killall -9 ssh-agent 2>/dev/null; rm -f " + socketPath);
                kpb.redirectErrorStream(true);
                Process kp = kpb.start();
                kp.waitFor(1, TimeUnit.SECONDS);
                Thread.sleep(200);
            } catch (Exception ignored) {}
            // Clean stale socket
            if (socketFile.exists()) {
                try {
                    socketFile.delete();
                    try { Os.remove(socketPath); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
            // Ensure parent dir exists and has correct perms
            try {
                File parent = socketFile.getParentFile();
                if (parent != null) parent.mkdirs();
                File tmpDir = new File(appContext.getFilesDir(), "tmp");
                tmpDir.mkdirs();
                Os.chmod(tmpDir.getAbsolutePath(), 0700);
                File home = getHomeDir();
                home.mkdirs();
                Os.chmod(home.getAbsolutePath(), 0700);
            } catch (Exception ignored) {}

            String sshAgentBin = getSshAgentBin();
            // Ensure canonical HOME for ssh-agent
            String homePath = getHomeDir().getAbsolutePath();
            try { homePath = getHomeDir().getCanonicalPath(); } catch (Exception ignored) {}
            Log.i(TAG, "Starting ssh-agent: " + sshAgentBin + " -a " + socketPath + " HOME=" + homePath);
            try {
                // Use sh -c with HOME prefix to match successful manual invocation
                String shCmd = "HOME=" + homePath + " " + sshAgentBin + " -a " + socketPath;
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", shCmd);
                pb.directory(getHomeDir());
                // Also set via environment for completeness
                pb.environment().put("HOME", homePath);
                pb.environment().put("SHELL", new File(appContext.getApplicationInfo().nativeLibraryDir, "libbash.so").exists() ?
                        new File(appContext.getApplicationInfo().nativeLibraryDir, "libbash.so").getAbsolutePath() : "/system/bin/sh");
                String oldPath = pb.environment().get("PATH");
                pb.environment().put("PATH", appContext.getFilesDir().getAbsolutePath() + "/usr/bin:" + (oldPath != null ? oldPath : "/system/bin"));
                String ldPath = pb.environment().get("LD_LIBRARY_PATH");
                String nativeLib = appContext.getApplicationInfo().nativeLibraryDir;
                if (ldPath == null || !ldPath.contains(nativeLib)) {
                    pb.environment().put("LD_LIBRARY_PATH", nativeLib + (ldPath != null ? ":" + ldPath : ""));
                }
                Log.i(TAG, "Env HOME=" + homePath + " PATH=" + pb.environment().get("PATH") + " LD_LIBRARY_PATH=" + pb.environment().get("LD_LIBRARY_PATH"));

                pb.redirectErrorStream(true);
                agentProcess = pb.start();

                // Drain output in background to avoid blocking
                final Process proc = agentProcess;
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            Log.d(TAG, "ssh-agent: " + line);
                        }
                    } catch (Exception ignored) {}
                }, "ssh-agent-log").start();

                // Wait for socket to appear, up to 3 seconds
                // For daemonized ssh-agent, the parent sh will exit with 0 quickly, but daemon holds socket
                // So we wait for socket regardless of parent exit code, only fail on non-zero exit without socket
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(200);
                    if (socketFile.exists()) {
                        try {
                            Os.chmod(socketPath, 0600);
                        } catch (Exception ignored) {}
                        Log.i(TAG, "ssh-agent started, socket at " + socketPath);
                        setLocked(false);
                        // Parent sh has likely exited, clear agentProcess reference but keep socket
                        // Keep a dummy process reference? We will clear and rely on socket existence for isRunning
                        // But we can keep proc as is; it will be considered not running via exitValue, but socket check will still pass
                        scheduleAutoLoad();
                        return true;
                    }
                    try {
                        int exit = proc.exitValue();
                        if (exit != 0) {
                            Log.w(TAG, "ssh-agent exited quickly with " + exit);
                            // Capture output
                            try (BufferedReader r2 = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = r2.readLine()) != null) sb.append(line).append("\n");
                                if (sb.length() > 0) Log.w(TAG, "ssh-agent output: " + sb);
                            } catch (Exception ignored) {}
                            agentProcess = null;
                            return false;
                        }
                        // exit 0 but no socket yet – continue waiting, daemon may still be starting
                    } catch (IllegalThreadStateException ignored) {
                        // still running (for -D mode)
                    }
                }
                Log.w(TAG, "ssh-agent socket not created after 3s");
                // Check if daemon is running via socket existence already handled; if not, try to see if process is still alive
                try {
                    int exit = proc.exitValue();
                    Log.w(TAG, "ssh-agent final exit " + exit);
                } catch (IllegalThreadStateException e) {
                    Log.w(TAG, "ssh-agent still running but no socket");
                }
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start ssh-agent", e);
                agentProcess = null;
                return false;
            }
        }
    }

    public synchronized boolean stopAgent() {
        synchronized (lock) {
            boolean wasRunning = isAgentRunning();
            // Stop Java server first
            if (javaServer != null) {
                try { javaServer.stop(); } catch (Exception e) { Log.w(TAG, "Error stopping Java agent", e); }
            }
            if (agentProcess != null) {
                try {
                    agentProcess.destroy();
                    if (!agentProcess.waitFor(2, TimeUnit.SECONDS)) {
                        agentProcess.destroyForcibly();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping agent process", e);
                } finally {
                    agentProcess = null;
                }
            }
            // Also try to kill daemonized ssh-agent via ssh-agent -k or pkill
            try {
                // Try ssh-agent -k via SSH_AUTH_SOCK
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", "SSH_AUTH_SOCK=" + socketPath + " " + getSshAgentBin() + " -k 2>&1; rm -f " + socketPath);
                pb.environment().put("HOME", getHomeDir().getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            try {
                // Fallback pkill via busybox
                ProcessBuilder pb2 = new ProcessBuilder("/system/bin/sh", "-c", "pkill -f ssh-agent; killall ssh-agent 2>/dev/null; rm -f " + socketPath);
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                p2.waitFor(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            // Remove socket file
            if (socketFile.exists()) {
                try {
                    socketFile.delete();
                    try { Os.remove(socketPath); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
            setLocked(false);
            Log.i(TAG, "ssh-agent stopped");
            return wasRunning;
        }
    }

    /**
     * Auto-load passphrase-less keys in the background after the agent comes up
     * (Termux-style). Passphrase-protected keys fail fast without an askpass and
     * are simply skipped; add them manually via ssh-add or the UI.
     */
    private void scheduleAutoLoad() {
        new Thread(() -> {
            try {
                boolean ok = addAllKeys();
                Log.i(TAG, "Auto-load keys: " + (ok ? "ok" : "partial (passphrase keys skipped)"));
            } catch (Exception e) {
                Log.w(TAG, "Auto-load keys failed", e);
            }
        }, "ssh-agent-autoload").start();
    }

    public synchronized boolean ensureAgentRunning() {
        if (isAutoStart() && !isAgentRunning()) {
            return startAgent();
        }
        return isAgentRunning();
    }

    // Execute ssh-add with given args, with SSH_AUTH_SOCK env
    private ExecResult execSshAdd(String[] args, long timeoutMs) throws Exception {
        String sshAddBin = getSshAddBin();
        List<String> cmd = new ArrayList<>();
        cmd.add(sshAddBin);
        if (args != null) {
            for (String a : args) cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("HOME", getHomeDir().getAbsolutePath());
        pb.environment().put("SSH_AUTH_SOCK", getSocketPath());
        pb.environment().put("SSH_ASKPASS_REQUIRE", "never");
        // Ensure PATH
        String oldPath = pb.environment().get("PATH");
        pb.environment().put("PATH", appContext.getFilesDir().getAbsolutePath() + "/usr/bin:" + (oldPath != null ? oldPath : "/system/bin"));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("\n");
            }
        }
        boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        int exit = finished ? proc.exitValue() : 124;
        if (!finished) {
            proc.destroyForcibly();
        }
        return new ExecResult(exit, out.toString());
    }

    private ExecResult execSshAddWithPassphrase(String keyPath, String passphrase, long timeoutMs) throws Exception {
        if (passphrase == null || passphrase.isEmpty()) {
            return execSshAdd(new String[]{keyPath}, timeoutMs);
        }
        // Passphrase is delivered via the native askpass helper (libaskpass.so):
        // no shell interpolation, no argv, no data-dir script exec (SELinux).
        File passFile = SshKeyManager.writePassphraseFile(appContext, passphrase);
        try {
            String sshAddBin = getSshAddBin();
            List<String> cmd = new ArrayList<>();
            cmd.add(sshAddBin);
            cmd.add(keyPath);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("HOME", getHomeDir().getAbsolutePath());
            pb.environment().put("SSH_AUTH_SOCK", getSocketPath());
            SshKeyManager.applyAskpassEnv(appContext, pb, passFile);
            String oldPath = pb.environment().get("PATH");
            pb.environment().put("PATH", appContext.getFilesDir().getAbsolutePath() + "/usr/bin:" + (oldPath != null ? oldPath : "/system/bin"));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
            }
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            int exit = finished ? proc.exitValue() : 124;
            if (!finished) proc.destroyForcibly();
            return new ExecResult(exit, out.toString());
        } finally {
            SshKeyManager.wipePassphraseFile(passFile);
        }
    }

    public synchronized List<AgentKeyInfo> listKeys() {
        List<AgentKeyInfo> result = new ArrayList<>();
        if (!isAgentRunning()) return result;
        try {
            ExecResult r = execSshAdd(new String[]{"-l"}, 3000);
            String out = r.out.trim();
            if (r.exitCode == 1 && out.contains("The agent has no identities")) {
                return result;
            }
            if (r.exitCode != 0 && r.exitCode != 1) {
                Log.w(TAG, "ssh-add -l failed: " + out);
                return result;
            }
            // Parse lines like "256 SHA256:xxx user@host (ED25519)" or "4096 SHA256:... (RSA)"
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.contains("The agent has no identities")) continue;
                // Only accept real identity lines like "256 SHA256:xxx comment (ED25519)";
                // skips error text such as "error fetching identities: agent refused operation"
                if (!line.matches("\\d+ SHA256:\\S+.*")) continue;
                // Example: 256 SHA256:abcdef... tinyhack@android (ED25519)
                // We can parse fingerprint and comment
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String bits = parts[0];
                    String fingerprint = parts[1];
                    String comment = "";
                    String type = "Unknown";
                    if (parts.length >= 3) {
                        // Find parentheses for type
                        String last = parts[parts.length-1];
                        if (last.startsWith("(") && last.endsWith(")")) {
                            type = last.substring(1, last.length()-1);
                            // comment is between fingerprint and type
                            if (parts.length > 3) {
                                StringBuilder cb = new StringBuilder();
                                for (int i=2;i<parts.length-1;i++) {
                                    if (cb.length()>0) cb.append(" ");
                                    cb.append(parts[i]);
                                }
                                comment = cb.toString();
                            }
                        } else {
                            // No type parentheses, rest is comment
                            StringBuilder cb = new StringBuilder();
                            for (int i=2;i<parts.length;i++) {
                                if (cb.length()>0) cb.append(" ");
                                cb.append(parts[i]);
                            }
                            comment = cb.toString();
                        }
                    }
                    result.add(new AgentKeyInfo(bits, fingerprint, comment, type, line));
                } else {
                    result.add(new AgentKeyInfo("", line, "", "Unknown", line));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listKeys failed", e);
        }
        return result;
    }

    public synchronized boolean addKey(String privateKeyPath, String passphrase) {
        if (!isAgentRunning()) {
            if (!startAgent()) return false;
        }
        if (isLocked()) {
            Log.w(TAG, "Agent locked; unlock before adding keys");
            return false;
        }
        try {
            File keyFile = new File(privateKeyPath);
            if (!keyFile.exists()) {
                Log.w(TAG, "Key file not found: " + privateKeyPath);
                return false;
            }
            SshKeyManager.AndroidSecurityKey securityKey =
                    SshKeyManager.readAndroidSecurityKey(keyFile);
            if (securityKey != null) {
                if (javaServer == null) {
                    Log.w(TAG, "Security keys require the Java agent (native agent in use)");
                    return false;
                }
                boolean added = javaServer.addAndroidSecurityKey(securityKey);
                Log.i(TAG, "Add Android security key " + securityKey.alias + " result=" + added);
                return added;
            }
            ExecResult r = execSshAddWithPassphrase(keyFile.getAbsolutePath(), passphrase, 5000);
            Log.i(TAG, "ssh-add " + keyFile.getName() + " exit=" + r.exitCode + " out=" + r.out);
            return r.exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "addKey failed", e);
            return false;
        }
    }

    public synchronized boolean addAllKeys() {
        if (!isAgentRunning()) {
            if (!startAgent()) return false;
        }
        if (isLocked()) {
            Log.w(TAG, "Agent locked; unlock before adding keys");
            return false;
        }
        List<SshKeyInfo> keys = SshKeyManager.listKeys(appContext);
        boolean allOk = true;
        for (SshKeyInfo k : keys) {
            if (k.getPrivateFile() != null && k.getPrivateFile().exists()) {
                boolean ok = addKey(k.getPrivateFile().getAbsolutePath(), null);
                if (!ok) allOk = false;
            }
        }
        return allOk;
    }

    public synchronized boolean removeAllKeys() {
        if (!isAgentRunning()) return false;
        try {
            ExecResult r = execSshAdd(new String[]{"-D"}, 3000);
            Log.i(TAG, "ssh-add -D exit=" + r.exitCode + " out=" + r.out);
            return r.exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "removeAll failed", e);
            return false;
        }
    }

    public synchronized boolean removeKey(String fingerprintOrPath) {
        if (!isAgentRunning()) return false;
        try {
            // Direct path (exists on disk): let ssh-add -d handle it
            File f = new File(fingerprintOrPath);
            if (f.exists()) {
                ExecResult r = execSshAdd(new String[]{"-d", f.getAbsolutePath()}, 3000);
                return r.exitCode == 0;
            }
            // Otherwise resolve a SHA256 fingerprint to its key file in ~/.ssh
            for (SshKeyInfo k : SshKeyManager.listKeys(appContext)) {
                if (fingerprintOrPath.equals(k.getFingerprint()) && k.getPrivateFile() != null) {
                    ExecResult r = execSshAdd(new String[]{"-d", k.getPrivateFile().getAbsolutePath()}, 3000);
                    return r.exitCode == 0;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "removeKey failed", e);
            return false;
        }
    }

    public synchronized int getKeyCount() {
        return listKeys().size();
    }

    public static class AgentKeyInfo {
        public final String bits;
        public final String fingerprint;
        public final String comment;
        public final String type;
        public final String rawLine;
        public AgentKeyInfo(String bits, String fingerprint, String comment, String type, String rawLine) {
            this.bits = bits;
            this.fingerprint = fingerprint;
            this.comment = comment;
            this.type = type;
            this.rawLine = rawLine;
        }
    }

    private static class ExecResult {
        final int exitCode;
        final String out;
        ExecResult(int exitCode, String out) {
            this.exitCode = exitCode;
            this.out = out;
        }
    }
}
