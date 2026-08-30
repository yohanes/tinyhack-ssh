package com.tinyhack.ssh.terminal;

import android.graphics.Bitmap;

public class RenderFrame {
    public static final int MAX_KITTY_PLACEMENTS = 16;
    public int cols = 80;
    public int rows = 24;
    public int cursorX = 0;
    public int cursorY = 0;
    public int cursorStyle = 1; // 0=bar, 1=block, 2=underline, 3=hollow
    public boolean cursorVisible = true;
    public boolean cursorBlinking = false;
    public int defaultBgColor = 0xFF181818;
    public int defaultFgColor = 0xFFFFFFFF;
    public int cursorColor = 0xFFFFFFFF;
    public boolean hasCursorColor = false;
    public boolean isDirty = true;

    // Terminal interaction state (refreshed each updateRenderFrame):
    // used to reset kitty-keyboard flags when an app exits the alt screen
    public boolean altScreenActive = false;
    public int kittyKeyboardFlags = 0;

    // Viewport scrollbar state (refreshed each updateRenderFrame):
    // offset is the absolute row of the viewport top (0 = top of scrollback);
    // used to pin the viewport while a text selection is active.
    public long scrollTotal = 0;
    public long scrollOffset = 0;
    public long scrollLen = 0;

    public final int[] palette = new int[256];

    public char[] chars = new char[80 * 24];
    public int[] fgColors = new int[80 * 24];
    public int[] bgColors = new int[80 * 24];
    public int[] styleFlags = new int[80 * 24];
    public int[] underlineStyles = new int[80 * 24]; // 0-none,1-single,2-double,3-curly,4-dotted,5-dashed (GHOSTTY_SGR_UNDERLINE_*)
    public int[] underlineColors = new int[80 * 24]; // ARGB, 0 = use fg
    public int[] cellSemantic = new int[80 * 24]; // GHOSTTY_CELL_SEMANTIC_OUTPUT=0,INPUT=1,PROMPT=2
    public int[] rowSemanticPrompt = new int[24]; // GHOSTTY_ROW_SEMANTIC_NONE=0,PROMPT=1,CONTINUATION=2
    public boolean[] dirtyRows = new boolean[24];

    // Kitty graphics placements. Bitmaps are owned and reused by the native
    // session; the parallel arrays describe how Canvas should place/crop them.
    public int kittyPlacementCount = 0;
    public final Bitmap[] kittyBitmaps = new Bitmap[MAX_KITTY_PLACEMENTS];
    public final int[] kittyImageIds = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittyDstLeft = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittyDstTop = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittyDstWidth = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittyDstHeight = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittySrcLeft = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittySrcTop = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittySrcWidth = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittySrcHeight = new int[MAX_KITTY_PLACEMENTS];
    public final int[] kittyZ = new int[MAX_KITTY_PLACEMENTS];

    public void ensureCapacity(int newCols, int newRows) {
        this.cols = newCols;
        this.rows = newRows;
        int total = newCols * newRows;
        if (chars == null || chars.length < total) {
            chars = new char[total];
            fgColors = new int[total];
            bgColors = new int[total];
            styleFlags = new int[total];
            underlineStyles = new int[total];
            underlineColors = new int[total];
            cellSemantic = new int[total];
        }
        if (dirtyRows == null || dirtyRows.length < newRows) {
            dirtyRows = new boolean[newRows];
        }
        if (rowSemanticPrompt == null || rowSemanticPrompt.length < newRows) {
            rowSemanticPrompt = new int[newRows];
        }
    }
}
