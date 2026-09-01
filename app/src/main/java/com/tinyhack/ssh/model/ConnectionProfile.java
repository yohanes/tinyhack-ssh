package com.tinyhack.ssh.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.Locale;

/**
 * Represents a reusable connection profile for creating terminal sessions.
 * Supports LOCAL (shell on device), SSH, and MOSH (remote, roaming) types.
 */
public class ConnectionProfile {
    public enum Type {
        LOCAL,
        SSH,
        MOSH;

        public static Type fromString(String s) {
            if (s == null) return LOCAL;
            try {
                return Type.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return LOCAL;
            }
        }
    }

    public enum AuthType {
        NONE,
        PASSWORD,
        KEY;

        public static AuthType fromString(String s) {
            if (s == null) return NONE;
            try {
                return AuthType.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return NONE;
            }
        }
    }

    private String id;
    private String name;
    private Type type;
    private long createdAt;
    private long updatedAt;

    // LOCAL fields
    private String shell;
    private String cwd;
    private String env; // newline separated KEY=VAL

    // SSH fields
    private String host;
    private int port = 22;
    private String username;
    private AuthType authType = AuthType.NONE;
    private String keyName; // SshKeyManager key name
    private String password; // in memory only; persisted encrypted (ProfileCrypto)
    private String sshArgs; // extra ssh args
    private boolean agentForwardingEnabled = false;

    // Terminal capabilities are opt-in because remote hosts control terminal output.
    private boolean kittyGraphicsEnabled = false;
    private boolean osc52ClipboardEnabled = false;

    // Cloudflare Access (cloudflared) fields - for SSH via Access tunnel
    private boolean cloudflaredEnabled = false;
    private String cloudflaredHostname; // Access hostname e.g. xaccess.example.com
    private String cloudflaredServiceTokenId;
    private String cloudflaredServiceTokenSecret; // encrypted via ProfileCrypto
    private String cloudflaredDestination; // optional --destination for bastion mode

    // Appearance
    private int color = 0xFF4D90FE;

