package com.tinyhack.ssh.util;

/** Intentional no-op logger: terminal and credential-adjacent data stays out of logcat. */
public final class SafeLog {
    private SafeLog() {}

    public static int d(String tag, String message) {
        return 0;
    }

    public static int i(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message, Throwable error) {
        return 0;
    }

    public static int e(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message, Throwable error) {
        return 0;
    }
}
