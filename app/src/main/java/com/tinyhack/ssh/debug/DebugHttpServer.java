package com.tinyhack.ssh.debug;

import android.util.Log;

import com.tinyhack.ssh.model.ConnectionProfile;
import com.tinyhack.ssh.model.ProfileManager;
import com.tinyhack.ssh.service.TerminalService;
import com.tinyhack.ssh.session.TerminalSession;
import com.tinyhack.ssh.terminal.KeyCodes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DebugHttpServer {
    private static final String TAG = "TinySSHDebugHttp";

    public static com.tinyhack.ssh.view.TerminalView debugTerminalView = null;

    /** UI hooks for /fullscreen automation; registered by MainActivity. */
    public static Runnable fullscreenToggle = null;
    public static java.util.function.BooleanSupplier fullscreenState = null;

    private final TerminalService service;
    private final int port;
    private ServerSocket serverSocket;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ExecutorService threadPool = Executors.newCachedThreadPool();
    private volatile String authToken;

    public DebugHttpServer(TerminalService service, int port) {
        this.service = service;
        this.port = port;
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        try {
            // Recreate the pool if a previous stop() shut it down
            if (threadPool.isShutdown()) {
                threadPool = Executors.newCachedThreadPool();
            }
            authToken = getOrCreateToken();
            // Loopback only: the debug server is reachable via `adb forward`, never from the network
            serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
            isRunning.set(true);
            Log.i(TAG, "TinySSH Debug HTTP Server listening on 127.0.0.1:" + port
                    + " (auth token: files/http_debug_token, read with: "
                    + "adb shell run-as " + service.getPackageName() + " cat files/http_debug_token)");

            Thread acceptThread = new Thread(() -> {
                while (isRunning.get() && !serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        threadPool.execute(() -> handleClient(socket));
                    } catch (IOException e) {
                        if (!isRunning.get()) break;
                    }
                }
            }, "TinySSH-HttpServer");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server on port " + port, e);
        }
    }

    public synchronized void stop() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {}
            threadPool.shutdownNow();
            Log.i(TAG, "TinySSH Debug HTTP Server stopped");
        }
    }

    public synchronized boolean isRunning() {
        return isRunning.get();
    }

    private void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream();
              OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String fullPath = parts[1];

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String k = line.substring(0, colon).trim().toLowerCase();
                    String v = line.substring(colon + 1).trim();
                    headers.put(k, v);
                    if ("content-length".equals(k)) {
                        try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Read body if any - need to handle binary safe? For our endpoints, char buffer is ok
            String body = "";
            if (contentLength > 0) {
                char[] cbuf = new char[contentLength];
                int readTotal = 0;
                while (readTotal < contentLength) {
                    int r = reader.read(cbuf, readTotal, contentLength - readTotal);
                    if (r < 0) break;
                    readTotal += r;
                }
                body = new String(cbuf, 0, readTotal);
            }

            // Parse path and query params
            String path = fullPath;
            Map<String, String> queryParams = new HashMap<>();
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                String qStr = fullPath.substring(qIdx + 1);
                for (String param : qStr.split("&")) {
                    if (param.isEmpty()) continue;
                    int eq = param.indexOf('=');
                    if (eq > 0) {
                        queryParams.put(
                            URLDecoder.decode(param.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(param.substring(eq + 1), "UTF-8")
                        );
                    } else if (eq < 0) {
                        queryParams.put(URLDecoder.decode(param, "UTF-8"), "");
                    }
                }
            }

            // Every request requires the debug token (header or ?token=)
            if (!isAuthorized(headers, queryParams)) {
                sendResponse(out, 401, "application/json; charset=utf-8",
                        "{\"error\":\"unauthorized\",\"hint\":\"pass ?token=<t> or Authorization: Bearer <t>; read token via: adb shell run-as com.tinyhack.ssh cat files/http_debug_token\"}"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }

            // Also parse body as query params if it looks like form encoded and path needs params (for POST)
            // But we keep body separate for JSON handling

            routeRequest(method, path, queryParams, body, out, headers);

        } catch (Exception e) {
            Log.w(TAG, "Error handling client request", e);
        }
    }

    private boolean isAuthorized(Map<String, String> headers, Map<String, String> params) {
        String token = authToken;
        if (token == null || token.isEmpty()) return false;
        String provided = null;
        String authHeader = headers != null ? headers.get("authorization") : null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = authHeader.substring(7).trim();
        }
        if (provided == null && params != null) {
            provided = params.get("token");
        }
        if (provided == null || provided.isEmpty()) return false;
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    private String getOrCreateToken() throws IOException {
        File tokenFile = new File(service.getFilesDir(), "http_debug_token");
        if (tokenFile.exists()) {
            byte[] buf = new byte[256];
            try (FileInputStream in = new FileInputStream(tokenFile)) {
                int n = in.read(buf);
                String existing = n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII).trim() : "";
                if (existing.matches("[0-9a-f]{32,128}")) return existing;
            } catch (IOException ignored) {}
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        String token = sb.toString();
        try (FileOutputStream out = new FileOutputStream(tokenFile)) {
            out.write(token.getBytes(StandardCharsets.US_ASCII));
        }
        try { android.system.Os.chmod(tokenFile.getAbsolutePath(), 0600); } catch (Exception ignored) {}
        return token;
    }

    private void routeRequest(String method, String path, Map<String, String> params, String body, OutputStream out, Map<String,String> headers) throws IOException {
        TerminalSession session = service.getCurrentSession();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            sendResponse(out, 204, "text/plain", new byte[0]);
            return;
        }

        if ("/dump".equals(path) || "/text".equals(path)) {
            String text = session != null ? session.getScreenText() : "(no active session)\n";
            sendResponse(out, 200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/vt".equals(path)) {
            String vt = session != null ? session.getVt() : "";
            sendResponse(out, 200, "text/plain; charset=utf-8", vt.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/json".equals(path)) {
            StringBuilder json = new StringBuilder();
            json.append("{");
            if (session != null) {
                json.append("\"id\":\"").append(session.getId()).append("\",");
                json.append("\"title\":\"").append(escapeJson(session.getTitle())).append("\",");
                json.append("\"displayTitle\":\"").append(escapeJson(session.getDisplayTitle())).append("\",");
                json.append("\"profileId\":\"").append(session.getProfileId()!=null? escapeJson(session.getProfileId()):"").append("\",");
                json.append("\"rows\":").append(session.getRows()).append(",");
                json.append("\"cols\":").append(session.getCols()).append(",");
                json.append("\"running\":").append(session.isRunning()).append(",");
                json.append("\"text\":\"").append(escapeJson(session.getScreenText())).append("\"");
            } else {
                json.append("\"session\":null");
            }
            json.append("}");
            sendResponse(out, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/input".equals(path) || "/exec".equals(path)) {
            if (session != null && !body.isEmpty()) {
                session.write(body);
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                String text = session.getScreenText();
                sendResponse(out, 200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
            } else {
                sendResponse(out, 400, "text/plain", "Missing input body or no active session\n".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if ("/key".equals(path)) {
            String k = params.get("k");
            if (k == null && !body.isEmpty()) k = body.trim();
            if (session != null && k != null) {
                handleKeyInput(session, k.toUpperCase());
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                String text = session.getScreenText();
                sendResponse(out, 200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
            } else {
                sendResponse(out, 400, "text/plain", "Missing key param (k=ENTER, ESC, TAB, UP, DOWN, etc.)\n".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        // Simulate IME-committed text exactly as the soft keyboard would deliver
        // it (routes through TerminalView.sendText, so sticky CTRL/ALT
        // modifiers from the key bar apply). Params: ?text=...[&post_return=1]
        if ("/type".equals(path)) {
            String text = params.containsKey("text") ? params.get("text") : body;
            if (debugTerminalView != null && text != null) {
                debugTerminalView.sendText(text);
                if ("1".equals(params.get("post_return"))) {
                    debugTerminalView.sendText("\r");
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                String screen = session != null ? session.getScreenText() : "";
                sendResponse(out, 200, "text/plain; charset=utf-8", screen.getBytes(StandardCharsets.UTF_8));
            } else {
                sendResponse(out, 400, "text/plain", "Missing text or no terminal view\n".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        // --- Sessions API ---
        if ("/fullscreen".equals(path)) {
            // Toggle the terminal fullscreen mode (hooks registered by MainActivity).
            // GET /fullscreen toggles; GET /fullscreen?state=true|false sets explicitly.
            Runnable toggle = fullscreenToggle;
            if (toggle == null || fullscreenState == null) {
                sendResponse(out, 503, "text/plain", "Fullscreen hooks not available\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String stateParam = params.get("state");
            boolean wantEnabled = stateParam != null ? Boolean.parseBoolean(stateParam) : !fullscreenState.getAsBoolean();
            if (wantEnabled != fullscreenState.getAsBoolean()) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(toggle);
            }
            sendResponse(out, 200, "application/json; charset=utf-8",
                ("{\"status\":\"ok\",\"fullscreen\":" + wantEnabled + "}").getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sessions".equals(path)) {
            List<TerminalSession> list = service.getSessions();
            int curIdx = service.getCurrentSessionIndex();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                TerminalSession s = list.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"index\":").append(i)
                  .append(",\"id\":\"").append(s.getId()).append("\"")
                  .append(",\"title\":\"").append(escapeJson(s.getTitle())).append("\"")
                  .append(",\"displayTitle\":\"").append(escapeJson(s.getDisplayTitle())).append("\"")
                  .append(",\"profileId\":\"").append(s.getProfileId()!=null?escapeJson(s.getProfileId()):"").append("\"")
                  .append(",\"running\":").append(s.isRunning())
                  .append(",\"current\":").append(i==curIdx)
                  .append(",\"rows\":").append(s.getRows())
                  .append(",\"cols\":").append(s.getCols())
                  .append(",\"createdAt\":").append(s.getCreatedAt())
                  .append("}");
            }
            sb.append("]");
            sendResponse(out, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sessions/raw".equals(path)) {
            String cmd = params.get("cmd");
            if (cmd == null || cmd.isEmpty()) {
                sendResponse(out, 400, "text/plain", "Missing cmd param\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String[] argv = null;
            String argvStr = params.get("argv");
            if (argvStr != null && !argvStr.isEmpty()) {
                argv = argvStr.split(",");
            }
            TerminalSession newSession = service.createSessionWithProfile(cmd, null, argv, null, null, "raw");
            String resp = "{\"status\":\"ok\",\"id\":\"" + newSession.getId() + "\",\"title\":\"" + escapeJson(newSession.getDisplayTitle()) + "\"}";
            sendResponse(out, 200, "application/json", resp.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sessions/new".equals(path)) {
            String profileId = params.get("profileId");
            if (profileId == null) profileId = params.get("profile");
            String name = params.get("name");
            if (name == null && !body.isEmpty() && body.trim().startsWith("{")) {
                // try parse json body for profileId/name
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(body);
                    if (jo.has("profileId")) profileId = jo.getString("profileId");
                    if (jo.has("name")) name = jo.getString("name");
                    if (jo.has("profile")) profileId = jo.getString("profile");
                } catch (Exception ignored) {}
            } else if (!body.isEmpty() && name==null && body.contains("profileId")) {
                // fallback form
            }
            TerminalSession newSession;
            if (profileId != null && !profileId.isEmpty()) {
                ProfileManager pm = ProfileManager.getInstance(service);
                ConnectionProfile p = pm.getProfile(profileId);
                if (p != null) {
                    // Allow override name
                    if (name != null && !name.isEmpty()) {
                        // clone with new name? Just create session with overridden display name
                        // For simplicity, use service.createSessionForProfile then rename
                        newSession = service.createSessionForProfile(p);
                        if (name != null && !name.isEmpty()) {
                            service.renameSession(newSession.getId(), name);
                        }
                    } else {
                        newSession = service.createSessionForProfile(p);
                    }
                } else {
                    sendResponse(out, 404, "application/json", ("{\"error\":\"profile not found: "+escapeJson(profileId)+"\"}").getBytes(StandardCharsets.UTF_8));
                    return;
                }
            } else {
                if (name != null && !name.isEmpty()) {
                    newSession = service.createSessionWithProfile(null, null, null, null, null, name);
                } else {
                    newSession = service.createSession(null, null, null, null);
                }
            }
            String resp = "{\"status\":\"ok\",\"id\":\"" + newSession.getId() + "\",\"title\":\""+escapeJson(newSession.getDisplayTitle())+"\"}";
            sendResponse(out, 200, "application/json", resp.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sessions/switch".equals(path)) {
            String idxStr = params.get("index");
            String idStr = params.get("id");
            if (idStr == null) idStr = params.get("sessionId");
            if (idStr != null && !idStr.isEmpty()) {
                boolean ok = service.setCurrentSessionById(idStr);
                if (ok) {
                    sendResponse(out, 200, "text/plain", ("Switched to session " + idStr + "\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    sendResponse(out, 404, "text/plain", "Session id not found\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (idxStr != null) {
                try {
                    int idx = Integer.parseInt(idxStr);
                    service.setCurrentSession(idx);
                    sendResponse(out, 200, "text/plain", ("Switched to session " + idx + "\n").getBytes(StandardCharsets.UTF_8));
                    return;
                } catch (NumberFormatException ignored) {}
            }
            // try body json
            if (!body.isEmpty()) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(body);
                    if (jo.has("id")) {
                        boolean ok = service.setCurrentSessionById(jo.getString("id"));
                        if (ok) { sendResponse(out, 200, "text/plain", ("Switched to session " + jo.getString("id") + "\n").getBytes(StandardCharsets.UTF_8)); return; }
                    }
                    if (jo.has("index")) {
                        service.setCurrentSession(jo.getInt("index"));
                        sendResponse(out, 200, "text/plain", ("Switched to session " + jo.getInt("index") + "\n").getBytes(StandardCharsets.UTF_8)); return;
                    }
                } catch (Exception ignored) {}
            }
            sendResponse(out, 400, "text/plain", "Invalid session index/id\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sessions/close".equals(path) || "/sessions/kill".equals(path)) {
            String idxStr = params.get("index");
            String idStr = params.get("id");
            if (idStr == null) idStr = params.get("sessionId");
            boolean ok = false;
            if (idStr != null && !idStr.isEmpty()) {
                ok = service.closeSessionById(idStr);
            } else if (idxStr != null) {
                try {
                    int idx = Integer.parseInt(idxStr);
                    List<TerminalSession> list = service.getSessions();
                    if (idx >=0 && idx < list.size()) {
                        service.removeSession(list.get(idx));
                        ok = true;
                    }
                } catch (NumberFormatException ignored) {}
            } else if (!body.isEmpty()) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(body);
                    if (jo.has("id")) ok = service.closeSessionById(jo.getString("id"));
                    else if (jo.has("index")) {
                        int idx = jo.getInt("index");
                        List<TerminalSession> list = service.getSessions();
                        if (idx>=0 && idx<list.size()) { service.removeSession(list.get(idx)); ok=true; }
                    }
                } catch (Exception ignored) {}
            } else {
                // close current
                TerminalSession cur = service.getCurrentSession();
                if (cur != null) { service.removeSession(cur); ok = true; }
            }
            if (ok) sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
            else sendResponse(out, 400, "text/plain", "Failed to close session\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sessions/rename".equals(path)) {
            String idStr = params.get("id");
            if (idStr == null) idStr = params.get("sessionId");
            String name = params.get("name");
            if (name == null) name = params.get("title");
            if ((idStr == null || name==null) && !body.isEmpty()) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(body);
                    if (jo.has("id")) idStr = jo.getString("id");
                    if (jo.has("name")) name = jo.getString("name");
                    if (jo.has("title")) name = jo.getString("title");
                } catch (Exception ignored) {}
            }
            if (idStr == null) {
                // rename current if id missing but name provided
                TerminalSession cur = service.getCurrentSession();
                if (cur != null) idStr = cur.getId();
            }
            if (idStr != null && name != null && !name.isEmpty()) {
                boolean ok = service.renameSession(idStr, name);
                if (ok) sendResponse(out, 200, "application/json", ("{\"status\":\"ok\",\"id\":\""+idStr+"\",\"name\":\""+escapeJson(name)+"\"}").getBytes(StandardCharsets.UTF_8));
                else sendResponse(out, 404, "text/plain", "Session not found\n".getBytes(StandardCharsets.UTF_8));
            } else {
                sendResponse(out, 400, "text/plain", "Missing id/name\n".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        // --- Profiles API ---
        if ("/profiles".equals(path)) {
            ProfileManager pm = ProfileManager.getInstance(service);
            List<ConnectionProfile> list = pm.loadProfiles();
            StringBuilder sb = new StringBuilder("[");
            for (int i=0;i<list.size();i++) {
                if (i>0) sb.append(",");
                try {
                    sb.append(list.get(i).toJson().toString());
                } catch (Exception e) { sb.append("{}"); }
            }
            sb.append("]");
            sendResponse(out, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/profiles/get".equals(path)) {
            String id = params.get("id");
            if (id == null && !body.isEmpty()) {
                try { org.json.JSONObject jo = new org.json.JSONObject(body); if (jo.has("id")) id = jo.getString("id"); } catch (Exception ignored) {}
            }
            if (id == null) { sendResponse(out, 400, "text/plain", "Missing id\n".getBytes(StandardCharsets.UTF_8)); return; }
            ProfileManager pm = ProfileManager.getInstance(service);
            ConnectionProfile p = pm.getProfile(id);
            if (p == null) { sendResponse(out, 404, "text/plain", "Profile not found\n".getBytes(StandardCharsets.UTF_8)); return; }
            try {
                sendResponse(out, 200, "application/json; charset=utf-8", p.toJson().toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                sendResponse(out, 500, "text/plain", "Error\n".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if ("/profiles/create".equals(path) || "/profiles/new".equals(path)) {
            // Accept JSON body or query params
            ConnectionProfile p = null;
            if (!body.isEmpty()) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject(body);
                    // If body contains "profile":{...} unwrap
                    if (jo.has("profile") && jo.get("profile") instanceof org.json.JSONObject) jo = jo.getJSONObject("profile");
                    p = ConnectionProfile.fromJson(jo);
                    // Ensure new id
                    if (jo.has("id") && !jo.getString("id").isEmpty()) p.setId(jo.getString("id"));
                    else p.setId(java.util.UUID.randomUUID().toString());
                } catch (Exception e) {
                    // fallback to params
                }
            }
            if (p == null) {
                // Build from params
                String name = params.get("name");
                if (name == null || name.isEmpty()) name = "New Profile";
                String typeStr = params.get("type");
                ConnectionProfile.Type type = ConnectionProfile.Type.fromString(typeStr!=null?typeStr:"LOCAL");
                p = new ConnectionProfile(name, type);
                if (params.containsKey("host")) p.setHost(params.get("host"));
                if (params.containsKey("port")) try { p.setPort(Integer.parseInt(params.get("port"))); } catch (Exception ignored) {}
                if (params.containsKey("username")) p.setUsername(params.get("username"));
                if (params.containsKey("keyName")) p.setKeyName(params.get("keyName"));
                if (params.containsKey("shell")) p.setShell(params.get("shell"));
                if (params.containsKey("cwd")) p.setCwd(params.get("cwd"));
                if (params.containsKey("sshArgs")) p.setSshArgs(params.get("sshArgs"));
                if (params.containsKey("color")) try { p.setColor(Integer.parseInt(params.get("color"))); } catch (Exception ignored) {}
            }
            ProfileManager pm = ProfileManager.getInstance(service);
            pm.addProfile(p);
            try {
                String resp = "{\"status\":\"ok\",\"profile\":"+p.toJson().toString()+"}";
                sendResponse(out, 200, "application/json; charset=utf-8", resp.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if ("/profiles/update".equals(path)) {
            if (body.isEmpty()) { sendResponse(out, 400, "text/plain", "Missing body\n".getBytes(StandardCharsets.UTF_8)); return; }
            try {
                org.json.JSONObject jo = new org.json.JSONObject(body);
                if (jo.has("profile") && jo.get("profile") instanceof org.json.JSONObject) jo = jo.getJSONObject("profile");
                ConnectionProfile p = ConnectionProfile.fromJson(jo);
                ProfileManager pm = ProfileManager.getInstance(service);
                pm.updateProfile(p);
                sendResponse(out, 200, "application/json", ("{\"status\":\"ok\",\"profile\":"+p.toJson().toString()+"}").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                sendResponse(out, 400, "text/plain", ("Error: "+e.getMessage()).getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        if ("/profiles/delete".equals(path)) {
            String id = params.get("id");
            if (id == null && !body.isEmpty()) {
                try { org.json.JSONObject jo = new org.json.JSONObject(body); if (jo.has("id")) id = jo.getString("id"); } catch (Exception ignored) {}
            }
            if (id == null) { sendResponse(out, 400, "text/plain", "Missing id\n".getBytes(StandardCharsets.UTF_8)); return; }
            ProfileManager pm = ProfileManager.getInstance(service);
            boolean ok = pm.deleteProfile(id);
            if (ok) sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
            else sendResponse(out, 404, "text/plain", "Profile not found\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/profiles/connect".equals(path)) {
            String id = params.get("id");
            if (id == null) id = params.get("profileId");
            if (id == null && !body.isEmpty()) {
                try { org.json.JSONObject jo = new org.json.JSONObject(body); if (jo.has("id")) id = jo.getString("id"); else if (jo.has("profileId")) id = jo.getString("profileId"); } catch (Exception ignored) {}
            }
            if (id == null) { sendResponse(out, 400, "text/plain", "Missing id\n".getBytes(StandardCharsets.UTF_8)); return; }
            ProfileManager pm = ProfileManager.getInstance(service);
            ConnectionProfile p = pm.getProfile(id);
            if (p == null) { sendResponse(out, 404, "text/plain", "Profile not found\n".getBytes(StandardCharsets.UTF_8)); return; }
            TerminalSession s = service.createSessionForProfile(p);
            // Attach via handler to UI thread for terminalView? The service already handles switching; but we need to ensure UI thread attach
            if (debugTerminalView != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                TerminalSession sess = s;
                h.post(() -> {
                    // detach previous?
                    TerminalSession prev = debugTerminalView.getSession();
                    if (prev != null) prev.setListener(null);
                    sess.setListener(debugTerminalView);
                    debugTerminalView.attachSession(sess);
                });
            }
            String resp = "{\"status\":\"ok\",\"sessionId\":\""+s.getId()+"\",\"title\":\""+escapeJson(s.getDisplayTitle())+"\"}";
            sendResponse(out, 200, "application/json", resp.getBytes(StandardCharsets.UTF_8));
            return;
        }

        // --- SSH Agent API ---
        if (path.startsWith("/ssh-agent")) {
            com.tinyhack.ssh.ssh.SshAgentManager agent = com.tinyhack.ssh.ssh.SshAgentManager.getInstance(service);
            if ("/ssh-agent/status".equals(path)) {
                boolean running = agent.isAgentRunning();
                int count = 0;
                try { count = agent.getKeyCount(); } catch (Exception ignored) {}
                String json = "{\"running\":"+running+",\"locked\":"+agent.isLocked()+",\"socket\":\""+escapeJson(agent.getSocketPath())+"\",\"keyCount\":"+count+",\"autoStart\":"+agent.isAutoStart()+"}";
                sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/start".equals(path)) {
                boolean ok = agent.startAgent();
                String json = "{\"status\":\""+(ok?"ok":"failed")+"\",\"running\":"+agent.isAgentRunning()+"}";
                sendResponse(out, ok?200:500, "application/json", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/stop".equals(path)) {
                boolean was = agent.isAgentRunning();
                agent.stopAgent();
                String json = "{\"status\":\"ok\",\"wasRunning\":"+was+",\"running\":"+agent.isAgentRunning()+"}";
                sendResponse(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/list".equals(path)) {
                java.util.List<com.tinyhack.ssh.ssh.SshAgentManager.AgentKeyInfo> list = agent.listKeys();
                StringBuilder sb = new StringBuilder("[");
                for (int i=0;i<list.size();i++) {
                    if (i>0) sb.append(",");
                    com.tinyhack.ssh.ssh.SshAgentManager.AgentKeyInfo k = list.get(i);
                    sb.append("{\"bits\":\"").append(escapeJson(k.bits)).append("\",\"fingerprint\":\"").append(escapeJson(k.fingerprint)).append("\",\"comment\":\"").append(escapeJson(k.comment)).append("\",\"type\":\"").append(escapeJson(k.type)).append("\"}");
                }
                sb.append("]");
                sendResponse(out, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/add".equals(path)) {
                String key = params.get("key");
                if (key == null) key = params.get("keyName");
                if (key == null && !body.isEmpty()) {
                    try { org.json.JSONObject jo = new org.json.JSONObject(body); if (jo.has("key")) key = jo.getString("key"); else if (jo.has("keyName")) key = jo.getString("keyName"); } catch (Exception ignored) {}
                }
                if (agent.isLocked()) {
                    sendResponse(out, 403, "application/json", "{\"error\":\"agent locked; unlock first\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (key != null && !key.isEmpty()) {
                    String pathToKey = key;
                    if (!key.contains("/")) {
                        java.io.File f = new java.io.File(service.getFilesDir().getAbsolutePath() + "/home/.ssh/" + key);
                        if (f.exists()) pathToKey = f.getAbsolutePath();
                    }
                    boolean ok = agent.addKey(pathToKey, null);
                    String json = "{\"status\":\""+(ok?"ok":"failed")+"\",\"key\":\""+escapeJson(key)+"\"}";
                    sendResponse(out, ok?200:500, "application/json", json.getBytes(StandardCharsets.UTF_8));
                    return;
                } else {
                    sendResponse(out, 400, "text/plain", "Missing key param\n".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            if ("/ssh-agent/addAll".equals(path)) {
                if (agent.isLocked()) {
                    sendResponse(out, 403, "application/json", "{\"error\":\"agent locked; unlock first\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                boolean ok = agent.addAllKeys();
                String json = "{\"status\":\""+(ok?"ok":"failed")+"\",\"keyCount\":"+agent.getKeyCount()+"}";
                sendResponse(out, ok?200:500, "application/json", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/removeAll".equals(path) || "/ssh-agent/clear".equals(path)) {
                boolean ok = agent.removeAllKeys();
                String json = "{\"status\":\""+(ok?"ok":"failed")+"\"}";
                sendResponse(out, ok?200:500, "application/json", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/lock".equals(path)) {
                agent.setLocked(true);
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"locked\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/unlock".equals(path)) {
                agent.setLocked(false);
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\",\"locked\":false}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/env".equals(path)) {
                String env = agent.getAgentEnv();
                boolean running = agent.isAgentRunning();
                String json = "{\"running\":"+running+",\"env\":\""+escapeJson(env)+"\",\"socket\":\""+escapeJson(agent.getSocketPath())+"\"}";
                sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/ssh-agent/prompt".equals(path)) {
                String alias = params.get("alias");
                if (alias == null || alias.isEmpty()) alias = "debug-test";
                boolean confirmed = com.tinyhack.ssh.ssh.SshAgentServer.awaitBiometricAuth(service, alias);
                String json = "{\"status\":\""+(confirmed?"confirmed":"cancelled-or-timeout")+"\",\"alias\":\""+escapeJson(alias)+"\"}";
                sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                return;
            }
            sendResponse(out, 404, "text/plain", "Unknown ssh-agent endpoint\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/scroll".equals(path)) {
            String deltaStr = params.get("delta");
            String typeStr = params.get("type");
            if (session != null && deltaStr != null) {
                try {
                    int delta = Integer.parseInt(deltaStr);
                    int type = 2;
                    if (typeStr != null) type = Integer.parseInt(typeStr);
                    session.scroll(type, delta);
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    String text = session.getScreenText();
                    sendResponse(out, 200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
                    return;
                } catch (NumberFormatException ignored) {}
            }
            sendResponse(out, 400, "text/plain", "Missing delta param\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/selection".equals(path)) {
            if (session != null) {
                String action = params.get("action");
                if ("set".equals(action)) {
                    try {
                        int sc = Integer.parseInt(params.getOrDefault("sc", "0"));
                        int sr = Integer.parseInt(params.getOrDefault("sr", "0"));
                        int ec = Integer.parseInt(params.getOrDefault("ec", "5"));
                        int er = Integer.parseInt(params.getOrDefault("er", "0"));
                        session.setSelection(sc, sr, ec, er, false);
                        sendResponse(out, 200, "text/plain", "selection set\n".getBytes(StandardCharsets.UTF_8));
                        return;
                    } catch (Exception e) {
                        sendResponse(out, 400, "text/plain", ("error: "+e).getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                } else if ("clear".equals(action)) {
                    session.clearSelection();
                    sendResponse(out, 200, "text/plain", "cleared\n".getBytes(StandardCharsets.UTF_8));
                    return;
                } else if ("get".equals(action)) {
                    String txt = session.getSelectionText();
                    sendResponse(out, 200, "text/plain; charset=utf-8", txt.getBytes(StandardCharsets.UTF_8));
                    return;
                } else if ("has".equals(action)) {
                    boolean has = session.hasSelection();
                    sendResponse(out, 200, "text/plain", (has?"true":"false").getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            sendResponse(out, 400, "text/plain", "selection error\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/mouse".equals(path)) {
            if (session != null) {
                try {
                    float x = Float.parseFloat(params.getOrDefault("x", "10"));
                    float y = Float.parseFloat(params.getOrDefault("y", "10"));
                    int cw = Integer.parseInt(params.getOrDefault("cw", "10"));
                    int ch = Integer.parseInt(params.getOrDefault("ch", "20"));
                    int sw = Integer.parseInt(params.getOrDefault("sw", "1080"));
                    int sh = Integer.parseInt(params.getOrDefault("sh", "2000"));
                    session.sendMouseClick(x, y, cw, ch, sw, sh);
                    sendResponse(out, 200, "text/plain", "mouse clicked\n".getBytes(StandardCharsets.UTF_8));
                    return;
                } catch (Exception e) {
                    sendResponse(out, 400, "text/plain", ("mouse error: "+e).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            sendResponse(out, 400, "text/plain", "no session\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/mode".equals(path)) {
            // Selection is no longer a mode: long-press in the terminal starts
            // a word selection with draggable handles. Keep the endpoint for
            // compatibility: POST clears the selection, GET reports state.
            String m = params.get("m");
            if (m == null) m = body.trim();
            if (debugTerminalView != null && m != null && !m.isEmpty()) {
                try {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> debugTerminalView.clearSelection());
                    sendResponse(out, 200, "text/plain",
                            "selection cleared (long-press to select)\n".getBytes(StandardCharsets.UTF_8));
                    return;
                } catch (Exception e) {
                    sendResponse(out, 500, "text/plain", ("mode error: "+e).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            String current = (debugTerminalView != null && debugTerminalView.hasSelection()) ? "active" : "inactive";
            sendResponse(out, 200, "text/plain", ("selection: "+current+"\n").getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/copy".equals(path)) {
            if (debugTerminalView != null) {
                final boolean[] result = new boolean[1];
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                h.post(() -> {
                    try {
                        boolean ok = debugTerminalView.copySelection();
                        result[0] = ok;
                    } catch (Exception e) {
                        result[0] = false;
                    }
                    latch.countDown();
                });
                try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                String resp = result[0] ? "copied\n" : "copy failed / empty\n";
                sendResponse(out, 200, "text/plain", resp.getBytes(StandardCharsets.UTF_8));
                return;
            }
            sendResponse(out, 400, "text/plain", "no terminal view\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/clipboard".equals(path)) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) service.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    android.content.ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence txt = clip.getItemAt(0).getText();
                        String s = txt != null ? txt.toString() : "";
                        sendResponse(out, 200, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                }
                sendResponse(out, 200, "text/plain", "".getBytes(StandardCharsets.UTF_8));
                return;
            } catch (Exception e) {
                sendResponse(out, 500, "text/plain", ("clipboard error: "+e).getBytes(StandardCharsets.UTF_8));
                return;
            }
        }

        if ("/triggerMenu".equals(path)) {
            if (debugTerminalView != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> {
                    try {
                        java.lang.reflect.Method m = debugTerminalView.getClass().getDeclaredMethod("showThreeFingerMenu");
                        m.setAccessible(true);
                        m.invoke(debugTerminalView);
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "triggerMenu failed", e);
                    }
                });
                sendResponse(out, 200, "text/plain", "menu triggered\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            sendResponse(out, 400, "text/plain", "no view\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/escTest".equals(path)) {
            String type = params.get("type");
            if (type == null) type = body.trim().toLowerCase();
            if (debugTerminalView != null) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                if ("esc".equals(type)) {
                    h.post(() -> debugTerminalView.sendSpecialKey(com.tinyhack.ssh.terminal.KeyCodes.GHOSTTY_KEY_ESCAPE, 0, "\u001b"));
                    sendResponse(out, 200, "text/plain", "esc sent (will timeout in 350ms if no follow)\n".getBytes(StandardCharsets.UTF_8));
                    return;
                } else if ("esc0".equals(type) || "f10".equals(type)) {
                    h.post(() -> {
                        debugTerminalView.sendSpecialKey(com.tinyhack.ssh.terminal.KeyCodes.GHOSTTY_KEY_ESCAPE, 0, "\u001b");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            debugTerminalView.sendText("0");
                        }, 100);
                    });
                    sendResponse(out, 200, "text/plain", "esc+0 sent (should be F10 \\e0)\n".getBytes(StandardCharsets.UTF_8));
                    return;
                } else if ("escDelay".equals(type)) {
                    h.post(() -> {
                        debugTerminalView.sendSpecialKey(com.tinyhack.ssh.terminal.KeyCodes.GHOSTTY_KEY_ESCAPE, 0, "\u001b");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            debugTerminalView.sendText("0");
                        }, 600);
                    });
                    sendResponse(out, 200, "text/plain", "esc then 0 after 600ms (should be separate ESC and 0)\n".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            sendResponse(out, 400, "text/plain", "escTest requires view and type=esc|esc0|escDelay\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/font".equals(path)) {
            if (debugTerminalView != null) {
                String family = params.get("family");
                if (family == null && !body.isEmpty()) family = body.trim();
                if (family != null && !family.isEmpty()) {
                    String fam = family;
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final String[] result = new String[1];
                    h.post(() -> {
                        try {
                            debugTerminalView.setFontFamily(fam);
                            result[0] = "font set to " + fam + " (" + com.tinyhack.ssh.view.TerminalView.getFontDisplayName(fam) + ")\n";
                        } catch (Exception e) {
                            result[0] = "error: " + e.getMessage() + "\n";
                        }
                        latch.countDown();
                    });
                    try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                    String resp = result[0] != null ? result[0] : "font set\n";
                    sendResponse(out, 200, "text/plain; charset=utf-8", resp.getBytes(StandardCharsets.UTF_8));
                    return;
                } else {
                    String current = debugTerminalView.getCurrentFontFamily();
                    StringBuilder sb = new StringBuilder();
                    sb.append("current: ").append(current).append(" (").append(com.tinyhack.ssh.view.TerminalView.getFontDisplayName(current)).append(")\n");
                    sb.append("available:\n");
                    for (String f : com.tinyhack.ssh.view.TerminalView.FONT_FAMILIES) {
                        sb.append("  ").append(f).append(" : ").append(com.tinyhack.ssh.view.TerminalView.getFontDisplayName(f));
                        if (f.equals(current)) sb.append(" *");
                        sb.append("\n");
                    }
                    sendResponse(out, 200, "text/plain; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            sendResponse(out, 400, "text/plain", "no terminal view\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/hyperlink".equals(path)) {
            String colStr = params.get("col");
            String rowStr = params.get("row");
            if (colStr == null) colStr = params.get("x");
            if (rowStr == null) rowStr = params.get("y");
            if (session != null && colStr != null && rowStr != null) {
                try {
                    int col = Integer.parseInt(colStr);
                    int row = Integer.parseInt(rowStr);
                    String uri = session.getHyperlinkUri(col, row);
                    String json = "{\"col\":" + col + ",\"row\":" + row + ",\"uri\":" + (uri != null ? ("\"" + escapeJson(uri) + "\"") : "null") + "}";
                    sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                    return;
                } catch (NumberFormatException e) {
                    sendResponse(out, 400, "text/plain", "Invalid col/row\n".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            sendResponse(out, 400, "text/plain", "Missing col/row or no session\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/openlink".equals(path)) {
            String colStr = params.get("col");
            String rowStr = params.get("row");
            if (colStr == null) colStr = params.get("x");
            if (rowStr == null) rowStr = params.get("y");
            if (session != null && colStr != null && rowStr != null) {
                try {
                    int col = Integer.parseInt(colStr);
                    int row = Integer.parseInt(rowStr);
                    String uri = session.getHyperlinkUri(col, row);
                    if (uri != null && !uri.isEmpty()) {
                        try {
                            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri));
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            service.startActivity(intent);
                            String json = "{\"status\":\"opened\",\"uri\":\"" + escapeJson(uri) + "\"}";
                            sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            String json = "{\"status\":\"failed\",\"uri\":\"" + escapeJson(uri) + "\",\"error\":\"" + escapeJson(e.toString()) + "\"}";
                            sendResponse(out, 500, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        sendResponse(out, 404, "application/json", "{\"status\":\"no link\"}".getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                } catch (NumberFormatException e) {
                    sendResponse(out, 400, "text/plain", "Invalid col/row\n".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            sendResponse(out, 400, "text/plain", "Missing col/row or no session\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/desktop-notify".equals(path) || "/notify".equals(path)) {
            String tParam = params.get("title");
            if (tParam == null) tParam = params.get("t");
            String bParam = params.get("body");
            if (bParam == null) bParam = params.get("b");
            if (bParam == null) bParam = params.get("msg");
            if (tParam == null && bParam == null) {
                tParam = "Test";
                bParam = "Hello via /desktop-notify";
            }
            if (bParam == null) bParam = "";
            if (tParam == null) tParam = "Terminal";
            com.tinyhack.ssh.util.DesktopNotificationHelper.show(tParam, bParam);
            String json = "{\"status\":\"ok\",\"title\":\"" + escapeJson(tParam) + "\",\"body\":\"" + escapeJson(bParam) + "\"}";
            sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/sync".equals(path)) {
            boolean active = session != null && session.isSyncOutputActive();
            String json = "{\"syncActive\":" + active + "}";
            sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/semantic".equals(path)) {
            String colStr = params.get("col");
            String rowStr = params.get("row");
            if (session != null && colStr != null && rowStr != null) {
                try {
                    int col = Integer.parseInt(colStr);
                    int row = Integer.parseInt(rowStr);
                    // Use RenderFrame semantic arrays if available (viewport only)
                    com.tinyhack.ssh.terminal.RenderFrame rf = new com.tinyhack.ssh.terminal.RenderFrame();
                    session.updateRenderFrame(rf);
                    int cols = rf.cols;
                    int rows = rf.rows;
                    if (col >= 0 && col < cols && row >= 0 && row < rows) {
                        int idx = row * cols + col;
                        int cellSem = rf.cellSemantic[idx];
                        int rowSem = rf.rowSemanticPrompt[row];
                        String json = "{\"col\":" + col + ",\"row\":" + row + ",\"cellSemantic\":" + cellSem + ",\"rowSemantic\":" + rowSem + "}";
                        sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                } catch (Exception ignored) {}
            }
            sendResponse(out, 400, "text/plain", "Missing col/row or out of bounds\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        if ("/promptPrev".equals(path) || "/scrollPrev".equals(path) || "/prevPrompt".equals(path)) {
            boolean ok = session != null && session.scrollToPreviousPrompt();
            if (ok && debugTerminalView != null) debugTerminalView.postInvalidate();
            String json = "{\"ok\":" + ok + "}";
            sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/promptNext".equals(path) || "/scrollNext".equals(path) || "/nextPrompt".equals(path)) {
            boolean ok = session != null && session.scrollToNextPrompt();
            if (ok && debugTerminalView != null) debugTerminalView.postInvalidate();
            String json = "{\"ok\":" + ok + "}";
            sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/copyOutput".equals(path) || "/lastOutput".equals(path)) {
            String outText = session != null ? session.getLastCommandOutput() : "";
            String json = "{\"text\":\"" + escapeJson(outText) + "\"}";
            sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("/promptRows".equals(path)) {
            if (session == null) { sendResponse(out, 400, "text/plain", "no session\n".getBytes(StandardCharsets.UTF_8)); return; }
            int[] fullPrompts = session.getPromptRows();
            com.tinyhack.ssh.terminal.RenderFrame rf = new com.tinyhack.ssh.terminal.RenderFrame();
            session.updateRenderFrame(rf);
            StringBuilder sb = new StringBuilder("{\"fullPrompts\":[");
            for (int i=0;i<fullPrompts.length;i++) { if(i>0) sb.append(","); sb.append(fullPrompts[i]); }
            sb.append("],\"viewportPrompts\":[");
            boolean first=true;
            for (int r=0;r<rf.rows;r++) if (rf.rowSemanticPrompt[r]==1) { if(!first) sb.append(","); sb.append(r); first=false; }
            sb.append("],\"totalRows\":").append(rf.rows).append(",\"cols\":").append(rf.cols).append("}");
            // Also include scrollbar if available via native
            sendResponse(out, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        // Default: HTML Web Terminal View
        String html = generateWebTerminalHtml(session);
        sendResponse(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    // Legacy wrapper
    private void routeRequest(String method, String path, Map<String,String> params, String body, OutputStream out) throws IOException {
        routeRequest(method, path, params, body, out, new HashMap<>());
    }

    private void handleKeyInput(TerminalSession session, String keyName) {
        switch (keyName) {
            case "ENTER":
            case "RETURN":
                session.writeKey(KeyCodes.GHOSTTY_KEY_ENTER, KeyCodes.ACTION_PRESS, 0, "\r");
                break;
            case "ESC":
            case "ESCAPE":
                session.writeKey(KeyCodes.GHOSTTY_KEY_ESCAPE, KeyCodes.ACTION_PRESS, 0, "\u001b");
                break;
            case "TAB":
                session.writeKey(KeyCodes.GHOSTTY_KEY_TAB, KeyCodes.ACTION_PRESS, 0, "\t");
                break;
            case "BACKSPACE":
                session.writeKey(KeyCodes.GHOSTTY_KEY_BACKSPACE, KeyCodes.ACTION_PRESS, 0, "\b");
                break;
            case "UP":
            case "ARROW_UP":
                session.writeKey(KeyCodes.GHOSTTY_KEY_ARROW_UP, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "DOWN":
            case "ARROW_DOWN":
                session.writeKey(KeyCodes.GHOSTTY_KEY_ARROW_DOWN, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "LEFT":
            case "ARROW_LEFT":
                session.writeKey(KeyCodes.GHOSTTY_KEY_ARROW_LEFT, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "RIGHT":
            case "ARROW_RIGHT":
                session.writeKey(KeyCodes.GHOSTTY_KEY_ARROW_RIGHT, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "CTRL_C":
            case "CTRL+C":
                session.write(new byte[]{0x03});
                break;
            case "CTRL_D":
            case "CTRL+D":
                session.write(new byte[]{0x04});
                break;
            case "CTRL_Z":
            case "CTRL+Z":
                session.write(new byte[]{0x1a});
                break;
            case "CTRL_L":
            case "CTRL+L":
                session.write(new byte[]{0x0c});
                break;
            case "F1":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F1, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F2":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F2, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F3":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F3, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F4":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F4, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F5":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F5, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F6":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F6, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F7":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F7, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F8":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F8, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F9":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F9, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F10":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F10, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F11":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F11, KeyCodes.ACTION_PRESS, 0, null);
                break;
            case "F12":
                session.writeKey(KeyCodes.GHOSTTY_KEY_F12, KeyCodes.ACTION_PRESS, 0, null);
                break;
            default:
                session.write(keyName);
                break;
        }
    }

    private String generateWebTerminalHtml(TerminalSession session) {
        String screenText = session != null ? session.getScreenText() : "(no active session)";
        String sessionTitle = session != null ? session.getDisplayTitle() : "Tinyhack SSH";

        // Build sessions list html
        StringBuilder sessionsHtml = new StringBuilder();
        try {
            List<TerminalSession> list = service.getSessions();
            int cur = service.getCurrentSessionIndex();
            for (int i=0;i<list.size();i++) {
                TerminalSession s = list.get(i);
                String t = escapeHtml(s.getDisplayTitle());
                sessionsHtml.append("<div style='padding:4px 8px;margin:2px 0;background:").append(i==cur?"#2A3A5A":"#222").append(";border-radius:4px;'>")
                    .append(i==cur?"▶ ":"  ").append(t).append(" <small style='color:#888;'>#").append(i).append(" ").append(s.isRunning()?"running":"closed").append("</small>")
                    .append("</div>");
            }
        } catch (Exception ignored) {}

        StringBuilder profilesHtml = new StringBuilder();
        try {
            ProfileManager pm = ProfileManager.getInstance(service);
            List<ConnectionProfile> plist = pm.loadProfiles();
            for (ConnectionProfile p : plist) {
                profilesHtml.append("<div style='padding:4px 8px;margin:2px 0;background:#1E1E1E;border-radius:4px;'>")
                    .append(escapeHtml(p.getName())).append(" <small style='color:#7DA9FF;'>").append(p.getTypeLabel()).append("</small>")
                    .append("<br><small style='color:#888;'>").append(escapeHtml(p.getDisplaySubtitle())).append("</small>")
                    .append("</div>");
            }
        } catch (Exception ignored) {}

        StringBuilder agentHtml = new StringBuilder();
        try {
            com.tinyhack.ssh.ssh.SshAgentManager am = com.tinyhack.ssh.ssh.SshAgentManager.getInstance(service);
            boolean running = am.isAgentRunning();
            int kc = 0; try { kc = am.getKeyCount(); } catch (Exception ignored) {}
            agentHtml.append("<div style='padding:8px;background:").append(running?"#1E3A2A":"#3A1E1E").append(";border-radius:4px;'>")
                .append("<b style='color:").append(running?"#7DFF9A":"#FF7D7D").append("'>").append(running?"● Running":"○ Stopped").append("</b>")
                .append(" <small style='color:#888;'>").append(kc).append(" keys</small>")
                .append(running? " <small style='color:#888;'>"+escapeHtml(am.getSocketPath())+"</small>" : "")
                .append("<br><small style='color:").append(am.isLocked()?"#FFB86C":"#7DA9FF").append("'>").append(am.isLocked()?"🔒 Locked":"🔓 Unlocked").append("</small>")
                .append("</div>");
            if (running) {
                java.util.List<com.tinyhack.ssh.ssh.SshAgentManager.AgentKeyInfo> aks = am.listKeys();
                for (com.tinyhack.ssh.ssh.SshAgentManager.AgentKeyInfo ak : aks) {
                    agentHtml.append("<div style='padding:4px 8px;margin:2px 0;background:#1E1E1E;border-radius:4px;'>")
                        .append("<small style='color:#7DA9FF;'>").append(escapeHtml(ak.type)).append("</small> ")
                        .append("<span style='font-size:11px;color:#E0E0E0;'>").append(escapeHtml(ak.fingerprint)).append("</span>")
                        .append("<br><small style='color:#888;'>").append(escapeHtml(ak.comment)).append("</small></div>");
                }
                if (aks.isEmpty()) agentHtml.append("<div style='padding:4px;color:#666;'><small>No keys loaded</small></div>");
            }
        } catch (Exception ignored) {
            agentHtml.append("<div style='color:#888;'><small>agent error</small></div>");
        }

        return "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "  <meta charset='utf-8'>\n" +
            "  <meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
            "  <title>Tinyhack SSH Android Debug - " + escapeHtml(sessionTitle) + "</title>\n" +
            "  <style>\n" +
            "    body { background-color: #121212; color: #E0E0E0; font-family: monospace; margin: 0; padding: 16px; }\n" +
            "    header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #333; padding-bottom: 8px; margin-bottom: 12px; }\n" +
            "    h1 { margin: 0; font-size: 18px; color: #4D90FE; }\n" +
            "    #terminal { background-color: #181818; padding: 12px; border-radius: 6px; white-space: pre; font-size: 14px; line-height: 1.4; overflow-x: auto; min-height: 300px; border: 1px solid #282828; }\n" +
            "    .toolbar { display: flex; gap: 6px; margin: 12px 0; flex-wrap: wrap; }\n" +
            "    button, input[type=text] { background: #282828; color: #EEE; border: 1px solid #444; border-radius: 4px; padding: 8px 12px; font-family: monospace; font-size: 13px; }\n" +
            "    button:hover { background: #383838; cursor: pointer; }\n" +
            "    button:active { background: #4D90FE; }\n" +
            "    .input-bar { display: flex; gap: 8px; margin-top: 12px; }\n" +
            "    .input-bar input { flex: 1; font-size: 14px; }\n" +
            "    .panel { display: flex; gap: 12px; margin-top: 16px; flex-wrap: wrap; }\n" +
            "    .panel > div { flex: 1; min-width: 280px; background:#181818; padding:12px; border-radius:6px; border:1px solid #282828; }\n" +
            "    h3 { margin: 0 0 8px 0; color: #4D90FE; font-size: 14px; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <header>\n" +
            "    <h1>👻 Tinyhack SSH Android VT Debug Console</h1>\n" +
            "    <div>\n" +
            "      <label><input type='checkbox' id='autoRefresh' checked> Auto Refresh (500ms)</label>\n" +
            "    </div>\n" +
            "  </header>\n" +
            "  <div class='toolbar'>\n" +
            "    <button onclick=\"sendKey('ESC')\">ESC</button>\n" +
            "    <button onclick=\"sendKey('TAB')\">TAB</button>\n" +
            "    <button onclick=\"sendKey('CTRL_C')\">Ctrl+C</button>\n" +
            "    <button onclick=\"sendKey('CTRL_D')\">Ctrl+D</button>\n" +
            "    <button onclick=\"sendKey('UP')\">↑</button>\n" +
            "    <button onclick=\"sendKey('DOWN')\">↓</button>\n" +
            "    <button onclick=\"sendKey('ENTER')\">↵ Enter</button>\n" +
            "    <button onclick=\"fetchText()\">🔄 Refresh</button>\n" +
            "    <button onclick=\"fetchSessions()\">Sessions</button>\n" +
            "    <button onclick=\"fetchProfiles()\">Profiles</button>\n" +
            "  </div>\n" +
            "  <div id='terminal'>" + escapeHtml(screenText) + "</div>\n" +
            "  <form class='input-bar' onsubmit='sendInput(event)'>\n" +
            "    <input type='text' id='cmdInput' placeholder='Type shell command and press Enter...' autofocus autocomplete='off'>\n" +
            "    <button type='submit'>Send</button>\n" +
            "  </form>\n" +
            "  <div class='panel'>\n" +
            "    <div><h3>Sessions</h3><div id='sessions'>" + sessionsHtml.toString() + "</div><button onclick='createSession()'>New Local Session</button></div>\n" +
            "    <div><h3>Profiles</h3><div id='profiles'>" + profilesHtml.toString() + "</div></div>\n" +
            "    <div><h3>SSH Agent</h3><div id='agent'>" + agentHtml.toString() + "</div><div style='margin-top:8px;display:flex;gap:6px;flex-wrap:wrap;'><button onclick=\"fetch('/ssh-agent/start'+Q).then(()=>location.reload())\">Start</button><button onclick=\"fetch('/ssh-agent/stop'+Q).then(()=>location.reload())\">Stop</button><button onclick=\"fetch('/ssh-agent/addAll'+Q,{method:'POST'}).then(()=>location.reload())\">Add All</button><button onclick=\"fetch('/ssh-agent/removeAll'+Q,{method:'POST'}).then(()=>location.reload())\">Clear</button></div></div>\n" +
            "  </div>\n" +
            "  <script>\n" +
            "    const TOKEN = '" + escapeHtml(authToken == null ? "" : authToken) + "';\n" +
            "    const Q = '?token=' + TOKEN;\n" +
            "    async function fetchText() {\n" +
            "      try {\n" +
            "        const res = await fetch('/text' + Q);\n" +
            "        const text = await res.text();\n" +
            "        document.getElementById('terminal').innerText = text;\n" +
            "      } catch (e) {}\n" +
            "    }\n" +
            "    async function sendInput(e) {\n" +
            "      e.preventDefault();\n" +
            "      const input = document.getElementById('cmdInput');\n" +
            "      const cmd = input.value + '\\n';\n" +
            "      input.value = '';\n" +
            "      await fetch('/input' + Q, { method: 'POST', body: cmd });\n" +
            "      await fetchText();\n" +
            "    }\n" +
            "    async function sendKey(k) {\n" +
            "      await fetch('/key' + Q + '&k=' + encodeURIComponent(k), { method: 'POST' });\n" +
            "      await fetchText();\n" +
            "    }\n" +
            "    async function fetchSessions(){\n" +
            "      const r=await fetch('/sessions' + Q); const j=await r.json(); alert(JSON.stringify(j,null,2));\n" +
            "    }\n" +
            "    async function fetchProfiles(){\n" +
            "      const r=await fetch('/profiles' + Q); const j=await r.json(); alert(JSON.stringify(j,null,2));\n" +
            "    }\n" +
            "    async function createSession(){ await fetch('/sessions/new' + Q,{method:'POST'}); fetchText(); location.reload(); }\n" +
            "    setInterval(() => {\n" +
            "      if (document.getElementById('autoRefresh').checked) fetchText();\n" +
            "    }, 500);\n" +
            "  </script>\n" +
            "</body>\n" +
            "</html>\n";
    }

    private void sendResponse(OutputStream out, int statusCode, String contentType, byte[] data) throws IOException {
        String statusText = statusCode == 200 ? "OK" : (statusCode == 204 ? "No Content" : statusCode==401?"Unauthorized": statusCode==404?"Not Found": "Error");
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Content-Length: " + data.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
            "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        if (data.length > 0) {
            out.write(data);
        }
        out.flush();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
