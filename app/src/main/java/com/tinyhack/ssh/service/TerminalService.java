package com.tinyhack.ssh.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tinyhack.ssh.MainActivity;
import com.tinyhack.ssh.R;
import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.session.TerminalSession;
import com.tinyhack.ssh.ssh.SshAgentManager;
import com.tinyhack.ssh.ssh.SshKeyManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TerminalService extends Service {
    private static final String TAG = "TerminalService";
    private static final String CHANNEL_ID = "tinyhack_sessions_channel";
    private static final int NOTIFICATION_ID = 1337;
    private static final String PREFS_NAME = "tinyhack_ssh_prefs";
    private static final String KEY_HTTP_DEBUG_ENABLED = "http_debug_enabled";
    private static final String KEY_STORAGE_ACCESS_ENABLED = "storage_access_enabled";
    private static final String KEY_STAY_CONNECTED_ENABLED = "stay_connected";
    /** Primary external storage root the ~/storage symlink points to. */
    public static final String STORAGE_TARGET = "/storage/emulated/0";
    public static final String ACTION_EXIT = "com.tinyhack.ssh.action.EXIT";

    public interface SessionsListener {
        void onSessionsChanged();
        void onCurrentSessionChanged(TerminalSession session, int index);
    }

    public class TerminalBinder extends Binder {
        public TerminalService getService() {
            return TerminalService.this;
        }
    }

    private final IBinder binder = new TerminalBinder();
    private final List<TerminalSession> sessions = new ArrayList<>();
    private int currentSessionIndex = -1;
    private com.tinyhack.ssh.debug.DebugHttpServer debugHttpServer;
    private final CopyOnWriteArrayList<SessionsListener> sessionsListeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        com.tinyhack.ssh.util.DesktopNotificationHelper.init(this);
        createNotificationChannel();
        com.tinyhack.ssh.util.BootstrapInstaller.installIfNeeded(this);
        debugHttpServer = new com.tinyhack.ssh.debug.DebugHttpServer(this, 8080);
        // HTTP debug server is opt-in (default off)
        if (isDebugServerEnabled()) {
            debugHttpServer.start();
        }
        // Re-establish ~/storage if storage access was previously enabled
        if (isStorageAccessEnabled()) {
            if (hasManageStoragePermission()) {
                String warning = setupStorageSymlink();
                if (warning != null) Log.w(TAG, warning);
            } else {
                Log.w(TAG, "Storage access enabled but MANAGE_EXTERNAL_STORAGE not granted");
            }
        }
        // Auto-start SSH agent if enabled
        try {
            com.tinyhack.ssh.ssh.SshAgentManager agent = com.tinyhack.ssh.ssh.SshAgentManager.getInstance(this);
            if (agent.isAutoStart()) {
                // Start in background to avoid blocking main thread
                new Thread(() -> {
                    try { agent.ensureAgentRunning(); } catch (Exception ignored) {}
                }, "SshAgentAutoStart").start();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to auto-start ssh-agent", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Tinyhack SSH Terminal Sessions",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active terminal session status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        // The service is non-exported. Keep the privileged Exit operation out
        // of the exported launcher activity by granting only this PendingIntent.
        Intent exitIntent = new Intent(this, TerminalService.class);
        exitIntent.setAction(ACTION_EXIT);
        PendingIntent exitPendingIntent = PendingIntent.getService(
            this,
            1,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        int count = sessions.size();
        String text = count == 1 ? "1 active terminal session" : count + " active terminal sessions";
        String title = "Tinyhack SSH";
        TerminalSession cur = getCurrentSession();
        if (cur != null) {
            String curTitle = cur.getDisplayTitle();
            if (curTitle != null && !curTitle.isEmpty()) {
                title = curTitle;
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(new NotificationCompat.Action.Builder(0, "Exit", exitPendingIntent).build())
            .build();
    }

    private void updateNotification() {
        if (sessions.isEmpty()) return;
        if (!isStayConnectedEnabled()) {
            stopForegroundIfNeeded();
            return;
        }
        startForegroundIfNeeded();
    }

    public void addSessionsListener(SessionsListener l) {
        if (l != null) sessionsListeners.addIfAbsent(l);
    }

    public void removeSessionsListener(SessionsListener l) {
        sessionsListeners.remove(l);
    }

    private void notifySessionsChanged() {
        updateNotification();
        for (SessionsListener l : sessionsListeners) {
            try { l.onSessionsChanged(); } catch (Exception ignored) {}
        }
    }

    private void notifyCurrentChanged() {
        TerminalSession cur = getCurrentSession();
        int idx = currentSessionIndex;
        for (SessionsListener l : sessionsListeners) {
            try { l.onCurrentSessionChanged(cur, idx); } catch (Exception ignored) {}
        }
        updateNotification();
    }

    public synchronized TerminalSession createSession(String cmd, String cwd, String[] argv, String[] envp) {
        return createSessionInternal(cmd, cwd, argv, envp, null, null);
    }

    public synchronized TerminalSession createSessionWithProfile(String cmd, String cwd, String[] argv, String[] envp, String profileId, String sessionName) {
        return createSessionInternal(cmd, cwd, argv, envp, profileId, sessionName);
    }

    public com.tinyhack.ssh.ssh.SshAgentManager getSshAgentManager() {
        return com.tinyhack.ssh.ssh.SshAgentManager.getInstance(this);
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** HTTP debug server is opt-in; disabled by default. */
    public boolean isDebugServerEnabled() {
        return prefs().getBoolean(KEY_HTTP_DEBUG_ENABLED, false);
    }

    public boolean isDebugServerRunning() {
        return debugHttpServer != null && debugHttpServer.isRunning();
    }

    public void setDebugServerEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_HTTP_DEBUG_ENABLED, enabled).apply();
        if (enabled) {
            debugHttpServer.start();
        } else {
            debugHttpServer.stop();
        }
    }

    /** Storage access (rsync of phone files) is opt-in; disabled by default. */
    public boolean isStorageAccessEnabled() {
        return prefs().getBoolean(KEY_STORAGE_ACCESS_ENABLED, false);
    }

    public void setStorageAccessEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_STORAGE_ACCESS_ENABLED, enabled).apply();
    }

    /**
     * Stay connected: keep sessions alive in the background via the persistent
     * (foreground-service) notification. Default: on.
     */
    public boolean isStayConnectedEnabled() {
        return prefs().getBoolean(KEY_STAY_CONNECTED_ENABLED, true);
    }

    public void setStayConnectedEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_STAY_CONNECTED_ENABLED, enabled).apply();
        if (enabled) {
            if (!sessions.isEmpty()) startForegroundIfNeeded();
        } else {
            stopForegroundIfNeeded();
        }
    }

    /** Promote the service to foreground (persistent notification) if "stay connected" is on. */
    private void startForegroundIfNeeded() {
        if (!isStayConnectedEnabled()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground error", e);
        }
    }

    /** Remove the persistent notification if "stay connected" is off. */
    private void stopForegroundIfNeeded() {
        if (isStayConnectedEnabled()) return;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {}
    }

    /** Re-post the persistent notification (e.g. after notification permission is granted). */
    public void refreshForegroundNotification() {
        if (sessions.isEmpty()) return;
        startForegroundIfNeeded();
    }

    public boolean hasManageStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager();
    }

    /**
     * Create (or fix) the ~/storage symlink to the shared storage root.
     * Returns null on success (including "already correct"), or a user-facing
     * warning/error message. Never touches ~/storage if it exists as a real file/dir.
     */
    public String setupStorageSymlink() {
        java.nio.file.Path link = new java.io.File(getFilesDir(), "home/storage").toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(link)) {
                String cur = java.nio.file.Files.readSymbolicLink(link).toString();
                if (cur.equals(STORAGE_TARGET)) return null; // already correct
                java.nio.file.Files.delete(link);            // stale link: recreate
            } else if (java.nio.file.Files.exists(link)) {
                // Real file/directory: do not touch, warn the user
                return "~/storage already exists and is not a symbolic link; it was left untouched";
            }
            java.io.File parent = link.getParent().toFile();
            if (!parent.exists()) parent.mkdirs();
            android.system.Os.symlink(STORAGE_TARGET, link.toString());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "setupStorageSymlink failed", e);
            return "Failed to create ~/storage symlink: " + e.getMessage();
        }
    }

    /** Remove ~/storage only if it is still our symlink (never touches real files). */
    public void removeStorageSymlink() {
        java.nio.file.Path link = new java.io.File(getFilesDir(), "home/storage").toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(link)
                    && STORAGE_TARGET.equals(java.nio.file.Files.readSymbolicLink(link).toString())) {
                java.nio.file.Files.delete(link);
            }
        } catch (Exception ignored) {}
    }

    private String[] injectAgentEnv(String[] envp) {
        try {
            com.tinyhack.ssh.ssh.SshAgentManager agent = com.tinyhack.ssh.ssh.SshAgentManager.getInstance(this);
            if (agent.isAgentRunning()) {
                String agentEnv = agent.getAgentEnv();
                if (envp == null) return new String[]{agentEnv};
                // Avoid duplicate
                for (String e : envp) if (e.startsWith("SSH_AUTH_SOCK=")) return envp;
                String[] out = new String[envp.length + 1];
                System.arraycopy(envp, 0, out, 0, envp.length);
                out[envp.length] = agentEnv;
                return out;
            }
        } catch (Exception ignored) {}
        return envp;
    }

    private synchronized TerminalSession createSessionInternal(String cmd, String cwd, String[] argv, String[] envp, String profileId, String sessionName) {
        // Inject SSH agent env if running
        envp = injectAgentEnv(envp);
        String homeDir = getFilesDir().getAbsolutePath() + "/home";
        java.io.File home = new java.io.File(homeDir);
        if (!home.exists()) {
            home.mkdirs();
        }

        String workDir = (cwd != null) ? cwd : homeDir;
        java.io.File bashBin = new java.io.File(getApplicationInfo().nativeLibraryDir, "libbash.so");
        String command = cmd;
        String[] arguments = argv;
        if (command == null) {
            if (bashBin.exists()) {
                command = bashBin.getAbsolutePath();
                if (arguments == null) {
                    arguments = new String[]{"bash", "-l"};
                }
            } else {
                command = "/system/bin/sh";
            }
        }

        TerminalSession session = new TerminalSession(command, workDir, arguments, envp, 24, 80, 10, 20, profileId, sessionName);
        sessions.add(session);
        currentSessionIndex = sessions.size() - 1;

        try {
            startForegroundIfNeeded();
        } catch (Exception e) {
            Log.w(TAG, "startForeground error", e);
        }

        notifySessionsChanged();
        notifyCurrentChanged();
        return session;
    }

    public synchronized TerminalSession createSessionForProfile(ConnectionProfile profile) {
        if (profile == null) return createSession(null, null, null, null);
        String homeDir = getFilesDir().getAbsolutePath() + "/home";
        String workDir;
        String cmd = null;
        String[] argv = null;
        String[] envp = null;
        String profileId = profile.getId();
        String sessionName = profile.getName();

        if (profile.getType() == ConnectionProfile.Type.SSH
                || profile.getType() == ConnectionProfile.Type.MOSH) {
            boolean isMosh = profile.getType() == ConnectionProfile.Type.MOSH;
            String rawHost = profile.getHost() != null ? profile.getHost().trim() : "";
            if (!rawHost.isEmpty() && !ConnectionProfile.isValidHost(rawHost)) {
                File busybox = new File(getApplicationInfo().nativeLibraryDir, "libbusybox.so");
                String shBin = busybox.exists() ? busybox.getAbsolutePath() : "sh";
                String msg = ("Cannot connect: profile '" + profile.getName() + "' has an invalid host '" + rawHost + "'.")
                        .replace("\"", "'");
                return createSessionInternal(shBin, homeDir, new String[]{"sh", "-c",
                        "echo \"" + msg + "\"; echo 'Host names cannot contain spaces, @, or other special characters.'; echo; exec sh"},
                        null, profileId, sessionName);
            }
            // Build SSH command
            File sshBin = new File(getApplicationInfo().nativeLibraryDir, "libssh.so");
            if (!sshBin.exists()) {
                sshBin = new File(getFilesDir(), "usr/bin/ssh");
            }
            String sshPath = sshBin.exists() ? sshBin.getAbsolutePath() : "ssh";

            String identityPath = null;
            if (profile.getAuthType() == ConnectionProfile.AuthType.KEY
                    && profile.getKeyName() != null && !profile.getKeyName().isEmpty()) {
                File keyFile = new File(homeDir + "/.ssh/" + profile.getKeyName());
                if (SshKeyManager.readAndroidSecurityKey(keyFile) != null) {
                    // Security key: the private key lives in the agent (Android
                    // Keystore), never in a file. Point ssh at the public key so it
                    // selects the agent-held identity, and make sure it is loaded.
                    identityPath = keyFile.getAbsolutePath() + ".pub";
                    try {
                        SshAgentManager agent = SshAgentManager.getInstance(this);
                        if (agent.ensureAgentRunning()) {
                            agent.addKey(keyFile.getAbsolutePath(), null);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot load security key into agent", e);
                    }
                } else if (keyFile.exists()) {
                    identityPath = keyFile.getAbsolutePath();
                } else {
                    File alt = new File(profile.getKeyName());
                    if (alt.exists()) identityPath = alt.getAbsolutePath();
                }
            }

            if (isMosh) {
                // mosh <launcher> [--ssh=ssh -i KEY -o ForwardAgent=yes] [--ssh-port=N] [extra mosh args] user@host
                File moshBin = new File(getApplicationInfo().nativeLibraryDir, "libmosh.so");
                if (!moshBin.exists()) {
                    moshBin = new File(getFilesDir(), "usr/bin/mosh");
                }
                cmd = moshBin.exists() ? moshBin.getAbsolutePath() : "mosh";

                java.util.ArrayList<String> args = new java.util.ArrayList<>();
                args.add("mosh");

                StringBuilder sshCmd = new StringBuilder(sshPath);
                sshCmd.append(" -o ForwardAgent=yes");
                if (identityPath != null) {
                    sshCmd.append(" -i ").append(identityPath);
                }
                args.add("--ssh=" + sshCmd);

                if (profile.getPort() != 22 && profile.getPort() > 0) {
                    args.add("--ssh-port=" + profile.getPort());
                }
                // For MOSH profiles sshArgs carries extra mosh args (--predict=always, --port=...)
                String moshArgsStr = profile.getSshArgs();
                if (moshArgsStr != null && !moshArgsStr.trim().isEmpty()) {
                    String[] extra = moshArgsStr.trim().split("\\s+");
                    for (String e : extra) if (!e.isEmpty()) args.add(e);
                }

                String host = profile.getHost() != null ? profile.getHost().trim() : "";
                String user = profile.getUsername() != null ? profile.getUsername().trim() : "";
                if (host.isEmpty()) {
                    return createSessionInternal(null, null, null, null, profileId, sessionName);
                }
                args.add(user.isEmpty() ? host : user + "@" + host);

                argv = args.toArray(new String[0]);
                workDir = homeDir;
                envp = null;
                return createSessionInternal(cmd, workDir, argv, envp, profileId, sessionName);
            }

            cmd = sshPath;

            java.util.ArrayList<String> args = new java.util.ArrayList<>();
            args.add("ssh");
            // Ensure known_hosts handling - add StrictHostKeyChecking=no for first connect? But respect profile
            // Add identity file if key specified
            if (identityPath != null) {
                args.add("-i");
                args.add(identityPath);
            }
            if (profile.getPort() != 22 && profile.getPort() > 0) {
                args.add("-p");
                args.add(String.valueOf(profile.getPort()));
            }
            // Enable agent forwarding by default unless the profile opts out via sshArgs
            String sshArgsStr = profile.getSshArgs();
            if (sshArgsStr == null || !sshArgsStr.contains("ForwardAgent")) {
                args.add("-o");
                args.add("ForwardAgent=yes");
            }
            // Cloudflare Access tunnel (cloudflared ProxyCommand)
            if (profile.isCloudflaredEnabled()) {
                String cfHost = profile.getCloudflaredHostname();
                if (cfHost == null || cfHost.trim().isEmpty()) {
                    cfHost = profile.getHost() != null ? profile.getHost().trim() : "";
                } else {
                    cfHost = cfHost.trim();
                }
                if (!cfHost.isEmpty()) {
                    boolean hasProxy = sshArgsStr != null && sshArgsStr.contains("ProxyCommand");
                    if (!hasProxy) {
                        String cfPath;
                        File cfNative = new File(getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
                        if (cfNative.exists()) {
                            cfPath = cfNative.getAbsolutePath();
                        } else {
                            cfPath = new File(getFilesDir(), "usr/bin/cloudflared").getAbsolutePath();
                        }
                        StringBuilder proxy = new StringBuilder();
                        proxy.append(cfPath).append(" access ssh --hostname ").append(cfHost);
                        String dest = profile.getCloudflaredDestination();
                        if (dest != null && !dest.trim().isEmpty()) {
                            proxy.append(" --destination ").append(dest.trim());
                        }
                        String tokenId = profile.getCloudflaredServiceTokenId();
                        if (tokenId != null && !tokenId.trim().isEmpty()) {
                            proxy.append(" --id ").append(tokenId.trim());
                        }
                        String tokenSecret = profile.getCloudflaredServiceTokenSecret();
                        if (tokenSecret != null && !tokenSecret.trim().isEmpty()) {
                            String secret = tokenSecret.trim();
                            // Escape for shell: wrap in single quotes if needed
                            if (secret.contains("'") || secret.contains(" ") || secret.contains("\"") || secret.contains("$") || secret.contains("`") || secret.contains("\\")) {
                                secret = "'" + secret.replace("'", "'\\''") + "'";
                            }
                            proxy.append(" --secret ").append(secret);
                        }
                        args.add("-o");
                        args.add("ProxyCommand=" + proxy.toString());
                    }
                }
            }
            // Extra args split
            if (sshArgsStr != null && !sshArgsStr.trim().isEmpty()) {
                String[] extra = sshArgsStr.trim().split("\\s+");
                for (String e : extra) if (!e.isEmpty()) args.add(e);
            }
            // User@Host mandatory
            String host = profile.getHost() != null ? profile.getHost().trim() : "";
            String user = profile.getUsername() != null ? profile.getUsername().trim() : "";
            if (host.isEmpty()) {
                // Fallback to local shell if host empty
                return createSessionInternal(null, null, null, null, profileId, sessionName);
            }
            String target = user.isEmpty() ? host : user + "@" + host;
            args.add(target);

            argv = args.toArray(new String[0]);
            workDir = homeDir;
            // Env: include TERM etc will be added in native
            envp = null;
        } else {
            // LOCAL
            String shell = profile.getShell();
            String cwd = profile.getCwd();
            if (cwd != null && !cwd.isEmpty()) {
                File cwdFile = new File(cwd);
                if (cwdFile.isDirectory()) workDir = cwdFile.getAbsolutePath();
                else workDir = homeDir;
            } else {
                workDir = homeDir;
            }
            if (shell != null && !shell.isEmpty()) {
                File shellFile = new File(shell);
                if (shellFile.exists() && shellFile.canExecute()) {
                    cmd = shellFile.getAbsolutePath();
                    argv = new String[]{shellFile.getName(), "-l"};
                } else if (shell.contains("/")) {
                    cmd = shell;
                    argv = null;
                } else {
                    // shell is like "bash" or "fish"
                    File binDir = new File(getFilesDir(), "usr/bin/" + shell);
                    if (binDir.exists()) {
                        cmd = binDir.getAbsolutePath();
                        argv = new String[]{shell, "-l"};
                    } else {
                        File lib = new File(getApplicationInfo().nativeLibraryDir, "lib" + shell + ".so");
                        if (lib.exists()) {
                            cmd = lib.getAbsolutePath();
                            argv = new String[]{shell, "-l"};
                        } else {
                            cmd = null; // fallback to bash
                        }
                    }
                }
            } else {
                cmd = null; // fallback to default bash
            }
            // Parse env
            if (profile.getEnv() != null && !profile.getEnv().trim().isEmpty()) {
                String[] lines = profile.getEnv().split("\n");
                List<String> envList = new ArrayList<>();
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.contains("=")) envList.add(line);
                }
                if (!envList.isEmpty()) envp = envList.toArray(new String[0]);
            }
        }

        return createSessionInternal(cmd, workDir, argv, envp, profileId, sessionName);
    }

    public synchronized List<TerminalSession> getSessions() {
        return new ArrayList<>(sessions);
    }

    public synchronized TerminalSession getCurrentSession() {
        if (currentSessionIndex >= 0 && currentSessionIndex < sessions.size()) {
            return sessions.get(currentSessionIndex);
        }
        if (!sessions.isEmpty()) {
            currentSessionIndex = 0;
            return sessions.get(0);
        }
        return null;
    }

    public synchronized int getCurrentSessionIndex() {
        return currentSessionIndex;
    }

    public synchronized TerminalSession getSessionById(String id) {
        if (id == null) return null;
        for (TerminalSession s : sessions) {
            if (id.equals(s.getId())) return s;
        }
        return null;
    }

    public synchronized int getSessionIndexById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < sessions.size(); i++) {
            if (id.equals(sessions.get(i).getId())) return i;
        }
        return -1;
    }

    public synchronized void setCurrentSession(int index) {
        if (index >= 0 && index < sessions.size() && index != currentSessionIndex) {
            currentSessionIndex = index;
            notifyCurrentChanged();
        }
    }

    public synchronized boolean setCurrentSessionById(String id) {
        int idx = getSessionIndexById(id);
        if (idx >= 0) {
            setCurrentSession(idx);
            return true;
        }
        return false;
    }

    public synchronized void setCurrentSession(TerminalSession session) {
        if (session == null) return;
        int idx = sessions.indexOf(session);
        if (idx >= 0) setCurrentSession(idx);
    }

    public synchronized boolean renameSession(String sessionId, String newName) {
        TerminalSession s = getSessionById(sessionId);
        if (s != null && newName != null && !newName.trim().isEmpty()) {
            s.setSessionName(newName.trim());
            notifySessionsChanged();
            if (sessions.indexOf(s) == currentSessionIndex) updateNotification();
            return true;
        }
        return false;
    }

    public synchronized boolean renameSession(TerminalSession session, String newName) {
        if (session == null) return false;
        return renameSession(session.getId(), newName);
    }

    public synchronized void removeSession(TerminalSession session) {
        if (session != null) {
            boolean wasCurrent = sessions.indexOf(session) == currentSessionIndex;
            // Remove from list immediately to update UI without blocking
            sessions.remove(session);
            if (currentSessionIndex >= sessions.size()) {
                currentSessionIndex = sessions.size() - 1;
            }
            if (sessions.isEmpty()) {
                currentSessionIndex = -1;
                try { stopForeground(true); } catch (Exception ignored) {}
                // No sessions left; nothing to keep alive in background
                stopSelf();
            } else {
                updateNotification();
            }
            notifySessionsChanged();
            if (wasCurrent || sessions.isEmpty()) notifyCurrentChanged();
            // Close native resources off the UI thread to avoid ANR (pthread_join can block)
            final TerminalSession toClose = session;
            new Thread(() -> {
                try { toClose.close(); } catch (Exception ignored) {}
            }, "TerminalClose-" + session.getId().substring(0,6)).start();
        }
    }

    public synchronized boolean closeSessionById(String id) {
        TerminalSession s = getSessionById(id);
        if (s != null) {
            removeSession(s);
            return true;
        }
        return false;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            exitApp();
            return START_NOT_STICKY;
        }
        // Keep running in background even when the activity is closed
        return START_STICKY;
    }

    /**
     * Termux-style "Exit": close all sessions and stop the service entirely.
     */
    public void exitApp() {
        List<TerminalSession> toClose;
        synchronized (this) {
            sessionsListeners.clear();
            toClose = new ArrayList<>(sessions);
            sessions.clear();
            currentSessionIndex = -1;
        }
        // Native close can block (pthread_join); do it off the main thread
        new Thread(() -> {
            for (TerminalSession s : toClose) {
                try { s.close(); } catch (Exception ignored) {}
            }
            // Terminate the whole app once sessions are cleaned up
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "TerminalExit").start();
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (debugHttpServer != null) {
            debugHttpServer.stop();
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        for (TerminalSession s : sessions) {
            s.close();
        }
        sessions.clear();
        currentSessionIndex = -1;
    }
}
