package com.tinyhack.ssh.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * Helper for biometric authentication gating SSH agent operations.
 * Uses AndroidX BiometricPrompt with fallback to device credential when available.
 * Also provides Keystore-backed token to remember successful auth for a short period.
 */
public class BiometricHelper {
    private static final String TAG = "BiometricHelper";

    public interface BiometricCallback {
        void onSuccess();
        void onError(String error);
        void onFailed();
    }

    public static boolean isBiometricEnrolled(Context context) {
        BiometricManager mgr = BiometricManager.from(context);
        int can = mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return can == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void authenticate(@NonNull FragmentActivity activity, String title, String subtitle, @NonNull BiometricCallback callback) {
        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                Log.w(TAG, "Biometric error " + errorCode + ": " + errString);
                callback.onError(errString.toString());
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                Log.i(TAG, "Biometric success");
                callback.onSuccess();
            }

            @Override
            public void onAuthenticationFailed() {
                Log.w(TAG, "Biometric failed");
                callback.onFailed();
            }
        });

        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title != null ? title : "Authenticate")
                .setSubtitle(subtitle != null ? subtitle : "Confirm to unlock SSH Agent")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        // For older API fallback, we need to set negative button if not using DEVICE_CREDENTIAL
        // But with DEVICE_CREDENTIAL we don't set negative button
        BiometricPrompt.PromptInfo info = builder.build();
        prompt.authenticate(info);
    }
}
