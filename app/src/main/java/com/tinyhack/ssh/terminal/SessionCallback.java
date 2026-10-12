package com.tinyhack.ssh.terminal;

public interface SessionCallback {
    void onDataAvailable();
    void onTitleChanged(String title);
    void onBell();
    void onClipboardWrite(String text);
    byte[] onKittyNotification(byte[] metadata, byte[] payload);
    void onSessionClosed(int exitCode);
}
