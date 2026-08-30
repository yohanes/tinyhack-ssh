package com.tinyhack.ssh.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.model.ProfileManager;
import com.tinyhack.ssh.ssh.SshKeyManager;
import com.tinyhack.ssh.ssh.SshKeyInfo;
import com.tinyhack.ssh.ui.adapter.ProfilesAdapter;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

public class ConnectionProfilesActivity extends AppCompatActivity implements ProfilesAdapter.OnProfileActionListener {

    private RecyclerView recyclerView;
    private TextView emptyView;
    private FloatingActionButton fab;
    private ProfilesAdapter adapter;
    private ProfileManager profileManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Connection Profiles");
        }

        recyclerView = findViewById(R.id.recycler_profiles);
        emptyView = findViewById(R.id.empty_view);
        fab = findViewById(R.id.fab_add_profile);

        profileManager = ProfileManager.getInstance(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfilesAdapter(this, false);
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(v -> showProfileEditDialog(null));

        loadProfiles();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadProfiles() {
        List<ConnectionProfile> list = profileManager.loadProfiles();
        adapter.updateProfiles(list);
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onProfileConnect(ConnectionProfile profile) {
        getSharedPreferences("tinyhack_ssh_prefs", MODE_PRIVATE)
                .edit()
                .putString("pending_connect_profile_id", profile.getId())
                .apply();
        Intent intent = new Intent(this, com.tinyhack.ssh.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    public void onProfileEdit(ConnectionProfile profile) {
        showProfileEditDialog(profile);
    }

    @Override
    public void onProfileDelete(ConnectionProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Profile")
                .setMessage("Delete profile '" + profile.getName() + "'? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    profileManager.deleteProfile(profile.getId());
                    loadProfiles();
                    Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onProfileDuplicate(ConnectionProfile profile) {
        try {
            ConnectionProfile copy = ConnectionProfile.fromJson(profile.toJson());
            copy.setId(java.util.UUID.randomUUID().toString());
            copy.setName(profile.getName() + " Copy");
            copy.setCreatedAt(System.currentTimeMillis());
            copy.setUpdatedAt(System.currentTimeMillis());
            profileManager.addProfile(copy);
            loadProfiles();
            Toast.makeText(this, "Duplicated " + copy.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Duplicate failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void showProfileEditDialog(@Nullable ConnectionProfile existing) {
        boolean isEdit = existing != null;
        ConnectionProfile profile = existing;
        if (profile == null) {
            profile = new ConnectionProfile("New Profile", ConnectionProfile.Type.LOCAL);
        }
        final ConnectionProfile editing = profile;

        Context ctx = this;
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        int pad = 12;

        // Name
        TextView nameLabel = new TextView(ctx);
        nameLabel.setText("Profile Name");
        nameLabel.setTextColor(0xFFAAAAAA);
        nameLabel.setTextSize(12);
        layout.addView(nameLabel);
        EditText editName = new EditText(ctx);
        editName.setText(editing.getName());
        editName.setHint("My Server");
        editName.setSingleLine(true);
        layout.addView(editName);

        // Type spinner
        TextView typeLabel = new TextView(ctx);
        typeLabel.setText("Type");
        typeLabel.setTextColor(0xFFAAAAAA);
        typeLabel.setTextSize(12);
        typeLabel.setPadding(0, pad, 0, 0);
        layout.addView(typeLabel);
        Spinner spinnerType = new Spinner(ctx);
        String[] types = {"LOCAL", "SSH", "MOSH"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, types);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerType.setAdapter(typeAdapter);
        int typeSel = editing.getType() == ConnectionProfile.Type.SSH ? 1
                : (editing.getType() == ConnectionProfile.Type.MOSH ? 2 : 0);
        spinnerType.setSelection(typeSel);
        layout.addView(spinnerType);

        // Local fields
        TextView shellLabel = new TextView(ctx);
        shellLabel.setText("Shell (optional, e.g. /data/data/.../usr/bin/bash)");
        shellLabel.setTextColor(0xFFAAAAAA);
        shellLabel.setTextSize(11);
        shellLabel.setPadding(0, pad, 0, 0);
        layout.addView(shellLabel);
        EditText editShell = new EditText(ctx);
        editShell.setText(editing.getShell() != null ? editing.getShell() : "");
        editShell.setHint("Default bash");
        editShell.setSingleLine(true);
        layout.addView(editShell);

        TextView cwdLabel = new TextView(ctx);
        cwdLabel.setText("Working Directory (optional)");
        cwdLabel.setTextColor(0xFFAAAAAA);
        cwdLabel.setTextSize(11);
        cwdLabel.setPadding(0, pad, 0, 0);
        layout.addView(cwdLabel);
        EditText editCwd = new EditText(ctx);
        editCwd.setText(editing.getCwd() != null ? editing.getCwd() : "");
        editCwd.setHint("/data/data/.../files/home");
        editCwd.setSingleLine(true);
        layout.addView(editCwd);

        TextView envLabel = new TextView(ctx);
        envLabel.setText("Environment (KEY=VAL per line)");
        envLabel.setTextColor(0xFFAAAAAA);
        envLabel.setTextSize(11);
        envLabel.setPadding(0, pad, 0, 0);
        layout.addView(envLabel);
        EditText editEnv = new EditText(ctx);
        editEnv.setText(editing.getEnv() != null ? editing.getEnv() : "");
        editEnv.setHint("FOO=bar\nBAZ=qux");
        editEnv.setMinLines(2);
        layout.addView(editEnv);

        // SSH fields
        TextView hostLabel = new TextView(ctx);
        hostLabel.setText("SSH Host");
        hostLabel.setTextColor(0xFFAAAAAA);
        hostLabel.setTextSize(11);
        hostLabel.setPadding(0, pad, 0, 0);
        layout.addView(hostLabel);
        EditText editHost = new EditText(ctx);
        editHost.setText(editing.getHost() != null ? editing.getHost() : "");
        editHost.setHint("192.168.1.10 or example.com");
        editHost.setSingleLine(true);
        layout.addView(editHost);

        TextView portLabel = new TextView(ctx);
        portLabel.setText("SSH Port");
        portLabel.setTextColor(0xFFAAAAAA);
        portLabel.setTextSize(11);
        layout.addView(portLabel);
        EditText editPort = new EditText(ctx);
        editPort.setText(String.valueOf(editing.getPort() != 0 ? editing.getPort() : 22));
        editPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(editPort);

        TextView userLabel = new TextView(ctx);
        userLabel.setText("Username");
        userLabel.setTextColor(0xFFAAAAAA);
        userLabel.setTextSize(11);
        layout.addView(userLabel);
        EditText editUser = new EditText(ctx);
        editUser.setText(editing.getUsername() != null ? editing.getUsername() : "");
        editUser.setHint("ubuntu");
        editUser.setSingleLine(true);
        layout.addView(editUser);

        TextView authLabel = new TextView(ctx);
        authLabel.setText("Auth Type");
        authLabel.setTextColor(0xFFAAAAAA);
        authLabel.setTextSize(11);
        layout.addView(authLabel);
        Spinner spinnerAuth = new Spinner(ctx);
        String[] auths = {"NONE", "KEY", "PASSWORD"};
        ArrayAdapter<String> authAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, auths);
        authAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAuth.setAdapter(authAdapter);
        int authIdx = 0;
        if (editing.getAuthType() == ConnectionProfile.AuthType.KEY) authIdx = 1;
        else if (editing.getAuthType() == ConnectionProfile.AuthType.PASSWORD) authIdx = 2;
        spinnerAuth.setSelection(authIdx);
        layout.addView(spinnerAuth);

        TextView keyLabel = new TextView(ctx);
        keyLabel.setText("SSH Key (name in ~/.ssh)");
        keyLabel.setTextColor(0xFFAAAAAA);
        keyLabel.setTextSize(11);
        layout.addView(keyLabel);
        // Build key dropdown from available keys
        List<SshKeyInfo> keys = SshKeyManager.listKeys(this);
        String[] keyNames = new String[keys.size() + 1];
        keyNames[0] = "(none)";
        for (int i = 0; i < keys.size(); i++) keyNames[i+1] = keys.get(i).getName();
        Spinner spinnerKey = new Spinner(ctx);
        ArrayAdapter<String> keyAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, keyNames);
        keyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKey.setAdapter(keyAdapter);
        int keySel = 0;
        if (editing.getKeyName() != null) {
            for (int i = 0; i < keyNames.length; i++) {
                if (keyNames[i].equals(editing.getKeyName())) { keySel = i; break; }
            }
        }
        spinnerKey.setSelection(keySel);
        layout.addView(spinnerKey);

        TextView sshArgsLabel = new TextView(ctx);
        sshArgsLabel.setText("Extra SSH Args (optional)");
        sshArgsLabel.setTextColor(0xFFAAAAAA);
        sshArgsLabel.setTextSize(11);
        layout.addView(sshArgsLabel);
        EditText editSshArgs = new EditText(ctx);
        editSshArgs.setText(editing.getSshArgs() != null ? editing.getSshArgs() : "");
        editSshArgs.setHint("-o StrictHostKeyChecking=no");
        editSshArgs.setSingleLine(true);
        layout.addView(editSshArgs);

        // Cloudflare Access section (only for SSH/MOSH; checkbox toggles extra fields)
        CheckBox checkCf = new CheckBox(ctx);
        checkCf.setText("Use Cloudflare Access (cloudflared)");
        checkCf.setTextColor(0xFFAAAAAA);
        checkCf.setTextSize(12);
        checkCf.setChecked(editing.isCloudflaredEnabled());
        layout.addView(checkCf);

        TextView cfHostLabel = new TextView(ctx);
        cfHostLabel.setText("Cloudflare Hostname (Access, e.g. xaccess.example.com)");
        cfHostLabel.setTextColor(0xFFAAAAAA);
        cfHostLabel.setTextSize(11);
        layout.addView(cfHostLabel);
        EditText editCfHost = new EditText(ctx);
        editCfHost.setText(editing.getCloudflaredHostname() != null ? editing.getCloudflaredHostname() : "");
        editCfHost.setHint("Leave empty to use SSH Host");
        editCfHost.setSingleLine(true);
        layout.addView(editCfHost);

        TextView cfIdLabel = new TextView(ctx);
        cfIdLabel.setText("Service Token ID (optional)");
        cfIdLabel.setTextColor(0xFFAAAAAA);
        cfIdLabel.setTextSize(11);
        layout.addView(cfIdLabel);
        EditText editCfId = new EditText(ctx);
        editCfId.setText(editing.getCloudflaredServiceTokenId() != null ? editing.getCloudflaredServiceTokenId() : "");
        editCfId.setHint("CF-Access-Client-Id");
        editCfId.setSingleLine(true);
        layout.addView(editCfId);

        TextView cfSecretLabel = new TextView(ctx);
        cfSecretLabel.setText("Service Token Secret (optional)");
        cfSecretLabel.setTextColor(0xFFAAAAAA);
        cfSecretLabel.setTextSize(11);
        layout.addView(cfSecretLabel);
        EditText editCfSecret = new EditText(ctx);
        editCfSecret.setText(editing.getCloudflaredServiceTokenSecret() != null ? editing.getCloudflaredServiceTokenSecret() : "");
        editCfSecret.setHint("CF-Access-Client-Secret");
        editCfSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editCfSecret.setSingleLine(true);
        layout.addView(editCfSecret);

        TextView cfDestLabel = new TextView(ctx);
        cfDestLabel.setText("Destination (optional, --destination host:port)");
        cfDestLabel.setTextColor(0xFFAAAAAA);
        cfDestLabel.setTextSize(11);
        layout.addView(cfDestLabel);
        EditText editCfDest = new EditText(ctx);
        editCfDest.setText(editing.getCloudflaredDestination() != null ? editing.getCloudflaredDestination() : "");
        editCfDest.setHint("Internal SSH destination, e.g. 10.0.0.1:22");
        editCfDest.setSingleLine(true);
        layout.addView(editCfDest);

        TextView cfHint = new TextView(ctx);
        cfHint.setText("Cloudflared access ssh --hostname. For browser auth, leave Service Token empty and run 'cloudflared access login https://<hostname>' in a local shell first. Or provide Service Token ID/Secret to skip browser.");
        cfHint.setTextColor(0xFF888888);
        cfHint.setTextSize(10);
        cfHint.setPadding(0, 8, 0, 0);
        layout.addView(cfHint);

        // Toggle visibility based on type
        Runnable updateVisibility = () -> {
            int t = spinnerType.getSelectedItemPosition();
            boolean isRemote = t == 1 || t == 2;
            boolean isSsh = t == 1;
            int visSsh = isRemote ? View.VISIBLE : View.GONE;
            int visLocal = isRemote ? View.GONE : View.VISIBLE;
            hostLabel.setText(t == 2 ? "Host" : "SSH Host");
            portLabel.setText(t == 2 ? "SSH Port (bootstrap)" : "SSH Port");
            keyLabel.setText("SSH Key (name in ~/.ssh)");
            sshArgsLabel.setText(t == 2 ? "Extra Mosh Args (optional)" : "Extra SSH Args (optional)");
            editSshArgs.setHint(t == 2 ? "--predict=always --port=60000:61000" : "-o StrictHostKeyChecking=no");
            shellLabel.setVisibility(visLocal);
            editShell.setVisibility(visLocal);
            cwdLabel.setVisibility(visLocal);
            editCwd.setVisibility(visLocal);
            envLabel.setVisibility(visLocal);
            editEnv.setVisibility(visLocal);

            hostLabel.setVisibility(visSsh);
            editHost.setVisibility(visSsh);
            portLabel.setVisibility(visSsh);
            editPort.setVisibility(visSsh);
            userLabel.setVisibility(visSsh);
            editUser.setVisibility(visSsh);
            authLabel.setVisibility(visSsh);
            spinnerAuth.setVisibility(visSsh);
            keyLabel.setVisibility(visSsh);
            spinnerKey.setVisibility(visSsh);
            sshArgsLabel.setVisibility(visSsh);
            editSshArgs.setVisibility(visSsh);
            // Cloudflare section: only sensible for SSH, but allow MOSH too (it will just SSH bootstrap)
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
            // MOSH warning: cloudflared only tunnels SSH bootstrap, not Mosh UDP itself
            if (t == 2 && cfOn) {
                cfHint.setText("Mosh over Cloudflare: cloudflared tunnels the SSH bootstrap only. UDP must still be reachable, or consider using SSH profile with Cloudflare instead.");
            } else if (isSsh && cfOn) {
                cfHint.setText("Cloudflared access ssh --hostname. For browser auth, leave Service Token empty and run 'cloudflared access login https://<hostname>' in a local shell first. Or provide Service Token ID/Secret to skip browser.");
            }
        };
        updateVisibility.run();
        spinnerType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateVisibility.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        checkCf.setOnCheckedChangeListener((buttonView, isChecked) -> updateVisibility.run());

        // Put layout in ScrollView
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "Edit Profile" : "New Profile")
                .setView(sv)
                .setPositiveButton(isEdit ? "Save" : "Create", null)
                .setNegativeButton("Cancel", null)
                .show();
        // Override the positive button so validation failures keep the dialog open
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = editName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            ConnectionProfile.Type type = spinnerType.getSelectedItemPosition() == 2 ? ConnectionProfile.Type.MOSH
                    : (spinnerType.getSelectedItemPosition() == 1 ? ConnectionProfile.Type.SSH : ConnectionProfile.Type.LOCAL);
            editing.setName(name);
            editing.setType(type);
            if (type == ConnectionProfile.Type.LOCAL) {
                String sh = editShell.getText().toString().trim();
                editing.setShell(sh.isEmpty() ? null : sh);
                String cwd = editCwd.getText().toString().trim();
                editing.setCwd(cwd.isEmpty() ? null : cwd);
                String env = editEnv.getText().toString().trim();
                editing.setEnv(env.isEmpty() ? null : env);
                // clear ssh fields
                editing.setHost(null);
                editing.setUsername(null);
                editing.setKeyName(null);
                editing.setCloudflaredEnabled(false);
                editing.setCloudflaredHostname(null);
                editing.setCloudflaredServiceTokenId(null);
                editing.setCloudflaredServiceTokenSecret(null);
                editing.setCloudflaredDestination(null);
            } else {
                String host = editHost.getText().toString().trim();
                if (host.isEmpty()) {
                    Toast.makeText(this, "Host required for " + type.name(), Toast.LENGTH_SHORT).show();
                    return;
                }
                editing.setHost(host);
                try {
                    int p = Integer.parseInt(editPort.getText().toString().trim());
                    editing.setPort(p > 0 ? p : 22);
                } catch (Exception e) { editing.setPort(22); }
                String user = editUser.getText().toString().trim();
                editing.setUsername(user.isEmpty() ? null : user);
                String authStr = (String) spinnerAuth.getSelectedItem();
                editing.setAuthType(ConnectionProfile.AuthType.fromString(authStr));
                String keySelStr = (String) spinnerKey.getSelectedItem();
                if (keySelStr != null && !keySelStr.equals("(none)")) editing.setKeyName(keySelStr);
                else editing.setKeyName(null);
                String extra = editSshArgs.getText().toString().trim();
                editing.setSshArgs(extra.isEmpty() ? null : extra);
                // Cloudflare Access
                boolean cfEnabled = checkCf.isChecked();
                editing.setCloudflaredEnabled(cfEnabled);
                if (cfEnabled) {
                    String cfH = editCfHost.getText().toString().trim();
                    editing.setCloudflaredHostname(cfH.isEmpty() ? null : cfH);
                    String cfId = editCfId.getText().toString().trim();
                    editing.setCloudflaredServiceTokenId(cfId.isEmpty() ? null : cfId);
                    String cfSec = editCfSecret.getText().toString().trim();
                    editing.setCloudflaredServiceTokenSecret(cfSec.isEmpty() ? null : cfSec);
                    String cfDest = editCfDest.getText().toString().trim();
                    editing.setCloudflaredDestination(cfDest.isEmpty() ? null : cfDest);
                    // If hostname empty, default to host for clarity
                    if (editing.getCloudflaredHostname() == null) {
                        // keep host as fallback; TerminalService will use host if cfHostname null
                    }
                } else {
                    editing.setCloudflaredHostname(null);
                    editing.setCloudflaredServiceTokenId(null);
                    editing.setCloudflaredServiceTokenSecret(null);
                    editing.setCloudflaredDestination(null);
                }
                // clear local env?
            }
            editing.touch();
            if (isEdit) {
                profileManager.updateProfile(editing);
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
            } else {
                profileManager.addProfile(editing);
                Toast.makeText(this, "Profile created", Toast.LENGTH_SHORT).show();
            }
            loadProfiles();
            dialog.dismiss();
        });
    }
}
