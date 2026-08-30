package com.tinyhack.ssh.ssh;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.util.BiometricHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;
import java.util.concurrent.Executors;

public class SshKeysActivity extends AppCompatActivity implements SshKeysAdapter.OnKeyActionListener {
    private RecyclerView recyclerView;
    private TextView emptyView;
    private FloatingActionButton fabAdd;
    private SshKeysAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_keys);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("SSH Key Management");
        }

        recyclerView = findViewById(R.id.recycler_view);
        emptyView = findViewById(R.id.empty_view);
        fabAdd = findViewById(R.id.fab_add);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SshKeysAdapter(this);
        recyclerView.setAdapter(adapter);

        fabAdd.setOnClickListener(v -> showAddKeyDialog());

        loadKeys();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadKeys() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<SshKeyInfo> keys = SshKeyManager.listKeys(this);
            runOnUiThread(() -> {
                adapter.setKeys(keys);
                emptyView.setVisibility(keys.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showAddKeyDialog() {
        String[] options = {"Generate New SSH Key", "Import Existing Private Key"};
        new AlertDialog.Builder(this)
                .setTitle("Add SSH Key")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showGenerateKeyDialog();
                    } else {
                        showImportKeyDialog();
                    }
                })
                .show();
    }

    private void showGenerateKeyDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        TextView typeLabel = new TextView(this);
        typeLabel.setText("Key Type:");
        layout.addView(typeLabel);

        RadioGroup radioGroup = new RadioGroup(this);
        radioGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbEd25519 = new RadioButton(this);
        rbEd25519.setText("ED25519 (Recommended)");
        rbEd25519.setId(View.generateViewId());
        rbEd25519.setChecked(true);
        RadioButton rbRsa = new RadioButton(this);
        rbRsa.setText("RSA 4096");
        rbRsa.setId(View.generateViewId());
        RadioButton rbSk = new RadioButton(this);
        rbSk.setText("Android Security Key (-sk, fingerprint)");
        rbSk.setId(View.generateViewId());
        radioGroup.addView(rbEd25519);
        radioGroup.addView(rbRsa);
        radioGroup.addView(rbSk);
        layout.addView(radioGroup);

        EditText editName = new EditText(this);
        editName.setHint("Key Name (e.g. id_ed25519)");
        editName.setText("id_ed25519");
        layout.addView(editName);

        rbEd25519.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) editName.setText("id_ed25519");
        });
        rbRsa.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) editName.setText("id_rsa");
        });

        EditText editPassphrase = new EditText(this);
        editPassphrase.setHint("Passphrase (optional)");
        // Secret input: no suggestions/autofill (third-party IME hardening)
        editPassphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editPassphrase.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        layout.addView(editPassphrase);

        rbSk.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) editName.setText("id_ecdsa_sk");
            editPassphrase.setEnabled(!isChecked);
            editPassphrase.setHint(isChecked
                    ? "Protected by fingerprint (no passphrase)"
                    : "Passphrase (optional)");
        });

        EditText editComment = new EditText(this);
        editComment.setHint("Comment (e.g. user@phone)");
        editComment.setText("tinyhack@android");
        layout.addView(editComment);

        new AlertDialog.Builder(this)
                .setTitle("Generate SSH Key")
                .setView(layout)
                .setPositiveButton("Generate", (dialog, which) -> {
                    boolean securityKey = rbSk.isChecked();
                    String keyType = securityKey ? "ecdsa-sk" :
                            (rbEd25519.isChecked() ? "ed25519" : "rsa");
                    int bits = rbRsa.isChecked() ? 4096 : 0;
                    String keyName = editName.getText().toString().trim();
                    String passphrase = editPassphrase.getText().toString();
                    String comment = editComment.getText().toString().trim();

                    if (keyName.isEmpty()) {
                        Toast.makeText(this, "Key name cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!SshKeyManager.isSafeKeyName(keyName)) {
                        Toast.makeText(this, "Key name: letters, digits, . _ + - only (must start alphanumeric)", Toast.LENGTH_LONG).show();
                        return;
                    }

                    if (securityKey) {
                        if (!BiometricHelper.isBiometricEnrolled(this)) {
                            Toast.makeText(this, "Enroll a strong fingerprint first",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        BiometricHelper.authenticate(this, "Create SSH Security Key",
                                "Confirm fingerprint enrollment for this non-exportable key",
                                new BiometricHelper.BiometricCallback() {
                                    @Override public void onSuccess() {
                                        generateKeyInBackground(true, keyType, bits, keyName,
                                                passphrase, comment);
                                    }
                                    @Override public void onError(String error) {
                                        Toast.makeText(SshKeysActivity.this, error,
                                                Toast.LENGTH_LONG).show();
                                    }
                                    @Override public void onFailed() {
                                        Toast.makeText(SshKeysActivity.this,
                                                "Fingerprint not recognized", Toast.LENGTH_SHORT).show();
                                    }
                                });
                    } else {
                        generateKeyInBackground(false, keyType, bits, keyName,
                                passphrase, comment);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void generateKeyInBackground(boolean securityKey, String keyType, int bits,
                                         String keyName, String passphrase, String comment) {
        Toast.makeText(this, "Generating " + keyType.toUpperCase() + " key...",
                Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = securityKey
                    ? SshKeyManager.generateSecurityKeyPair(this, keyName, comment)
                    : SshKeyManager.generateKeyPair(this, keyType, bits, keyName,
                            passphrase, comment);
            if (ok) {
                try {
                    SshAgentManager agent = SshAgentManager.getInstance(this);
                    if (agent.isAgentRunning()) {
                        java.io.File keyFile = new java.io.File(
                                SshKeyManager.getSshDir(this), keyName);
                        boolean added = agent.addKey(keyFile.getAbsolutePath(), null);
                        Log.i("SshKeysActivity", "Auto-add new key to agent: " + added);
                    }
                } catch (Exception e) {
                    Log.w("SshKeysActivity", "Auto-add new key to agent failed", e);
                }
            }
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, securityKey
                            ? "Fingerprint-backed SSH security key created"
                            : "SSH key generated successfully", Toast.LENGTH_SHORT).show();
                    loadKeys();
                } else {
                    Toast.makeText(this, "Failed to generate SSH key", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showImportKeyDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        EditText editName = new EditText(this);
        editName.setHint("Key Name (e.g. id_custom)");
        layout.addView(editName);

        EditText editPriv = new EditText(this);
        editPriv.setHint("Paste Private Key (-----BEGIN OPENSSH PRIVATE KEY----- ...)");
        editPriv.setMinLines(4);
        editPriv.setTypeface(Typeface.MONOSPACE);
        editPriv.setTextSize(12);
        editPriv.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        layout.addView(editPriv);

        EditText editPub = new EditText(this);
        editPub.setHint("Paste Public Key (optional)");
        editPub.setMinLines(2);
        editPub.setTypeface(Typeface.MONOSPACE);
        editPub.setTextSize(12);
        layout.addView(editPub);

        new AlertDialog.Builder(this)
                .setTitle("Import SSH Key")
                .setView(layout)
                .setPositiveButton("Import", (dialog, which) -> {
                    String keyName = editName.getText().toString().trim();
                    String privKey = editPriv.getText().toString();
                    String pubKey = editPub.getText().toString();

                    if (keyName.isEmpty() || privKey.trim().isEmpty()) {
                        Toast.makeText(this, "Key name and Private Key are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!SshKeyManager.isSafeKeyName(keyName)) {
                        Toast.makeText(this, "Key name: letters, digits, . _ + - only (must start alphanumeric)", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Executors.newSingleThreadExecutor().execute(() -> {
                        boolean ok = SshKeyManager.importKey(this, keyName, privKey, pubKey);
                        runOnUiThread(() -> {
                            if (ok) {
                                Toast.makeText(this, "SSH Key imported successfully!", Toast.LENGTH_SHORT).show();
                                loadKeys();
                            } else {
                                Toast.makeText(this, "Failed to import key", Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onCopyPublicKey(SshKeyInfo key) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("SSH Public Key", key.getPublicKey());
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Public key copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onViewKey(SshKeyInfo key) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        TextView fpText = new TextView(this);
        fpText.setText("Fingerprint:\n" + key.getFingerprint());
        fpText.setTypeface(Typeface.MONOSPACE);
        fpText.setTextSize(12);
        fpText.setPadding(0, 0, 0, 16);
        layout.addView(fpText);

        TextView pubLabel = new TextView(this);
        pubLabel.setText("Public Key (" + key.getType() + "):");
        layout.addView(pubLabel);

        TextView pubText = new TextView(this);
        pubText.setText(key.getPublicKey());
        pubText.setTypeface(Typeface.MONOSPACE);
        pubText.setTextSize(11);
        pubText.setTextIsSelectable(true);
        pubText.setPadding(0, 8, 0, 16);
        layout.addView(pubText);

        new AlertDialog.Builder(this)
                .setTitle(key.getName())
                .setView(layout)
                .setPositiveButton("Copy", (dialog, which) -> onCopyPublicKey(key))
                .setNeutralButton("Share", (dialog, which) -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SSH Public Key - " + key.getName());
                    shareIntent.putExtra(Intent.EXTRA_TEXT, key.getPublicKey());
                    startActivity(Intent.createChooser(shareIntent, "Share Public Key"));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    public void onDeleteKey(SshKeyInfo key) {
        new AlertDialog.Builder(this)
                .setTitle("Delete SSH Key")
                .setMessage("Are you sure you want to delete '" + key.getName() + "' and its public key? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean ok = SshKeyManager.deleteKey(this, key.getName());
                    if (ok) {
                        Toast.makeText(this, "Key deleted", Toast.LENGTH_SHORT).show();
                        loadKeys();
                    } else {
                        Toast.makeText(this, "Failed to delete key", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
