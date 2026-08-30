package com.tinyhack.ssh.ssh;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.util.BiometricHelper;

import java.util.concurrent.Executor;

/**
 * Transparent host activity for the SSH agent's security-key sign gate.
 * Launched by {@link SshAgentServer} when a Keystore-backed -sk signature
 * needs a fresh strong-biometric window; the agent worker blocks until the
 * result is delivered via {@link SshAgentServer#completeBiometricAuth}.
 */
public class BiometricAuthActivity extends AppCompatActivity {
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_ALIAS = "alias";

    private long requestId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getLongExtra(EXTRA_REQUEST_ID, -1);
        String alias = getIntent().getStringExtra(EXTRA_ALIAS);
        if (requestId < 0) {
            finish();
            return;
        }
        if (!BiometricHelper.isBiometricEnrolled(this)) {
            SshAgentServer.completeBiometricAuth(requestId, false);
            Toast.makeText(this, "No fingerprint enrolled; cannot use SSH security key", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        SshAgentServer.registerPrompt(requestId, this);
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        SshAgentServer.completeBiometricAuth(requestId, true);
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        SshAgentServer.completeBiometricAuth(requestId, false);
                        finish();
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("SSH Security Key")
                .setSubtitle("Confirm to sign with " + (alias != null ? alias : "security key"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .setConfirmationRequired(false)
                .build();
        prompt.authenticate(info);
    }

    @Override
    protected void onDestroy() {
        if (requestId >= 0) SshAgentServer.unregisterPrompt(requestId);
        super.onDestroy();
    }
}
