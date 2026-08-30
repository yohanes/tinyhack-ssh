package com.tinyhack.ssh.session;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tinyhack.ssh.terminal.NativeBridge;
import com.tinyhack.ssh.terminal.RenderFrame;
import com.tinyhack.ssh.terminal.SessionCallback;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalSession implements SessionCallback {
    private static final String TAG = "TerminalSession";

    public interface Listener {
        void onDataAvailable();
        void onTitleChanged(String title);
        void onBell();
        void onClipboardWrite(String text);
        void onSessionClosed(int exitCode);
    }

    private final String id;
    private String title = "Terminal";
    private long sessionPtr = 0;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    private int rows = 24;
    private int cols = 80;
    private int cellWidth = 10;
    private int cellHeight = 20;

    private Thread processWatcherThread;
    private volatile Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RenderFrame renderFrame = new RenderFrame();

    // Profile & session metadata for multi-session UI
    private final String profileId;
    private String sessionName;
    private final long createdAt;
    private int exitCode = -1;

    public TerminalSession(String cmd, String cwd, String[] argv, String[] envp, int rows, int cols, int cellWidth, int cellHeight) {
        this(cmd, cwd, argv, envp, rows, cols, cellWidth, cellHeight, null, null);
    }

    public TerminalSession(String cmd, String cwd, String[] argv, String[] envp, int rows, int cols, int cellWidth, int cellHeight, String profileId, String sessionName) {
        this.id = UUID.randomUUID().toString();
        this.profileId = profileId;
        this.sessionName = sessionName;
        this.createdAt = System.currentTimeMillis();
        this.rows = Math.max(1, rows);
        this.cols = Math.max(1, cols);
        this.cellWidth = Math.max(1, cellWidth);
        this.cellHeight = Math.max(1, cellHeight);
        this.renderFrame.ensureCapacity(this.cols, this.rows);

        this.sessionPtr = NativeBridge.nativeCreateSession(
            cmd, cwd, argv, envp, this.rows, this.cols, this.cellWidth, this.cellHeight, this
        );

        if (this.sessionPtr != 0) {
            this.isRunning.set(true);
            startWatcherThread();
        }
        // initial title fallback to sessionName if set
        if (sessionName != null && !sessionName.isEmpty()) {
            this.title = sessionName;
        }
    }

    private void startWatcherThread() {
        processWatcherThread = new Thread(() -> {
            if (sessionPtr != 0) {
                int status = NativeBridge.nativeWaitChild(sessionPtr);
                int exitCode = 0;
                if ((status & 0x7f) == 0) {
                    exitCode = (status >> 8) & 0xff;
                } else {
                    exitCode = 128 + (status & 0x7f);
                }
                onSessionClosed(exitCode);
            }
        }, "Terminal-Watcher-" + id);
        processWatcherThread.setDaemon(true);
        processWatcherThread.start();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void write(byte[] data) {
        if (isClosed.get() || sessionPtr == 0 || data == null || data.length == 0) return;
        NativeBridge.nativeWritePty(sessionPtr, data, 0, data.length);
    }

    public void write(String text) {
        if (text != null) {
            write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public synchronized void writeKey(int ghosttyKey, int action, int mods, String text) {
        if (isClosed.get() || sessionPtr == 0) return;
        NativeBridge.nativeWriteKey(sessionPtr, ghosttyKey, action, mods, text);
    }

    public synchronized void writePaste(String text) {
        if (isClosed.get() || sessionPtr == 0 || text == null) return;
        NativeBridge.nativeWritePaste(sessionPtr, text);
    }

    public synchronized void resize(int newRows, int newCols, int newCellWidth, int newCellHeight) {
        if (isClosed.get() || sessionPtr == 0) return;
        if (newRows == this.rows && newCols == this.cols && newCellWidth == this.cellWidth && newCellHeight == this.cellHeight) {
            return;
        }
        this.rows = Math.max(1, newRows);
        this.cols = Math.max(1, newCols);
        this.cellWidth = Math.max(1, newCellWidth);
        this.cellHeight = Math.max(1, newCellHeight);
        this.renderFrame.ensureCapacity(this.cols, this.rows);
        NativeBridge.nativeResize(sessionPtr, this.rows, this.cols, this.cellWidth, this.cellHeight);
    }

    public synchronized void scroll(int type, int deltaOrRow) {
        if (isClosed.get() || sessionPtr == 0) return;
        NativeBridge.nativeScroll(sessionPtr, type, deltaOrRow);
        onDataAvailable();
    }

    public synchronized void sendMouseEvent(int action, int button, int mods, float x, float y, int cellWidth, int cellHeight, int screenWidth, int screenHeight) {
        if (isClosed.get() || sessionPtr == 0) return;
        NativeBridge.nativeSendMouseEvent(sessionPtr, action, button, mods, x, y, cellWidth, cellHeight, screenWidth, screenHeight);
    }

    public synchronized void sendMouseClick(float x, float y, int cellWidth, int cellHeight, int screenWidth, int screenHeight) {
        if (isClosed.get() || sessionPtr == 0) return;
        // Press then release to simulate click
        NativeBridge.nativeSendMouseEvent(sessionPtr, 0, 1, 0, x, y, cellWidth, cellHeight, screenWidth, screenHeight);
        NativeBridge.nativeSendMouseEvent(sessionPtr, 1, 1, 0, x, y, cellWidth, cellHeight, screenWidth, screenHeight);
    }

    /** Send a mouse-wheel event: button 4 = scroll up, 5 = scroll down. */
    public synchronized void sendMouseWheel(int lines, boolean scrollUp, float x, float y, int cellWidth, int cellHeight, int screenWidth, int screenHeight) {
        if (isClosed.get() || sessionPtr == 0) return;
        int button = scrollUp ? 4 : 5;
        for (int i = 0; i < lines; i++) {
            NativeBridge.nativeSendMouseEvent(sessionPtr, 0, button, 0, x, y, cellWidth, cellHeight, screenWidth, screenHeight);
        }
    }

    /** Feed raw bytes into the terminal's VT parser (as if received from the child). */
    public synchronized void feedVt(byte[] data) {
        if (isClosed.get() || sessionPtr == 0 || data == null || data.length == 0) return;
        NativeBridge.nativeFeedVt(sessionPtr, data, 0, data.length);
        onDataAvailable();
    }

    /**
     * Terminal interaction state of the underlying terminal screen:
     * bit0 = alternate screen active, bit1 = mouse tracking enabled.
     */
    public int getScreenState() {
        if (isClosed.get() || sessionPtr == 0) return 0;
        return NativeBridge.nativeGetScreenState(sessionPtr);
    }

    public boolean isAlternateScreenActive() {
        return (getScreenState() & 1) != 0;
    }

    public boolean isMouseTrackingEnabled() {
        return (getScreenState() & 2) != 0;
    }

    public synchronized void setSelection(int startCol, int startRow, int endCol, int endRow, boolean isRectangle) {
        if (isClosed.get() || sessionPtr == 0) return;
        NativeBridge.nativeSetSelection(sessionPtr, startCol, startRow, endCol, endRow, isRectangle);
        onDataAvailable();
    }

    public synchronized void clearSelection() {
        if (isClosed.get() || sessionPtr == 0) return;
        NativeBridge.nativeClearSelection(sessionPtr);
        onDataAvailable();
    }

    public synchronized boolean hasSelection() {
        if (isClosed.get() || sessionPtr == 0) return false;
        return NativeBridge.nativeHasSelection(sessionPtr);
    }

    public synchronized String getSelectionText() {
        if (isClosed.get() || sessionPtr == 0) return "";
        return NativeBridge.nativeGetSelectionText(sessionPtr);
    }

    /**
     * Select the word under the given viewport cell and install it as the
     * terminal's active (text-anchored) selection. Returns the ordered bounds
     * in viewport coordinates {startCol, startRow, endCol, endRow}, or null.
     */
    public synchronized int[] selectWord(int col, int row) {
        if (isClosed.get() || sessionPtr == 0) return null;
        try {
            int[] sel = NativeBridge.nativeSelectWord(sessionPtr, col, row);
            if (sel != null) onDataAvailable();
            return sel;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ordered endpoints of the active selection in viewport coordinates
     * {startCol, startRow, endCol, endRow, visFlags}, or null if no selection.
     */
    public synchronized int[] getSelectionViewport() {
        if (isClosed.get() || sessionPtr == 0) return null;
        try {
            return NativeBridge.nativeGetSelectionViewport(sessionPtr);
        } catch (Exception e) {
            return null;
        }
    }

    /** Scroll the viewport to an absolute row (0 = top of scrollback). */
    public void scrollToRow(int row) {
        scroll(3, row);
    }

    public synchronized String getHyperlinkUri(int col, int row) {
        if (isClosed.get() || sessionPtr == 0) return null;
        try {
            return NativeBridge.nativeGetHyperlinkUri(sessionPtr, col, row);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean isSyncOutputActive() {
        if (isClosed.get() || sessionPtr == 0) return false;
        try {
            return NativeBridge.nativeIsSyncOutputActive(sessionPtr);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean scrollToPreviousPrompt() {
        if (isClosed.get() || sessionPtr == 0) return false;
        try {
            boolean ok = NativeBridge.nativeScrollToPreviousPrompt(sessionPtr);
            if (ok) onDataAvailable();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean scrollToNextPrompt() {
        if (isClosed.get() || sessionPtr == 0) return false;
        try {
            boolean ok = NativeBridge.nativeScrollToNextPrompt(sessionPtr);
            if (ok) onDataAvailable();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized String getLastCommandOutput() {
        if (isClosed.get() || sessionPtr == 0) return "";
        try {
            String s = NativeBridge.nativeGetLastCommandOutput(sessionPtr);
            return s != null ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized int[] getPromptRows() {
        if (isClosed.get() || sessionPtr == 0) return new int[0];
        try {
            int[] arr = NativeBridge.nativeGetPromptRows(sessionPtr);
            return arr != null ? arr : new int[0];
        } catch (Exception e) {
            return new int[0];
        }
    }

    public synchronized String copySelection() {
        String t = getSelectionText();
        if (t != null && !t.isEmpty()) {
            clearSelection();
        }
        return t != null ? t : "";
    }

    public synchronized boolean updateRenderFrame(RenderFrame targetFrame) {
        if (isClosed.get() || sessionPtr == 0) return false;
        targetFrame.ensureCapacity(cols, rows);
        return NativeBridge.nativeUpdateRender(
            sessionPtr,
            targetFrame,
            targetFrame.chars,
            targetFrame.fgColors,
            targetFrame.bgColors,
            targetFrame.styleFlags,
            targetFrame.underlineStyles,
            targetFrame.underlineColors,
            targetFrame.cellSemantic,
            targetFrame.rowSemanticPrompt,
            targetFrame.dirtyRows
        );
    }

    public synchronized String format(int formatType) {
        if (isClosed.get() || sessionPtr == 0) return "";
        return NativeBridge.nativeFormatTerminal(sessionPtr, formatType);
    }

    public synchronized String getScreenText() {
        String s = format(0);
        if (s != null && !s.isEmpty()) return s;
        // Fallback using renderFrame chars
        RenderFrame frame = new RenderFrame();
        updateRenderFrame(frame);
        StringBuilder sb = new StringBuilder();
        int cCount = frame.cols;
        int rCount = frame.rows;
        for (int r = 0; r < rCount; r++) {
            int lineEnd = cCount;
            while (lineEnd > 0 && (frame.chars[r * cCount + lineEnd - 1] == ' ' || frame.chars[r * cCount + lineEnd - 1] == 0)) {
                lineEnd--;
            }
            if (lineEnd > 0) {
                sb.append(frame.chars, r * cCount, lineEnd);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public synchronized String getHtml() {
        return format(2);
    }

    public synchronized String getVt() {
        return format(1);
    }

    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            isRunning.set(false);
            long ptr;
            synchronized (this) {
                ptr = sessionPtr;
                sessionPtr = 0;
            }
            if (ptr != 0) {
                NativeBridge.nativeClose(ptr);
            }
        }
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public boolean isRunning() { return isRunning.get() && !isClosed.get(); }
    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public String getProfileId() { return profileId; }
    public String getSessionName() { return sessionName; }
    public long getCreatedAt() { return createdAt; }
    public int getExitCode() { return exitCode; }
    public boolean isClosed() { return isClosed.get(); }

    public String getDisplayTitle() {
        if (sessionName != null && !sessionName.isEmpty()) return sessionName;
        if (title != null && !title.isEmpty() && !title.equals("Terminal")) return title;
        if (sessionName != null) return sessionName;
        return title != null ? title : "Terminal";
    }

    public void setSessionName(String name) {
        this.sessionName = name;
        if (name != null && !name.isEmpty()) {
            this.title = name;
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) l.onTitleChanged(name);
            });
        }
    }

    @Override
    public void onDataAvailable() {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onDataAvailable();
        });
    }

    @Override
    public void onTitleChanged(String title) {
        this.title = title;
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onTitleChanged(title);
        });
    }

    @Override
    public void onBell() {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onBell();
        });
    }

    @Override
    public void onClipboardWrite(String text) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onClipboardWrite(text);
        });
    }

    @Override
    public void onSessionClosed(int exitCode) {
        isRunning.set(false);
        this.exitCode = exitCode;
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) l.onSessionClosed(exitCode);
        });
    }
}
