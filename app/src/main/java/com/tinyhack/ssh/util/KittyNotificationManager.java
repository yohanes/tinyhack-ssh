package com.tinyhack.ssh.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.tinyhack.ssh.MainActivity;
import com.tinyhack.ssh.R;
import com.tinyhack.ssh.service.TerminalService;
import com.tinyhack.ssh.session.TerminalSession;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** OSC 99 state and Android effects. All mutable state belongs to the main looper. */
public final class KittyNotificationManager implements TerminalSession.NotificationListener {
    public static final String TAG_PREFIX = "tinyhack_osc99:";
    private static final String ACTION = "com.tinyhack.ssh.NOTIFICATION_EVENT";
    private static final String CAPABILITIES = "p=title,body,?,close,alive:a=focus,report:c=1:"
            + "o=always,unfocused,invisible:s=system,silent:u=0,1,2:w=1";
    private static final int MAX_TEXT_BYTES = 16384;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_PER_SESSION = 8;
    private static final long ASSEMBLY_TTL_MS = 30000;
    private static WeakReference<KittyNotificationManager> live = new WeakReference<>(null);

    private final TerminalService service;
    private final NotificationManager platform;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Assembly> pending = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> active = new LinkedHashMap<>();
    private final Map<String, Entry> tokens = new HashMap<>();
    private final Map<String, Rate> rates = new HashMap<>();
    private int nextId = 3000;
    private boolean destroyed;
    private boolean sweepScheduled;

    public KittyNotificationManager(TerminalService service) {
        this.service = service;
        platform = service.getSystemService(NotificationManager.class);
        live = new WeakReference<>(this);
        if (platform != null) {
            int[] levels = {NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_DEFAULT,
                    NotificationManager.IMPORTANCE_HIGH};
            String[] names = {"Terminal notifications: low", "Terminal notifications", "Terminal notifications: urgent"};
            for (int i = 0; i < levels.length; i++) {
                NotificationChannel channel = new NotificationChannel(channel(i), names[i], levels[i]);
                channel.setDescription("Notifications requested by enabled terminal profiles (OSC 99)");
                platform.createNotificationChannel(channel);
            }
            // Session IDs and reply targets cannot survive process death.
            for (android.service.notification.StatusBarNotification n : platform.getActiveNotifications()) {
                if (n.getTag() != null && n.getTag().startsWith(TAG_PREFIX)) platform.cancel(n.getTag(), n.getId());
            }
        }
    }

    private static String channel(int urgency) { return "tinyhack_terminal_notifications_" + urgency; }

    @Override
    public void onNotification(TerminalSession session, byte[] metadata, byte[] payload) {
        if (destroyed || !session.isRunning() || service.getSessionById(session.getId()) != session) return;
        try {
            Map<String, String> options = metadata(metadata);
            if (options == null) return;
            String id = options.get("i");
            if (id != null && (id.isEmpty() || id.length() > 128 || !id.matches("[a-zA-Z0-9_+.-]+"))) return;
            String type = options.getOrDefault("p", "title");
            switch (type) {
                case "?": reply(session, id, "p=?", CAPABILITIES); return;
                case "alive":
                    reconcile();
                    ArrayList<String> ids = new ArrayList<>();
                    for (Entry e : active.values()) if (e.session == session && e.id != null) ids.add(e.id);
                    reply(session, id, "p=alive", String.join(",", ids));
                    return;
                case "close":
                    if (id != null) {
                        String key = scope(session, id);
                        pending.remove(key);
                        close(active.get(key), true);
                    }
                    return;
                case "title": case "body": break;
                default: return; // Icons and buttons are not advertised.
            }
            sweepPending();
            String key = scope(session, id);
            Assembly a = pending.get(key);
            if (a == null) {
                trimPending(session);
                a = new Assembly(session, id);
                pending.put(key, a);
            }
            try {
                if (a.failed) {
                    if (!"0".equals(options.get("d"))) pending.remove(key);
                    return;
                }
                a.options(options);
                TextPart part = type.equals("body") ? a.body : a.title;
                if ("1".equals(options.get("e"))) part.encoded(payload);
                else part.plain(payload);
                if (a.title.bytes.size() + a.body.bytes.size() > MAX_TEXT_BYTES) throw new IllegalArgumentException();
                if ("0".equals(options.get("d"))) { scheduleSweep(); return; }
                pending.remove(key);
                a.title.finish();
                a.body.finish();
                if (a.title.bytes.size() + a.body.bytes.size() > MAX_TEXT_BYTES) return;
                show(a, text(a.title.bytes.toByteArray()), text(a.body.bytes.toByteArray()));
            } catch (IllegalArgumentException | CharacterCodingException ignored) {
                a.failed = true;
                // Remember a rejected transaction until its final chunk so a
                // suffix cannot accidentally become a new notification.
                if (!"0".equals(options.get("d"))) pending.remove(key);
                else { pending.put(key, a); scheduleSweep(); }
            }
        } catch (RuntimeException e) {
            SafeLog.w("KittyNotifications", "Unable to handle notification request", e);
        }
    }

