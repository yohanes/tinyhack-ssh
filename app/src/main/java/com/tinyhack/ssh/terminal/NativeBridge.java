package com.tinyhack.ssh.terminal;

public class NativeBridge {
    static {
        System.loadLibrary("ghostty-android");
    }

    public static native long nativeCreateSession(
        String cmd,
        String cwd,
        String[] argv,
        String[] envp,
        int rows,
        int cols,
        int cellWidth,
        int cellHeight,
        SessionCallback callback
    );

    public static native int nativeGetPtmFd(long sessionPtr);
    public static native int nativeGetChildPid(long sessionPtr);
    public static native void nativeWritePty(long sessionPtr, byte[] data, int offset, int length);
    public static native void nativeFeedVt(long sessionPtr, byte[] data, int offset, int length);
    public static native void nativeResize(long sessionPtr, int rows, int cols, int cellWidth, int cellHeight);
    public static native void nativeScroll(long sessionPtr, int type, int deltaOrRow);
    public static native void nativeWriteKey(long sessionPtr, int ghosttyKey, int action, int mods, String text);
    public static native void nativeWritePaste(long sessionPtr, String text);
    public static native boolean nativeUpdateRender(
        long sessionPtr,
        RenderFrame frame,
        char[] chars,
        int[] fg,
        int[] bg,
        int[] style,
        int[] underlineStyle,
        int[] underlineColor,
        int[] cellSemantic,
        int[] rowSemanticPrompt,
        boolean[] dirty
    );
    public static native String nativeFormatTerminal(long sessionPtr, int format);
    public static native void nativeClose(long sessionPtr);
    public static native int nativeWaitChild(long sessionPtr);

    // Mouse and selection support
    public static native void nativeSendMouseEvent(
        long sessionPtr,
        int action, int button, int mods,
        float x, float y,
        int cellWidth, int cellHeight,
        int screenWidth, int screenHeight
    );
    public static native void nativeSetSelection(
        long sessionPtr,
        int startCol, int startRow, int endCol, int endRow, boolean isRectangle
    );
    public static native void nativeClearSelection(long sessionPtr);
    public static native boolean nativeHasSelection(long sessionPtr);
    public static native String nativeGetSelectionText(long sessionPtr);
    /** Select the word under a viewport cell; returns {sc, sr, ec, er} viewport coords or null. */
    public static native int[] nativeSelectWord(long sessionPtr, int col, int row);
    /** Ordered selection endpoints in viewport coords {sc, sr, ec, er, visFlags}, or null. */
    public static native int[] nativeGetSelectionViewport(long sessionPtr);
    public static native String nativeGetHyperlinkUri(long sessionPtr, int col, int row);
    public static native boolean nativeIsSyncOutputActive(long sessionPtr);
    public static native boolean nativeScrollToPreviousPrompt(long sessionPtr);
    public static native boolean nativeScrollToNextPrompt(long sessionPtr);
    public static native String nativeGetLastCommandOutput(long sessionPtr);
    public static native int[] nativeGetPromptRows(long sessionPtr);

    /**
     * Terminal interaction state bitfield: bit0 = alternate screen active,
     * bit1 = mouse tracking enabled by the application.
     */
    public static native int nativeGetScreenState(long sessionPtr);
}
