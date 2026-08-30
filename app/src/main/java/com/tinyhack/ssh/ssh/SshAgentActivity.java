package com.tinyhack.ssh.ssh;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

public class SshAgentActivity extends AppCompatActivity {
    private TextView textStatus;
    private TextView textSocket;
    private TextView textKeyCount;
    private MaterialButton btnStartStop;
    private MaterialButton btnLockUnlock;
    private MaterialButton btnAddAll;
    private MaterialButton btnRemoveAll;
    private MaterialSwitch switchAutostart;
    private TextView textLoadedEmpty;
    private TextView textAvailableEmpty;
    private RecyclerView recyclerAgent;
    private RecyclerView recyclerAvailable;

    private SshAgentManager agentManager;
    private AgentKeysAdapter agentAdapter;
    private AvailableKeysAdapter availableAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_agent);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("SSH Agent");
        }

        agentManager = SshAgentManager.getInstance(this);

        textStatus = findViewById(R.id.text_agent_status);
        textSocket = findViewById(R.id.text_socket_path);
        textKeyCount = findViewById(R.id.text_key_count);
        btnStartStop = findViewById(R.id.btn_start_stop);
        btnLockUnlock = findViewById(R.id.btn_lock_unlock);
        btnAddAll = findViewById(R.id.btn_add_all);
        btnRemoveAll = findViewById(R.id.btn_remove_all);
        switchAutostart = findViewById(R.id.switch_autostart);
        textLoadedEmpty = findViewById(R.id.text_loaded_empty);
        textAvailableEmpty = findViewById(R.id.text_available_empty);
        recyclerAgent = findViewById(R.id.recycler_agent_keys);
        recyclerAvailable = findViewById(R.id.recycler_available_keys);

        recyclerAgent.setLayoutManager(new LinearLayoutManager(this));
        agentAdapter = new AgentKeysAdapter();
        recyclerAgent.setAdapter(agentAdapter);

        recyclerAvailable.setLayoutManager(new LinearLayoutManager(this));
        availableAdapter = new AvailableKeysAdapter(this::doAddSingleKey);
        recyclerAvailable.setAdapter(availableAdapter);

        switchAutostart.setChecked(agentManager.isAutoStart());

        switchAutostart.setOnCheckedChangeListener((btn, checked) -> {
            agentManager.setAutoStart(checked);
            Toast.makeText(this, checked ? "Auto-start enabled" : "Auto-start disabled", Toast.LENGTH_SHORT).show();
        });

        btnStartStop.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                boolean running = agentManager.isAgentRunning();
                if (running) {
                    agentManager.stopAgent();
                } else {
                    boolean ok = agentManager.startAgent();
                    if (!ok) runOnUiThread(() -> Toast.makeText(this, "Failed to start agent", Toast.LENGTH_SHORT).show());
                }
                runOnUiThread(this::refreshAll);
            });
        });

        btnLockUnlock.setOnClickListener(v -> {
            if (agentManager.isLocked()) {
                agentManager.setLocked(false);
                Toast.makeText(this, "Agent unlocked", Toast.LENGTH_SHORT).show();
            } else {
                agentManager.setLocked(true);
                Toast.makeText(this, "Agent locked", Toast.LENGTH_SHORT).show();
            }
            updateUi();
        });

        btnAddAll.setOnClickListener(v -> {
            if (!agentManager.isAgentRunning()) {
                Toast.makeText(this, "Agent not running", Toast.LENGTH_SHORT).show();
                return;
            }
            doAddAll();
        });

        btnRemoveAll.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() -> {
                boolean ok = agentManager.removeAllKeys();
                runOnUiThread(() -> {
                    if (ok) Toast.makeText(this, "All keys removed", Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this, "Failed to remove (no agent?)", Toast.LENGTH_SHORT).show();
                    refreshAll();
                });
            });
        });

        refreshAll();
    }

    private void doAddSingleKey(SshKeyInfo key) {
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = agentManager.addKey(key.getPrivateFile().getAbsolutePath(), null);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, "Added " + key.getName(), Toast.LENGTH_SHORT).show();
                    refreshAll();
                } else {
                    Toast.makeText(this, "Failed to add " + key.getName(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void doAddAll() {
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = agentManager.addAllKeys();
            runOnUiThread(() -> {
                if (ok) Toast.makeText(this, "Keys added to agent", Toast.LENGTH_SHORT).show();
                else Toast.makeText(this, "Some keys failed (check passphrase?)", Toast.LENGTH_LONG).show();
                refreshAll();
            });
        });
    }

    private void refreshAll() {
        updateUi();
        Executors.newSingleThreadExecutor().execute(() -> {
            List<SshAgentManager.AgentKeyInfo> loaded = agentManager.listKeys();
            List<SshKeyInfo> available = SshKeyManager.listKeys(this);
            Set<String> loadedFps = new HashSet<>();
            for (SshAgentManager.AgentKeyInfo k : loaded) {
                if (k.fingerprint != null && !k.fingerprint.isEmpty()) loadedFps.add(k.fingerprint);
            }
            runOnUiThread(() -> {
                agentAdapter.setKeys(loaded);
                textLoadedEmpty.setVisibility(loaded.isEmpty() ? View.VISIBLE : View.GONE);
                recyclerAgent.setVisibility(loaded.isEmpty() ? View.GONE : View.VISIBLE);
                textKeyCount.setText(loaded.size() + (loaded.size()==1?" key loaded":" keys loaded"));
                availableAdapter.setKeys(available, loadedFps);
                textAvailableEmpty.setVisibility(available.isEmpty() ? View.VISIBLE : View.GONE);
                recyclerAvailable.setVisibility(available.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void updateUi() {
        boolean running = agentManager.isAgentRunning();
        boolean locked = agentManager.isLocked();

        if (running) {
            textStatus.setText("Running");
            textStatus.setBackgroundColor(0xFF2E4E3B);
            textStatus.setTextColor(0xFF7DFF9A);
            btnStartStop.setText("Stop Agent");
            btnStartStop.setIconResource(android.R.drawable.ic_media_pause);
        } else {
            textStatus.setText("Stopped");
            textStatus.setBackgroundColor(0xFF4E2E2E);
            textStatus.setTextColor(0xFFFF7D7D);
            btnStartStop.setText("Start Agent");
            btnStartStop.setIconResource(android.R.drawable.ic_media_play);
        }
        textSocket.setText("Socket: " + agentManager.getSocketPath());

        if (locked) {
            btnLockUnlock.setText("Unlock");
            btnLockUnlock.setIconResource(android.R.drawable.ic_lock_idle_lock);
        } else {
            btnLockUnlock.setText("Lock");
            btnLockUnlock.setIconResource(android.R.drawable.ic_lock_idle_lock);
        }
        btnLockUnlock.setEnabled(running);
        btnAddAll.setEnabled(running);
        btnRemoveAll.setEnabled(running);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
