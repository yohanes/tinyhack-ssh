package com.tinyhack.ssh.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages persistence of ConnectionProfile list.
 * Stores JSON array both in SharedPreferences and as fallback file profiles.json
 * for durability.
 */
public class ProfileManager {
    private static final String TAG = "ProfileManager";
    private static final String PREFS = "ghostty_profiles";
    private static final String KEY_PROFILES_JSON = "profiles_json";
    private static final String FILE_NAME = "profiles.json";

    private static ProfileManager sInstance;

    private final Context appContext;

    private ProfileManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static synchronized ProfileManager getInstance(Context ctx) {
        if (sInstance == null) {
            sInstance = new ProfileManager(ctx);
        }
        return sInstance;
    }

    public synchronized List<ConnectionProfile> loadProfiles() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_PROFILES_JSON, null);
        if (json == null || json.isEmpty()) {
            // Try file fallback
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            if (f.exists()) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read profiles file", e);
                }
            }
        }
        if (json == null || json.isEmpty()) {
            // Return default profiles for new install
            return createDefaultProfiles();
        }
        try {
            JSONArray arr = new JSONArray(json);
            List<ConnectionProfile> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                try {
                    ConnectionProfile p = ConnectionProfile.fromJson(o);
                    list.add(p);
                } catch (Exception e) {
                    Log.w(TAG, "Skip broken profile index " + i, e);
                }
            }
            if (list.isEmpty()) {
                return createDefaultProfiles();
            }
            return list;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse profiles json", e);
            return createDefaultProfiles();
        }
    }

    private List<ConnectionProfile> createDefaultProfiles() {
        List<ConnectionProfile> list = new ArrayList<>();
        ConnectionProfile local = new ConnectionProfile("Local shell", ConnectionProfile.Type.LOCAL);
        local.setShell(null);
        local.setCwd(null);
        local.setColor(0xFF4D90FE);
        list.add(local);
        // Example SSH placeholder - user can edit
        // We don't auto-add SSH example to keep UI clean; but could add commented.
        saveProfiles(list);
        return list;
    }

    public synchronized void saveProfiles(List<ConnectionProfile> profiles) {
        try {
            JSONArray arr = new JSONArray();
            for (ConnectionProfile p : profiles) {
                arr.put(p.toJson());
            }
            String json = arr.toString();
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PROFILES_JSON, json).apply();

            // Also write to file for backup / inspectability
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "Failed to write profiles file", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save profiles", e);
        }
    }

    public synchronized void addProfile(ConnectionProfile profile) {
        List<ConnectionProfile> list = loadProfiles();
        list.add(profile);
        saveProfiles(list);
    }

    public synchronized void updateProfile(ConnectionProfile profile) {
        List<ConnectionProfile> list = loadProfiles();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(profile.getId())) {
                profile.touch();
                list.set(i, profile);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(profile);
        }
        saveProfiles(list);
    }

    public synchronized boolean deleteProfile(String id) {
        List<ConnectionProfile> list = loadProfiles();
        boolean removed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(id)) {
                list.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            // Ensure at least one profile remains
            if (list.isEmpty()) {
                list = createDefaultProfiles();
            } else {
                saveProfiles(list);
            }
        }
        return removed;
    }

    public synchronized ConnectionProfile getProfile(String id) {
        if (id == null) return null;
        List<ConnectionProfile> list = loadProfiles();
        for (ConnectionProfile p : list) {
            if (id.equals(p.getId())) return p;
        }
        return null;
    }

    public synchronized List<ConnectionProfile> getProfilesOrdered() {
        List<ConnectionProfile> list = loadProfiles();
        // Sort by updatedAt descending, but keep stable? For now return as stored.
        // Ensure updatedAt sorting optional.
        return new ArrayList<>(list);
    }
}
