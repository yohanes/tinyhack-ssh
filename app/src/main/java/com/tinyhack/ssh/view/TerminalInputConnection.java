package com.tinyhack.ssh.view;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

import com.tinyhack.ssh.terminal.KeyCodes;

public class TerminalInputConnection extends BaseInputConnection {
    private final TerminalView terminalView;

    public TerminalInputConnection(TerminalView targetView, boolean fullEditor) {
        super(targetView, fullEditor);
        this.terminalView = targetView;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (text == null || text.length() == 0) return true;

        if (terminalView.isCtrlActive() || terminalView.isAltActive()) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int ghosttyKey = KeyCodes.GHOSTTY_KEY_UNIDENTIFIED;
                if (c >= 'a' && c <= 'z') {
                    ghosttyKey = KeyCodes.GHOSTTY_KEY_A + (c - 'a');
                } else if (c >= 'A' && c <= 'Z') {
                    ghosttyKey = KeyCodes.GHOSTTY_KEY_A + (c - 'A');
                } else if (c >= '0' && c <= '9') {
                    ghosttyKey = KeyCodes.GHOSTTY_KEY_DIGIT_0 + (c - '0');
                }

                int mods = 0;
                if (terminalView.isCtrlActive()) mods |= KeyCodes.MODS_CTRL;
                if (terminalView.isAltActive()) mods |= KeyCodes.MODS_ALT;
                terminalView.setCtrlActive(false);
                terminalView.setAltActive(false);

                terminalView.sendSpecialKey(ghosttyKey, mods, String.valueOf(c));
            }
            return true;
        }

        String str = text.toString();
        // Convert any newlines from IME to carriage return
        str = str.replace("\n", "\r");
        terminalView.sendText(str);
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (beforeLength > 0) {
            for (int i = 0; i < beforeLength; i++) {
                terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_BACKSPACE, 0, "\u007f");
            }
            return true;
        }
        return super.deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ENTER, 0, "\r");
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return terminalView.onKeyDown(event.getKeyCode(), event);
        }
        return true;
    }
}
