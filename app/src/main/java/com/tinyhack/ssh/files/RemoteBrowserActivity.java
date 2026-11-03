package com.tinyhack.ssh.files;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.tinyhack.ssh.MainActivity;
import com.tinyhack.ssh.R;
import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.model.ProfileManager;
import com.tinyhack.ssh.service.TerminalService;
import com.tinyhack.ssh.ssh.SshTarget;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browse a saved server over SFTP: navigate, download, open, rename, delete
 * and create folders, or pick the destination folder of an upload.
 *
 * <p>The login itself runs once in an OpenSSH control master (see
 * {@link RemoteConnection}); listing uses an SFTP channel on that master and
 * downloads run as scp / rsync terminal sessions that reuse it.
 */
public class RemoteBrowserActivity extends AppCompatActivity implements FileListAdapter.Listener {
    public static final String EXTRA_PROFILE_ID = "com.tinyhack.ssh.extra.PROFILE_ID";
    public static final String EXTRA_LOCAL_DIR = "com.tinyhack.ssh.extra.LOCAL_DIR";
    public static final String EXTRA_REMOTE_DIR = "com.tinyhack.ssh.extra.REMOTE_DIR";
    /** Pick a destination folder; the result carries {@link #EXTRA_REMOTE_DIR}. */
    public static final String EXTRA_PICK_FOLDER = "com.tinyhack.ssh.extra.PICK_FOLDER";
    /** Local files to upload into the picked folder when nobody waits for a result. */
    public static final String EXTRA_UPLOAD_PATHS = "com.tinyhack.ssh.extra.UPLOAD_PATHS";
    private static final String EXTRA_AFTER_LOGIN = "com.tinyhack.ssh.extra.AFTER_LOGIN";

    private enum State { CONNECTING, CONNECTED, FAILED }

    private SharedPreferences prefs;
    private MaterialToolbar toolbar;
    private ChipGroup rootsGroup;
    private LinearLayout breadcrumbContainer;
    private HorizontalScrollView breadcrumbScroll;
    private View banner;
    private TextView bannerText;
    private MaterialButton bannerButton;
    private RecyclerView recycler;
    private LinearLayoutManager layoutManager;
    private TextView emptyView;
    private ProgressBar loading;
    private View bottomBar;
    private TextView bottomText;
    private FileListAdapter adapter;

    private ConnectionProfile profile;
    private File localDir;
    private boolean pickMode;
    private String[] uploadPaths;
    private boolean afterLogin;

    private RemoteConnection conn;
    private State state = State.CONNECTING;
    private String homePath;
    private String currentPath;
    private String requestedStart;
    private final Deque<String> history = new ArrayDeque<>();
    private final Map<String, Parcelable> scrollStates = new HashMap<>();
    private int listGeneration;
    private final ExecutorService remote = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private ActionMode actionMode;
    /** "Log in in terminal" was tapped while the silent attempt was running. */
    private boolean wantTerminalLogin;