    public ConnectionProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "New Profile";
        this.type = Type.LOCAL;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public ConnectionProfile(String name, Type type) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // Getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }
    public String getCwd() { return cwd; }
    public void setCwd(String cwd) { this.cwd = cwd; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public static boolean isValidHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == ':';
            if (!ok) return false;
        }
        return true;
    }

    public static String hostValidationError(String host, String fieldLabel) {
        if (host == null || host.trim().isEmpty()) return fieldLabel + " is required";
        String h = host.trim();
        if (h.contains("@")) return fieldLabel + " must not contain '@' (put the username in the User field)";
        if (!isValidHost(h)) return fieldLabel + " contains invalid characters (spaces and symbols are not allowed; only letters, digits, '.', '-', '_', and ':' for IPv6)";
        return null;
    }

    public static String destinationValidationError(String destination) {
        if (destination == null || destination.trim().isEmpty()) return null;
        String value = destination.trim();
        String host;
        String portText;
        if (value.startsWith("[")) {
            int end = value.indexOf("]:");
            if (end <= 1) return "Destination must be host:port or [IPv6]:port";
            host = value.substring(1, end);
            portText = value.substring(end + 2);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                return "Destination must be host:port";
            }
            host = value.substring(0, colon);
            portText = value.substring(colon + 1);
        }
        if (!isValidHost(host)) return "Destination host contains invalid characters";
        try {
            int parsedPort = Integer.parseInt(portText);
            if (parsedPort < 1 || parsedPort > 65535) return "Destination port must be between 1 and 65535";
        } catch (NumberFormatException e) {
            return "Destination port must be a number";
        }
        return null;
    }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType authType) { this.authType = authType; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSshArgs() { return sshArgs; }
    public void setSshArgs(String sshArgs) { this.sshArgs = sshArgs; }
    public boolean isAgentForwardingEnabled() { return agentForwardingEnabled; }
    public void setAgentForwardingEnabled(boolean enabled) { this.agentForwardingEnabled = enabled; }
    public boolean isKittyGraphicsEnabled() { return kittyGraphicsEnabled; }
    public void setKittyGraphicsEnabled(boolean enabled) { this.kittyGraphicsEnabled = enabled; }
    public boolean isOsc52ClipboardEnabled() { return osc52ClipboardEnabled; }
    public void setOsc52ClipboardEnabled(boolean enabled) { this.osc52ClipboardEnabled = enabled; }
    public boolean isCloudflaredEnabled() { return cloudflaredEnabled; }
    public void setCloudflaredEnabled(boolean enabled) { this.cloudflaredEnabled = enabled; }
    public String getCloudflaredHostname() { return cloudflaredHostname; }
    public void setCloudflaredHostname(String h) { this.cloudflaredHostname = h; }
    public String getCloudflaredServiceTokenId() { return cloudflaredServiceTokenId; }
    public void setCloudflaredServiceTokenId(String id) { this.cloudflaredServiceTokenId = id; }
    public String getCloudflaredServiceTokenSecret() { return cloudflaredServiceTokenSecret; }
    public void setCloudflaredServiceTokenSecret(String s) { this.cloudflaredServiceTokenSecret = s; }
    public String getCloudflaredDestination() { return cloudflaredDestination; }
    public void setCloudflaredDestination(String d) { this.cloudflaredDestination = d; }
    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public String getDisplaySubtitle() {
        if (type == Type.SSH || type == Type.MOSH) {
            StringBuilder sb = new StringBuilder();
            if (cloudflaredEnabled) sb.append("[CF] ");
            if (username != null && !username.isEmpty()) sb.append(username).append("@");
            String displayHost = host;
            if (cloudflaredEnabled && cloudflaredHostname != null && !cloudflaredHostname.isEmpty()) {
                displayHost = cloudflaredHostname;
            }
            if (displayHost != null) sb.append(displayHost);
            if (port != 22) sb.append(":").append(port);
            if (sb.length() == 0 || (cloudflaredEnabled && sb.toString().equals("[CF] "))) sb.append(type == Type.MOSH ? "Mosh" : "SSH");
            return sb.toString();
        } else {
            if (cwd != null && !cwd.isEmpty()) return cwd;
            if (shell != null && !shell.isEmpty()) return shell;
            return "Local shell";
        }
    }

    public String getTypeLabel() {
        switch (type) {
            case SSH: return "SSH";
            case MOSH: return "MOSH";
            default: return "LOCAL";
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name != null ? name : "");
        o.put("type", type.name());
        o.put("createdAt", createdAt);
        o.put("updatedAt", updatedAt);
        o.put("shell", shell != null ? shell : "");
        o.put("cwd", cwd != null ? cwd : "");
        o.put("env", env != null ? env : "");
        o.put("host", host != null ? host : "");
        o.put("port", port);
        o.put("username", username != null ? username : "");
        o.put("authType", authType.name());
        o.put("keyName", keyName != null ? keyName : "");
        // Never serialize the password in plaintext; store AES-GCM ciphertext.
        // Accepting a plaintext "password" field on input (fromJson) is for
        // import/compat only and gets migrated to ciphertext on the next save.
        String enc = ProfileCrypto.encrypt(password);
        o.put("passwordEnc", enc != null ? enc : "");
        o.put("sshArgs", sshArgs != null ? sshArgs : "");
        o.put("agentForwardingEnabled", agentForwardingEnabled);
        o.put("kittyGraphicsEnabled", kittyGraphicsEnabled);
        o.put("osc52ClipboardEnabled", osc52ClipboardEnabled);
        o.put("cloudflaredEnabled", cloudflaredEnabled);
        o.put("cloudflaredHostname", cloudflaredHostname != null ? cloudflaredHostname : "");
        o.put("cloudflaredServiceTokenId", cloudflaredServiceTokenId != null ? cloudflaredServiceTokenId : "");
        String encCf = ProfileCrypto.encrypt(cloudflaredServiceTokenSecret);
        o.put("cloudflaredServiceTokenSecretEnc", encCf != null ? encCf : "");
        // Keep the legacy key only as an empty migration marker. Encryption
        // failure must fail closed and never put the secret in profile JSON.
        o.put("cloudflaredServiceTokenSecret", "");
        o.put("cloudflaredDestination", cloudflaredDestination != null ? cloudflaredDestination : "");
        o.put("color", color);
        return o;
    }

    public static ConnectionProfile fromJson(JSONObject o) throws JSONException {
        ConnectionProfile p = new ConnectionProfile();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "Unnamed");
        p.type = Type.fromString(o.optString("type", "LOCAL"));
        p.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        p.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
        String s = o.optString("shell", "");
        p.shell = s.isEmpty() ? null : s;
        s = o.optString("cwd", "");
        p.cwd = s.isEmpty() ? null : s;
        s = o.optString("env", "");
        p.env = s.isEmpty() ? null : s;
        s = o.optString("host", "");
        p.host = s.isEmpty() ? null : s;
        p.port = o.optInt("port", 22);
        s = o.optString("username", "");
        p.username = s.isEmpty() ? null : s;
        p.authType = AuthType.fromString(o.optString("authType", "NONE"));
        s = o.optString("keyName", "");
        p.keyName = s.isEmpty() ? null : s;
        s = o.optString("passwordEnc", "");
        if (s.isEmpty()) {
            // Legacy plaintext fallback (pre-encryption profiles / user import)
            s = o.optString("password", "");
            p.password = s.isEmpty() ? null : s;
        } else {
            p.password = ProfileCrypto.decrypt(s);
        }
        s = o.optString("sshArgs", "");
        p.sshArgs = s.isEmpty() ? null : s;
        p.agentForwardingEnabled = o.optBoolean("agentForwardingEnabled", false);
        p.kittyGraphicsEnabled = o.optBoolean("kittyGraphicsEnabled", false);
        p.osc52ClipboardEnabled = o.optBoolean("osc52ClipboardEnabled", false);
        p.cloudflaredEnabled = o.optBoolean("cloudflaredEnabled", false);
        s = o.optString("cloudflaredHostname", "");
        p.cloudflaredHostname = s.isEmpty() ? null : s;
        s = o.optString("cloudflaredServiceTokenId", "");
        p.cloudflaredServiceTokenId = s.isEmpty() ? null : s;
        s = o.optString("cloudflaredServiceTokenSecretEnc", "");
        if (s.isEmpty()) {
            s = o.optString("cloudflaredServiceTokenSecret", "");
            p.cloudflaredServiceTokenSecret = s.isEmpty() ? null : s;
        } else {
            p.cloudflaredServiceTokenSecret = ProfileCrypto.decrypt(s);
        }
        s = o.optString("cloudflaredDestination", "");
        p.cloudflaredDestination = s.isEmpty() ? null : s;
        p.color = o.optInt("color", 0xFF4D90FE);
        return p;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
