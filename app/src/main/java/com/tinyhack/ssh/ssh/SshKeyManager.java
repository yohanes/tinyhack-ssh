package com.tinyhack.ssh.ssh;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import com.tinyhack.ssh.util.SafeLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SshKeyManager {
    private static final String TAG = "SshKeyManager";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String SK_MARKER = "GHOSTTY_ANDROID_SK_V1";
    private static final String SK_ALIAS_PREFIX = "ghostty.ssh.sk.";
    public static final String SK_ECDSA_TYPE = "sk-ecdsa-sha2-nistp256@openssh.com";
    public static final String SK_APPLICATION = "ssh:";
    // The agent independently prompts before every SSH signature. Keep the
    // Keystore token window only long enough for the prompt callback to reach the
    // StrongBox/TEE signing operation.
    private static final int SK_AUTH_SECONDS = 15;

    /**
     * Key names become filenames inside ~/.ssh: reject path traversal and
     * shell/hostile characters up front. Must start alphanumeric.
     */
    static boolean isSafeKeyName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 128
                && name.matches("[A-Za-z0-9][A-Za-z0-9._+-]*");
    }

    // --- headless passphrase delivery (no argv, no shell, no data-dir exec) ---
    // Askpass helper is libaskpass.so in nativeLibraryDir (data-dir scripts are
    // not executable under targetSdk 29+ SELinux policy).

    static File writePassphraseFile(Context context, String passphrase) throws IOException {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        tmpDir.mkdirs();
        File passFile = new File(tmpDir, "askpass.pass." + android.os.Process.myPid() + "." + System.nanoTime());
        try (FileOutputStream fos = new FileOutputStream(passFile)) {
            fos.write(passphrase.getBytes(StandardCharsets.UTF_8));
            fos.write('\n');
        }
        try { android.system.Os.chmod(passFile.getAbsolutePath(), 0600); } catch (Exception ignored) {}
        return passFile;
    }

    static void applyAskpassEnv(Context context, ProcessBuilder pb, File passFile) {
        pb.environment().put("DISPLAY", "dummy:0");
        pb.environment().put("SSH_ASKPASS",
                context.getApplicationInfo().nativeLibraryDir + "/libaskpass.so");
        pb.environment().put("SSH_ASKPASS_REQUIRE", "force");
        pb.environment().put("GHOSTTY_ASKPASS_FILE", passFile.getAbsolutePath());
    }

    static void wipePassphraseFile(File passFile) {
        if (passFile == null) return;
        try (FileOutputStream fos = new FileOutputStream(passFile)) {
            fos.write(new byte[64]);
        } catch (Exception ignored) {
        }
        try { passFile.delete(); } catch (Exception ignored) {}
    }

    public static File getSshDir(Context context) {
        File home = new File(context.getFilesDir(), "home");
        File ssh = new File(home, ".ssh");
        if (!ssh.exists()) {
            ssh.mkdirs();
        }
        try {
            android.system.Os.chmod(ssh.getAbsolutePath(), 0700);
        } catch (Exception ignored) {}
        return ssh;
    }

    public static List<SshKeyInfo> listKeys(Context context) {
        File sshDir = getSshDir(context);
        File[] files = sshDir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<SshKeyInfo> keyList = new ArrayList<>();
        for (File pubFile : files) {
            if (pubFile.isFile() && pubFile.getName().endsWith(".pub")) {
                String baseName = pubFile.getName().substring(0, pubFile.getName().length() - 4);
                File privFile = new File(sshDir, baseName);

                String pubContent = readFile(pubFile).trim();
                String type = "Unknown";
                String comment = "";
                String fingerprint = getFingerprint(context, pubFile);

                if (!pubContent.isEmpty()) {
                    String[] parts = pubContent.split("\\s+");
                    if (parts.length >= 1) {
                        type = parts[0].replace("ssh-", "").toUpperCase(Locale.ROOT);
                    }
                    if (parts.length >= 3) {
                        comment = parts[2];
                    }
                }

                keyList.add(new SshKeyInfo(
                        baseName,
                        type,
                        fingerprint,
                        comment,
                        pubContent,
                        privFile.exists() ? privFile : null,
                        pubFile
                ));
            }
        }
        return keyList;
    }

    /**
     * Fingerprint of a public key file, computed in process (no ssh-keygen
     * spawn per key per refresh). Same value as `ssh-keygen -lf`.
     */
    public static String getFingerprint(Context context, File pubFile) {
        return computeFingerprint(readFile(pubFile));
    }

    /** "SHA256:<unpadded-b64>" over the wire-format public key blob. */
    static String computeFingerprint(String pubLine) {
        try {
            if (pubLine == null) return "SHA256:Unavailable";
            String first = pubLine.trim().split("\\R", 2)[0].trim();
            String[] parts = first.split("\\s+");
            if (parts.length < 2) return "SHA256:Unavailable";
            byte[] blob = Base64.getDecoder().decode(parts[1]);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "SHA256:Unavailable";
        }
    }

    public static boolean generateKeyPair(Context context, String keyType, int bits, String name,
                                          String passphrase, String comment) {
        if (!isSafeKeyName(name)) {
            SafeLog.w(TAG, "Refusing unsafe key name: " + name);
            return false;
        }
        File sshDir = getSshDir(context);
        File keyFile = new File(sshDir, name);

        String sshKeygenPath = context.getApplicationInfo().nativeLibraryDir + "/libssh-keygen.so";
        if (!new File(sshKeygenPath).exists()) {
            sshKeygenPath = new File(context.getFilesDir(), "usr/bin/ssh-keygen").getAbsolutePath();
        }

        boolean hasPassphrase = passphrase != null && !passphrase.isEmpty();
        List<String> cmd = new ArrayList<>();
        cmd.add(sshKeygenPath);
        cmd.add("-t");
        cmd.add(keyType.toLowerCase(Locale.ROOT));
        if ("rsa".equalsIgnoreCase(keyType) && bits > 0) {
            cmd.add("-b");
            cmd.add(String.valueOf(bits));
        }
        if (!hasPassphrase) {
            cmd.add("-N");
            cmd.add("");
        }
        cmd.add("-C");
        cmd.add(comment != null && !comment.isEmpty() ? comment : "tinyhack@android");
        cmd.add("-f");
        cmd.add(keyFile.getAbsolutePath());

        // Passphrase is delivered via the native askpass helper (never on the
        // argv, which is briefly visible to same-uid processes via /proc)
        File passFile = null;
        try {
            if (hasPassphrase) {
                passFile = writePassphraseFile(context, passphrase);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("HOME", new File(context.getFilesDir(), "home").getAbsolutePath());
            if (passFile != null) {
                applyAskpassEnv(context, pb, passFile);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            SafeLog.d(TAG, "ssh-keygen exit=" + exitCode + ", out=" + sb);
            if (exitCode == 0) {
                try {
                    android.system.Os.chmod(keyFile.getAbsolutePath(), 0600);
                    File pubFile = new File(sshDir, name + ".pub");
                    if (pubFile.exists()) {
                        android.system.Os.chmod(pubFile.getAbsolutePath(), 0644);
                    }
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        } catch (Exception e) {
            SafeLog.e(TAG, "ssh-keygen execution failed", e);
            return false;
        } finally {
            wipePassphraseFile(passFile);
        }
    }

    /** Generate a non-exportable OpenSSH -sk key backed by Android Keystore. */
    public static boolean generateSecurityKeyPair(Context context, String name, String comment) {
        if (!isSafeKeyName(name)) {
            SafeLog.w(TAG, "Refusing unsafe key name: " + name);
            return false;
        }
        File sshDir = getSshDir(context);
        File metadataFile = new File(sshDir, name);
        File publicFile = new File(sshDir, name + ".pub");
        String alias = SK_ALIAS_PREFIX + name;
        try {
            if (metadataFile.exists() || publicFile.exists()) {
                SafeLog.w(TAG, "Key already exists: " + name);
                return false;
            }
            KeyPair pair;
            try {
                pair = generateAndroidSkKey(alias, true);
            } catch (StrongBoxUnavailableException e) {
                SafeLog.i(TAG, "StrongBox unavailable; using the device TEE for " + alias);
                pair = generateAndroidSkKey(alias, false);
            }
            ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
            byte[] blob = buildSkPublicBlob(publicKey, SK_APPLICATION);
            String keyComment = comment == null || comment.trim().isEmpty()
                    ? "ghostty-sk@android" : comment.trim();
            String publicLine = SK_ECDSA_TYPE + " " +
                    Base64.getEncoder().encodeToString(blob) + " " + keyComment + "\n";
            String metadata = SK_MARKER + "\n" + alias + "\n" + SK_APPLICATION + "\n";
            try (FileOutputStream out = new FileOutputStream(metadataFile)) {
                out.write(metadata.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream out = new FileOutputStream(publicFile)) {
                out.write(publicLine.getBytes(StandardCharsets.UTF_8));
            }
            android.system.Os.chmod(metadataFile.getAbsolutePath(), 0600);
            android.system.Os.chmod(publicFile.getAbsolutePath(), 0644);
            return true;
        } catch (Exception e) {
            SafeLog.e(TAG, "Failed to generate Android security key", e);
            try {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);
                keyStore.deleteEntry(alias);
            } catch (Exception ignored) {
            }
            metadataFile.delete();
            publicFile.delete();
            return false;
        }
    }

    private static KeyPair generateAndroidSkKey(String alias, boolean strongBox) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
        // Strong biometrics only: a device-credential (PIN/pattern) unlock must
        // not open a signing window for security keys. SshAgentServer explicitly
        // prompts for every signature; this short window lets the resulting auth
        // token reach the StrongBox/TEE operation.
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(SK_AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true);
        if (strongBox) builder.setIsStrongBoxBacked(true);
        generator.initialize(builder.build());
        return generator.generateKeyPair();
    }

    public static AndroidSecurityKey readAndroidSecurityKey(File privateFile) {
        if (privateFile == null || !privateFile.exists()) return null;
        String content = readFile(privateFile);
        String[] lines = content.split("\\R");
        if (lines.length < 3 || !SK_MARKER.equals(lines[0])) return null;
        if (!lines[1].startsWith(SK_ALIAS_PREFIX) || lines[2].isEmpty()) return null;
        File publicFile = new File(privateFile.getParentFile(), privateFile.getName() + ".pub");
        if (!publicFile.exists()) return null;
        try {
            String[] publicParts = readFile(publicFile).trim().split("\\s+", 3);
            if (publicParts.length < 2 || !SK_ECDSA_TYPE.equals(publicParts[0])) return null;
            byte[] blob = Base64.getDecoder().decode(publicParts[1]);
            String comment = publicParts.length == 3 ? publicParts[2] : privateFile.getName();
            return new AndroidSecurityKey(lines[1], lines[2], blob, comment);
        } catch (IllegalArgumentException e) {
            SafeLog.w(TAG, "Invalid Android security-key metadata " + privateFile, e);
            return null;
        }
    }

    private static byte[] buildSkPublicBlob(ECPublicKey key, String application) throws IOException {
        byte[] point = new byte[65];
        point[0] = 0x04;
        copyUnsigned32(key.getW().getAffineX().toByteArray(), point, 1);
        copyUnsigned32(key.getW().getAffineY().toByteArray(), point, 33);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        writeSshString(out, SK_ECDSA_TYPE.getBytes(StandardCharsets.UTF_8));
        writeSshString(out, "nistp256".getBytes(StandardCharsets.UTF_8));
        writeSshString(out, point);
        writeSshString(out, application.getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    private static void copyUnsigned32(byte[] source, byte[] target, int offset) {
        int sourceOffset = source.length > 32 ? source.length - 32 : 0;
        int length = Math.min(source.length, 32);
        System.arraycopy(source, sourceOffset, target, offset + 32 - length, length);
    }

    private static void writeSshString(java.io.DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    public static final class AndroidSecurityKey {
        public final String alias;
        public final String application;
        public final byte[] publicBlob;
        public final String comment;

        AndroidSecurityKey(String alias, String application, byte[] publicBlob, String comment) {
            this.alias = alias;
            this.application = application;
            this.publicBlob = publicBlob;
            this.comment = comment;
        }
    }

    public static boolean deleteKey(Context context, String name) {
        if (!isSafeKeyName(name)) {
            SafeLog.w(TAG, "Refusing unsafe key name: " + name);
            return false;
        }
        File sshDir = getSshDir(context);
        File privFile = new File(sshDir, name);
        File pubFile = new File(sshDir, name + ".pub");
        boolean ok = true;
        AndroidSecurityKey securityKey = readAndroidSecurityKey(privFile);
        if (securityKey != null) {
            try {
                KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
                keyStore.load(null);
                keyStore.deleteEntry(securityKey.alias);
            } catch (Exception e) {
                SafeLog.e(TAG, "Failed to delete Android Keystore key " + securityKey.alias, e);
                ok = false;
            }
        }
        if (privFile.exists()) ok = privFile.delete() && ok;
        if (pubFile.exists()) ok = pubFile.delete() && ok;
        return ok;
    }

    public static boolean importKey(Context context, String name, String privateKeyContent, String publicKeyContent) {
        if (!isSafeKeyName(name)) {
            SafeLog.w(TAG, "Refusing unsafe key name: " + name);
            return false;
        }
        File sshDir = getSshDir(context);
        File privFile = new File(sshDir, name);
        File pubFile = new File(sshDir, name + ".pub");

        try {
            if (privateKeyContent == null || privateKeyContent.trim().isEmpty()) {
                SafeLog.w(TAG, "Import requires a private key");
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(privFile)) {
                fos.write(privateKeyContent.trim().getBytes(StandardCharsets.UTF_8));
                fos.write('\n');
            }
            try {
                android.system.Os.chmod(privFile.getAbsolutePath(), 0600);
            } catch (Exception ignored) {}

            // Validate by deriving the public key with ssh-keygen -y; also
            // catches a provided public key that doesn't match the private one.
            String sshKeygenPath = context.getApplicationInfo().nativeLibraryDir + "/libssh-keygen.so";
            if (!new File(sshKeygenPath).exists()) {
                sshKeygenPath = new File(context.getFilesDir(), "usr/bin/ssh-keygen").getAbsolutePath();
            }
            Process process = new ProcessBuilder(sshKeygenPath, "-y", "-f", privFile.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            boolean valid = process.waitFor() == 0;
            String derived = sb.toString().trim();

            if (!valid || derived.isEmpty()) {
                SafeLog.w(TAG, "Imported private key failed validation, removing");
                privFile.delete();
                return false;
            }

            String[] derivedParts = derived.split("\\s+");
            if (publicKeyContent != null && !publicKeyContent.trim().isEmpty()) {
                String[] providedParts = publicKeyContent.trim().split("\\s+");
                boolean matches = providedParts.length >= 2
                        && providedParts[0].equals(derivedParts[0])
                        && providedParts[1].equals(derivedParts[1]);
                if (!matches) {
                    SafeLog.w(TAG, "Provided public key does not match private key, removing");
                    privFile.delete();
                    return false;
                }
                try (FileOutputStream fos = new FileOutputStream(pubFile)) {
                    fos.write(publicKeyContent.trim().getBytes(StandardCharsets.UTF_8));
                    fos.write('\n');
                }
            } else {
                try (FileOutputStream fos = new FileOutputStream(pubFile)) {
                    fos.write(derived.getBytes(StandardCharsets.UTF_8));
                    fos.write('\n');
                }
            }
            try {
                android.system.Os.chmod(pubFile.getAbsolutePath(), 0644);
            } catch (Exception ignored) {}
            return true;
        } catch (Exception e) {
            SafeLog.e(TAG, "Failed to import key", e);
            privFile.delete();
            return false;
        }
    }

    private static String readFile(File file) {
        if (!file.exists()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
