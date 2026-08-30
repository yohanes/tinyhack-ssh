package com.tinyhack.ssh.terminal;

public interface SessionCallback {
    void onDataAvailable();
    void onTitleChanged(String title);
    void onBell();
    void onClipboardWrite(String text);
    void onSessionClosed(int exitCode);
}
