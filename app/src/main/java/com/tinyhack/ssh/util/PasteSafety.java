package com.tinyhack.ssh.util;

/** Conservative clipboard policy, independent of terminal mode and Android UI. */
public final class PasteSafety {
    private PasteSafety() {}

    public static boolean hasControlCharacters(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) return true;
        }
        return false;
    }

    public static boolean requiresConfirmation(String text) {
        return text.length() > 128 || hasControlCharacters(text);
    }
}
