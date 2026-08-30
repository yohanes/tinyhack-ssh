package com.tinyhack.ssh;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.MenuItem;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import androidx.core.view.GravityCompat;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.model.ProfileManager;
import com.tinyhack.ssh.service.TerminalService;
import com.tinyhack.ssh.session.TerminalSession;
import com.tinyhack.ssh.ssh.SshKeyInfo;
import com.tinyhack.ssh.ssh.SshKeyManager;
import com.tinyhack.ssh.ui.adapter.ProfilesAdapter;
import com.tinyhack.ssh.ui.adapter.SessionsAdapter;
import com.tinyhack.ssh.view.ExtraKeysView;
import com.tinyhack.ssh.view.TerminalView;

import java.util.List;

public class MainActivity extends AppCompatActivity implements TerminalSession.Listener, TerminalService.SessionsListener {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "tinyhack_ssh_prefs";
    private static final String PREF_PENDING_CONNECT_PROFILE = "pending_connect_profile_id";
    private static final String PREF_NOTIFICATION_DISCLOSURE_SHOWN = "notification_disclosure_shown";
    private static final String PREF_FULLSCREEN_STATUS_BAR = "fullscreen_show_status_bar";

    private TerminalView terminalView;
    private ExtraKeysView extraKeysView;
    private Toolbar toolbar;
    private LinearLayout selectionBar;
    private android.widget.Button btnCopy;
    private android.widget.Button btnCancelSelection;
    private View btnCloseSession;

    private TerminalService terminalService;
    private boolean isBound = false;

    // Fullscreen mode: hide toolbar + system bars
    private boolean fullscreenMode = false;

