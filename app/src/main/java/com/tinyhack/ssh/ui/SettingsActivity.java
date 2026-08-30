package com.tinyhack.ssh.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.service.TerminalService;
import com.tinyhack.ssh.view.TerminalView;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "tinyhack_ssh_prefs";
    private static final String PREF_HTTP_DEBUG_ENABLED = "http_debug_enabled";
    private static final String PREF_STORAGE_ACCESS_ENABLED = "storage_access_enabled";
    private static final String PREF_NOTIFICATION_DISCLOSURE_SHOWN = "notification_disclosure_shown";

    private TerminalService terminalService;
    private boolean isBound = false;

    private SharedPreferences prefs;
    private TextView textFontSummary;
    private TextView textFontSize;
    private SwitchCompat switchStayConnected;
    private SwitchCompat switchConfirmUrl;
    private SwitchCompat switchStatusBarFullscreen;
    private SwitchCompat switchHttpDebug;
    private SwitchCompat switchStorageAccess;

    private final androidx.activity.result.ActivityResultLauncher<Intent> storagePermLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            result -> applyStorageSetup());

    private final androidx.activity.result.ActivityResultLauncher<String> notificationPermLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted && terminalService != null) {
                    // Re-post the (previously hidden) persistent notification
                    terminalService.refreshForegroundNotification();
                } else if (!granted) {
                    Toast.makeText(this,
                        "Notification permission denied; the persistent notification will not show",
                        Toast.LENGTH_LONG).show();
                }
            });

    private final CompoundButton.OnCheckedChangeListener httpDebugListener =
        (btn, checked) -> setDebugServer(checked);
    private final CompoundButton.OnCheckedChangeListener storageAccessListener =
        (btn, checked) -> setStorageAccess(checked);
    private final CompoundButton.OnCheckedChangeListener stayConnectedListener =
        (btn, checked) -> setStayConnected(checked);

    private void setStayConnected(boolean checked) {
        if (terminalService == null) {
            revertSwitch(switchStayConnected, stayConnectedListener, !checked);
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        terminalService.setStayConnectedEnabled(checked);
        if (checked) requestNotificationPermissionIfNeeded();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TerminalService.TerminalBinder terminalBinder = (TerminalService.TerminalBinder) binder;
            terminalService = terminalBinder.getService();
            isBound = true;
            syncServiceToggles();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            terminalService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        textFontSummary = findViewById(R.id.text_font_summary);
        textFontSize = findViewById(R.id.text_font_size);
        switchStayConnected = findViewById(R.id.switch_stay_connected);
        switchConfirmUrl = findViewById(R.id.switch_confirm_url);
        switchStatusBarFullscreen = findViewById(R.id.switch_status_bar_fullscreen);
        switchHttpDebug = findViewById(R.id.switch_http_debug);
        switchStorageAccess = findViewById(R.id.switch_storage_access);

        // Font (applied to the terminal view when MainActivity resumes)
        refreshFontSummary();
        findViewById(R.id.row_font).setOnClickListener(v -> showFontSelectionDialog());
        findViewById(R.id.btn_font_down).setOnClickListener(v -> changeFontSize(-1));
        findViewById(R.id.btn_font_up).setOnClickListener(v -> changeFontSize(1));

        // Stay connected (default on; asking for POST_NOTIFICATIONS when enabled)
        switchStayConnected.setChecked(prefs.getBoolean("stay_connected", true));
        switchStayConnected.setOnCheckedChangeListener(stayConnectedListener);
        findViewById(R.id.row_stay_connected).setOnClickListener(v -> switchStayConnected.toggle());

        // Confirm URL click (pure pref toggle)
        switchConfirmUrl.setChecked(prefs.getBoolean("confirm_url_click", false));
        switchConfirmUrl.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("confirm_url_click", checked).apply();
            Toast.makeText(this, checked ? "Links will open directly" : "Link confirmation enabled", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.row_confirm_url).setOnClickListener(v -> switchConfirmUrl.toggle());

        // Show status bar when fullscreen (default on; read live by setFullscreen)
        switchStatusBarFullscreen.setChecked(prefs.getBoolean("fullscreen_show_status_bar", true));
        switchStatusBarFullscreen.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("fullscreen_show_status_bar", checked).apply());
        findViewById(R.id.row_status_bar_fullscreen).setOnClickListener(v -> switchStatusBarFullscreen.toggle());

        // Initialize from prefs before attaching listeners so the programmatic
        // setChecked calls below do not fire the toggle handlers
        switchHttpDebug.setChecked(prefs.getBoolean(PREF_HTTP_DEBUG_ENABLED, false));
        switchStorageAccess.setChecked(prefs.getBoolean(PREF_STORAGE_ACCESS_ENABLED, false));
        switchHttpDebug.setOnCheckedChangeListener(httpDebugListener);
        switchStorageAccess.setOnCheckedChangeListener(storageAccessListener);
        findViewById(R.id.row_http_debug).setOnClickListener(v -> switchHttpDebug.toggle());
        findViewById(R.id.row_storage_access).setOnClickListener(v -> switchStorageAccess.toggle());

        Intent intent = new Intent(this, TerminalService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Re-sync the service-backed switches from the running service. */
    private void syncServiceToggles() {
        if (terminalService == null) return;
        switchHttpDebug.setOnCheckedChangeListener(null);
        switchStorageAccess.setOnCheckedChangeListener(null);
        switchStayConnected.setOnCheckedChangeListener(null);
        switchHttpDebug.setChecked(terminalService.isDebugServerEnabled());
        switchStorageAccess.setChecked(terminalService.isStorageAccessEnabled());
        switchStayConnected.setChecked(terminalService.isStayConnectedEnabled());
        switchHttpDebug.setOnCheckedChangeListener(httpDebugListener);
        switchStorageAccess.setOnCheckedChangeListener(storageAccessListener);
        switchStayConnected.setOnCheckedChangeListener(stayConnectedListener);
    }

    /** Ask for POST_NOTIFICATIONS when the platform requires a runtime grant. */
    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        Runnable requestSystemPermission = () -> {
            prefs.edit()
                .putBoolean(PREF_NOTIFICATION_DISCLOSURE_SHOWN, true)
                .putBoolean("notif_permission_asked", true)
                .apply();
            try {
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            } catch (Exception ignored) {}
        };
        if (prefs.getBoolean(PREF_NOTIFICATION_DISCLOSURE_SHOWN, false)) {
            requestSystemPermission.run();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Keep terminal sessions connected")
            .setMessage("Enable notifications if you want terminal sessions to stay connected while Tinyhack SSH is in the background. Tinyhack SSH will still function normally without notification permission, but background sessions may be disconnected.")
            .setPositiveButton("Enable notifications", (dialog, which) -> requestSystemPermission.run())
            .setNegativeButton("Not now", (dialog, which) -> prefs.edit()
                .putBoolean(PREF_NOTIFICATION_DISCLOSURE_SHOWN, true)
                .putBoolean("notif_permission_asked", true)
                .apply())
            .show();
    }

    /** Temporarily detach the listener while reverting a switch programmatically. */
    private void revertSwitch(SwitchCompat sw, CompoundButton.OnCheckedChangeListener listener, boolean checked) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
    }

    private void setDebugServer(boolean enabled) {
        if (terminalService == null) {
            revertSwitch(switchHttpDebug, httpDebugListener, !enabled);
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        terminalService.setDebugServerEnabled(enabled);
        Toast.makeText(this,
            enabled ? "HTTP debug server enabled (port 8080)" : "HTTP debug server disabled",
            Toast.LENGTH_SHORT).show();
    }

    private void setStorageAccess(boolean enabled) {
        if (terminalService == null) {
            revertSwitch(switchStorageAccess, storageAccessListener, !enabled);
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!enabled) {
            terminalService.setStorageAccessEnabled(false);
            terminalService.removeStorageSymlink();
            Toast.makeText(this, "Storage access disabled", Toast.LENGTH_SHORT).show();
            return;
        }
        terminalService.setStorageAccessEnabled(true);
        if (!terminalService.hasManageStoragePermission()) {
            Toast.makeText(this, "Grant 'All files access' to enable storage access", Toast.LENGTH_LONG).show();
            launchStoragePermission();
            return;
        }
        applyStorageSetup();
    }

    private void launchStoragePermission() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName()));
            storagePermLauncher.launch(i);
        } catch (Exception e) {
            try {
                storagePermLauncher.launch(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Ensure the "All files access" permission is granted and ~/storage is linked.
     * If the link target is occupied by a real file, the user gets a warning.
     */
    private void applyStorageSetup() {
        if (terminalService == null || !terminalService.isStorageAccessEnabled()) return;
        if (!terminalService.hasManageStoragePermission()) {
            // Permission declined: roll back so shells do not rely on a missing link
            terminalService.setStorageAccessEnabled(false);
            revertSwitch(switchStorageAccess, storageAccessListener, false);
            Toast.makeText(this, "Permission not granted; storage access disabled", Toast.LENGTH_LONG).show();
            return;
        }
        String warning = terminalService.setupStorageSymlink();
        if (warning == null) {
            Toast.makeText(this, "Storage access enabled: ~/storage -> " + TerminalService.STORAGE_TARGET, Toast.LENGTH_SHORT).show();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Storage Access")
                .setMessage(warning)
                .setPositiveButton("OK", null)
                .show();
        }
    }

    // ---- Font ----

    private float currentFontSizeSp() {
        // Default mirrors TerminalView's 14.0f default
        return prefs.getFloat("font_size_sp", 14.0f);
    }

    private void refreshFontSummary() {
        String family = prefs.getString("font_family", "JetBrainsMono");
        int size = Math.round(currentFontSizeSp());
        textFontSummary.setText(TerminalView.getFontDisplayName(family) + " • " + size + " sp");
        textFontSize.setText(size + " sp");
    }

    private void changeFontSize(int delta) {
        float cur = currentFontSizeSp();
        float next = Math.max(8.0f, Math.min(32.0f, cur + delta));
        if (next != cur) {
            prefs.edit().putFloat("font_size_sp", next).apply();
            refreshFontSummary();
        }
    }

    private void showFontSelectionDialog() {
        String[] displayNames = new String[TerminalView.FONT_FAMILIES.length];
        String current = prefs.getString("font_family", "JetBrainsMono");
        int checkedIdx = 0;
        for (int i = 0; i < TerminalView.FONT_FAMILIES.length; i++) {
            displayNames[i] = TerminalView.getFontDisplayName(TerminalView.FONT_FAMILIES[i]);
            if (TerminalView.FONT_FAMILIES[i].equals(current)) {
                checkedIdx = i;
                displayNames[i] = displayNames[i] + " ✓";
            }
        }
        new AlertDialog.Builder(this)
            .setTitle("Select Font")
            .setSingleChoiceItems(displayNames, checkedIdx, (dialog, which) -> {
                String selected = TerminalView.FONT_FAMILIES[which];
                prefs.edit().putString("font_family", selected).apply();
                refreshFontSummary();
                dialog.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
