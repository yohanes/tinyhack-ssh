package com.tinyhack.ssh.model;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts connection-profile passwords at rest with an Android Keystore key
 * (AES-256-GCM). The key never leaves the keystore, so files/profiles.json and
 * SharedPreferences only ever hold ciphertext ("v1:iv:ct").
 *
 * Best effort: on any keystore failure decrypt() returns null and encrypt()
 * returns null, so callers store "no password" rather than plaintext.
 */
final class ProfileCrypto {
    private static final String TAG = "ProfileCrypto";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "tinyhack.profile.passwords";
    private static final String PREFIX = "v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private ProfileCrypto() {}

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static synchronized String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            return PREFIX
                    + Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed; storing no password", e);
            return null;
        }
    }

    static synchronized String decrypt(String value) {
        if (!isEncrypted(value)) return null;
        try {
            String[] parts = value.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
            if (iv.length != IV_LEN) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed", e);
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        SecretKey key = generator.generateKey();
        // Touch the RNG so key generation isn't the only consumer
        new SecureRandom().nextBytes(new byte[8]);
        return key;
    }
}
