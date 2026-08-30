package com.tinyhack.ssh.ssh;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * In-process SSH agent served through Linux's abstract Unix-socket namespace.
 *
 * Android apps cannot reliably create pathname Unix sockets in their data directory,
 * while {@link LocalServerSocket#LocalServerSocket(String)} directly supports abstract
 * sockets. OpenSSH represents these as a leading '@' in SSH_AUTH_SOCK; our Android
 * OpenSSH build converts that marker to sun_path[0] == '\0'.
 */
public final class SshAgentServer {
    private static final String TAG = "SshAgentServer";

    private static final int SSH_AGENT_FAILURE = 5;
    private static final int SSH_AGENT_SUCCESS = 6;
    private static final int SSH_AGENT_IDENTITIES_ANSWER = 12;
    private static final int SSH_AGENT_SIGN_RESPONSE = 14;
    private static final int SSH_AGENTC_REQUEST_IDENTITIES = 11;
    private static final int SSH_AGENTC_SIGN_REQUEST = 13;
    private static final int SSH_AGENTC_ADD_IDENTITY = 17;
    private static final int SSH_AGENTC_REMOVE_IDENTITY = 18;
    private static final int SSH_AGENTC_REMOVE_ALL_IDENTITIES = 19;
    private static final int SSH_AGENTC_LOCK = 22;
    private static final int SSH_AGENTC_UNLOCK = 23;
    private static final int SSH_AGENTC_ADD_ID_CONSTRAINED = 25;
    private static final int SSH_AGENT_RSA_SHA2_256 = 2;
    private static final int SSH_AGENT_RSA_SHA2_512 = 4;
    private static final int MAX_MESSAGE_SIZE = 256 * 1024;
    private static final int MAX_CONCURRENT_CLIENTS = 16;
    private static final int SK_USER_PRESENT = 0x01;
    private static final String SK_COUNTER_PREFS = "ssh_sk_counters";

    private final String namePrefix;
    private final Context appContext;
    private String abstractName;
    private String envPath;
    private final CopyOnWriteArrayList<StoredKey> keys = new CopyOnWriteArrayList<>();
    private LocalServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile boolean locked;
    private final AtomicInteger activeClients = new AtomicInteger();

    private static final class StoredKey {
        final byte[] blob;
        final String comment;
        final String keyType;
        final PrivateKey privateKey;
        final String keyStoreAlias;
        final String application;

        StoredKey(byte[] blob, String comment, String keyType, PrivateKey privateKey) {
            this(blob, comment, keyType, privateKey, null, null);
        }

        StoredKey(byte[] blob, String comment, String keyType, PrivateKey privateKey,
                  String keyStoreAlias, String application) {
            this.blob = blob;
            this.comment = comment;
            this.keyType = keyType;
            this.privateKey = privateKey;
            this.keyStoreAlias = keyStoreAlias;
            this.application = application;
        }
    }

    public SshAgentServer(String ignoredPath) {
        this(null, ignoredPath);
    }

    public SshAgentServer(Context context, String ignoredPath) {
        appContext = context == null ? null : context.getApplicationContext();
        namePrefix = "com.tinyhack.ssh.ssh-agent." + Process.myUid() + ".";
    }

    public String getActualSocketPath() {
        return envPath;
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            abstractName = namePrefix + UUID.randomUUID().toString().replace("-", "");
            envPath = "@" + abstractName;
            serverSocket = new LocalServerSocket(abstractName);
            locked = false;
            running = true;
            acceptThread = new Thread(this::acceptLoop, "SshAgentAccept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "SSH agent listening at " + envPath);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Cannot bind abstract SSH agent socket " + envPath, e);
            serverSocket = null;
            running = false;
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        // Some Android LocalSocket implementations do not unblock accept() merely by
        // closing the listening wrapper. Wake it first so the native descriptor and
        // abstract address are actually released.
        if (abstractName != null) {
            try (LocalSocket wake = new LocalSocket()) {
                wake.connect(new LocalSocketAddress(
                        abstractName, LocalSocketAddress.Namespace.ABSTRACT));
            } catch (IOException ignored) {
            }
        }
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
        if (acceptThread != null) {
            try {
                acceptThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acceptThread = null;
        }
        keys.clear();
        Log.i(TAG, "SSH agent stopped");
    }

    public boolean isRunning() {
        return running && serverSocket != null;
    }

    /** While locked the agent refuses every operation (identities, signing, adds). */
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isLocked() {
        return locked;
    }

    public int getKeyCount() {
        return keys.size();
    }

    public void removeAll() {
        keys.clear();
    }

    public boolean addAndroidSecurityKey(SshKeyManager.AndroidSecurityKey key) {
        if (appContext == null || key == null) return false;
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(key.alias, null);
            if (privateKey == null) return false;
            StoredKey stored = new StoredKey(key.publicBlob, key.comment,
                    SshKeyManager.SK_ECDSA_TYPE, privateKey, key.alias, key.application);
            keys.removeIf(existing -> Arrays.equals(existing.blob, stored.blob));
            keys.add(stored);
            Log.i(TAG, "Added Android security-key identity " + key.alias);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot load Android security key " + key.alias, e);
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                LocalSocket client = serverSocket.accept();
                Credentials peer = client.getPeerCredentials();
                if (peer == null || peer.getUid() != Process.myUid()) {
                    Log.w(TAG, "Rejected SSH-agent client with uid " +
                            (peer == null ? "unknown" : peer.getUid()));
                    client.close();
                    continue;
                }
                if (activeClients.incrementAndGet() > MAX_CONCURRENT_CLIENTS) {
                    Log.w(TAG, "Rejecting SSH-agent client: too many concurrent connections");
                    activeClients.decrementAndGet();
                    client.close();
                    continue;
                }
                Thread worker = new Thread(() -> {
                    try {
                        handleClient(client);
                    } finally {
                        activeClients.decrementAndGet();
                    }
                }, "SshAgentClient");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "SSH-agent accept failed", e);
                break;
            }
        }
    }

    private void handleClient(LocalSocket socket) {
        try (LocalSocket client = socket;
             DataInputStream in = new DataInputStream(client.getInputStream());
             DataOutputStream out = new DataOutputStream(client.getOutputStream())) {
            while (running) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    return;
                }
                if (length < 1 || length > MAX_MESSAGE_SIZE) return;
                byte[] message = new byte[length];
                in.readFully(message);
                int type = message[0] & 0xff;
                try {
                    if (locked) {
                        // Mirrors OpenSSH: a locked agent answers FAILURE to everything
                        sendStatus(out, false);
                    } else {
                        switch (type) {
                        case SSH_AGENTC_REQUEST_IDENTITIES:
                            sendIdentities(out);
                            break;
                        case SSH_AGENTC_ADD_IDENTITY:
                            addIdentity(message, false);
                            sendStatus(out, true);
                            break;
                        case SSH_AGENTC_ADD_ID_CONSTRAINED:
                            addIdentity(message, true);
                            sendStatus(out, true);
                            break;
                        case SSH_AGENTC_REMOVE_IDENTITY:
                            removeIdentity(message);
                            sendStatus(out, true);
                            break;
                        case SSH_AGENTC_REMOVE_ALL_IDENTITIES:
                            removeAll();
                            sendStatus(out, true);
                            break;
                        case SSH_AGENTC_SIGN_REQUEST:
                            sign(message, out);
                            break;
                        case SSH_AGENTC_LOCK:
                        case SSH_AGENTC_UNLOCK:
                            sendStatus(out, false);
                            break;
                        default:
                            sendStatus(out, false);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "SSH-agent request " + type + " failed", e);
                    sendStatus(out, false);
                }
                out.flush();
            }
        } catch (IOException e) {
            Log.d(TAG, "SSH-agent client disconnected: " + e.getMessage());
        }
    }

    private void addIdentity(byte[] message, boolean constrained) throws Exception {
        WireReader reader = new WireReader(message, 1);
        String keyType = reader.readStringUtf8();
        StoredKey key;
        if ("ssh-ed25519".equals(keyType)) {
            byte[] publicKey = reader.readString();
            byte[] privateAndPublic = reader.readString();
            if (publicKey.length != 32 || privateAndPublic.length < 32)
                throw new IOException("Invalid Ed25519 identity");
            byte[] seed = Arrays.copyOf(privateAndPublic, 32);
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(ed25519Pkcs8(seed)));
            String comment = reader.readStringUtf8();
            key = new StoredKey(wireBlob(keyType, publicKey), comment, keyType, privateKey);
        } else if ("ssh-rsa".equals(keyType)) {
            BigInteger n = reader.readMpInt();
            BigInteger e = reader.readMpInt();
            BigInteger d = reader.readMpInt();
            BigInteger iqmp = reader.readMpInt();
            BigInteger p = reader.readMpInt();
            BigInteger q = reader.readMpInt();
            String comment = reader.readStringUtf8();
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new RSAPrivateCrtKeySpec(n, e, d, p, q,
                            d.mod(p.subtract(BigInteger.ONE)),
                            d.mod(q.subtract(BigInteger.ONE)), iqmp));
            key = new StoredKey(publicBlob(keyType, e, n), comment, keyType, privateKey);
        } else {
            throw new IOException("Unsupported identity type " + keyType);
        }

        if (constrained && reader.remaining() != 0)
            throw new IOException("Constrained identities are not supported");
        keys.removeIf(existing -> Arrays.equals(existing.blob, key.blob));
        keys.add(key);
        Log.i(TAG, "Added " + keyType + " identity " + key.comment);
    }

    private void sign(byte[] message, DataOutputStream out) throws Exception {
        WireReader reader = new WireReader(message, 1);
        byte[] requestedBlob = reader.readString();
        byte[] data = reader.readString();
        int flags = reader.readInt();
        StoredKey key = null;
        for (StoredKey candidate : keys) {
            if (Arrays.equals(candidate.blob, requestedBlob)) {
                key = candidate;
                break;
            }
        }
        if (key == null) throw new IOException("Identity not found");

        String sshAlgorithm;
        String javaAlgorithm;
        if (SshKeyManager.SK_ECDSA_TYPE.equals(key.keyType)) {
            signSecurityKey(key, data, out);
            return;
        } else if ("ssh-ed25519".equals(key.keyType)) {
            sshAlgorithm = "ssh-ed25519";
            javaAlgorithm = "Ed25519";
        } else if (!"ssh-rsa".equals(key.keyType)) {
            throw new IOException("Unsupported identity type " + key.keyType);
        } else if ((flags & SSH_AGENT_RSA_SHA2_512) != 0) {
            sshAlgorithm = "rsa-sha2-512";
            javaAlgorithm = "SHA512withRSA";
        } else if ((flags & SSH_AGENT_RSA_SHA2_256) != 0) {
            sshAlgorithm = "rsa-sha2-256";
            javaAlgorithm = "SHA256withRSA";
        } else {
            // flags == 0 means plain ssh-rsa (SHA-1); refused as weak crypto
            throw new IOException("ssh-rsa (SHA-1) signatures refused; client must request rsa-sha2-256/512");
        }

        Signature signer = Signature.getInstance(javaAlgorithm);
        signer.initSign(key.privateKey);
        signer.update(data);
        byte[] signatureBlob = wireBlob(sshAlgorithm, signer.sign());
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(response);
        payload.writeByte(SSH_AGENT_SIGN_RESPONSE);
        writeString(payload, signatureBlob);
        sendPayload(out, response.toByteArray());
    }

    private void signSecurityKey(StoredKey key, byte[] message, DataOutputStream out)
            throws Exception {
        int counter = nextSecurityKeyCounter(key.keyStoreAlias);
        byte flags = SK_USER_PRESENT;
        ByteArrayOutputStream signed = new ByteArrayOutputStream();
        signed.write(MessageDigest.getInstance("SHA-256").digest(
                key.application.getBytes(StandardCharsets.UTF_8)));
        signed.write(flags);
        DataOutputStream signedOut = new DataOutputStream(signed);
        signedOut.writeInt(counter);
        signed.write(MessageDigest.getInstance("SHA-256").digest(message));

        Signature signer = Signature.getInstance("SHA256withECDSA");
        try {
            signer.initSign(key.privateKey);
            signer.update(signed.toByteArray());
            byte[] ecdsaBlob = ecdsaDerToSsh(signer.sign());

            ByteArrayOutputStream signature = new ByteArrayOutputStream();
            DataOutputStream signatureOut = new DataOutputStream(signature);
            writeString(signatureOut,
                    SshKeyManager.SK_ECDSA_TYPE.getBytes(StandardCharsets.UTF_8));
            writeString(signatureOut, ecdsaBlob);
            signatureOut.writeByte(flags);
            signatureOut.writeInt(counter);

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(response);
            payload.writeByte(SSH_AGENT_SIGN_RESPONSE);
            writeString(payload, signature.toByteArray());
            sendPayload(out, response.toByteArray());
        } catch (UserNotAuthenticatedException e) {
            Log.w(TAG, "Fingerprint authentication required for " + key.keyStoreAlias);
            throw e;
        }
    }

    private synchronized int nextSecurityKeyCounter(String alias) {
        SharedPreferences prefs = appContext.getSharedPreferences(
                SK_COUNTER_PREFS, Context.MODE_PRIVATE);
        int next = prefs.getInt(alias, 0) + 1;
        prefs.edit().putInt(alias, next).apply();
        return next;
    }

    private static byte[] ecdsaDerToSsh(byte[] der) throws IOException {
        DerReader reader = new DerReader(der);
        if (reader.readByte() != 0x30) throw new IOException("Invalid ECDSA sequence");
        int sequenceLength = reader.readLength();
        if (sequenceLength != reader.remaining()) throw new IOException("Invalid ECDSA length");
        BigInteger r = reader.readInteger();
        BigInteger s = reader.readInteger();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        writeString(out, r.toByteArray());
        writeString(out, s.toByteArray());
        return result.toByteArray();
    }

    private void sendIdentities(DataOutputStream out) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(response);
        payload.writeByte(SSH_AGENT_IDENTITIES_ANSWER);
        payload.writeInt(keys.size());
        for (StoredKey key : keys) {
            writeString(payload, key.blob);
            writeString(payload, key.comment.getBytes(StandardCharsets.UTF_8));
        }
        sendPayload(out, response.toByteArray());
    }

    private void removeIdentity(byte[] message) throws IOException {
        byte[] blob = new WireReader(message, 1).readString();
        keys.removeIf(key -> Arrays.equals(key.blob, blob));
    }

    private static byte[] publicBlob(String type, BigInteger e, BigInteger n) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        writeString(out, type.getBytes(StandardCharsets.UTF_8));
        writeString(out, e.toByteArray());
        writeString(out, n.toByteArray());
        return result.toByteArray();
    }

    private static byte[] ed25519Pkcs8(byte[] seed) {
        // RFC 8410 PrivateKeyInfo for Ed25519: algorithm OID 1.3.101.112 and
        // a nested OCTET STRING containing the 32-byte seed.
        byte[] prefix = new byte[]{
                0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
                0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
        };
        byte[] encoded = Arrays.copyOf(prefix, prefix.length + seed.length);
        System.arraycopy(seed, 0, encoded, prefix.length, seed.length);
        return encoded;
    }

    private static byte[] wireBlob(String first, byte[] second) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        writeString(out, first.getBytes(StandardCharsets.UTF_8));
        writeString(out, second);
        return result.toByteArray();
    }

    private static void sendStatus(DataOutputStream out, boolean success) throws IOException {
        sendPayload(out, new byte[]{(byte) (success ? SSH_AGENT_SUCCESS : SSH_AGENT_FAILURE)});
    }

    private static void sendPayload(DataOutputStream out, byte[] payload) throws IOException {
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static void writeString(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static final class WireReader {
        private final ByteArrayInputStream bytes;
        private final DataInputStream in;

        WireReader(byte[] message, int offset) {
            bytes = new ByteArrayInputStream(message, offset, message.length - offset);
            in = new DataInputStream(bytes);
        }

        int remaining() {
            return bytes.available();
        }

        int readInt() throws IOException {
            return in.readInt();
        }

        byte[] readString() throws IOException {
            int length = in.readInt();
            if (length < 0 || length > MAX_MESSAGE_SIZE || length > bytes.available())
                throw new IOException("Invalid SSH string length " + length);
            byte[] value = new byte[length];
            in.readFully(value);
            return value;
        }

        String readStringUtf8() throws IOException {
            return new String(readString(), StandardCharsets.UTF_8);
        }

        BigInteger readMpInt() throws IOException {
            byte[] value = readString();
            return value.length == 0 ? BigInteger.ZERO : new BigInteger(value);
        }
    }

    private static final class DerReader {
        private final ByteArrayInputStream bytes;

        DerReader(byte[] value) {
            bytes = new ByteArrayInputStream(value);
        }

        int remaining() {
            return bytes.available();
        }

        int readByte() throws IOException {
            int value = bytes.read();
            if (value < 0) throw new EOFException();
            return value;
        }

        int readLength() throws IOException {
            int first = readByte();
            if ((first & 0x80) == 0) return first;
            int count = first & 0x7f;
            if (count < 1 || count > 2) throw new IOException("Invalid DER length");
            int value = 0;
            for (int i = 0; i < count; i++) value = (value << 8) | readByte();
            return value;
        }

        BigInteger readInteger() throws IOException {
            if (readByte() != 0x02) throw new IOException("Expected DER integer");
            int length = readLength();
            if (length < 1 || length > remaining()) throw new IOException("Invalid DER integer");
            byte[] value = new byte[length];
            if (bytes.read(value, 0, length) != length) throw new EOFException();
            return new BigInteger(value);
        }
    }
}
