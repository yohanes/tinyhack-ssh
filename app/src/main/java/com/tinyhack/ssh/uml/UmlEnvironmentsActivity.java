package com.tinyhack.ssh.uml;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.tinyhack.ssh.MainActivity;
import com.tinyhack.ssh.R;
import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.model.ProfileManager;
import com.tinyhack.ssh.service.TerminalService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Manage UML Linux environments: create (Debian or Ubuntu), start, change
 * memory and /data sharing, view the console log, and delete. Long-running
 * work (creation, the guest console) runs in terminal sessions; quick changes
 * call {@code uml-env} in the background.
 */
public class UmlEnvironmentsActivity extends AppCompatActivity {
    private static final long POLL_MS = 4000;

    private RecyclerView recyclerView;
    private TextView emptyView;
    private final EnvAdapter adapter = new EnvAdapter();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private boolean resumed;
    private boolean destroyed;
    private int refreshGeneration;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_uml_environments);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Linux environments");
        }
        recyclerView = findViewById(R.id.recycler_view);
        emptyView = findViewById(R.id.empty_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showCreateDialog());

        Intent svc = new Intent(this, TerminalService.class);
        startService(svc);
        bindService(svc, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(poll);
        worker.shutdown();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.uml_environments_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.uml_refresh) {
            refresh();
            return true;
        } else if (id == R.id.uml_help) {
            showHelp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        handler.removeCallbacks(poll);
        int generation = ++refreshGeneration;
        worker.execute(() -> {
            List<UmlEnv> envs;
            String error = null;
            try {
                envs = UmlEnv.list(this);
            } catch (IOException e) {
                envs = new ArrayList<>();
                error = e.getMessage();
            }
            List<UmlEnv> result = envs;
            String failure = error;
            runOnUiThread(() -> {
                if (destroyed || generation != refreshGeneration) return;
                adapter.set(result);
                if (failure != null) {
                    emptyView.setText("Could not read the environments: " + failure
                            + "\n\nIf the app was just updated, open a terminal once so it can finish installing its files.");
                }
                emptyView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                // Running guests stop from inside their console; keep the list current.
                boolean active = false;
                for (UmlEnv e : result) {
                    if (e.state == UmlEnv.State.RUNNING || e.state == UmlEnv.State.CREATING) active = true;
                }
                if (resumed && active) handler.postDelayed(poll, POLL_MS);
            });
        });
    }

    private boolean nameTaken(String name) {
        for (UmlEnv e : adapter.envs) {
            if (e.name.equalsIgnoreCase(name)) return true;
        }
        return new File(UmlEnv.envsDir(this), name).exists();
    }

    // --------------------------------------------------------------- actions

    private void start(UmlEnv env) {
        if (env.state == UmlEnv.State.RUNNING) {
            Toast.makeText(this, env.name + " is already running. Its console is in the session list.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        withService(s -> {
            s.createCommandSession(env.name + " (" + env.typeLabel() + ")", homeDir().getAbsolutePath(),
                            UmlEnv.command(this, "start", env.name), null, TerminalService.HoldMode.ON_FAILURE)
                    .setReturnIntents(new Intent(this, UmlEnvironmentsActivity.class));
            showTerminal();
        });
    }

    private void runConfig(UmlEnv env, String... args) {
        worker.execute(() -> {
            String message;
            try {
                List<String> all = new ArrayList<>();
                all.add("config");
                all.add(env.name);
                java.util.Collections.addAll(all, args);
                UmlEnv.Result r = UmlEnv.run(this, all.toArray(new String[0]));
                message = r.ok()
                        ? (env.state == UmlEnv.State.RUNNING ? "Saved. Restart " + env.name + " to apply." : "Saved.")
                        : r.message();
            } catch (IOException e) {
                message = "Could not save: " + e.getMessage();
            }
            String shown = message;
            runOnUiThread(() -> {
                if (destroyed) return;
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void delete(UmlEnv env) {
        worker.execute(() -> {
            String message;
            boolean ok = false;
            try {
                UmlEnv.Result r = UmlEnv.run(this, "delete", env.name, "--yes");
                ok = r.ok();
                message = r.message();
            } catch (IOException e) {
                message = "Could not delete: " + e.getMessage();
            }
            String shown = message;
            boolean deleted = ok;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (deleted) {
                    Toast.makeText(this, shown, Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Not deleted")
                            .setMessage(shown)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
                refresh();
            });
        });
    }

    private void addProfile(UmlEnv env) {
        ProfileManager pm = ProfileManager.getInstance(this);
        for (ConnectionProfile p : pm.loadProfiles()) {
            if (p.getType() == ConnectionProfile.Type.LOCAL && env.name.equals(p.getUmlEnvironment())) {
                Toast.makeText(this, "Profile \"" + p.getName() + "\" already starts " + env.name + ".",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        ConnectionProfile p = new ConnectionProfile(env.name + " (" + env.typeLabel() + ")",
                ConnectionProfile.Type.LOCAL);
        p.setUmlEnvironment(env.name);
        pm.addProfile(p);
        Toast.makeText(this, "Added \"" + p.getName() + "\" to the connection profiles in the drawer.",
                Toast.LENGTH_LONG).show();
    }

    // --------------------------------------------------------------- dialogs

    private void showCreateDialog() {
        LinearLayout layout = form();

        label(layout, "System");
        RadioGroup types = new RadioGroup(this);
        RadioButton debian = new RadioButton(this);
        debian.setId(View.generateViewId());
        debian.setText("Debian 13 (trixie)");
        RadioButton ubuntu = new RadioButton(this);
        ubuntu.setId(View.generateViewId());
        ubuntu.setText("Ubuntu 26.04 LTS");
        types.addView(debian);
        types.addView(ubuntu);
        debian.setChecked(true);
        layout.addView(types);

        EditText name = field(layout, "Name", "e.g. devenv", InputType.TYPE_CLASS_TEXT, "");
        EditText user = field(layout, "Username", "Linux user with passwordless sudo",
                InputType.TYPE_CLASS_TEXT, "user");
        EditText disk = field(layout, "Maximum disk size", "e.g. 4G; storage is used only as files are written",
                InputType.TYPE_CLASS_TEXT, "4G");
        EditText memory = field(layout, "Memory", "e.g. 512M or 2G; changeable later",
                InputType.TYPE_CLASS_TEXT, "512M");
        SwitchCompat share = shareSwitch(layout, true);

        TextView note = new TextView(this);
        note.setText("Creation needs internet access. The first environment of each system downloads its image (35–45 MB), then sudo and a few tools; it takes a few minutes.");
        note.setTextSize(12);
        note.setPadding(0, dp(12), 0, 0);
        layout.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New Linux environment")
                .setView(scroll(layout))
                .setPositiveButton("Create", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String u = user.getText().toString().trim();
            String size = disk.getText().toString().trim().toUpperCase(Locale.ROOT);
            String mem = memory.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!UmlEnv.NAME.matcher(n).matches()) {
                name.setError("1–48 letters, digits, _ or -, starting with a letter or digit");
                return;
            }
            if (nameTaken(n)) {
                name.setError("An environment with this name already exists");
                return;
            }
            if (!UmlEnv.USER.matcher(u).matches() || u.equals("root")) {
                user.setError("Lowercase letters, digits, _ or -, starting with a letter; not root");
                return;
            }
            int sizeMb = UmlEnv.parseSizeMb(size);
            if (sizeMb < 512 || sizeMb > 1048576) {
                disk.setError("From 512M to 1024G, e.g. 4G");
                return;
            }
            int memMb = UmlEnv.parseSizeMb(mem);
            if (memMb < 256 || memMb > 65536) {
                memory.setError("From 256M to 64G, e.g. 512M");
                return;
            }
            String type = ubuntu.isChecked() ? "ubuntu" : "debian";
            dialog.dismiss();
            create(type, n, size, u, mem, share.isChecked());
        }));
        dialog.show();
    }

    private void create(String type, String name, String size, String user, String memory, boolean share) {
        withService(s -> {
            s.createCommandSession("Create " + name, homeDir().getAbsolutePath(),
                            UmlEnv.command(this, "create", "-t", type, "-n", name, "-d", size, "-u", user,
                                    "--memory", memory, "--share-home", share ? "on" : "off"),
                            null, TerminalService.HoldMode.ALWAYS)
                    .setReturnIntents(new Intent(this, UmlEnvironmentsActivity.class));
            showTerminal();
        });
    }

    private void showSettings(UmlEnv env) {
        LinearLayout layout = form();
        EditText memory = field(layout, "Memory", "e.g. 512M or 2G", InputType.TYPE_CLASS_TEXT,
                env.memoryMb() % 1024 == 0 && env.memoryMb() > 0 ? (env.memoryMb() / 1024) + "G" : env.memory);
        SwitchCompat share = shareSwitch(layout, env.shareHome);
        TextView note = new TextView(this);
        note.setText(env.state == UmlEnv.State.RUNNING
                ? env.name + " is running; changes apply the next time it starts."
                : "Changes apply the next time the environment starts. The disk size cannot be changed.");
        note.setTextSize(12);
        note.setPadding(0, dp(12), 0, 0);
        layout.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(env.name + " settings")
                .setView(scroll(layout))
                .setPositiveButton("Save", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String mem = memory.getText().toString().trim().toUpperCase(Locale.ROOT);
            int memMb = UmlEnv.parseSizeMb(mem);
            if (memMb < 256 || memMb > 65536) {
                memory.setError("From 256M to 64G, e.g. 512M");
                return;
            }
            dialog.dismiss();
            runConfig(env, "--memory", mem, "--share-home", share.isChecked() ? "on" : "off");
        }));
        dialog.show();
    }

    private void confirmDelete(UmlEnv env) {
        if (env.state == UmlEnv.State.RUNNING || env.state == UmlEnv.State.CREATING) {
            new AlertDialog.Builder(this)
                    .setTitle("Shut it down first")
                    .setMessage(env.name + " is running. In its console, run \"sudo poweroff\", then delete it.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String used = env.usedBytes > 0 ? " (" + size(env.usedBytes) + ")" : "";
        new AlertDialog.Builder(this)
                .setTitle("Delete " + env.name + "?")
                .setMessage("This permanently deletes its disk" + used + ", including every file and package"
                        + " installed inside it. Files in the shared app home (/data) are not affected."
                        + "\n\nConnection profiles that start " + env.name + " will stop working.")
                .setPositiveButton("Delete", (d, w) -> delete(env))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLog(UmlEnv env) {
        worker.execute(() -> {
            String text = env.readLogTail(this, 32 * 1024);
            runOnUiThread(() -> {
                if (destroyed) return;
                TextView view = new TextView(this);
                view.setTypeface(Typeface.MONOSPACE);
                view.setTextSize(11);
                view.setTextIsSelectable(true);
                view.setPadding(dp(16), dp(8), dp(16), dp(8));
                view.setText(text.isEmpty() ? "The console log is empty." : text);
                ScrollView sv = new ScrollView(this);
                sv.addView(view);
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                new AlertDialog.Builder(this)
                        .setTitle(env.name + " console log")
                        .setView(sv)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Linux environments")
                .setMessage("Each environment is a Debian or Ubuntu system running inside this app with"
                        + " User-Mode Linux. It has its own disk, user and installed packages; no root"
                        + " access to the phone is needed or granted.\n\n"
                        + "• Your user has passwordless sudo: sudo apt-get install PACKAGE.\n"
                        + "• With \"Share app home\" on, the app's home folder (the Android shell's ~)"
                        + " appears at /data inside the environment.\n"
                        + "• Shut down with sudo poweroff before closing the console.\n"
                        + "• Use \"Add to profiles\" for one-tap access from the drawer.\n\n"
                        + "The same is available in a terminal: uml-env help.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // --------------------------------------------------------------- helpers

    private void withService(Consumer<TerminalService> action) {
        if (terminalService != null) action.accept(terminalService);
        else pendingServiceActions.add(action);
    }

    private void showTerminal() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private File homeDir() {
        return new File(getFilesDir(), "home");
    }

    /** Binary units, matching the M/G sizes typed when creating ("4G" shows as 4 GB). */
    static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) return Math.round(mb) + " MB";
        double gb = mb / 1024;
        return (gb == Math.floor(gb) || gb >= 100)
                ? Math.round(gb) + " GB" : String.format(Locale.ROOT, "%.1f GB", gb);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout form() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), dp(4));
        return layout;
    }

    private ScrollView scroll(View content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        return sv;
    }

    private void label(LinearLayout layout, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setPadding(0, dp(10), 0, 0);
        layout.addView(label);
    }

    private EditText field(LinearLayout layout, String title, String hint, int inputType, String value) {
        label(layout, title);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(inputType | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setHint(hint);
        edit.setText(value);
        layout.addView(edit);
        return edit;
    }

    private SwitchCompat shareSwitch(LinearLayout layout, boolean checked) {
        SwitchCompat share = new SwitchCompat(this);
        share.setText("Share app home at /data");
        share.setChecked(checked);
        share.setPadding(0, dp(12), 0, 0);
        layout.addView(share);
        TextView hint = new TextView(this);
        hint.setText("The Android shell's home folder (~) is mounted at /data and owned by your Linux user.");
        hint.setTextSize(12);
        layout.addView(hint);
        return share;
    }

    // --------------------------------------------------------------- adapter

    private final class EnvAdapter extends RecyclerView.Adapter<EnvAdapter.Holder> {
        final List<UmlEnv> envs = new ArrayList<>();

        void set(List<UmlEnv> list) {
            envs.clear();
            envs.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return envs.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_uml_environment, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            UmlEnv env = envs.get(position);
            Context ctx = h.itemView.getContext();
            h.name.setText(env.name);
            h.type.setText(env.typeLabel().toUpperCase(Locale.ROOT));
            StringBuilder details = new StringBuilder();
            switch (env.state) {
                case RUNNING:
                    h.state.setText("● Running");
                    h.state.setTextColor(0xFF66BB6A);
                    break;
                case CREATING:
                    h.state.setText("Being created…");
                    h.state.setTextColor(0xFFFFB74D);
                    break;
                case INCOMPLETE:
                    h.state.setText("Creation did not finish");
                    h.state.setTextColor(0xFFFF6B6B);
                    break;
                default:
                    h.state.setText("Stopped");
                    h.state.setTextColor(0xFF9E9E9E);
                    break;
            }
            if (!env.user.isEmpty()) details.append("User ").append(env.user).append("  •  ");
            details.append(env.memoryMb() >= 1024 && env.memoryMb() % 1024 == 0
                    ? (env.memoryMb() / 1024) + " GB" : env.memoryMb() + " MB").append(" memory");
            if (env.diskBytes > 0) {
                details.append("\nDisk ").append(size(env.diskBytes))
                        .append(", ").append(size(env.usedBytes)).append(" used");
            }
            details.append(env.shareHome ? "\nApp home shared at /data" : "\nApp home not shared");
            if (env.state == UmlEnv.State.INCOMPLETE) {
                details.append("\nView the log to see what failed, then delete it and create a new one.");
            }
            h.details.setText(details);

            boolean ready = env.state == UmlEnv.State.READY || env.state == UmlEnv.State.RUNNING;
            h.settings.setVisibility(ready ? View.VISIBLE : View.GONE);
            h.settings.setOnClickListener(v -> showSettings(env));
            if (env.state == UmlEnv.State.INCOMPLETE || env.state == UmlEnv.State.CREATING) {
                h.start.setText("View log");
                h.start.setEnabled(true);
                h.start.setOnClickListener(v -> showLog(env));
            } else {
                h.start.setText(env.state == UmlEnv.State.RUNNING ? "Running" : "Start");
                h.start.setEnabled(env.state != UmlEnv.State.RUNNING);
                h.start.setOnClickListener(v -> start(env));
            }
            h.start.setAlpha(h.start.isEnabled() ? 1f : 0.5f);
            h.more.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(ctx, v);
                Menu m = menu.getMenu();
                m.add(0, 1, 0, "View console log");
                if (ready) m.add(0, 2, 1, "Add to profiles");
                m.add(0, 3, 2, "Delete");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) showLog(env);
                    else if (item.getItemId() == 2) addProfile(env);
                    else if (item.getItemId() == 3) confirmDelete(env);
                    return true;
                });
                menu.show();
            });
            h.itemView.setOnClickListener(v -> {
                if (env.state == UmlEnv.State.READY) start(env);
                else if (env.state != UmlEnv.State.RUNNING) showLog(env);
            });
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView type;
            final TextView state;
            final TextView details;
            final Button settings;
            final Button start;
            final ImageButton more;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.text_env_name);
                type = v.findViewById(R.id.text_env_type);
                state = v.findViewById(R.id.text_env_state);
                details = v.findViewById(R.id.text_env_details);
                settings = v.findViewById(R.id.btn_env_settings);
                start = v.findViewById(R.id.btn_env_start);
                more = v.findViewById(R.id.btn_env_more);
            }
        }
    }
}
