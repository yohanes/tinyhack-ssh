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
import android.os.Environment;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.tinyhack.ssh.BuildConfig;
import com.tinyhack.ssh.MainActivity;
import com.tinyhack.ssh.R;
import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.model.ProfileManager;
import com.tinyhack.ssh.service.TerminalService;
import com.tinyhack.ssh.ssh.SshTarget;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Local file manager: browse phone storage and the app home, copy / move /
 * rename / delete / share files, and send them to (or fetch them from) a
 * saved SSH server with scp, rsync or sftp. Transfers run in a terminal
 * session so every OpenSSH prompt stays interactive.
 */
public class FileManagerActivity extends AppCompatActivity implements FileListAdapter.Listener {
    public static final String EXTRA_START_DIR = "com.tinyhack.ssh.extra.START_DIR";

    static final String PREFS = "tinyhack_ssh_prefs";
    private static final String PREF_LAST_DIR = "fm_last_dir";
    static final String PREF_SORT = "fm_sort";
    static final String PREF_SHOW_HIDDEN = "fm_show_hidden";
    static final String PREF_METHOD = "fm_transfer_method";
    static final String PREF_LAST_PROFILE = "fm_last_profile";
    static final String PREF_REMOTE_DIR_PREFIX = "fm_remote_dir_";
    private static final String PREF_REMOTE_GET_PREFIX = "fm_remote_get_";

    enum Sort { NAME, SIZE, DATE }
    private enum TransferKind { UPLOAD, DOWNLOAD, SFTP }

    private static final class Root {
        final String label;
        final File dir;
        Root(String label, File dir) { this.label = label; this.dir = dir; }
    }

    private interface Op {
        FileOps.Result run(FileOps.Progress progress, AtomicBoolean cancel);
    }

    private SharedPreferences prefs;
    private ChipGroup rootsGroup;
    private LinearLayout breadcrumbContainer;
    private HorizontalScrollView breadcrumbScroll;
    private View permissionBanner;
    private RecyclerView recycler;
    private LinearLayoutManager layoutManager;
    private TextView emptyView;
    private ProgressBar loading;
    private View pasteBar;
    private TextView pasteText;
    private FileListAdapter adapter;

    private final List<Root> roots = new ArrayList<>();
    private File homeDir;
    private File currentDir;
    private final Map<String, Parcelable> scrollStates = new HashMap<>();
    private int listGeneration;
    private final ExecutorService lister = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean destroyed;

    private ActionMode actionMode;
    private final List<File> clipboard = new ArrayList<>();
    private boolean clipboardMove;