    /** Pure parsing on the PTY thread: DA1 must not overtake the support reply. */
    public static byte[] capabilityReply(byte[] bytes) {
        Map<String, String> options = metadata(bytes);
        if (options == null || !"?".equals(options.get("p"))) return null;
        String id = options.get("i");
        if (id != null && (id.isEmpty() || id.length() > 128 || !id.matches("[a-zA-Z0-9_+.-]+"))) return new byte[0];
        return ("\u001b]99;i=" + (id == null ? "0" : id) + ":p=?;" + CAPABILITIES + "\u001b\\")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> metadata(byte[] bytes) {
        if (bytes.length > 2048) return null;
        for (byte b : bytes) if (b < 0 || b < 0x20 || b > 0x7e) return null;
        Map<String, String> result = new HashMap<>();
        for (String item : new String(bytes, StandardCharsets.US_ASCII).split(":")) {
            if (item.length() < 2 || item.charAt(1) != '=') continue;
            char key = item.charAt(0);
            if ((key < 'a' || key > 'z') && (key < 'A' || key > 'Z')) continue;
            result.putIfAbsent(String.valueOf(key), item.substring(2));
        }
        return result;
    }

    private static String text(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Decoder supports both independently encoded chunks and fragments of one encoded stream. */
    private static final class TextPart {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final StringBuilder quartet = new StringBuilder(4);
        void plain(byte[] data) throws CharacterCodingException {
            if (quartet.length() != 0) throw new IllegalArgumentException();
            String value = text(data);
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch < 32 || (ch >= 127 && ch <= 159)) throw new IllegalArgumentException();
            }
            append(data);
        }
        void encoded(byte[] data) {
            for (byte b : data) {
                char c = (char) (b & 255);
                if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                        && !(c >= '0' && c <= '9') && c != '+' && c != '/' && c != '=') throw new IllegalArgumentException();
                quartet.append(c);
                if (quartet.length() == 4) {
                    String group = quartet.toString();
                    if (!group.matches("[A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==")) throw new IllegalArgumentException();
                    append(Base64.decode(group, Base64.NO_WRAP));
                    quartet.setLength(0);
                }
            }
        }
        void finish() {
            if (quartet.length() == 0) return;
            if (!quartet.toString().matches("[A-Za-z0-9+/]{2,3}")) throw new IllegalArgumentException();
            append(Base64.decode(quartet.toString(), Base64.NO_WRAP));
            quartet.setLength(0);
        }
        void append(byte[] data) {
            if (bytes.size() + data.length > MAX_TEXT_BYTES) throw new IllegalArgumentException();
            bytes.write(data, 0, data.length);
        }
    }

    private static final class Assembly {
        final TerminalSession session;
        final String id;
        final long created = SystemClock.elapsedRealtime();
        final TextPart title = new TextPart(), body = new TextPart();
        boolean focus = true, report, closeReport, silent, failed;
        String occasion = "always";
        int urgency = 1;
        long expires = -1;
        Assembly(TerminalSession session, String id) { this.session = session; this.id = id; }
        void options(Map<String, String> o) throws CharacterCodingException {
            if (o.containsKey("a")) {
                focus = true; report = false;
                for (String action : o.get("a").split(",")) {
                    switch (action) {
                        case "focus": focus = true; break;
                        case "-focus": focus = false; break;
                        case "report": report = true; break;
                        case "-report": report = false; break;
                    }
                }
            }
            if (o.containsKey("c")) closeReport = "1".equals(o.get("c"));
            if (o.containsKey("o")) {
                String value = o.get("o");
                occasion = value.equals("unfocused") || value.equals("invisible") ? value : "always";
            }
            if (o.containsKey("u")) {
                String value = o.get("u");
                urgency = value.equals("0") ? 0 : value.equals("2") ? 2 : 1;
            }
            if (o.containsKey("s")) {
                String encoded = o.get("s");
                if (!encoded.matches("[A-Za-z0-9+/]*={0,2}")) throw new IllegalArgumentException();
                silent = text(Base64.decode(encoded, Base64.NO_WRAP)).equals("silent");
            }
            if (o.containsKey("w")) {
                try {
                    String value = o.get("w");
                    if (value.matches("-?[0-9]+")) {
                        long n = Long.parseLong(value);
                        if (n >= -1 && n <= Integer.MAX_VALUE) expires = n;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    private static final class Rate { long start; int count; }

    private void show(Assembly a, String title, String body) {
        if (title.isEmpty() && body.isEmpty()) return;
        if (a.occasion.equals("unfocused") && service.isTerminalFocused(a.session)) return;
        if (a.occasion.equals("invisible") && service.isTerminalVisible(a.session)) return;
        if (platform == null || !NotificationManagerCompat.from(service).areNotificationsEnabled()) return;
        NotificationChannel channel = platform.getNotificationChannel(channel(a.urgency));
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        Rate rate = rates.computeIfAbsent(a.session.getId(), id -> new Rate());
        long now = SystemClock.elapsedRealtime();
        if (now - rate.start >= 10000) { rate.start = now; rate.count = 0; }
        if (rate.count++ >= 10) return;
        reconcile();
        String key = a.id == null ? scope(a.session, UUID.randomUUID().toString()) : scope(a.session, a.id);
        Entry previous = active.get(key);
        if (previous == null) trimActive(a.session);
        Entry e = new Entry(a, key, previous == null ? nextId++ : previous.androidId);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(service, channel(a.urgency))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(shorten(title.isEmpty() ? body : title, 4096))
                .setContentText(shorten(body, 8192))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(shorten(body, 8192)))
                .setSubText(shorten(a.session.getDisplayTitle(), 128))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(false)
                .setOnlyAlertOnce(previous != null)
                .setSilent(a.silent)
                .setContentIntent(pendingIntent(e, "activate", a.focus))
                .setDeleteIntent(pendingIntent(e, "dismiss", false));
        if (a.expires > 0) builder.setTimeoutAfter(a.expires);
        try {
            platform.notify(e.tag, e.androidId, builder.build());
        } catch (RuntimeException exception) {
            SafeLog.w("KittyNotifications", "Unable to post notification", exception);
            return;
        }
        if (previous != null) forget(previous); // Replacement does not report the old notification closing.
        active.put(key, e);
        tokens.put(e.token, e);
        if (a.expires > 0) {
            e.expiry = () -> { if (tokens.get(e.token) == e) close(e, true); };
            main.postDelayed(e.expiry, a.expires);
        }
    }

    private static String shorten(String value, int limit) {
        if (value == null) return "";
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end) + "…";
    }

    private static final class Entry {
        final TerminalSession session;
        final String id, key, tag, token = UUID.randomUUID().toString();
        final long posted = SystemClock.elapsedRealtime();
        final int androidId;
        final boolean focus, report, closeReport;
        Runnable expiry;
        Entry(Assembly a, String key, int androidId) {
            session = a.session; id = a.id; this.key = key; this.androidId = androidId;
            tag = TAG_PREFIX + key;
            focus = a.focus; report = a.report; closeReport = a.closeReport;
        }
    }

    private PendingIntent pendingIntent(Entry e, String event, boolean launchActivity) {
        Intent intent = new Intent(service, launchActivity ? MainActivity.class : KittyNotificationReceiver.class);
        intent.setAction(ACTION);
        intent.setData(new Uri.Builder().scheme("tinyhack-notify").authority(event).appendPath(e.token).build());
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (launchActivity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return PendingIntent.getActivity(service, e.androidId, intent, flags);
        }
        return PendingIntent.getBroadcast(service, e.androidId, intent, flags);
    }

    /** Validates an opaque, per-generation token; exported activity intents cannot address sessions directly. */
    public String handleIntent(Intent intent) {
        if (destroyed || intent == null || !ACTION.equals(intent.getAction())) return null;
        Uri uri = intent.getData();
        if (uri == null || !"tinyhack-notify".equals(uri.getScheme())) return null;
        Entry e = tokens.get(uri.getLastPathSegment());
        if (e == null) return null;
        String event = uri.getAuthority();
        if ("activate".equals(event)) {
            if (e.report) reply(e.session, e.id, null, "");
            String focus = e.focus ? e.session.getId() : null;
            close(e, true);
            return focus;
        }
        if ("dismiss".equals(event)) close(e, true);
        return null;
    }

    static void receive(Intent intent) {
        KittyNotificationManager manager = live.get();
        if (manager != null) manager.handleIntent(intent);
    }

    private static String scope(TerminalSession session, String id) { return session.getId() + ":" + (id == null ? "" : id); }

    private static void reply(TerminalSession session, String id, String operation, String payload) {
        if (!session.isRunning()) return;
        session.write("\u001b]99;i=" + (id == null ? "0" : id)
                + (operation == null ? "" : ":" + operation) + ";" + payload + "\u001b\\");
    }

    private void forget(Entry e) {
        active.remove(e.key);
        tokens.remove(e.token);
        if (e.expiry != null) main.removeCallbacks(e.expiry);
    }

    private void close(Entry e, boolean report) {
        if (e == null || tokens.get(e.token) != e) return;
        forget(e);
        if (platform != null) platform.cancel(e.tag, e.androidId);
        if (report && e.closeReport) reply(e.session, e.id, "p=close", "");
    }

    private void reconcile() {
        if (platform == null || active.isEmpty()) return;
        Set<String> shown = new HashSet<>();
        for (android.service.notification.StatusBarNotification n : platform.getActiveNotifications()) {
            if (n.getTag() != null) shown.add(n.getTag());
        }
        for (Entry e : new ArrayList<>(active.values())) if (!shown.contains(e.tag) && SystemClock.elapsedRealtime() - e.posted > 1000) close(e, true);
    }

    private void trimActive(TerminalSession session) {
        Entry oldest = null;
        int count = 0;
        for (Entry e : active.values()) if (e.session == session) { if (oldest == null) oldest = e; count++; }
        if (count >= MAX_PER_SESSION) close(oldest, true);
        if (active.size() >= MAX_ENTRIES) close(active.values().iterator().next(), true);
    }

    private void trimPending(TerminalSession session) {
        String oldest = null;
        int count = 0;
        for (Map.Entry<String, Assembly> e : pending.entrySet()) if (e.getValue().session == session) {
            if (oldest == null) oldest = e.getKey(); count++;
        }
        if (count >= MAX_PER_SESSION) pending.remove(oldest);
        if (pending.size() >= MAX_ENTRIES) pending.remove(pending.keySet().iterator().next());
    }

    private void sweepPending() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Assembly> it = pending.values().iterator();
        while (it.hasNext()) if (now - it.next().created > ASSEMBLY_TTL_MS) it.remove();
    }

    private void scheduleSweep() {
        if (sweepScheduled) return;
        sweepScheduled = true;
        main.postDelayed(() -> {
            sweepScheduled = false;
            if (destroyed) return;
            sweepPending();
            if (!pending.isEmpty()) scheduleSweep();
        }, ASSEMBLY_TTL_MS);
    }

    @Override
    public void onSessionClosed(TerminalSession session) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> onSessionClosed(session)); return; }
        pending.values().removeIf(a -> a.session == session);
        for (Entry e : new ArrayList<>(active.values())) if (e.session == session) close(e, false);
        rates.remove(session.getId());
    }

    public void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        for (Entry e : new ArrayList<>(active.values())) close(e, false);
        pending.clear(); rates.clear();
        if (live.get() == this) live.clear();
    }
}