    private final androidx.activity.result.ActivityResultLauncher<String> notificationPermLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            granted -> {
                getSharedPreferences("tinyhack_ssh_prefs", MODE_PRIVATE)
                    .edit().putBoolean("notif_permission_asked", true).apply();
                if (granted) {
                    // The FGS notification may have been posted while the
                    // permission was missing (silently hidden); re-post it.
                    runOnUiThread(() -> {
                        if (terminalService != null) terminalService.refreshForegroundNotification();
                    });
                } else {
                    Toast.makeText(this,
                        "Notification permission denied; the persistent notification will not show",
                        Toast.LENGTH_LONG).show();
                }
            });

    // Drawer
    private DrawerLayout drawerLayout;
    private ActionBarDrawerToggle drawerToggle;
    private RecyclerView recyclerSessions;
    private RecyclerView recyclerProfilesDrawer;
    private SessionsAdapter sessionsAdapter;
    private ProfilesAdapter profilesDrawerAdapter;
    private TextView textSessionCount;
    private View btnNewSessionDrawer;
    private View btnNewProfileDrawer;
    private View btnManageProfiles;
    private View btnSshKeysDrawer;
    private View btnSshAgentDrawer;
    private ProfileManager profileManager;
    private String pendingConnectProfileId;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TerminalService.TerminalBinder terminalBinder = (TerminalService.TerminalBinder) binder;
            terminalService = terminalBinder.getService();
            isBound = true;
            terminalService.addSessionsListener(MainActivity.this);
            setupSession();
            refreshSessionsList();
            refreshProfilesDrawer();
            if (pendingConnectProfileId != null) {
                String pid = pendingConnectProfileId;
                pendingConnectProfileId = null;
                handlePendingConnect(pid);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (terminalService != null) terminalService.removeSessionsListener(MainActivity.this);
            terminalService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                if (terminalView != null && terminalView.hasSelection()) {
                    terminalView.clearSelection();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        toolbar = findViewById(R.id.toolbar);
        terminalView = findViewById(R.id.terminal_view);
        extraKeysView = findViewById(R.id.extra_keys_view);
        selectionBar = findViewById(R.id.selection_bar);
        btnCopy = findViewById(R.id.btn_copy);
        btnCancelSelection = findViewById(R.id.btn_cancel_selection);
        btnCloseSession = findViewById(R.id.btn_close_session);
        drawerLayout = findViewById(R.id.drawer_layout);
        recyclerSessions = findViewById(R.id.recycler_sessions);
        recyclerProfilesDrawer = findViewById(R.id.recycler_profiles_drawer);
        textSessionCount = findViewById(R.id.text_session_count);
        btnNewSessionDrawer = findViewById(R.id.btn_new_session_drawer);
        btnNewProfileDrawer = findViewById(R.id.btn_new_profile_drawer);
        btnManageProfiles = findViewById(R.id.btn_manage_profiles);
        btnSshKeysDrawer = findViewById(R.id.btn_ssh_keys_drawer);
        btnSshAgentDrawer = findViewById(R.id.btn_ssh_agent_drawer);

        profileManager = ProfileManager.getInstance(this);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }
        if (drawerLayout != null && toolbar != null) {
            drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.app_name, R.string.app_name);
            drawerLayout.addDrawerListener(drawerToggle);
            drawerToggle.syncState();
        }

        if (extraKeysView != null && terminalView != null) {
            extraKeysView.setTerminalView(terminalView);
        }
        com.tinyhack.ssh.debug.DebugHttpServer.debugTerminalView = terminalView;
        com.tinyhack.ssh.debug.DebugHttpServer.fullscreenToggle =
                () -> runOnUiThread(() -> setFullscreen(!fullscreenMode));
        com.tinyhack.ssh.debug.DebugHttpServer.fullscreenState = () -> fullscreenMode;

        // Actions from the 3-finger tap menu handled by the activity (fullscreen, drawer)
        if (terminalView != null) {
            terminalView.setTerminalMenuActionListener(new TerminalView.TerminalMenuActionListener() {
                @Override public boolean isFullscreen() {
                    return fullscreenMode;
                }
                @Override public void toggleFullscreen() {
                    runOnUiThread(() -> setFullscreen(!fullscreenMode));
                }
                @Override public void openDrawer() {
                    runOnUiThread(() -> {
                        if (drawerLayout != null) drawerLayout.openDrawer(GravityCompat.START);
                    });
                }
                @Override public List<TerminalSession> getSessionsForMenu() {
                    return terminalService != null ? terminalService.getSessions() : java.util.Collections.emptyList();
                }
                @Override public void switchToSession(TerminalSession target) {
                    if (terminalService == null || target == null) return;
                    List<TerminalSession> list = terminalService.getSessions();
                    int idx = list.indexOf(target);
                    if (idx >= 0) runOnUiThread(() -> MainActivity.this.switchToSession(idx));
                }
                @Override public List<ConnectionProfile> getProfilesForMenu() {
                    try {
                        return ProfileManager.getInstance(MainActivity.this).loadProfiles();
                    } catch (Exception e) {
                        return java.util.Collections.emptyList();
                    }
                }
                @Override public void startSessionWithProfile(ConnectionProfile profile) {
                    runOnUiThread(() -> connectProfile(profile));
                }
            });
            // The terminal view owns the session listener slot; it republishes
            // process-exit events here (session listener fan-out).
            terminalView.setSessionClosedListener(exitCode -> runOnUiThread(() -> handleSessionClosed(exitCode)));
            // "Close session" button on the <session closed> overlay
            terminalView.setOnCloseSessionRequested(this::closeDeadCurrentSession);
            // "Reopen" button on the <session closed> overlay (reconnect after e.g. SSH drop)
            terminalView.setOnReopenSessionRequested(this::reopenDeadSession);
        }

        // Edge-to-edge insets handling (uniform on Android 14–16).
        //
        // Why: with targetSdk 35+ Android enforces edge-to-edge, so
        // android:windowSoftInputMode="adjustResize" no longer resizes the
        // window — the extra-keys bar would end up BEHIND the soft keyboard
        // (the "two rows missing" bug on Android 15/16 devices). Going
        // edge-to-edge ourselves (decorFitsSystemWindows=false) and padding
        // the root from WindowInsets makes the behavior identical everywhere:
        // the window keeps its size and we inset content by status bar /
        // navigation bar / IME ourselves. This listener is also the single
        // source of truth for keyboard visibility (compact vs two-row key bar).
        // minSdk is 34, so the framework IME-insets API always exists.
        getWindow().setDecorFitsSystemWindows(false);
        // DrawerLayout ignores its own padding (custom onLayout places content
        // edge-to-edge), so inset the content column and the drawer column.
        final View contentRoot = findViewById(R.id.main_content_root);
        final View drawerContent = findViewById(R.id.drawer_content);
        // Pass insets through to children (ActionBarOverlayLayout & friends);
        // the actual work happens in the global-layout listener below, which
        // reads getRootWindowInsets() — authoritative regardless of dispatch
        // quirks — and fires on IME show/hide, fullscreen and rotation.
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) ->
                v.onApplyWindowInsets(insets));
        drawerLayout.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            WindowInsets wi = drawerLayout.getRootWindowInsets();
            if (wi == null) return;
            boolean imeVisible = wi.isVisible(WindowInsets.Type.ime());
            int imeBottom = wi.getInsets(WindowInsets.Type.ime()).bottom;
            // In fullscreen the status/nav bars are controller-hidden, but
            // getInsets(systemBars()) can keep reporting their old values on
            // some OEMs — gate each inset on the bar actually being visible,
            // while always honoring the display cutout.
            int cutoutTop = wi.getInsets(WindowInsets.Type.displayCutout()).top;
            int sysTop = wi.isVisible(WindowInsets.Type.statusBars())
                    ? Math.max(wi.getInsets(WindowInsets.Type.systemBars()).top, cutoutTop)
                    : cutoutTop;
            int cutoutBottom = wi.getInsets(WindowInsets.Type.displayCutout()).bottom;
            int sysBottom = wi.isVisible(WindowInsets.Type.navigationBars())
                    ? Math.max(wi.getInsets(WindowInsets.Type.systemBars()).bottom, cutoutBottom)
                    : cutoutBottom;
            int bottomPad = Math.max(imeBottom, sysBottom);
            if (contentRoot.getPaddingTop() != sysTop || contentRoot.getPaddingBottom() != bottomPad) {
                contentRoot.setPadding(0, sysTop, 0, bottomPad);
            }
            if (drawerContent.getPaddingTop() != sysTop) {
                drawerContent.setPadding(0, sysTop, 0, 0);
            }
            // ExtraKeysView.setKeyboardVisible() self-dedupes; call it on every
            // pass so the compact/two-row layout always tracks the real IME
            // state (imeVisibleFallback is also touched by toggle/show paths).
            imeVisibleFallback = imeVisible;
            if (extraKeysView != null) extraKeysView.setKeyboardVisible(imeVisible);
        });

        // Selection bar handling: shown while a long-press text selection is active
        if (selectionBar != null) {
            selectionBar.setVisibility(View.GONE);
        }
        if (terminalView != null) {
            terminalView.setSelectionListener(active -> {
                runOnUiThread(() -> updateSelectionBar(active));
            });
        }
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                if (terminalView != null) {
                    boolean copied = terminalView.copySelection();
                }
            });
        }
        if (btnCancelSelection != null) {
            btnCancelSelection.setOnClickListener(v -> {
                if (terminalView != null) {
                    terminalView.clearSelection();
                }
            });
        }

        // Dismiss a dead session ("<session closed>" banner / button path)
        if (btnCloseSession != null) {
            btnCloseSession.setOnClickListener(v -> closeDeadCurrentSession());
        }

        // Drawer adapters
        if (recyclerSessions != null) {
            recyclerSessions.setLayoutManager(new LinearLayoutManager(this));
            sessionsAdapter = new SessionsAdapter(new SessionsAdapter.OnSessionActionListener() {
                @Override public void onSessionSelected(TerminalSession session, int position) {
                    switchToSession(position);
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
                @Override public void onSessionRename(TerminalSession session, int position) {
                    showRenameSessionDialog(session);
                }
                @Override public void onSessionClose(TerminalSession session, int position) {
                    confirmCloseSession(session);
                }
            });
            recyclerSessions.setAdapter(sessionsAdapter);
        }
        if (recyclerProfilesDrawer != null) {
            recyclerProfilesDrawer.setLayoutManager(new LinearLayoutManager(this));
            profilesDrawerAdapter = new ProfilesAdapter(new ProfilesAdapter.OnProfileActionListener() {
                @Override public void onProfileConnect(ConnectionProfile profile) {
                    connectProfile(profile);
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
                @Override public void onProfileEdit(ConnectionProfile profile) {
                    // Open manage activity
                    startActivity(new Intent(MainActivity.this, com.tinyhack.ssh.ui.ConnectionProfilesActivity.class));
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
                @Override public void onProfileDelete(ConnectionProfile profile) {
                    // delegate to manage
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Delete Profile")
                            .setMessage("Delete '" + profile.getName() + "'?")
                            .setPositiveButton("Delete", (d,w)-> {
                                profileManager.deleteProfile(profile.getId());
                                refreshProfilesDrawer();
                            })
                            .setNegativeButton("Cancel", null).show();
                }
                @Override public void onProfileDuplicate(ConnectionProfile profile) {
                    try {
                        ConnectionProfile copy = ConnectionProfile.fromJson(profile.toJson());
                        copy.setId(java.util.UUID.randomUUID().toString());
                        copy.setName(profile.getName() + " Copy");
                        copy.setCreatedAt(System.currentTimeMillis());
                        copy.setUpdatedAt(System.currentTimeMillis());
                        profileManager.addProfile(copy);
                        refreshProfilesDrawer();
                        Toast.makeText(MainActivity.this, "Duplicated " + copy.getName(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Duplicate failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, true);
            recyclerProfilesDrawer.setAdapter(profilesDrawerAdapter);
        }

        if (btnNewSessionDrawer != null) {
            btnNewSessionDrawer.setOnClickListener(v -> {
                createNewLocalSession();
                drawerLayout.closeDrawer(GravityCompat.START);
            });
        }
        if (btnNewProfileDrawer != null) {
            btnNewProfileDrawer.setOnClickListener(v -> {
                showNewProfileDialog();
            });
        }
        if (btnManageProfiles != null) {
            btnManageProfiles.setOnClickListener(v -> {
                startActivity(new Intent(this, com.tinyhack.ssh.ui.ConnectionProfilesActivity.class));
                drawerLayout.closeDrawer(GravityCompat.START);
            });
        }
        if (btnSshKeysDrawer != null) {
            btnSshKeysDrawer.setOnClickListener(v -> {
                startActivity(new Intent(this, com.tinyhack.ssh.ssh.SshKeysActivity.class));
                drawerLayout.closeDrawer(GravityCompat.START);
            });
        }
        if (btnSshAgentDrawer != null) {
            btnSshAgentDrawer.setOnClickListener(v -> {
                startActivity(new Intent(this, com.tinyhack.ssh.ssh.SshAgentActivity.class));
                drawerLayout.closeDrawer(GravityCompat.START);
            });
        }

        // Also handle three-finger menu? Already in view

        com.tinyhack.ssh.util.BootstrapInstaller.installIfNeeded(this);

        Intent intent = new Intent(this, TerminalService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        handlePendingConnectRequest();

        maybeRequestNotificationPermission();
    }

    /**
     * "Stay connected" defaults to on, so on first launch ask for the
     * POST_NOTIFICATIONS runtime permission (Android 13+); without it the
     * foreground-service notification is silently hidden.
     */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!p.getBoolean("stay_connected", true)) return;
        if (p.getBoolean("notif_permission_asked", false)) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        Runnable requestSystemPermission = () -> {
            p.edit()
                .putBoolean(PREF_NOTIFICATION_DISCLOSURE_SHOWN, true)
                .putBoolean("notif_permission_asked", true)
                .apply();
            try {
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            } catch (Exception ignored) {}
        };
        if (p.getBoolean(PREF_NOTIFICATION_DISCLOSURE_SHOWN, false)) {
            requestSystemPermission.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Keep terminal sessions connected")
            .setMessage("Enable notifications if you want terminal sessions to stay connected while Tinyhack SSH is in the background. Tinyhack SSH will still function normally without notification permission, but background sessions may be disconnected.")
            .setPositiveButton("Enable notifications", (dialog, which) -> requestSystemPermission.run())
            .setNegativeButton("Not now", (dialog, which) -> p.edit()
                .putBoolean(PREF_NOTIFICATION_DISCLOSURE_SHOWN, true)
                .putBoolean("notif_permission_asked", true)
                .apply())
            .show();
    }

    /** Consume an app-private one-shot request made by ConnectionProfilesActivity. */
    private void handlePendingConnectRequest() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String pid = p.getString(PREF_PENDING_CONNECT_PROFILE, null);
        if (pid == null || pid.isEmpty()) return;
        p.edit().remove(PREF_PENDING_CONNECT_PROFILE).apply();
        if (isBound && terminalService != null) {
            handlePendingConnect(pid);
        } else {
            pendingConnectProfileId = pid;
        }
    }

    private void handlePendingConnect(String profileId) {
        if (profileId == null || profileId.isEmpty()) return;
        ConnectionProfile p = ProfileManager.getInstance(this).getProfile(profileId);
        if (p != null) {
            connectProfile(p);
        } else {
            Toast.makeText(this, "Profile not found", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSelectionBar(boolean selectionActive) {
        if (selectionBar == null) return;
        selectionBar.setVisibility(selectionActive ? View.VISIBLE : View.GONE);
        // Hide the extra-keys rows while selecting so the selection bar is the
        // only overlay at the bottom (restored when the selection ends).
        if (extraKeysView != null) {
            extraKeysView.setVisibility(selectionActive ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Show the floating "Close session" button while the displayed session has
     * exited but not been dismissed.
     */
    private void updateSessionClosedUi() {
        if (btnCloseSession == null || terminalView == null) return;
        TerminalSession s = terminalView.getSession();
        boolean dead = s != null && !s.isRunning();
        btnCloseSession.setVisibility(dead ? View.VISIBLE : View.GONE);
    }

    /** Remove the dead session currently displayed and open/show another one. */
    private void closeDeadCurrentSession() {
        if (terminalService == null || terminalView == null) return;
        TerminalSession cur = terminalView.getSession();
        if (cur == null || cur.isRunning()) return;
        cur.setListener(null);
        terminalService.removeSession(cur); // closes native resources on a worker thread
        setupSession();
    }

    /**
     * Replace the dead displayed session with a fresh one: same connection
     * profile when it had one (SSH/MOSH/local profile reconnect), otherwise a
     * plain local shell.
     */
    private void reopenDeadSession() {
        if (terminalService == null || terminalView == null) return;
        TerminalSession cur = terminalView.getSession();
        if (cur == null || cur.isRunning()) return;
        ConnectionProfile profile = null;
        String profileId = cur.getProfileId();
        if (profileId != null && !profileId.isEmpty()) {
            try {
                profile = ProfileManager.getInstance(this).getProfile(profileId);
            } catch (Exception ignored) {}
        }
        cur.setListener(null);
        terminalService.removeSession(cur); // closes native resources on a worker thread
        if (profile != null) {
            connectProfile(profile);
        } else {
            createNewLocalSession();
        }
    }

    private void setupSession() {
        if (!isBound || terminalService == null) return;

        TerminalSession session = terminalService.getCurrentSession();
        if (session == null || !session.isRunning()) {
            // Ensure at least one session
            if (terminalService.getSessions().isEmpty()) {
                session = terminalService.createSession(null, null, null, null);
            } else {
                session = terminalService.getCurrentSession();
                if (session == null) session = terminalService.getSessions().get(0);
            }
        }

        if (session != null) {
            session.setListener(this);
            terminalView.attachSession(session);
            updateTitle(session.getDisplayTitle());
            // Ensure current index correct
            terminalService.setCurrentSession(session);
        }
        refreshSessionsList();
        updateSessionClosedUi();
    }

    private void switchToSession(int index) {
        if (terminalService == null) return;
        List<TerminalSession> list = terminalService.getSessions();
        if (index < 0 || index >= list.size()) return;
        // Detach old listener?
        TerminalSession current = terminalView.getSession();
        if (current != null) current.setListener(null);
        terminalService.setCurrentSession(index);
        TerminalSession target = terminalService.getCurrentSession();
        if (target != null) {
            target.setListener(this);
            terminalView.attachSession(target);
            updateTitle(target.getDisplayTitle());
        }
        refreshSessionsList();
        updateSessionClosedUi();
    }

    private void createNewLocalSession() {
        if (terminalService == null) return;
        TerminalSession prev = terminalView.getSession();
        if (prev != null) prev.setListener(null);
        TerminalSession session = terminalService.createSession(null, null, null, null);
        session.setListener(this);
        terminalView.attachSession(session);
        updateTitle(session.getDisplayTitle());
        refreshSessionsList();
        updateSessionClosedUi();
        Toast.makeText(this, "New session created", Toast.LENGTH_SHORT).show();
    }

    private void connectProfile(ConnectionProfile profile) {
        if (terminalService == null || profile == null) return;
        TerminalSession prev = terminalView.getSession();
        if (prev != null) prev.setListener(null);
        TerminalSession session = terminalService.createSessionForProfile(profile);
        session.setListener(this);
        terminalView.attachSession(session);
        updateTitle(session.getDisplayTitle());
        refreshSessionsList();
        updateSessionClosedUi();
        Toast.makeText(this, "Connecting: " + profile.getName(), Toast.LENGTH_SHORT).show();
    }

    private void refreshSessionsList() {
        if (terminalService == null || sessionsAdapter == null) return;
        runOnUiThread(() -> {
            List<TerminalSession> list = terminalService.getSessions();
            int curIdx = terminalService.getCurrentSessionIndex();
            sessionsAdapter.updateSessions(list, curIdx);
            if (textSessionCount != null) textSessionCount.setText(String.valueOf(list.size()));
        });
    }

    private void refreshProfilesDrawer() {
        if (profilesDrawerAdapter == null || profileManager == null) return;
        runOnUiThread(() -> {
            List<ConnectionProfile> list = profileManager.loadProfiles();
            profilesDrawerAdapter.updateProfiles(list);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfilesDrawer();
        refreshSessionsList();
        // Re-assert immersive state (system bars can reappear after returning from another app)
        if (fullscreenMode) setFullscreen(true);
        if (terminalView != null) {
            terminalView.reloadPersistedFont();
            terminalView.requestFocus();
        }
        updateSessionClosedUi();
    }

    private void showRenameSessionDialog(TerminalSession session) {
        if (session == null) return;
        EditText edit = new EditText(this);
        edit.setText(session.getDisplayTitle());
        edit.setSelection(edit.getText().length());
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Rename Session")
                .setView(edit)
                .setPositiveButton("Rename", (d,w)-> {
                    String newName = edit.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        terminalService.renameSession(session, newName);
                        updateTitle(newName);
                        refreshSessionsList();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmCloseSession(TerminalSession session) {
        if (session == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Close Session")
                .setMessage("Close '" + session.getDisplayTitle() + "'?")
                .setPositiveButton("Close", (d,w)-> {
                    boolean wasCurrent = session == terminalView.getSession();
                    terminalService.removeSession(session);
                    if (wasCurrent) {
                        setupSession();
                    }
                    refreshSessionsList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showNewProfileDialog() {
        // Quick dialog to create simple profile: choose LOCAL, SSH, or MOSH template
        String[] options = {"Local Shell", "SSH Connection", "Mosh Connection"};
        new AlertDialog.Builder(this)
                .setTitle("New Profile")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showProfileEditDialog(null, ConnectionProfile.Type.LOCAL);
                    } else if (which == 1) {
                        showProfileEditDialog(null, ConnectionProfile.Type.SSH);
                    } else {
                        showProfileEditDialog(null, ConnectionProfile.Type.MOSH);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showProfileEditDialog(ConnectionProfile existing, ConnectionProfile.Type defaultType) {
        boolean isEdit = existing != null;
        ConnectionProfile profile = existing;
        if (profile == null) {
            profile = new ConnectionProfile(defaultType == ConnectionProfile.Type.SSH ? "New SSH"
                    : (defaultType == ConnectionProfile.Type.MOSH ? "New Mosh" : "New Local"), defaultType);
        }
        final ConnectionProfile editing = profile;
        // Build dialog similar to ConnectionProfilesActivity
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        int pad = 12;

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Profile Name");
        nameLabel.setTextColor(0xFFAAAAAA);
        nameLabel.setTextSize(12);
        layout.addView(nameLabel);
        EditText editName = new EditText(this);
        editName.setText(editing.getName());
        editName.setSingleLine(true);
        layout.addView(editName);

        TextView typeLabel = new TextView(this);
        typeLabel.setText("Type");
        typeLabel.setTextColor(0xFFAAAAAA);
        typeLabel.setTextSize(12);
        typeLabel.setPadding(0, pad, 0, 0);
        layout.addView(typeLabel);
        Spinner spinnerType = new Spinner(this);
        String[] types = {"LOCAL", "SSH", "MOSH"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, types);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerType.setAdapter(typeAdapter);
        int typeSel = editing.getType() == ConnectionProfile.Type.SSH ? 1
                : (editing.getType() == ConnectionProfile.Type.MOSH ? 2 : 0);
        spinnerType.setSelection(typeSel);
        layout.addView(spinnerType);

        TextView shellLabel = new TextView(this);
        shellLabel.setText("Shell (optional)");
        shellLabel.setTextColor(0xFFAAAAAA);
        shellLabel.setTextSize(11);
        shellLabel.setPadding(0, pad, 0, 0);
        layout.addView(shellLabel);
        EditText editShell = new EditText(this);
        editShell.setText(editing.getShell() != null ? editing.getShell() : "");
        editShell.setHint("Default bash");
        editShell.setSingleLine(true);
        layout.addView(editShell);

        TextView cwdLabel = new TextView(this);
        cwdLabel.setText("Working Directory");
        cwdLabel.setTextColor(0xFFAAAAAA);
        cwdLabel.setTextSize(11);
        cwdLabel.setPadding(0, pad, 0, 0);
        layout.addView(cwdLabel);
        EditText editCwd = new EditText(this);
        editCwd.setText(editing.getCwd() != null ? editing.getCwd() : "");
        editCwd.setHint("/data/data/.../files/home");
        editCwd.setSingleLine(true);
        layout.addView(editCwd);

        TextView hostLabel = new TextView(this);
        hostLabel.setText("SSH Host");
        hostLabel.setTextColor(0xFFAAAAAA);
        hostLabel.setTextSize(11);
        hostLabel.setPadding(0, pad, 0, 0);
        layout.addView(hostLabel);
        EditText editHost = new EditText(this);
        editHost.setText(editing.getHost() != null ? editing.getHost() : "");
        editHost.setHint("192.168.1.10");
        editHost.setSingleLine(true);
        layout.addView(editHost);

        TextView portLabel = new TextView(this);
        portLabel.setText("SSH Port");
        portLabel.setTextColor(0xFFAAAAAA);
        portLabel.setTextSize(11);
        layout.addView(portLabel);
        EditText editPort = new EditText(this);
        editPort.setText(String.valueOf(editing.getPort() != 0 ? editing.getPort() : 22));
        editPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(editPort);

        TextView userLabel = new TextView(this);
        userLabel.setText("Username");
        userLabel.setTextColor(0xFFAAAAAA);
        userLabel.setTextSize(11);
        layout.addView(userLabel);
        EditText editUser = new EditText(this);
        editUser.setText(editing.getUsername() != null ? editing.getUsername() : "");
        editUser.setHint("root");
        editUser.setSingleLine(true);
        layout.addView(editUser);

        TextView keyLabel = new TextView(this);
        keyLabel.setText("SSH Key");
        keyLabel.setTextColor(0xFFAAAAAA);
        keyLabel.setTextSize(11);
        layout.addView(keyLabel);
        List<SshKeyInfo> keys = SshKeyManager.listKeys(this);
        String[] keyNames = new String[keys.size() + 1];
        keyNames[0] = "(none)";
        for (int i=0;i<keys.size();i++) keyNames[i+1]=keys.get(i).getName();
        Spinner spinnerKey = new Spinner(this);
        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, keyNames);
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKey.setAdapter(keyAdapter);
        int keySel = 0;
        if (editing.getKeyName()!=null) {
            for(int i=0;i<keyNames.length;i++) if(keyNames[i].equals(editing.getKeyName())) keySel=i;
        }
        spinnerKey.setSelection(keySel);
        layout.addView(spinnerKey);

        TextView sshArgsLabel = new TextView(this);
        sshArgsLabel.setText("Extra SSH Args");
        sshArgsLabel.setTextColor(0xFFAAAAAA);
        sshArgsLabel.setTextSize(11);
        layout.addView(sshArgsLabel);
        EditText editSshArgs = new EditText(this);
        editSshArgs.setText(editing.getSshArgs()!=null?editing.getSshArgs():"");
        editSshArgs.setHint("-o StrictHostKeyChecking=no");
        editSshArgs.setSingleLine(true);
        layout.addView(editSshArgs);

        android.widget.CheckBox checkCf = new android.widget.CheckBox(this);
        checkCf.setText("Use Cloudflare Access (cloudflared)");
        checkCf.setTextColor(0xFFAAAAAA);
        checkCf.setTextSize(12);
        checkCf.setChecked(editing.isCloudflaredEnabled());
        layout.addView(checkCf);

        TextView cfHostLabel = new TextView(this);
        cfHostLabel.setText("Cloudflare Hostname (e.g. xaccess.example.com)");
        cfHostLabel.setTextColor(0xFFAAAAAA);
        cfHostLabel.setTextSize(11);
        layout.addView(cfHostLabel);
        EditText editCfHost = new EditText(this);
        editCfHost.setText(editing.getCloudflaredHostname()!=null?editing.getCloudflaredHostname():"");
        editCfHost.setHint("Leave empty to use SSH Host");
        editCfHost.setSingleLine(true);
        layout.addView(editCfHost);

        TextView cfIdLabel = new TextView(this);
        cfIdLabel.setText("Service Token ID (optional)");
        cfIdLabel.setTextColor(0xFFAAAAAA);
        cfIdLabel.setTextSize(11);
        layout.addView(cfIdLabel);
        EditText editCfId = new EditText(this);
        editCfId.setText(editing.getCloudflaredServiceTokenId()!=null?editing.getCloudflaredServiceTokenId():"");
        editCfId.setHint("CF-Access-Client-Id");
        editCfId.setSingleLine(true);
        layout.addView(editCfId);

        TextView cfSecretLabel = new TextView(this);
        cfSecretLabel.setText("Service Token Secret (optional)");
        cfSecretLabel.setTextColor(0xFFAAAAAA);
        cfSecretLabel.setTextSize(11);
        layout.addView(cfSecretLabel);
        EditText editCfSecret = new EditText(this);
        editCfSecret.setText(editing.getCloudflaredServiceTokenSecret()!=null?editing.getCloudflaredServiceTokenSecret():"");
        editCfSecret.setHint("CF-Access-Client-Secret");
        editCfSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editCfSecret.setSingleLine(true);
        layout.addView(editCfSecret);

        TextView cfDestLabel = new TextView(this);
        cfDestLabel.setText("Destination (optional, --destination host:port)");
        cfDestLabel.setTextColor(0xFFAAAAAA);
        cfDestLabel.setTextSize(11);
        layout.addView(cfDestLabel);
        EditText editCfDest = new EditText(this);
        editCfDest.setText(editing.getCloudflaredDestination()!=null?editing.getCloudflaredDestination():"");
        editCfDest.setHint("10.0.0.1:22");
        editCfDest.setSingleLine(true);
        layout.addView(editCfDest);

        TextView cfHint = new TextView(this);
        cfHint.setText("Cloudflared access ssh --hostname. For browser auth, leave Service Token empty and run 'cloudflared access login https://<hostname>' in a local shell first.");
        cfHint.setTextColor(0xFF888888);
        cfHint.setTextSize(10);
        cfHint.setPadding(0, 8, 0, 0);
        layout.addView(cfHint);

        Runnable updateVisibility = () -> {
            int t = spinnerType.getSelectedItemPosition();
            boolean isRemote = t == 1 || t == 2;
            int visSsh = isRemote ? View.VISIBLE : View.GONE;
            int visLocal = isRemote ? View.GONE : View.VISIBLE;
            hostLabel.setText(t == 2 ? "Host" : "SSH Host");
            portLabel.setText(t == 2 ? "SSH Port (bootstrap)" : "SSH Port");
            sshArgsLabel.setText(t == 2 ? "Extra Mosh Args" : "Extra SSH Args");
            editSshArgs.setHint(t == 2 ? "--predict=always --port=60000:61000" : "-o StrictHostKeyChecking=no");
            shellLabel.setVisibility(visLocal);
            editShell.setVisibility(visLocal);
            cwdLabel.setVisibility(visLocal);
            editCwd.setVisibility(visLocal);
            hostLabel.setVisibility(visSsh);
            editHost.setVisibility(visSsh);
            portLabel.setVisibility(visSsh);
            editPort.setVisibility(visSsh);
            userLabel.setVisibility(visSsh);
            editUser.setVisibility(visSsh);
            keyLabel.setVisibility(visSsh);
            spinnerKey.setVisibility(visSsh);
            sshArgsLabel.setVisibility(visSsh);
            editSshArgs.setVisibility(visSsh);
            int visCfBase = isRemote ? View.VISIBLE : View.GONE;
            checkCf.setVisibility(visCfBase);
            boolean cfOn = checkCf.isChecked() && isRemote;
            int visCf = cfOn ? View.VISIBLE : View.GONE;
            cfHostLabel.setVisibility(visCf);
            editCfHost.setVisibility(visCf);
            cfIdLabel.setVisibility(visCf);
            editCfId.setVisibility(visCf);
            cfSecretLabel.setVisibility(visCf);
            editCfSecret.setVisibility(visCf);
            cfDestLabel.setVisibility(visCf);
            editCfDest.setVisibility(visCf);
            cfHint.setVisibility(visCf);
        };
        updateVisibility.run();
        spinnerType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id){ updateVisibility.run();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        checkCf.setOnCheckedChangeListener((b, c) -> updateVisibility.run());

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isEdit?"Edit Profile":"New Profile")
                .setView(sv)
                .setPositiveButton(isEdit?"Save":"Create", null)
                .setNegativeButton("Cancel", null)
                .show();
        // Override the positive button so validation failures keep the dialog open
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            if(name.isEmpty()){ Toast.makeText(this,"Name required",Toast.LENGTH_SHORT).show(); return; }
            ConnectionProfile.Type type = spinnerType.getSelectedItemPosition()==2? ConnectionProfile.Type.MOSH
                    :(spinnerType.getSelectedItemPosition()==1? ConnectionProfile.Type.SSH: ConnectionProfile.Type.LOCAL);
            editing.setName(name);
            editing.setType(type);
            if(type==ConnectionProfile.Type.LOCAL){
                String sh = editShell.getText().toString().trim();
                editing.setShell(sh.isEmpty()?null:sh);
                String cwd = editCwd.getText().toString().trim();
                editing.setCwd(cwd.isEmpty()?null:cwd);
                editing.setHost(null); editing.setUsername(null); editing.setKeyName(null);
                editing.setCloudflaredEnabled(false);
                editing.setCloudflaredHostname(null);
                editing.setCloudflaredServiceTokenId(null);
                editing.setCloudflaredServiceTokenSecret(null);
                editing.setCloudflaredDestination(null);
            } else {
                String host = editHost.getText().toString().trim();
                if(host.isEmpty()){ Toast.makeText(this,"Host required",Toast.LENGTH_SHORT).show(); return; }
                editing.setHost(host);
                try{ int p = Integer.parseInt(editPort.getText().toString().trim()); editing.setPort(p>0?p:22);} catch(Exception e){ editing.setPort(22); }
                String user = editUser.getText().toString().trim();
                editing.setUsername(user.isEmpty()?null:user);
                String keySelStr = (String) spinnerKey.getSelectedItem();
                editing.setKeyName(keySelStr!=null&&!keySelStr.equals("(none)")?keySelStr:null);
                editing.setAuthType(editing.getKeyName()!=null? ConnectionProfile.AuthType.KEY: ConnectionProfile.AuthType.NONE);
                String extra = editSshArgs.getText().toString().trim();
                editing.setSshArgs(extra.isEmpty()?null:extra);
                boolean cfEnabled = checkCf.isChecked();
                editing.setCloudflaredEnabled(cfEnabled);
                if (cfEnabled) {
                    String cfH = editCfHost.getText().toString().trim();
                    editing.setCloudflaredHostname(cfH.isEmpty()?null:cfH);
                    String cfId = editCfId.getText().toString().trim();
                    editing.setCloudflaredServiceTokenId(cfId.isEmpty()?null:cfId);
                    String cfSec = editCfSecret.getText().toString().trim();
                    editing.setCloudflaredServiceTokenSecret(cfSec.isEmpty()?null:cfSec);
                    String cfDest = editCfDest.getText().toString().trim();
                    editing.setCloudflaredDestination(cfDest.isEmpty()?null:cfDest);
                } else {
                    editing.setCloudflaredHostname(null);
                    editing.setCloudflaredServiceTokenId(null);
                    editing.setCloudflaredServiceTokenSecret(null);
                    editing.setCloudflaredDestination(null);
                }
            }
            editing.touch();
            if(isEdit) profileManager.updateProfile(editing); else profileManager.addProfile(editing);
            refreshProfilesDrawer();
            Toast.makeText(this, isEdit?"Profile updated":"Profile created", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private void setFullscreen(boolean enabled) {
        boolean changed = fullscreenMode != enabled;
        fullscreenMode = enabled;
        if (toolbar != null) {
            toolbar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
        // Default: keep the status bar (signal/battery/clock) visible in
        // fullscreen; only the navigation bar is hidden. Users who want full
        // immersion can turn this off in Settings.
        boolean keepStatusBar = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_FULLSCREEN_STATUS_BAR, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (enabled) {
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.navigationBars());
                    if (keepStatusBar) controller.show(WindowInsets.Type.statusBars());
                    else controller.hide(WindowInsets.Type.statusBars());
                } else {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        } else {
            View decor = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (enabled) {
                flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                if (!keepStatusBar) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            }
            decor.setSystemUiVisibility(flags);
        }
        if (changed) {
            Toast.makeText(this, enabled ? "Fullscreen enabled" : "Fullscreen disabled", Toast.LENGTH_SHORT).show();
        }
        updateSessionClosedUi();
    }

    private void updateTitle(String title) {
        if (toolbar != null) {
            toolbar.setTitle(title != null && !title.isEmpty() ? title : "Tinyhack SSH");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    private boolean imeVisibleFallback = false;

    /** True if the soft keyboard is currently showing (API 30+ uses real inset state). */
    private boolean isImeVisible() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            return insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        }
        return imeVisibleFallback;
    }

    private void toggleSoftKeyboard() {
        View target = terminalView != null ? terminalView : getWindow().getDecorView();
        target.requestFocus();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // InsetsController is more reliable than InputMethodManager while
            // system bars are hidden (fullscreen mode).
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (isImeVisible()) {
                    controller.hide(WindowInsets.Type.ime());
                    imeVisibleFallback = false;
                } else {
                    controller.show(WindowInsets.Type.ime());
                    imeVisibleFallback = true;
                }
                return;
            }
        }
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (isImeVisible()) {
            imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
            imeVisibleFallback = false;
        } else {
            imm.showSoftInput(target, 0);
            imeVisibleFallback = true;
        }
        if (extraKeysView != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            extraKeysView.setKeyboardVisible(imeVisibleFallback);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_toggle_keyboard) {
            toggleSoftKeyboard();
            return true;
        } else if (id == R.id.action_new_session) {
            createNewLocalSession();
            return true;
        } else if (id == R.id.action_new_profile) {
            showNewProfileDialog();
            return true;
        } else if (id == R.id.action_manage_profiles) {
            startActivity(new Intent(this, com.tinyhack.ssh.ui.ConnectionProfilesActivity.class));
            return true;
        } else if (id == R.id.action_font_up) {
            terminalView.setFontSize(16.0f);
            return true;
        } else if (id == R.id.action_font_down) {
            terminalView.setFontSize(12.0f);
            return true;
        } else if (id == R.id.action_ssh_keys) {
            Intent intent = new Intent(this, com.tinyhack.ssh.ssh.SshKeysActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_ssh_agent) {
            startActivity(new Intent(this, com.tinyhack.ssh.ssh.SshAgentActivity.class));
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, com.tinyhack.ssh.ui.SettingsActivity.class));
            return true;
        } else if (id == R.id.action_about) {
            startActivity(new Intent(this, com.tinyhack.ssh.ui.AboutActivity.class));
            return true;
        } else if (id == R.id.action_reset) {
            if (terminalView.getSession() != null) {
                terminalView.getSession().scroll(1, 0); // Scroll to bottom
            }
            return true;
        } else if (id == android.R.id.home) {
            if (drawerLayout != null) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START);
                else drawerLayout.openDrawer(GravityCompat.START);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (drawerToggle != null) drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (drawerToggle != null) drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePendingConnectRequest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            if (terminalService != null) terminalService.removeSessionsListener(this);
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    public void onDataAvailable() {
        if (terminalView != null) {
            terminalView.onDataAvailable();
        }
    }

    @Override
    public void onTitleChanged(String title) {
        updateTitle(title);
        refreshSessionsList();
    }

    @Override
    public void onBell() {
        if (terminalView != null) {
            terminalView.onBell();
        }
    }

    @Override
    public void onClipboardWrite(String text) {
        if (terminalView != null) {
            terminalView.onClipboardWrite(text);
        }
    }

    @Override
    public void onSessionClosed(int exitCode) {
        // Unused in practice: the terminal view owns the session's listener slot
        // and republishes close events via SessionClosedListener below.
    }

    private void handleSessionClosed(int exitCode) {
        // Deliberately keep the dead session on screen: the terminal draws a
        // "<session closed>" banner and (outside fullscreen) a Close button,
        // so remote disconnects are never mistaken for a live session.
        Toast.makeText(this, "Session closed (exit code: " + exitCode + ")", Toast.LENGTH_SHORT).show();
        updateSessionClosedUi();
        refreshSessionsList();
        if (terminalView != null) terminalView.invalidate();
    }

    // TerminalService.SessionsListener
    @Override
    public void onSessionsChanged() {
        refreshSessionsList();
    }

    @Override
    public void onCurrentSessionChanged(TerminalSession session, int index) {
        runOnUiThread(() -> {
            if (session != null) {
                session.setListener(this);
                terminalView.attachSession(session);
                updateTitle(session.getDisplayTitle());
            }
            refreshSessionsList();
            updateSessionClosedUi();
        });
    }
}