    private TerminalService terminalService;
    private boolean bound;
    private final List<Consumer<TerminalService>> pendingServiceActions = new ArrayList<>();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            terminalService = ((TerminalService.TerminalBinder) binder).getService();
            bound = true;
            List<Consumer<TerminalService>> run = new ArrayList<>(pendingServiceActions);
            pendingServiceActions.clear();
            for (Consumer<TerminalService> c : run) c.accept(terminalService);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            terminalService = null;
            bound = false;
        }
    };

    private final ActivityResultLauncher<Intent> allFilesAccessLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                boolean wasHome = currentDir == null || currentDir.equals(homeDir);
                refreshRoots();
                updatePermissionBanner();
                if (hasAllFilesAccess()) {
                    Toast.makeText(this, "Phone storage is now available", Toast.LENGTH_SHORT).show();
                    if (wasHome && !roots.isEmpty()) navigateTo(roots.get(0).dir);
                }
            });

    /** Remote folder field of the open upload dialog, filled by the folder picker. */
    private EditText pendingRemoteField;

    private final ActivityResultLauncher<Intent> remoteFolderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String dir = result.getData().getStringExtra(RemoteBrowserActivity.EXTRA_REMOTE_DIR);
                if (dir != null && pendingRemoteField != null) {
                    pendingRemoteField.setText(dir);
                    pendingRemoteField.setSelection(dir.length());
                }
            });

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        homeDir = new File(getFilesDir(), "home");
        homeDir.mkdirs();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        rootsGroup = findViewById(R.id.roots_group);
        breadcrumbContainer = findViewById(R.id.breadcrumb_container);
        breadcrumbScroll = findViewById(R.id.breadcrumb_scroll);
        permissionBanner = findViewById(R.id.permission_banner);
        recycler = findViewById(R.id.recycler_files);
        emptyView = findViewById(R.id.empty_view);
        loading = findViewById(R.id.progress_loading);
        pasteBar = findViewById(R.id.paste_bar);
        pasteText = findViewById(R.id.paste_text);

        layoutManager = new LinearLayoutManager(this);
        recycler.setLayoutManager(layoutManager);
        adapter = new FileListAdapter(this);
        recycler.setAdapter(adapter);

        findViewById(R.id.btn_grant_access).setOnClickListener(v -> explainAndRequestAccess());
        findViewById(R.id.btn_paste_cancel).setOnClickListener(v -> clearClipboard());
        findViewById(R.id.btn_paste).setOnClickListener(v -> pasteHere());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                File up = parentWithinRoot(currentDir);
                if (up != null) {
                    navigateTo(up);
                } else {
                    finish();
                }
            }
        });

        refreshRoots();
        currentDir = initialDirectory();

        Intent svc = new Intent(this, TerminalService.class);
        startService(svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Access may have been granted or revoked in system Settings meanwhile.
        refreshRoots();
        updatePermissionBanner();
        if (currentDir == null || !currentDir.isDirectory()) currentDir = initialDirectory();
        loadDirectory(true);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        lister.shutdownNow();
        worker.shutdown(); // let a running copy finish; it no longer touches the UI
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ storage roots

    private boolean hasAllFilesAccess() {
        return BuildConfig.STORAGE_ACCESS_AVAILABLE && Environment.isExternalStorageManager();
    }

    private void refreshRoots() {
        roots.clear();
        if (hasAllFilesAccess()) {
            StorageManager sm = getSystemService(StorageManager.class);
            if (sm != null) {
                for (StorageVolume v : sm.getStorageVolumes()) {
                    File dir = v.getDirectory();
                    if (dir == null || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                    roots.add(new Root(v.getDescription(this), dir));
                }
            }
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null && downloads.isDirectory()) roots.add(new Root("Downloads", downloads));
        }
        roots.add(new Root("Home (~)", homeDir));

        rootsGroup.removeAllViews();
        for (Root r : roots) {
            Chip chip = new Chip(this);
            chip.setText(r.label);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setChipIconResource(R.drawable.ic_folder);
            chip.setChipIconSize(dp(18));
            chip.setChipIconTint(android.content.res.ColorStateList.valueOf(0xFF7DA9FF));
            chip.setOnClickListener(v -> navigateTo(r.dir));
            rootsGroup.addView(chip);
        }
        updateLocationUi();
    }

    private void updatePermissionBanner() {
        permissionBanner.setVisibility(BuildConfig.STORAGE_ACCESS_AVAILABLE && !hasAllFilesAccess()
                ? View.VISIBLE : View.GONE);
    }

    private File initialDirectory() {
        String extra = getIntent() != null ? getIntent().getStringExtra(EXTRA_START_DIR) : null;
        if (extra != null && new File(extra).isDirectory()) return new File(extra);
        String last = prefs.getString(PREF_LAST_DIR, null);
        if (last != null) {
            File f = new File(last);
            if (f.isDirectory() && f.canRead() && rootFor(f) != null) return f;
        }
        return roots.get(0).dir; // phone storage when allowed, otherwise ~
    }

    /** The most specific root containing {@code dir}, or null (e.g. after "Go to path"). */
    private Root rootFor(File dir) {
        Root best = null;
        for (Root r : roots) {
            if (FileOps.isInside(dir, r.dir)
                    && (best == null || r.dir.getAbsolutePath().length() > best.dir.getAbsolutePath().length())) {
                best = r;
            }
        }
        return best;
    }

    private File parentWithinRoot(File dir) {
        if (dir == null) return null;
        Root r = rootFor(dir);
        if (r != null && dir.equals(r.dir)) {
            // Downloads sits inside phone storage: allow going up into it.
            for (Root outer : roots) {
                if (outer != r && FileOps.isInside(dir, outer.dir) && !dir.equals(outer.dir)) {
                    return dir.getParentFile();
                }
            }
            return null;
        }
        if (r == null && dir.getParentFile() == null) return null;
        return dir.getParentFile();
    }

    // -------------------------------------------------------------- navigation

    private void navigateTo(File dir) {
        if (dir == null) return;
        if (!dir.isDirectory()) {
            Toast.makeText(this, "Not a folder: " + dir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentDir != null) scrollStates.put(currentDir.getAbsolutePath(), layoutManager.onSaveInstanceState());
        if (actionMode != null) actionMode.finish();
        currentDir = dir;
        prefs.edit().putString(PREF_LAST_DIR, dir.getAbsolutePath()).apply();
        loadDirectory(false);
    }

    /** @param keepScroll reload in place (onResume / after an operation). */
    private void loadDirectory(boolean keepScroll) {
        final File dir = currentDir;
        final int gen = ++listGeneration;
        final Parcelable scroll = keepScroll ? layoutManager.onSaveInstanceState()
                : scrollStates.get(dir.getAbsolutePath());
        final boolean showHidden = prefs.getBoolean(PREF_SHOW_HIDDEN, false);
        final Sort sort = sortMode();
        updateLocationUi();
        Runnable showSpinner = () -> { if (gen == listGeneration) loading.setVisibility(View.VISIBLE); };
        recycler.postDelayed(showSpinner, 200);
        try {
            lister.execute(() -> {
                File[] kids = dir.listFiles();
                List<FileListAdapter.Entry> entries = new ArrayList<>();
                if (kids != null) {
                    for (File k : kids) {
                        if (!showHidden && k.getName().startsWith(".")) continue;
                        entries.add(new FileListAdapter.Entry(k));
                    }
                    entries.sort(comparator(sort));
                }
                runOnUiThread(() -> {
                    if (destroyed || gen != listGeneration) return;
                    recycler.removeCallbacks(showSpinner);
                    loading.setVisibility(View.GONE);
                    adapter.setEntries(entries);
                    if (scroll != null) layoutManager.onRestoreInstanceState(scroll);
                    else layoutManager.scrollToPosition(0);
                    if (kids == null) {
                        emptyView.setText(!hasAllFilesAccess() && rootFor(dir) != null && !FileOps.isInside(dir, homeDir)
                                ? "Allow All files access to open this folder"
                                : "Cannot open this folder");
                        emptyView.setVisibility(View.VISIBLE);
                    } else if (entries.isEmpty()) {
                        emptyView.setText(kids.length > 0 ? "Only hidden files here" : "This folder is empty");
                        emptyView.setVisibility(View.VISIBLE);
                    } else {
                        emptyView.setVisibility(View.GONE);
                    }
                    if (actionMode != null) actionMode.invalidate();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity is finishing.
        }
    }

    private Sort sortMode() {
        return sortMode(prefs);
    }

    static Sort sortMode(SharedPreferences prefs) {
        try {
            return Sort.valueOf(prefs.getString(PREF_SORT, Sort.NAME.name()));
        } catch (IllegalArgumentException e) {
            return Sort.NAME;
        }
    }

    static Comparator<FileListAdapter.Entry> comparator(Sort sort) {
        Comparator<FileListAdapter.Entry> byName = (a, b) -> {
            int c = a.name.compareToIgnoreCase(b.name);
            return c != 0 ? c : a.name.compareTo(b.name);
        };
        Comparator<FileListAdapter.Entry> primary;
        switch (sort) {
            case SIZE: primary = (a, b) -> Long.compare(b.size, a.size); break;
            case DATE: primary = (a, b) -> Long.compare(b.modified, a.modified); break;
            default: primary = byName; break;
        }
        Comparator<FileListAdapter.Entry> dirsFirst = (a, b) -> Boolean.compare(!a.directory, !b.directory);
        return dirsFirst.thenComparing(primary).thenComparing(byName);
    }

    private void updateLocationUi() {
        if (currentDir == null) return;
        Root root = rootFor(currentDir);
        for (int i = 0; i < rootsGroup.getChildCount() && i < roots.size(); i++) {
            ((Chip) rootsGroup.getChildAt(i)).setChecked(roots.get(i) == root);
        }

        // Breadcrumbs: root label › folder › folder
        List<File> chain = new ArrayList<>();
        File stop = root != null ? root.dir : null;
        for (File f = currentDir; f != null; f = f.getParentFile()) {
            chain.add(0, f);
            if (stop != null && f.equals(stop)) break;
        }
        breadcrumbContainer.removeAllViews();
        for (int i = 0; i < chain.size(); i++) {
            File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            String label;
            if (i == 0 && root != null) label = root.label;
            else if (f.getParentFile() == null) label = "/";
            else label = f.getName();
            if (i > 0) {
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
                crumb.setOnClickListener(v -> navigateTo(f));
            }
            breadcrumbContainer.addView(crumb);
        }
        breadcrumbScroll.post(() -> breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
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
            navigateTo(entry.file);
        } else {
            openFile(entry.file, false);
        }
    }

    @Override
    public void onEntryLongClick(FileListAdapter.Entry entry, int position) {
        if (actionMode == null) actionMode = startSupportActionMode(selectionCallback);
        adapter.toggle(position);
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        if (actionMode == null) return;
        if (adapter.getSelectedCount() == 0) {
            actionMode.finish();
        } else {
            actionMode.invalidate();
        }
    }

    private final ActionMode.Callback selectionCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.file_selection_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            List<FileListAdapter.Entry> sel = adapter.getSelectedEntries();
            int n = sel.size();
            mode.setTitle(n + " selected");
            boolean single = n == 1;
            boolean singleFile = single && !sel.get(0).directory;
            boolean allFiles = n > 0;
            for (FileListAdapter.Entry e : sel) if (e.directory) allFiles = false;
            menu.findItem(R.id.sel_rename).setVisible(single);
            menu.findItem(R.id.sel_details).setVisible(single);
            menu.findItem(R.id.sel_open_with).setVisible(singleFile);
            menu.findItem(R.id.sel_edit_terminal).setVisible(singleFile);
            menu.findItem(R.id.sel_share).setVisible(allFiles);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            List<FileListAdapter.Entry> sel = adapter.getSelectedEntries();
            if (sel.isEmpty()) return true;
            List<File> files = new ArrayList<>();
            for (FileListAdapter.Entry e : sel) files.add(e.file);
            int id = item.getItemId();
            if (id == R.id.sel_upload) {
                showTransferDialog(TransferKind.UPLOAD, files);
            } else if (id == R.id.sel_copy || id == R.id.sel_cut) {
                setClipboard(files, id == R.id.sel_cut);
                mode.finish();
            } else if (id == R.id.sel_delete) {
                confirmDelete(files);
            } else if (id == R.id.sel_share) {
                shareFiles(files);
            } else if (id == R.id.sel_rename) {
                showRenameDialog(files.get(0));
            } else if (id == R.id.sel_open_with) {
                openFile(files.get(0), true);
            } else if (id == R.id.sel_edit_terminal) {
                editInTerminal(files.get(0));
                mode.finish();
            } else if (id == R.id.sel_details) {
                showDetails(sel.get(0));
            } else if (id == R.id.sel_copy_path) {
                StringBuilder sb = new StringBuilder();
                for (File f : files) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(f.getAbsolutePath());
                }
                copyToClipboard(sb.toString());
            } else if (id == R.id.sel_select_all) {
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
        getMenuInflater().inflate(R.menu.file_manager_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.fm_show_hidden).setChecked(prefs.getBoolean(PREF_SHOW_HIDDEN, false));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.fm_browse_server) {
            chooseServerAndBrowse();
        } else if (id == R.id.fm_download) {
            showTransferDialog(TransferKind.DOWNLOAD, null);
        } else if (id == R.id.fm_sftp) {
            showTransferDialog(TransferKind.SFTP, null);
        } else if (id == R.id.fm_new_folder) {
            showNewFolderDialog();
        } else if (id == R.id.fm_terminal) {
            withService(s -> {
                s.createSession(null, currentDir.getAbsolutePath(), null, null);
                showTerminal();
            });
        } else if (id == R.id.fm_sort) {
            showSortDialog();
        } else if (id == R.id.fm_show_hidden) {
            boolean show = !prefs.getBoolean(PREF_SHOW_HIDDEN, false);
            prefs.edit().putBoolean(PREF_SHOW_HIDDEN, show).apply();
            item.setChecked(show);
            loadDirectory(true);
        } else if (id == R.id.fm_go_to) {
            showGoToDialog();
        } else if (id == R.id.fm_copy_path) {
            copyToClipboard(currentDir.getAbsolutePath());
        } else if (id == R.id.fm_refresh) {
            loadDirectory(true);
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void showSortDialog() {
        String[] labels = {"Name", "Size (largest first)", "Date modified (newest first)"};
        new AlertDialog.Builder(this)
                .setTitle("Sort by")
                .setSingleChoiceItems(labels, sortMode().ordinal(), (d, which) -> {
                    prefs.edit().putString(PREF_SORT, Sort.values()[which].name()).apply();
                    d.dismiss();
                    loadDirectory(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showGoToDialog() {
        EditText edit = singleLineEdit(currentDir.getAbsolutePath(), "/path/to/folder");
        new AlertDialog.Builder(this)
                .setTitle("Go to path")
                .setView(padded(edit))
                .setPositiveButton("Go", (d, w) -> {
                    String p = edit.getText().toString().trim();
                    if (p.startsWith("~")) p = homeDir.getAbsolutePath() + p.substring(1);
                    File f = new File(p);
                    if (!f.isAbsolute()) f = new File(currentDir, p);
                    navigateTo(f);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----------------------------------------------------- local file actions

    private void openFile(File file, boolean forceChooser) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Cannot share files from this location", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mimeType(file));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(forceChooser ? Intent.createChooser(view, "Open with") : view);
        } catch (ActivityNotFoundException e) {
            new AlertDialog.Builder(this)
                    .setTitle(file.getName())
                    .setMessage("No installed app can open this type of file.")
                    .setPositiveButton("Edit in terminal", (d, w) -> editInTerminal(file))
                    .setNeutralButton("Share…", (d, w) -> shareFiles(java.util.Collections.singletonList(file)))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private static String mimeType(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "*/*";
    }

    private void shareFiles(List<File> files) {
        ArrayList<Uri> uris = new ArrayList<>();
        String type = null;
        for (File f : files) {
            if (f.isDirectory()) continue;
            try {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", f));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String m = mimeType(f);
            type = type == null ? m : (type.equals(m) ? type : "*/*");
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.setType(type);
        ClipData clip = ClipData.newRawUri(null, uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show();
        }
    }

    private void editInTerminal(File file) {
        List<String> cmd = new ArrayList<>();
        cmd.add("vi");
        cmd.add(file.getAbsolutePath());
        File parent = file.getParentFile();
        withService(s -> {
            s.createCommandSession("vi " + file.getName(),
                    parent != null ? parent.getAbsolutePath() : homeDir.getAbsolutePath(), cmd, null, false)
                    .setReturnToFilesOnExit(true);
            showTerminal();
        });
    }

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
            File target = new File(currentDir, name);
            if (err == null && FileOps.exists(target)) err = "'" + name + "' already exists";
            if (err == null && !target.mkdir()) err = "Cannot create folder here";
            if (err != null) {
                edit.setError(err);
                return;
            }
            dlg.dismiss();
            loadDirectory(true);
        }));
        dlg.show();
        showKeyboard(edit);
    }

    private void showRenameDialog(File file) {
        String name = file.getName();
        EditText edit = singleLineEdit(name, "New name");
        int dot = name.lastIndexOf('.');
        if (dot > 0 && !file.isDirectory()) edit.setSelection(0, dot);
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
            File target = new File(file.getParentFile(), newName);
            if (err == null && FileOps.exists(target)
                    && !newName.equalsIgnoreCase(name)) err = "'" + newName + "' already exists";
            if (err == null && !file.renameTo(target)) err = "Rename failed";
            if (err != null) {
                edit.setError(err);
                return;
            }
            dlg.dismiss();
            if (actionMode != null) actionMode.finish();
            loadDirectory(true);
        }));
        dlg.show();
        showKeyboard(edit);
    }

    private void confirmDelete(List<File> files) {
        StringBuilder msg = new StringBuilder();
        int shown = Math.min(files.size(), 6);
        for (int i = 0; i < shown; i++) msg.append("• ").append(files.get(i).getName()).append('\n');
        if (files.size() > shown) msg.append("… and ").append(files.size() - shown).append(" more\n");
        msg.append("\nFolders are deleted with everything inside. This cannot be undone.");
        new AlertDialog.Builder(this)
                .setTitle("Delete " + plural(files.size(), "item") + "?")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> {
                    if (actionMode != null) actionMode.finish();
                    runOp("Deleting", "Deleted " + plural(files.size(), "item"),
                            (p, cancel) -> FileOps.delete(files, p, cancel));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDetails(FileListAdapter.Entry e) {
        File f = e.file;
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM);
        String base = "Path\n" + f.getAbsolutePath() + "\n\n"
                + "Type\n" + (e.symlink ? "Symbolic link → " + e.linkTarget : e.directory ? "Folder" : mimeType(f).replace("*/*", "File")) + "\n\n"
                + "Modified\n" + (e.modified > 0 ? df.format(new Date(e.modified)) : "unknown") + "\n\n"
                + "Permissions\n" + FileOps.modeString(f) + "\n\n"
                + "Size\n";
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(base + (e.directory && !e.symlink ? "Calculating…" : FileOps.formatSize(e.size)))
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy path", (d, w) -> copyToClipboard(f.getAbsolutePath()))
                .show();
        if (e.directory && !e.symlink) {
            AtomicBoolean cancel = new AtomicBoolean();
            dlg.setOnDismissListener(d -> cancel.set(true));
            try {
                worker.execute(() -> {
                    long[] m = FileOps.measure(java.util.Collections.singletonList(f), cancel);
                    runOnUiThread(() -> {
                        if (destroyed || !dlg.isShowing()) return;
                        dlg.setMessage(base + FileOps.formatSize(m[0]) + " in " + plural((int) m[1], "file"));
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {}
        }
    }

    // ------------------------------------------------------- copy / move bar

    private void setClipboard(List<File> files, boolean move) {
        clipboard.clear();
        clipboard.addAll(files);
        clipboardMove = move;
        pasteText.setText((move ? "Move " : "Copy ") + plural(files.size(), "item")
                + " — open the destination folder, then paste");
        pasteBar.setVisibility(View.VISIBLE);
    }

    private void clearClipboard() {
        clipboard.clear();
        pasteBar.setVisibility(View.GONE);
    }

    private void pasteHere() {
        if (clipboard.isEmpty()) return;
        final File dest = currentDir;
        final List<File> sources = new ArrayList<>(clipboard);
        final boolean move = clipboardMove;
        int conflicts = 0;
        for (File src : sources) {
            File target = new File(dest, src.getName());
            if (!target.getAbsolutePath().equals(src.getAbsolutePath()) && FileOps.exists(target)) conflicts++;
        }
        if (conflicts == 0) {
            startPaste(sources, dest, move, FileOps.Conflict.KEEP_BOTH);
            return;
        }
        String[] choices = {"Replace existing", "Keep both (rename new copies)", "Skip existing"};
        FileOps.Conflict[] map = {FileOps.Conflict.REPLACE, FileOps.Conflict.KEEP_BOTH, FileOps.Conflict.SKIP};
        new AlertDialog.Builder(this)
                .setTitle(plural(conflicts, "item") + " already " + (conflicts == 1 ? "exists" : "exist") + " here")
                .setItems(choices, (d, which) -> startPaste(sources, dest, move, map[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startPaste(List<File> sources, File dest, boolean move, FileOps.Conflict conflict) {
        clearClipboard();
        runOp(move ? "Moving" : "Copying",
                (move ? "Moved " : "Copied ") + plural(sources.size(), "item"),
                (p, cancel) -> FileOps.copy(sources, dest, conflict, move, p, cancel));
    }

    /** Run a file operation on the worker thread behind a cancellable progress dialog. */
    private void runOp(String title, String doneMessage, Op op) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        TextView label = new TextView(this);
        label.setTextColor(0xFFE0E0E0);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setText("Preparing…");
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        TextView counts = new TextView(this);
        counts.setTextColor(0xFF888888);
        counts.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(label);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(counts);

        AtomicBoolean cancel = new AtomicBoolean();
        AlertDialog dlg = new AlertDialog.Builder(this)
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

        long[] lastPost = {0};
        FileOps.Progress progress = (name, bytesDone, bytesTotal, itemsDone, itemsTotal) -> {
            long now = System.nanoTime();
            if (now - lastPost[0] < 80_000_000L) return;
            lastPost[0] = now;
            runOnUiThread(() -> {
                if (destroyed || cancel.get()) return;
                label.setText(name);
                if (bytesTotal > 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(1000, bytesDone * 1000 / bytesTotal));
                    counts.setText(FileOps.formatSize(bytesDone) + " of " + FileOps.formatSize(bytesTotal)
                            + "  •  " + itemsDone + "/" + itemsTotal + " files");
                } else if (itemsTotal > 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress(itemsDone * 1000 / itemsTotal);
                    counts.setText(itemsDone + "/" + itemsTotal);
                }
            });
        };
        try {
            worker.execute(() -> {
                FileOps.Result r = op.run(progress, cancel);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    dlg.dismiss();
                    loadDirectory(true);
                    if (!r.errors.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        int n = Math.min(r.errors.size(), 15);
                        for (int i = 0; i < n; i++) sb.append("• ").append(r.errors.get(i)).append('\n');
                        if (r.errors.size() > n) sb.append("… and ").append(r.errors.size() - n).append(" more");
                        new AlertDialog.Builder(this)
                                .setTitle(r.cancelled ? "Cancelled with errors" : "Some items failed")
                                .setMessage(sb.toString().trim())
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        Toast.makeText(this, r.cancelled ? "Cancelled" : doneMessage, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            dlg.dismiss();
        }
    }

    // ------------------------------------------------------- remote transfers

    /** Saved SSH/mosh profiles with a host; shows a hint and returns empty when there are none. */
    private List<ConnectionProfile> serversOrExplain() {
        List<ConnectionProfile> servers = RemoteBrowserActivity.serverProfiles(this);
        if (servers.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No servers yet")
                    .setMessage("Create an SSH connection profile first. Its host, user, key and Cloudflare Access settings are used for file transfers.")
                    .setPositiveButton("Open profiles", (d, w) -> startActivity(
                            new Intent(this, com.tinyhack.ssh.ui.ConnectionProfilesActivity.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
        return servers;
    }

    private void chooseServerAndBrowse() {
        List<ConnectionProfile> servers = serversOrExplain();
        if (servers.isEmpty()) return;
        if (servers.size() == 1) {
            browseServer(servers.get(0));
            return;
        }
        String[] names = new String[servers.size()];
        int checked = 0;
        String lastId = prefs.getString(PREF_LAST_PROFILE, null);
        for (int i = 0; i < servers.size(); i++) {
            names[i] = servers.get(i).getName() + "  (" + servers.get(i).getDisplaySubtitle() + ")";
            if (servers.get(i).getId().equals(lastId)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Browse server")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    d.dismiss();
                    browseServer(servers.get(which));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void browseServer(ConnectionProfile profile) {
        prefs.edit().putString(PREF_LAST_PROFILE, profile.getId()).apply();
        startActivity(RemoteBrowserActivity.intent(this, profile.getId(), currentDir,
                prefs.getString(PREF_REMOTE_DIR_PREFIX + "browse_" + profile.getId(), null)));
    }

    private void showTransferDialog(TransferKind kind, List<File> files) {
        List<ConnectionProfile> servers = serversOrExplain();
        if (servers.isEmpty()) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        TextView summary = new TextView(this);
        summary.setTextColor(0xFFBBBBBB);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(summary);

        box.addView(fieldLabel("Server"));
        Spinner spinner = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (ConnectionProfile p : servers) names.add(p.getName() + "  (" + p.getDisplaySubtitle() + ")");
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        box.addView(spinner);

        box.addView(fieldLabel(kind == TransferKind.DOWNLOAD ? "Remote file or folder" : "Remote folder"));
        EditText remote = singleLineEdit("", kind == TransferKind.DOWNLOAD
                ? "e.g. backup/photos or /var/log/syslog" : "empty = home folder, e.g. uploads/");
        LinearLayout remoteRow = new LinearLayout(this);
        remoteRow.setOrientation(LinearLayout.HORIZONTAL);
        remoteRow.setGravity(Gravity.CENTER_VERTICAL);
        remoteRow.addView(remote, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        com.google.android.material.button.MaterialButton browse =
                new com.google.android.material.button.MaterialButton(this, null,
                        android.R.attr.borderlessButtonStyle);
        browse.setText("Browse…");
        browse.setTextColor(0xFF7DA9FF);
        remoteRow.addView(browse);
        box.addView(remoteRow);

        RadioGroup methods = new RadioGroup(this);
        methods.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton[] methodButtons = new RadioButton[RemoteTransfer.Method.values().length];
        if (kind != TransferKind.SFTP) {
            box.addView(fieldLabel("Method"));
            RemoteTransfer.Method saved = RemoteTransfer.Method.fromName(prefs.getString(PREF_METHOD, "SCP"));
            for (RemoteTransfer.Method m : RemoteTransfer.Method.values()) {
                RadioButton rb = new RadioButton(this);
                rb.setId(View.generateViewId());
                rb.setText(m == RemoteTransfer.Method.SCP ? "scp" : "rsync (resumable)");
                rb.setChecked(m == saved);
                methodButtons[m.ordinal()] = rb;
                methods.addView(rb);
            }
            box.addView(methods);
            TextView hint = new TextView(this);
            hint.setTextColor(0xFF777777);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setText("The transfer opens in a terminal session, so password, passphrase and host-key prompts work as usual. rsync must also be installed on the server.");
            box.addView(hint);
        } else {
            TextView hint = new TextView(this);
            hint.setTextColor(0xFF777777);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            hint.setPadding(0, dp(8), 0, 0);
            hint.setText("Opens an interactive sftp> session. Use put / get; the local side starts in this folder.");
            box.addView(hint);
        }

        String prefix = kind == TransferKind.DOWNLOAD ? PREF_REMOTE_GET_PREFIX : PREF_REMOTE_DIR_PREFIX;
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                remote.setText(prefs.getString(prefix + servers.get(position).getId(), ""));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        String lastId = prefs.getString(PREF_LAST_PROFILE, null);
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).getId().equals(lastId)) spinner.setSelection(i);
        }

        String title;
        String action;
        if (kind == TransferKind.UPLOAD) {
            title = "Upload to server";
            action = "Upload";
            summary.setText(plural(files.size(), "item") + " from " + displayPath(currentDir));
            measureInto(summary, files);
        } else if (kind == TransferKind.DOWNLOAD) {
            title = "Download from server";
            action = "Download";
            summary.setText("Into " + displayPath(currentDir));
        } else {
            title = "SFTP session";
            action = "Open";
            summary.setText("Local folder: " + displayPath(currentDir));
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(action, null)
                .setNegativeButton("Cancel", null)
                .create();
        browse.setOnClickListener(v -> {
            int pos = spinner.getSelectedItemPosition();
            if (pos < 0) return;
            ConnectionProfile profile = servers.get(pos);
            String typed = remote.getText().toString().trim();
            if (kind == TransferKind.UPLOAD) {
                // Pick the destination folder; the dialog stays open for it.
                pendingRemoteField = remote;
                Intent pick = RemoteBrowserActivity.intent(this, profile.getId(), currentDir, typed);
                pick.putExtra(RemoteBrowserActivity.EXTRA_PICK_FOLDER, true);
                String[] paths = new String[files.size()];
                for (int i = 0; i < files.size(); i++) paths[i] = files.get(i).getAbsolutePath();
                pick.putExtra(RemoteBrowserActivity.EXTRA_UPLOAD_PATHS, paths);
                remoteFolderPicker.launch(pick);
            } else {
                // Download / sftp: browse there instead and act on the files directly.
                dlg.dismiss();
                prefs.edit().putString(PREF_LAST_PROFILE, profile.getId()).apply();
                String start = typed;
                if (kind == TransferKind.DOWNLOAD && !typed.isEmpty()) {
                    String parent = RemoteConnection.parent(typed);
                    start = parent != null ? parent : typed;
                }
                startActivity(RemoteBrowserActivity.intent(this, profile.getId(), currentDir, start));
            }
        });
        dlg.setOnDismissListener(d -> { if (pendingRemoteField == remote) pendingRemoteField = null; });
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int pos = spinner.getSelectedItemPosition();
            if (pos < 0) return;
            ConnectionProfile profile = servers.get(pos);
            String remotePath = remote.getText().toString().trim();
            if (kind == TransferKind.DOWNLOAD && remotePath.isEmpty()) {
                remote.setError("Enter the remote path to download");
                return;
            }
            RemoteTransfer.Method method = RemoteTransfer.Method.SCP;
            for (RemoteTransfer.Method m : RemoteTransfer.Method.values()) {
                if (methodButtons[m.ordinal()] != null && methodButtons[m.ordinal()].isChecked()) method = m;
            }
            SharedPreferences.Editor ed = prefs.edit()
                    .putString(PREF_LAST_PROFILE, profile.getId())
                    .putString(prefix + profile.getId(), remotePath);
            if (kind != TransferKind.SFTP) ed.putString(PREF_METHOD, method.name());
            ed.apply();
            dlg.dismiss();
            RemoteTransfer.Method chosen = method;
            File localDir = currentDir;
            withService(s -> startTransfer(s, kind, profile, chosen, files, remotePath, localDir));
        }));
        dlg.show();
    }

    private void measureInto(TextView summary, List<File> files) {
        String prefix = summary.getText().toString();
        try {
            worker.execute(() -> {
                long[] m = FileOps.measure(files, new AtomicBoolean());
                runOnUiThread(() -> {
                    if (!destroyed) summary.setText(prefix + "\n" + FileOps.formatSize(m[0]) + " in " + plural((int) m[1], "file"));
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void startTransfer(TerminalService s, TransferKind kind, ConnectionProfile profile,
                               RemoteTransfer.Method method, List<File> files, String remotePath, File localDir) {
        SshTarget target = s.buildSshTarget(profile, true);
        if (target.error != null || target.destination == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Cannot use " + profile.getName())
                    .setMessage(target.error != null ? target.error : "The profile has no host.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        // Reuse the remote browser's login for this server when it is still up.
        target = new RemoteConnection(target, s.sshControlDir(), java.util.Collections.emptyMap()).sharedTarget();
        List<String> cmd;
        String sessionName;
        if (kind == TransferKind.UPLOAD) {
            List<String> paths = new ArrayList<>();
            for (File f : files) paths.add(f.getAbsolutePath());
            cmd = RemoteTransfer.upload(method, target, paths, remotePath);
            sessionName = "Upload → " + profile.getName();
        } else if (kind == TransferKind.DOWNLOAD) {
            cmd = RemoteTransfer.download(method, target, remotePath, localDir.getAbsolutePath());
            sessionName = "Download ← " + profile.getName();
        } else {
            cmd = RemoteTransfer.sftp(target, remotePath);
            sessionName = "SFTP " + profile.getName();
        }
        if (actionMode != null) actionMode.finish();
        s.createCommandSession(sessionName, localDir.getAbsolutePath(), cmd, target.envp, true)
                .setReturnToFilesOnExit(true);
        showTerminal();
    }

    // -------------------------------------------------------- permission flow

    private void explainAndRequestAccess() {
        new AlertDialog.Builder(this)
                .setTitle("Allow access to your files")
                .setMessage("The Tinyhack SSH file manager needs All files access to:\n\n"
                        + "• show the folders and files on your phone storage and SD card\n"
                        + "• copy, move, rename, share and delete them\n"
                        + "• upload them to, or download them from, your own servers with scp, rsync or sftp\n\n"
                        + "Files are read or sent only when you start an action. Nothing is uploaded automatically.\n\n"
                        + "On the next screen, turn on \"Allow access to manage all files\".")
                .setPositiveButton("Continue", (d, w) -> launchAllFilesAccessSettings())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void launchAllFilesAccessSettings() {
        try {
            allFilesAccessLauncher.launch(new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                allFilesAccessLauncher.launch(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Open Android Settings › Apps › Special app access › All files access", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void withService(Consumer<TerminalService> action) {
        if (terminalService != null) {
            action.accept(terminalService);
        } else {
            pendingServiceActions.add(action);
        }
    }

    /** Bring the terminal (now showing the newly created session) to the front. */
    private void showTerminal() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private String displayPath(File dir) {
        Root r = rootFor(dir);
        if (r == null) return dir.getAbsolutePath();
        String rel = dir.getAbsolutePath().substring(r.dir.getAbsolutePath().length());
        return r.label + rel;
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
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private TextView fieldLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.05f);
        t.setPadding(0, dp(14), 0, 0);
        t.setGravity(Gravity.START);
        return t;
    }

    private void showKeyboard(EditText edit) {
        edit.requestFocus();
        edit.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
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