    private TerminalService terminalService;
    private boolean bound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            terminalService = ((TerminalService.TerminalBinder) binder).getService();
            bound = true;
            setUpConnection();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            terminalService = null;
            bound = false;
        }
    };

    /** Intent that browses {@code profile} (or picks a folder on it). */
    public static Intent intent(Context ctx, String profileId, File localDir, String remoteDir) {
        Intent i = new Intent(ctx, RemoteBrowserActivity.class);
        i.putExtra(EXTRA_PROFILE_ID, profileId);
        if (localDir != null) i.putExtra(EXTRA_LOCAL_DIR, localDir.getAbsolutePath());
        if (remoteDir != null && !remoteDir.isEmpty()) i.putExtra(EXTRA_REMOTE_DIR, remoteDir);
        return i;
    }

    /** Hosts that the browser (and transfers) can use. */
    static List<ConnectionProfile> serverProfiles(Context ctx) {
        List<ConnectionProfile> servers = new ArrayList<>();
        for (ConnectionProfile p : ProfileManager.getInstance(ctx).getProfilesOrdered()) {
            if (p.getType() != ConnectionProfile.Type.LOCAL && p.getHost() != null && !p.getHost().trim().isEmpty()) {
                servers.add(p);
            }
        }
        return servers;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);
        prefs = getSharedPreferences(FileManagerActivity.PREFS, MODE_PRIVATE);

        Intent in = getIntent();
        profile = ProfileManager.getInstance(this).getProfile(in.getStringExtra(EXTRA_PROFILE_ID));
        String local = in.getStringExtra(EXTRA_LOCAL_DIR);
        localDir = local != null ? new File(local) : new File(getFilesDir(), "home");
        pickMode = in.getBooleanExtra(EXTRA_PICK_FOLDER, false);
        uploadPaths = in.getStringArrayExtra(EXTRA_UPLOAD_PATHS);
        afterLogin = in.getBooleanExtra(EXTRA_AFTER_LOGIN, false);
        requestedStart = in.getStringExtra(EXTRA_REMOTE_DIR);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        rootsGroup = findViewById(R.id.roots_group);
        breadcrumbContainer = findViewById(R.id.breadcrumb_container);
        breadcrumbScroll = findViewById(R.id.breadcrumb_scroll);
        banner = findViewById(R.id.permission_banner);
        bannerText = findViewById(R.id.permission_text);
        bannerButton = findViewById(R.id.btn_grant_access);
        recycler = findViewById(R.id.recycler_files);
        emptyView = findViewById(R.id.empty_view);
        loading = findViewById(R.id.progress_loading);
        bottomBar = findViewById(R.id.paste_bar);
        bottomText = findViewById(R.id.paste_text);

        layoutManager = new LinearLayoutManager(this);
        recycler.setLayoutManager(layoutManager);
        adapter = new FileListAdapter(this);
        recycler.setAdapter(adapter);

        if (profile == null) {
            Toast.makeText(this, "This server profile no longer exists", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        toolbar.setTitle(pickMode ? "Choose a folder" : profile.getName());
        toolbar.setSubtitle(profile.getDisplaySubtitle());
        toolbar.setSubtitleTextColor(0xFF9E9E9E);

        if (pickMode) {
            bottomBar.setVisibility(View.VISIBLE);
            MaterialButton use = findViewById(R.id.btn_paste);
            use.setText("Upload here");
            findViewById(R.id.btn_paste_cancel).setOnClickListener(v -> finish());
            use.setOnClickListener(v -> pickCurrentFolder());
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (state == State.CONNECTED && !history.isEmpty()) {
                    navigateTo(history.pop(), false);
                } else {
                    finish();
                }
            }
        });

        cleanOpenCache();
        showStatus("Connecting to " + profile.getName() + "…", null, null);

        Intent svc = new Intent(this, TerminalService.class);
        startService(svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        RemoteConnection c = conn;
        // Close the SFTP channel off the main thread; the master stays up
        // for a while so coming back (or a transfer) needs no new login.
        remote.shutdownNow();
        if (c != null) new Thread(c::closeSftp, "sftp-close").start();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------- connecting

    private void setUpConnection() {
        if (conn != null || terminalService == null || destroyed) return;
        SshTarget target = terminalService.buildSshTarget(profile, true);
        if (target.error != null || target.destination == null) {
            showStatus(target.error != null ? target.error : "The profile has no host.", null, null);
            state = State.FAILED;
            return;
        }
        conn = new RemoteConnection(target, terminalService.sshControlDir(),
                terminalService.sshProcessEnvironment(target.envp));
        connect();
    }

    private void connect() {
        state = State.CONNECTING;
        showStatus("Connecting to " + profile.getName() + "…", "Log in in terminal", this::onLoginButton);
        loading.setVisibility(View.VISIBLE);
        submit(() -> {
            if (!conn.isMasterAlive()) {
                String err = conn.tryStartMasterSilently();
                if (err != null) {
                    ui(() -> onSilentConnectFailed(err));
                    return;
                }
            }
            try {
                SftpClient c = conn.openSftp();
                String home = c.realpath(".");
                String start = home;
                if (requestedStart != null && !requestedStart.trim().isEmpty()) {
                    try {
                        String want = requestedStart.trim();
                        if (!want.startsWith("/")) want = RemoteConnection.join(home, want);
                        String real = c.realpath(want);
                        if (c.stat(real).isDirectory()) start = real;
                    } catch (IOException ignored) {
                        // Saved folder is gone: fall back to home.
                    }
                }
                String first = start;
                ui(() -> onConnected(home, first));
            } catch (IOException e) {
                ui(() -> onConnectFailed(e.getMessage()));
            }
        });
    }

    private void onLoginButton() {
        if (state == State.CONNECTING && conn != null) {
            // Stop waiting for the silent attempt; its failure opens the terminal.
            wantTerminalLogin = true;
            conn.cancelSilentAttempt();
            return;
        }
        launchLogin();
    }

    private void onSilentConnectFailed(String error) {
        state = State.FAILED;
        loading.setVisibility(View.GONE);
        if (afterLogin && !wantTerminalLogin) {
            // The user just tried in the terminal; do not loop back there.
            onConnectFailed(firstLine(error));
        } else {
            // Needs a password, passphrase, host-key answer or browser login.
            launchLogin();
        }
    }

    private void onConnectFailed(String error) {
        state = State.FAILED;
        loading.setVisibility(View.GONE);
        adapter.setEntries(Collections.emptyList());
        emptyView.setVisibility(View.GONE);
        showStatus("Not connected: " + (error != null ? error : "unknown error"), "Connect", this::launchLogin);
    }

    private void onConnected(String home, String start) {
        state = State.CONNECTED;
        homePath = home;
        hideStatus();
        refreshRoots();
        navigateTo(start, false);
        invalidateOptionsMenu();
    }

    /** Authenticate in a terminal session, then come back here. */
    private void launchLogin() {
        if (terminalService == null || conn == null) return;
        TerminalService s = terminalService;
        RemoteConnection c = conn;
        submit(() -> {
            if (c.isMasterAlive()) {
                ui(this::connect);
                return;
            }
            c.removeStaleSocket();
            ui(() -> {
                if (destroyed) return;
                Intent back = intent(this, profile.getId(), localDir,
                        currentPath != null ? currentPath : requestedStart);
                back.putExtra(EXTRA_AFTER_LOGIN, true);
                if (pickMode) {
                    back.putExtra(EXTRA_PICK_FOLDER, true);
                    // The caller's dialog is gone after the terminal round
                    // trip, so the picker uploads these itself.
                    back.putExtra(EXTRA_UPLOAD_PATHS, uploadPaths);
                }
                s.createCommandSession("Log in to " + profile.getName()
                                + " (the file browser reopens when you are in)",
                        localDir.getAbsolutePath(), c.interactiveMasterCommand(),
                        c.getTarget().envp, TerminalService.HoldMode.ON_FAILURE)
                        .setReturnIntents(filesIntent(), back);
                showTerminal();
            });
        });
    }

    // -------------------------------------------------------------- navigation

    private void refreshRoots() {
        rootsGroup.removeAllViews();
        addRootChip("Home (~)", homePath);
        addRootChip("/", "/");
        updateLocationUi();
    }

    private void addRootChip(String label, String path) {
        Chip chip = new Chip(this);
        chip.setText(label);
        chip.setCheckable(true);
        chip.setCheckedIconVisible(false);
        chip.setChipIconResource(R.drawable.ic_folder);
        chip.setChipIconSize(dp(18));
        chip.setChipIconTint(android.content.res.ColorStateList.valueOf(0xFF7DA9FF));
        chip.setTag(path);
        chip.setOnClickListener(v -> navigateTo(path, true));
        rootsGroup.addView(chip);
    }

    private void navigateTo(String path, boolean remember) {
        if (path == null || state != State.CONNECTED) return;
        if (actionMode != null) actionMode.finish();
        if (currentPath != null) {
            scrollStates.put(currentPath, layoutManager.onSaveInstanceState());
            if (remember && !currentPath.equals(path)) history.push(currentPath);
        }
        currentPath = path;
        if (!pickMode) prefs.edit().putString(FileManagerActivity.PREF_REMOTE_DIR_PREFIX + "browse_" + profile.getId(), path).apply();
        loadDirectory(false);
    }

    private void loadDirectory(boolean keepScroll) {
        if (state != State.CONNECTED || currentPath == null) return;
        final String dir = currentPath;
        final int gen = ++listGeneration;
        final Parcelable scroll = keepScroll ? layoutManager.onSaveInstanceState() : scrollStates.get(dir);
        final boolean showHidden = prefs.getBoolean(FileManagerActivity.PREF_SHOW_HIDDEN, false);
        final FileManagerActivity.Sort sort = FileManagerActivity.sortMode(prefs);
        updateLocationUi();
        Runnable showSpinner = () -> { if (gen == listGeneration) loading.setVisibility(View.VISIBLE); };
        recycler.postDelayed(showSpinner, 200);
        submit(() -> {
            List<FileListAdapter.Entry> entries = new ArrayList<>();
            int hidden = 0;
            String error = null;
            boolean lost = false;
            try {
                SftpClient c = sftp();
                for (SftpClient.Name n : c.list(dir)) {
                    if (n.filename.startsWith(".") && !showHidden) {
                        hidden++;
                        continue;
                    }
                    entries.add(toEntry(c, dir, n));
                }
                entries.sort(FileManagerActivity.comparator(sort));
            } catch (SftpClient.SftpException e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = e.getMessage();
                lost = true;
            }
            String err = error;
            boolean connectionLost = lost;
            int hiddenCount = hidden;
            ui(() -> {
                if (gen != listGeneration) return;
                recycler.removeCallbacks(showSpinner);
                loading.setVisibility(View.GONE);
                if (connectionLost) {
                    onConnectionLost(err);
                    return;
                }
                adapter.setEntries(entries);
                if (scroll != null) layoutManager.onRestoreInstanceState(scroll);
                else layoutManager.scrollToPosition(0);
                if (err != null) {
                    emptyView.setText("Cannot open this folder: " + err);
                    emptyView.setVisibility(View.VISIBLE);
                } else if (entries.isEmpty()) {
                    emptyView.setText(hiddenCount > 0 ? "Only hidden files here" : "This folder is empty");
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    emptyView.setVisibility(View.GONE);
                }
                if (actionMode != null) actionMode.invalidate();
            });
        });
    }

    /** Snapshot for the list; links are resolved so a link to a folder opens it. */
    private static FileListAdapter.Entry toEntry(SftpClient c, String dir, SftpClient.Name n) {
        SftpClient.Attrs a = n.attrs;
        String path = RemoteConnection.join(dir, n.filename);
        boolean link = a.isSymlink();
        boolean directory = a.isDirectory();
        long size = a.size;
        String target = null;
        if (link) {
            try { target = c.readlink(path); } catch (IOException ignored) {}
            try {
                SftpClient.Attrs t = c.stat(path);
                directory = t.isDirectory();
                if (!directory) size = t.size;
            } catch (IOException ignored) {
                // Dangling link.
            }
        } else if (!a.hasType() && n.longname != null && n.longname.startsWith("d")) {
            directory = true; // servers that omit the type bits
        }
        return new FileListAdapter.Entry(new File(path), directory, link,
                directory ? 0 : Math.max(0, size), a.mtime > 0 ? a.mtime * 1000 : 0, target, a.modeString());
    }

    private void onConnectionLost(String error) {
        state = State.FAILED;
        RemoteConnection c = conn;
        submit(c::closeSftp);
        showStatus("Connection lost" + (error != null ? ": " + firstLine(error) : ""), "Reconnect", this::connect);
    }

    private void updateLocationUi() {
        if (currentPath == null) return;
        for (int i = 0; i < rootsGroup.getChildCount(); i++) {
            Chip chip = (Chip) rootsGroup.getChildAt(i);
            chip.setChecked(currentPath.equals(chip.getTag()));
        }
        List<String> chain = new ArrayList<>();
        for (String p = currentPath; p != null; p = RemoteConnection.parent(p)) chain.add(0, p);
        breadcrumbContainer.removeAllViews();
        for (int i = 0; i < chain.size(); i++) {
            String p = chain.get(i);
            boolean last = i == chain.size() - 1;
            String label = p.equals("/") ? "/" : RemoteConnection.name(p);
            if (i > 1) {
                TextView sep = new TextView(this);
                sep.setText("›");
                sep.setTextColor(0xFF666666);
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                sep.setPadding(dp(2), 0, dp(2), 0);
                breadcrumbContainer.addView(sep);
            }
            TextView crumb = new TextView(this);
            crumb.setText(label);
            crumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            crumb.setTextColor(last ? 0xFFFFFFFF : 0xFF7DA9FF);
            crumb.setTypeface(last ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            crumb.setPadding(dp(6), dp(8), dp(6), dp(8));
            if (!last) {
                crumb.setBackgroundResource(selectableBackground());
                crumb.setOnClickListener(v -> navigateTo(p, true));
            }
            breadcrumbContainer.addView(crumb);
        }
        breadcrumbScroll.post(() -> breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
        if (pickMode) bottomText.setText("Upload into " + currentPath);
    }

    // ------------------------------------------------------------ list events

    @Override
    public void onEntryClick(FileListAdapter.Entry entry, int position) {
        if (actionMode != null) {
            adapter.toggle(position);
            onSelectionChanged();
            return;
        }
        if (entry.directory) {
            navigateTo(entry.file.getPath(), true);
        } else if (!pickMode) {
            showFileActions(entry);
        }
    }

    @Override
    public void onEntryLongClick(FileListAdapter.Entry entry, int position) {
        if (pickMode) return;
        if (actionMode == null) actionMode = startSupportActionMode(selectionCallback);
        adapter.toggle(position);
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        if (actionMode == null) return;
        if (adapter.getSelectedCount() == 0) actionMode.finish();
        else actionMode.invalidate();
    }

    private void showFileActions(FileListAdapter.Entry e) {
        String[] items = {"Download to " + displayLocal(localDir), "Open", "Rename", "Delete", "Details", "Copy path"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, (d, which) -> {
                    List<FileListAdapter.Entry> one = Collections.singletonList(e);
                    switch (which) {
                        case 0: confirmDownload(one); break;
                        case 1: openRemoteFile(e); break;
                        case 2: showRenameDialog(e); break;
                        case 3: confirmDelete(one); break;
                        case 4: showDetails(e); break;
                        default: copyToClipboard(e.file.getPath()); break;
                    }
                })
                .show();
    }

    private final ActionMode.Callback selectionCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.remote_selection_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            List<FileListAdapter.Entry> sel = adapter.getSelectedEntries();
            int n = sel.size();
            mode.setTitle(n + " selected");
            boolean single = n == 1;
            menu.findItem(R.id.rsel_rename).setVisible(single);
            menu.findItem(R.id.rsel_details).setVisible(single);
            menu.findItem(R.id.rsel_open).setVisible(single && !sel.get(0).directory);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            List<FileListAdapter.Entry> sel = adapter.getSelectedEntries();
            if (sel.isEmpty()) return true;
            int id = item.getItemId();
            if (id == R.id.rsel_download) {
                confirmDownload(sel);
            } else if (id == R.id.rsel_delete) {
                confirmDelete(sel);
            } else if (id == R.id.rsel_rename) {
                showRenameDialog(sel.get(0));
            } else if (id == R.id.rsel_open) {
                openRemoteFile(sel.get(0));
            } else if (id == R.id.rsel_details) {
                showDetails(sel.get(0));
            } else if (id == R.id.rsel_copy_path) {
                StringBuilder sb = new StringBuilder();
                for (FileListAdapter.Entry e : sel) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(e.file.getPath());
                }
                copyToClipboard(sb.toString());
            } else if (id == R.id.rsel_select_all) {
                adapter.selectAll();
                onSelectionChanged();
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            adapter.clearSelection();
        }
    };

    // ------------------------------------------------------------- toolbar menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.remote_browser_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean connected = state == State.CONNECTED;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem it = menu.getItem(i);
            if (it.getItemId() != R.id.rb_disconnect) it.setEnabled(connected);
        }
        menu.findItem(R.id.rb_upload_here).setVisible(!pickMode && connected);
        menu.findItem(R.id.rb_show_hidden).setChecked(prefs.getBoolean(FileManagerActivity.PREF_SHOW_HIDDEN, false));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.rb_upload_here) {
            uploadHereFromFiles();
        } else if (id == R.id.rb_new_folder) {
            showNewFolderDialog();
        } else if (id == R.id.rb_shell) {
            startRemoteSession(RemoteTransfer.shellAt(conn.sharedTarget(), currentPath),
                    "ssh " + profile.getName() + ":" + RemoteConnection.name(currentPath), TerminalService.HoldMode.NONE);
        } else if (id == R.id.rb_sftp) {
            startRemoteSession(RemoteTransfer.sftp(conn.sharedTarget(), currentPath),
                    "SFTP " + profile.getName(), TerminalService.HoldMode.ALWAYS);
        } else if (id == R.id.rb_sort) {
            showSortDialog();
        } else if (id == R.id.rb_show_hidden) {
            boolean show = !prefs.getBoolean(FileManagerActivity.PREF_SHOW_HIDDEN, false);
            prefs.edit().putBoolean(FileManagerActivity.PREF_SHOW_HIDDEN, show).apply();
            item.setChecked(show);
            loadDirectory(true);
        } else if (id == R.id.rb_go_to) {
            showGoToDialog();
        } else if (id == R.id.rb_copy_path) {
            copyToClipboard(currentPath);
        } else if (id == R.id.rb_refresh) {
            loadDirectory(true);
        } else if (id == R.id.rb_disconnect) {
            RemoteConnection c = conn;
            if (c != null) {
                new Thread(c::disconnect, "ssh-disconnect").start();
                Toast.makeText(this, "Logged out of " + profile.getName(), Toast.LENGTH_SHORT).show();
            }
            finish();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void showSortDialog() {
        String[] labels = {"Name", "Size (largest first)", "Date modified (newest first)"};
        new AlertDialog.Builder(this)
                .setTitle("Sort by")
                .setSingleChoiceItems(labels, FileManagerActivity.sortMode(prefs).ordinal(), (d, which) -> {
                    prefs.edit().putString(FileManagerActivity.PREF_SORT,
                            FileManagerActivity.Sort.values()[which].name()).apply();
                    d.dismiss();
                    loadDirectory(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGoToDialog() {
        EditText edit = singleLineEdit(currentPath, "/path/to/folder");
        new AlertDialog.Builder(this)
                .setTitle("Go to path")
                .setView(padded(edit))
                .setPositiveButton("Go", (d, w) -> {
                    String p = edit.getText().toString().trim();
                    if (p.isEmpty()) return;
                    if (p.equals("~")) p = homePath;
                    else if (p.startsWith("~/")) p = RemoteConnection.join(homePath, p.substring(2));
                    else if (!p.startsWith("/")) p = RemoteConnection.join(currentPath, p);
                    resolveAndOpen(p);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resolveAndOpen(String path) {
        submit(() -> {
            try {
                SftpClient c = sftp();
                String real = c.realpath(path);
                boolean dir = c.stat(real).isDirectory();
                ui(() -> {
                    if (dir) navigateTo(real, true);
                    else Toast.makeText(this, "Not a folder: " + real, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                ui(() -> Toast.makeText(this, path + ": " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---------------------------------------------------------- remote actions

    private void showNewFolderDialog() {
        EditText edit = singleLineEdit("", "Folder name");
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("New folder")
                .setView(padded(edit))
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = edit.getText().toString().trim();
            String err = FileOps.validateName(name);
            if (err != null) {
                edit.setError(err);
                return;
            }
            String path = RemoteConnection.join(currentPath, name);
            submit(() -> {
                String failure = null;
                try {
                    sftp().mkdir(path);
                } catch (IOException e) {
                    failure = existsMessage(path, name, e);
                }
                String f = failure;
                ui(() -> {
                    if (f != null) {
                        edit.setError(f);
                        return;
                    }
                    dlg.dismiss();
                    loadDirectory(true);
                });
            });
        }));
        dlg.show();
        showKeyboard(edit);
    }

    private void showRenameDialog(FileListAdapter.Entry e) {
        String name = e.name;
        EditText edit = singleLineEdit(name, "New name");
        int dot = name.lastIndexOf('.');
        if (dot > 0 && !e.directory) edit.setSelection(0, dot);
        else edit.selectAll();
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(padded(edit))
                .setPositiveButton("Rename", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = edit.getText().toString().trim();
            if (newName.equals(name)) {
                dlg.dismiss();
                return;
            }
            String err = FileOps.validateName(newName);
            if (err != null) {
                edit.setError(err);
                return;
            }
            String from = e.file.getPath();
            String to = RemoteConnection.join(RemoteConnection.parent(from), newName);
            submit(() -> {
                String failure = null;
                try {
                    sftp().rename(from, to);
                } catch (IOException ex) {
                    failure = existsMessage(to, newName, ex);
                }
                String f = failure;
                ui(() -> {
                    if (f != null) {
                        edit.setError(f);
                        return;
                    }
                    dlg.dismiss();
                    if (actionMode != null) actionMode.finish();
                    loadDirectory(true);
                });
            });
        }));
        dlg.show();
        showKeyboard(edit);
    }

    /** SFTP v3 reports "already exists" as a generic failure; check to explain it. */
    private String existsMessage(String path, String name, IOException e) {
        try {
            sftp().lstat(path);
            return "'" + name + "' already exists";
        } catch (IOException ignored) {
            return e.getMessage();
        }
    }

    private void confirmDelete(List<FileListAdapter.Entry> entries) {
        StringBuilder msg = new StringBuilder();
        int shown = Math.min(entries.size(), 6);
        for (int i = 0; i < shown; i++) msg.append("• ").append(entries.get(i).name).append('\n');
        if (entries.size() > shown) msg.append("… and ").append(entries.size() - shown).append(" more\n");
        msg.append("\nThese are deleted on ").append(profile.getName())
                .append(". Folders are deleted with everything inside. This cannot be undone.");
        new AlertDialog.Builder(this)
                .setTitle("Delete " + plural(entries.size(), "item") + " on the server?")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> {
                    if (actionMode != null) actionMode.finish();
                    List<String> paths = new ArrayList<>();
                    for (FileListAdapter.Entry e : entries) paths.add(e.file.getPath());
                    runDelete(paths);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runDelete(List<String> paths) {
        Progress p = new Progress("Deleting");
        submit(() -> {
            List<String> errors = new ArrayList<>();
            boolean cancelled = false;
            try {
                SftpClient c = sftp();
                for (String path : paths) {
                    RemoteConnection.deleteRecursive(c, path, p.cancel, errors,
                            (item, done) -> p.update(RemoteConnection.name(item), -1, -1, done + " deleted"));
                }
            } catch (InterruptedIOException e) {
                cancelled = true;
            } catch (IOException e) {
                errors.add(e.getMessage());
            }
            boolean wasCancelled = cancelled;
            ui(() -> {
                p.dismiss();
                loadDirectory(true);
                if (!errors.isEmpty()) showErrors(wasCancelled ? "Cancelled with errors" : "Some items failed", errors);
                else Toast.makeText(this, wasCancelled ? "Cancelled" : "Deleted " + plural(paths.size(), "item"),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showDetails(FileListAdapter.Entry e) {
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM);
        String path = e.file.getPath();
        String base = "Server\n" + profile.getName() + "\n\n"
                + "Path\n" + path + "\n\n"
                + "Type\n" + (e.symlink ? "Symbolic link → " + e.linkTarget : e.directory ? "Folder" : "File") + "\n\n"
                + "Modified\n" + (e.modified > 0 ? df.format(new Date(e.modified)) : "unknown") + "\n\n"
                + "Permissions\n" + (e.mode != null ? e.mode : "?") + "\n\n"
                + "Size\n";
        boolean measure = e.directory && !e.symlink;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(base + (measure ? "Calculating…" : FileOps.formatSize(e.size)))
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy path", (d, w) -> copyToClipboard(path))
                .show();
        if (!measure) return;
        AtomicBoolean cancel = new AtomicBoolean();
        dlg.setOnDismissListener(d -> cancel.set(true));
        submit(() -> {
            String text;
            try {
                long[] m = RemoteConnection.measure(sftp(), path, cancel);
                text = FileOps.formatSize(m[0]) + " in " + plural((int) m[1], "file");
            } catch (InterruptedIOException ex) {
                return;
            } catch (IOException ex) {
                text = "unknown (" + ex.getMessage() + ")";
            }
            String t = text;
            ui(() -> { if (dlg.isShowing()) dlg.setMessage(base + t); });
        });
    }

    /** Download to a private cache with a progress dialog, then hand it to a viewer. */
    private void openRemoteFile(FileListAdapter.Entry e) {
        if (actionMode != null) actionMode.finish();
        File dir = new File(getCacheDir(), "remote-open/" + Long.toHexString(System.nanoTime()));
        File dest = new File(dir, e.name);
        Progress p = new Progress("Opening " + e.name);
        submit(() -> {
            String error = null;
            boolean ok = false;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (OutputStream out = new FileOutputStream(dest)) {
                sftp().download(e.file.getPath(), out,
                        (done, total) -> p.update(e.name, done, total, null), p.cancel);
                ok = true;
            } catch (InterruptedIOException ex) {
                // cancelled
            } catch (IOException ex) {
                error = ex.getMessage();
            }
            if (!ok) FileOps.delete(Collections.singletonList(dir), (a, b, c, d, f) -> {}, new AtomicBoolean());
            boolean success = ok;
            String err = error;
            ui(() -> {
                p.dismiss();
                if (err != null) {
                    showErrors("Cannot open " + e.name, Collections.singletonList(err));
                } else if (success) {
                    viewLocalFile(dest);
                }
            });
        });
    }

    private void viewLocalFile(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mimeType(file.getName()));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, "Open " + file.getName() + " with"));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "No installed app can open this type of file", Toast.LENGTH_SHORT).show();
        }
    }

    /** Opened copies are temporary; drop ones left from earlier visits. */
    private void cleanOpenCache() {
        File root = new File(getCacheDir(), "remote-open");
        File[] old = root.listFiles();
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - 60 * 60 * 1000L;
        List<File> stale = new ArrayList<>();
        for (File f : old) if (f.lastModified() < cutoff) stale.add(f);
        if (!stale.isEmpty()) {
            new Thread(() -> FileOps.delete(stale, (a, b, c, d, f) -> {}, new AtomicBoolean()), "cache-clean").start();
        }
    }

    // ------------------------------------------------------------- transfers

    private void confirmDownload(List<FileListAdapter.Entry> entries) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        TextView summary = new TextView(this);
        summary.setTextColor(0xFFBBBBBB);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        summary.setText(plural(entries.size(), "item") + " from " + profile.getName()
                + "\ninto " + displayLocal(localDir));
        box.addView(summary);
        RadioGroup methods = new RadioGroup(this);
        methods.setOrientation(RadioGroup.HORIZONTAL);
        RemoteTransfer.Method saved = RemoteTransfer.Method.fromName(
                prefs.getString(FileManagerActivity.PREF_METHOD, "SCP"));
        RadioButton[] buttons = new RadioButton[RemoteTransfer.Method.values().length];
        for (RemoteTransfer.Method m : RemoteTransfer.Method.values()) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(m == RemoteTransfer.Method.SCP ? "scp" : "rsync (resumable)");
            rb.setChecked(m == saved);
            buttons[m.ordinal()] = rb;
            methods.addView(rb);
        }
        methods.setPadding(0, dp(8), 0, 0);
        box.addView(methods);
        new AlertDialog.Builder(this)
                .setTitle("Download")
                .setView(box)
                .setPositiveButton("Download", (d, w) -> {
                    RemoteTransfer.Method method = buttons[1].isChecked()
                            ? RemoteTransfer.Method.RSYNC : RemoteTransfer.Method.SCP;
                    prefs.edit().putString(FileManagerActivity.PREF_METHOD, method.name()).apply();
                    List<String> paths = new ArrayList<>();
                    for (FileListAdapter.Entry e : entries) paths.add(e.file.getPath());
                    startRemoteSession(RemoteTransfer.download(method, conn.sharedTarget(), paths,
                            localDir.getAbsolutePath()), "Download ← " + profile.getName(),
                            TerminalService.HoldMode.ALWAYS);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickCurrentFolder() {
        if (state != State.CONNECTED || currentPath == null) return;
        prefs.edit()
                .putString(FileManagerActivity.PREF_LAST_PROFILE, profile.getId())
                .putString(FileManagerActivity.PREF_REMOTE_DIR_PREFIX + profile.getId(), currentPath)
                .apply();
        if (getCallingActivity() != null) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REMOTE_DIR, currentPath));
            finish();
            return;
        }
        // Reopened after a terminal login: nobody waits for the answer.
        if (uploadPaths != null && uploadPaths.length > 0) {
            RemoteTransfer.Method method = RemoteTransfer.Method.fromName(
                    prefs.getString(FileManagerActivity.PREF_METHOD, "SCP"));
            List<String> paths = new ArrayList<>();
            Collections.addAll(paths, uploadPaths);
            startRemoteSession(RemoteTransfer.upload(method, conn.sharedTarget(), paths, currentPath),
                    "Upload → " + profile.getName(), TerminalService.HoldMode.ALWAYS, false);
        } else {
            finish();
        }
    }

    /** Remember this folder as the upload target, then let the user pick local files. */
    private void uploadHereFromFiles() {
        prefs.edit()
                .putString(FileManagerActivity.PREF_LAST_PROFILE, profile.getId())
                .putString(FileManagerActivity.PREF_REMOTE_DIR_PREFIX + profile.getId(), currentPath)
                .apply();
        Toast.makeText(this, "Long-press the files to send, then tap Upload. They go to "
                + profile.getName() + ":" + currentPath, Toast.LENGTH_LONG).show();
        if (isTaskRoot()) startActivity(filesIntent());
        finish();
    }

    private void startRemoteSession(List<String> cmd, String name, TerminalService.HoldMode hold) {
        startRemoteSession(cmd, name, hold, true);
    }

    private void startRemoteSession(List<String> cmd, String name, TerminalService.HoldMode hold,
                                    boolean comeBackHere) {
        if (terminalService == null || conn == null) return;
        if (actionMode != null) actionMode.finish();
        Intent back = intent(this, profile.getId(), localDir, currentPath);
        terminalService.createCommandSession(name, localDir.getAbsolutePath(), cmd,
                conn.getTarget().envp, hold)
                .setReturnIntents(comeBackHere ? new Intent[]{filesIntent(), back} : new Intent[]{filesIntent()});
        showTerminal();
    }

    private Intent filesIntent() {
        Intent i = new Intent(this, FileManagerActivity.class);
        i.putExtra(FileManagerActivity.EXTRA_START_DIR, localDir.getAbsolutePath());
        return i;
    }

    private void showTerminal() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ------------------------------------------------------------------ helpers

    /** The SFTP channel, or an IOException when the connection is gone. */
    private SftpClient sftp() throws IOException {
        RemoteConnection c = conn;
        SftpClient s = c != null ? c.getSftp() : null;
        if (s == null || s.isClosed()) throw new IOException("Not connected");
        return s;
    }

    private void submit(Runnable r) {
        try {
            remote.execute(() -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    // Never let a malformed reply take the whole app down.
                    android.util.Log.w("RemoteBrowser", "remote task failed", e);
                    ui(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity is finishing.
        }
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> { if (!destroyed) r.run(); });
    }

    private void showStatus(String text, String button, Runnable action) {
        banner.setVisibility(View.VISIBLE);
        bannerText.setText(text);
        if (button != null) {
            bannerButton.setVisibility(View.VISIBLE);
            bannerButton.setText(button);
            bannerButton.setOnClickListener(v -> action.run());
        } else {
            bannerButton.setVisibility(View.GONE);
        }
        invalidateOptionsMenu();
    }

    private void hideStatus() {
        banner.setVisibility(View.GONE);
    }

    private void showErrors(String title, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(errors.size(), 15);
        for (int i = 0; i < n; i++) sb.append("• ").append(errors.get(i)).append('\n');
        if (errors.size() > n) sb.append("… and ").append(errors.size() - n).append(" more");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .show();
    }

    /** Cancellable progress dialog driven from the worker thread. */
    private final class Progress {
        final AtomicBoolean cancel = new AtomicBoolean();
        private final AlertDialog dlg;
        private final TextView label;
        private final ProgressBar bar;
        private final TextView counts;
        private long lastPost;

        Progress(String title) {
            LinearLayout box = new LinearLayout(RemoteBrowserActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(12), dp(24), 0);
            label = new TextView(RemoteBrowserActivity.this);
            label.setTextColor(0xFFE0E0E0);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            label.setText("Working…");
            bar = new ProgressBar(RemoteBrowserActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setMax(1000);
            counts = new TextView(RemoteBrowserActivity.this);
            counts.setTextColor(0xFF888888);
            counts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            box.addView(label);
            box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(counts);
            dlg = new AlertDialog.Builder(RemoteBrowserActivity.this)
                    .setTitle(title)
                    .setView(box)
                    .setCancelable(false)
                    .setNegativeButton("Cancel", null)
                    .create();
            dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                cancel.set(true);
                label.setText("Cancelling…");
            }));
            dlg.show();
        }

        /** Worker thread; throttled. done/total &lt; 0 = indeterminate. */
        void update(String name, long done, long total, String text) {
            long now = System.nanoTime();
            if (now - lastPost < 80_000_000L) return;
            lastPost = now;
            ui(() -> {
                if (cancel.get()) return;
                label.setText(name);
                if (total > 0 && done >= 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(1000, done * 1000 / total));
                    counts.setText(FileOps.formatSize(done) + " of " + FileOps.formatSize(total));
                } else if (text != null) {
                    counts.setText(text);
                }
            });
        }

        void dismiss() {
            if (dlg.isShowing()) dlg.dismiss();
        }
    }

    private String displayLocal(File dir) {
        String path = dir.getAbsolutePath();
        String home = new File(getFilesDir(), "home").getAbsolutePath();
        if (path.startsWith(home)) return "~" + path.substring(home.length());
        String storage = TerminalService.STORAGE_TARGET;
        if (path.startsWith(storage)) return "Phone storage" + path.substring(storage.length());
        return path;
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        String t = s.trim();
        // ssh prints warnings first; the last line is the actual failure.
        int nl = t.lastIndexOf('\n');
        return nl >= 0 ? t.substring(nl + 1).trim() : t;
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "*/*";
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("path", text));
        Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show();
    }

    private EditText singleLineEdit(String text, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setHint(hint);
        e.setText(text);
        e.setSelection(e.getText().length());
        return e;
    }

    private View padded(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void showKeyboard(EditText edit) {
        edit.requestFocus();
        edit.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.showSoftInput(edit, 0);
        }, 150);
    }

    private int selectableBackground() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
