package com.tinyhack.ssh.view;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.tinyhack.ssh.session.TerminalSession;
import com.tinyhack.ssh.terminal.KeyCodes;
import com.tinyhack.ssh.terminal.RenderFrame;

public class TerminalView extends View implements TerminalSession.Listener {
    private static final String TAG = "TerminalView";

    private TerminalSession session;
    private final RenderFrame renderFrame = new RenderFrame();

    private float fontSizeSp = 14.0f;
    private float cellWidth = 10f;
    private float cellHeight = 20f;
    private float fontBaseline = 16f;

    private static final int STYLE_HYPERLINK = 1 << 9;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint cursorPaint = new Paint();
    private final Paint selectionPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kittyImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint underlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect kittySourceRect = new Rect();
    private final Rect kittyDestinationRect = new Rect();

    // Session-closed overlay banner (drawn over a dead session's last frame)
    private final Paint scrimPaint = new Paint();
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bannerSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bannerBtnFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerBtnBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerBtnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF sessionClosedBtnReopenBounds = new RectF();
    private final RectF sessionClosedBtnCloseBounds = new RectF();
    private final RectF sessionClosedBtnDismissBounds = new RectF();
    /** True once the user tapped "Dismiss" on the <session closed> overlay. */
    private boolean sessionClosedDismissed = false;
    private boolean overlayDownOnReopen = false;
    private boolean overlayDownOnClose = false;
    private boolean overlayDownOnDismiss = false;
    private OnCloseSessionRequested closeSessionRequestedListener;
    private OnReopenSessionRequested reopenSessionRequestedListener;

    private Typeface regularTypeface;
    private Typeface boldTypeface;
    private Typeface italicTypeface;
    private Typeface boldItalicTypeface;

    private boolean isCtrlActive = false;
    private boolean isAltActive = false;

    private boolean cursorBlinkState = true;
    private final Handler blinkHandler = new Handler(Looper.getMainLooper());
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            cursorBlinkState = !cursorBlinkState;
            invalidate();
            blinkHandler.postDelayed(this, 500);
        }
    };

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    // Alt-screen tracking for kitty-keyboard flag reset (see onDraw)
    private boolean lastAltScreenActive = false;

    /** Notified when a text selection appears / is dismissed (drives the Copy/Cancel bar). */
    public interface SelectionListener {
        void onSelectionActiveChanged(boolean active);
    }

    private SelectionListener selectionListener;

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    /** Actions the host activity handles for the 3-finger tap menu (fullscreen, drawer, sessions, profiles). */
    public interface TerminalMenuActionListener {
        boolean isFullscreen();
        void toggleFullscreen();
        void openDrawer();
        java.util.List<TerminalSession> getSessionsForMenu();
        void switchToSession(TerminalSession session);
        java.util.List<com.tinyhack.ssh.model.ConnectionProfile> getProfilesForMenu();
        void startSessionWithProfile(com.tinyhack.ssh.model.ConnectionProfile profile);
    }

    private TerminalMenuActionListener terminalMenuActionListener;

    public void setTerminalMenuActionListener(TerminalMenuActionListener listener) {
        this.terminalMenuActionListener = listener;
    }

    // ---- Text selection (Termux-style: long-press to start, drag handles to
    // extend, finger scroll selects across the scrollback) ----
    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END = 2;
    private static final long HANDLE_AUTO_SCROLL_INTERVAL_MS = 50L;
    private static final int HANDLE_AUTO_SCROLL_STEP_ROWS = 3;

    /** True while a native text selection exists and handles are shown. */
    private boolean selectionActive = false;
    private int draggingHandle = HANDLE_NONE;
    private float handleGrabOffsetX = 0f;
    private float handleGrabOffsetY = 0f;
    private float handleDragX = 0f;
    private float handleDragY = 0f;
    private long lastHandleAutoScrollMs = 0;

    // Last-known handle anchor points (view px, refreshed each frame). Each
    // handle circle sits on the bottom corner of its endpoint cell.
    private float selStartHandleX = -1f, selStartHandleY = -1f;
    private float selEndHandleX = -1f, selEndHandleY = -1f;
    private boolean selStartHandleVisible = false, selEndHandleVisible = false;

    // Viewport pinning while selecting: keep the same content on screen when
    // new output arrives instead of following it (Termux behavior).
    private long prevScrollTotal = -1, prevScrollOffset = -1, prevScrollLen = -1;

    private final Runnable handleAutoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (draggingHandle == HANDLE_NONE) return;
            applyHandleDrag();
            postDelayed(this, HANDLE_AUTO_SCROLL_INTERVAL_MS);
        }
    };

    // Keyboard visibility tracking
    private boolean keyboardVisible = false;

    /** Notified when the view programmatically shows/hides the soft keyboard. */
    public interface KeyboardVisibilityListener {
        void onKeyboardVisibilityChanged(boolean visible);
    }

    private KeyboardVisibilityListener keyboardVisibilityListener;

    public void setKeyboardVisibilityListener(KeyboardVisibilityListener listener) {
        this.keyboardVisibilityListener = listener;
    }

    // Three finger handling
    private boolean threeFingerConsumed = false;

    // Font handling
    private static final String PREFS_NAME = "tinyhack_ssh_prefs";
    private static final String PREF_FONT_FAMILY = "font_family";
    private static final String PREF_FONT_SIZE = "font_size_sp";
    private static final String DEFAULT_FONT_FAMILY = "JetBrainsMono";
    private String currentFontFamily = DEFAULT_FONT_FAMILY;

    public static final String[] FONT_FAMILIES = {
        "JetBrainsMono",
        "Hack",
        "FiraCode",
        "DejaVuSansMono",
        "CascadiaCode",
        "CascadiaMono",
        "NotoMono",
        "Inconsolata",
        "UbuntuMono"
    };

    public static String getFontDisplayName(String family) {
        switch (family) {
            case "JetBrainsMono": return "JetBrains Mono";
            case "Hack": return "Hack";
            case "FiraCode": return "Fira Code";
            case "DejaVuSansMono": return "DejaVu Sans Mono";
            case "CascadiaCode": return "Cascadia Code";
            case "CascadiaMono": return "Cascadia Mono";
            case "NotoMono": return "Noto Mono";
            case "Inconsolata": return "Inconsolata";
            case "UbuntuMono": return "Ubuntu Mono";
            default: return family;
        }
    }

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TerminalView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private String getPersistedFontFamily() {
        try {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(PREF_FONT_FAMILY, DEFAULT_FONT_FAMILY);
        } catch (Exception e) {
            return DEFAULT_FONT_FAMILY;
        }
    }

    private void persistFontFamily(String family) {
        try {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_FONT_FAMILY, family).apply();
        } catch (Exception ignored) {}
    }

    private float getPersistedFontSize() {
        try {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getFloat(PREF_FONT_SIZE, fontSizeSp);
        } catch (Exception e) {
            return fontSizeSp;
        }
    }

    private void persistFontSize(float size) {
        try {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putFloat(PREF_FONT_SIZE, size).apply();
        } catch (Exception ignored) {}
    }

    private Typeface loadTypefaceAsset(String family, String style) {
        String base = "fonts/" + family + "-" + style;
        // Try .ttf then .otf
        String[] exts = {".ttf", ".otf"};
        for (String ext : exts) {
            String path = base + ext;
            try {
                return Typeface.createFromAsset(getContext().getAssets(), path);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void loadFontFamily(String family) {
        if (family == null || family.isEmpty()) family = DEFAULT_FONT_FAMILY;
        currentFontFamily = family;
        Typeface reg = loadTypefaceAsset(family, "Regular");
        Typeface bold = loadTypefaceAsset(family, "Bold");
        Typeface italic = loadTypefaceAsset(family, "Italic");
        Typeface boldItalic = loadTypefaceAsset(family, "BoldItalic");

        if (reg == null) {
            // Fallback to JetBrains or monospace
            try {
                reg = Typeface.createFromAsset(getContext().getAssets(), "fonts/JetBrainsMono-Regular.ttf");
            } catch (Exception e) {
                reg = Typeface.MONOSPACE;
            }
        }
        regularTypeface = reg;
        // Synthesize missing variants
        if (bold == null) {
            try {
                bold = Typeface.create(reg, Typeface.BOLD);
            } catch (Exception e) {
                bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            }
        }
        if (italic == null) {
            try {
                italic = Typeface.create(reg, Typeface.ITALIC);
            } catch (Exception e) {
                italic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC);
            }
        }
        if (boldItalic == null) {
            try {
                boldItalic = Typeface.create(reg, Typeface.BOLD_ITALIC);
            } catch (Exception e) {
                boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC);
            }
        }
        boldTypeface = bold;
        italicTypeface = italic;
        boldItalicTypeface = boldItalic;
    }

    private void initTypefaces() {
        String persisted = getPersistedFontFamily();
        loadFontFamily(persisted);
        // Load persisted font size
        float persistedSize = getPersistedFontSize();
        if (persistedSize >= 8.0f && persistedSize <= 32.0f) {
            fontSizeSp = persistedSize;
        }
    }

    public String getCurrentFontFamily() {
        return currentFontFamily;
    }

    public void setFontFamily(String family) {
        if (family == null || family.equals(currentFontFamily)) return;
        loadFontFamily(family);
        persistFontFamily(family);
        updateFontMetrics();
        requestLayout();
        if (session != null) {
            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0) {
                int cols = Math.max(10, (int) (w / cellWidth));
                int rows = Math.max(4, (int) (h / cellHeight));
                session.resize(rows, cols, (int) cellWidth, (int) cellHeight);
            }
        }
        invalidate();
        Toast.makeText(getContext(), "Font: " + getFontDisplayName(family), Toast.LENGTH_SHORT).show();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);

        initTypefaces();
        updateFontMetrics();

        bgPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setStyle(Paint.Style.FILL);
        selectionPaint.setStyle(Paint.Style.FILL);
        selectionPaint.setColor(0x664D90FE); // Soft translucent blue
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFF4D90FE);
        handleBorderPaint.setStyle(Paint.Style.STROKE);
        handleBorderPaint.setStrokeWidth(dpToPx(2));
        handleBorderPaint.setColor(0xFFFFFFFF);
        underlinePaint.setStyle(Paint.Style.STROKE);
        underlinePaint.setStrokeCap(Paint.Cap.ROUND);
        underlinePaint.setStrokeJoin(Paint.Join.ROUND);

        scrimPaint.setStyle(Paint.Style.FILL);
        scrimPaint.setColor(0x66000000);

        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(0xF21E1E1E);
        cardBorderPaint.setStyle(Paint.Style.STROKE);
        cardBorderPaint.setStrokeWidth(dpToPx(1));
        cardBorderPaint.setColor(0xFF4A4A4A);

        bannerTitlePaint.setTextAlign(Paint.Align.CENTER);
        bannerTitlePaint.setTypeface(boldTypeface != null ? boldTypeface : regularTypeface);
        bannerTitlePaint.setTextSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
        bannerTitlePaint.setColor(0xFFFFFFFF);

        bannerSubPaint.setTextAlign(Paint.Align.CENTER);
        bannerSubPaint.setTypeface(regularTypeface);
        bannerSubPaint.setTextSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        bannerSubPaint.setColor(0xFFAAAAAA);

        bannerBtnFillPaint.setStyle(Paint.Style.FILL);
        bannerBtnFillPaint.setColor(0xFF4D90FE);
        bannerBtnBorderPaint.setStyle(Paint.Style.STROKE);
        bannerBtnBorderPaint.setStrokeWidth(dpToPx(1));
        bannerBtnBorderPaint.setColor(0xFF4A4A4A);
        bannerBtnTextPaint.setTextAlign(Paint.Align.CENTER);
        bannerBtnTextPaint.setTypeface(boldTypeface != null ? boldTypeface : regularTypeface);
        bannerBtnTextPaint.setTextSize(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        bannerBtnTextPaint.setColor(0xFFFFFFFF);

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            // Accumulate fractional scroll to handle small distanceY per event
            private float scrollAccum = 0f;

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // A tap while a selection is shown dismisses it (like Termux)
                if (selectionActive) {
                    clearSelection();
                    return true;
                }
                // Single tap is interpreted as mouse click
                // Also request focus for typing via hardware keyboard, but don't automatically force IME
                requestFocus();
                float x = e.getX();
                float y = e.getY();
                // OSC 8 hyperlink has priority over mouse reporting
                if (tryOpenHyperlink(x, y)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    return true;
                }
                sendMouseClick(x, y);
                // Provide haptic
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // Long-press starts a word selection with draggable handles
                if (session == null || isSessionClosedOverlayShowing()) return;
                if (scaleGestureDetector.isInProgress() || selectionActive) return;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                startTextSelection(e.getX(), e.getY());
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (session != null) {
                    if (selectionActive) {
                        // While selecting, finger scrolling always moves the
                        // viewport so the selection can be extended across the
                        // scrollback (select-while-scrolling).
                        scrollAccum += distanceY;
                        int delta = (int) (scrollAccum / cellHeight);
                        if (delta != 0) {
                            session.scroll(2, delta);
                            scrollAccum -= delta * cellHeight;
                            invalidate();
                            return true;
                        }
                        return false;
                    }
                    // Accumulate to handle fractional cell heights
                    scrollAccum += distanceY;
                    int delta = (int) (scrollAccum / cellHeight);
                    if (delta != 0) {
                        dispatchTouchScroll(delta, e2.getX(), e2.getY());
                        scrollAccum -= delta * cellHeight;
                        invalidate();
                        return true;
                    }
                    // Reset accum if scroll ends (when ACTION_UP, GestureDetector will stop calling onScroll)
                    // Keep accum for next events within same gesture
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (session != null) {
                    int delta = (int) (-velocityY / (cellHeight * 10));
                    if (delta != 0) {
                        if (selectionActive) {
                            session.scroll(2, delta);
                        } else {
                            dispatchTouchScroll(delta, e2.getX(), e2.getY());
                        }
                        scrollAccum = 0;
                        invalidate();
                        return true;
                    }
                }
                return false;
            }
        });
        // Long press starts text selection (word under the finger)
        gestureDetector.setIsLongpressEnabled(true);

        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (selectionActive) return true; // don't zoom while adjusting a selection
                float factor = detector.getScaleFactor();
                if (factor > 1.05f) {
                    setFontSize(fontSizeSp + 1.0f);
                    return true;
                } else if (factor < 0.95f) {
                    setFontSize(fontSizeSp - 1.0f);
                    return true;
                }
                return false;
            }
        });
    }

    public void setFontSize(float sp) {
        float newSize = Math.max(8.0f, Math.min(32.0f, sp));
        if (Math.abs(newSize - fontSizeSp) > 0.1f) {
            fontSizeSp = newSize;
            persistFontSize(newSize);
            updateFontMetrics();
            requestLayout();
            if (session != null) {
                int w = getWidth();
                int h = getHeight();
                if (w > 0 && h > 0) {
                    int cols = Math.max(10, (int) (w / cellWidth));
                    int rows = Math.max(4, (int) (h / cellHeight));
                    session.resize(rows, cols, (int) cellWidth, (int) cellHeight);
                }
            }
            invalidate();
        }
    }

    /**
     * Re-applies the persisted font preferences if they were changed externally
     * (e.g. from the Settings page while this view was not visible).
     */
    public void reloadPersistedFont() {
        String fam = getPersistedFontFamily();
        float size = getPersistedFontSize();
        if (!fam.equals(currentFontFamily)) {
            setFontFamily(fam);
            return; // setFontFamily refreshes metrics/session size already
        }
        if (Math.abs(size - fontSizeSp) > 0.1f) {
            setFontSize(size);
        }
    }

    private void updateFontMetrics() {
        float px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            getContext().getResources().getDisplayMetrics()
        );
        textPaint.setTextSize(px);
        textPaint.setTypeface(regularTypeface);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        cellHeight = (float) Math.ceil(fm.descent - fm.ascent + fm.leading);
        cellWidth = textPaint.measureText("W");
        fontBaseline = -fm.ascent;
    }

    public void attachSession(TerminalSession newSession) {
        if (this.session != null) {
            this.session.setListener(null);
        }
        this.session = newSession;
        sessionClosedDismissed = false;
        sessionClosedBtnReopenBounds.setEmpty();
        sessionClosedBtnCloseBounds.setEmpty();
        sessionClosedBtnDismissBounds.setEmpty();
        stopHandleDrag();
        setSelectionActive(false);
        // A session can keep its (text-anchored) native selection across view
        // re-attaches; drop it so handles/bar state can't go stale.
        if (this.session != null) {
            this.session.clearSelection();
        }
        prevScrollTotal = -1;
        prevScrollOffset = -1;
        prevScrollLen = -1;
        if (this.session != null) {
            this.session.setListener(this);
            int w = getWidth();
            int h = getHeight();
            if (w > 0 && h > 0) {
                int cols = Math.max(10, (int) (w / cellWidth));
                int rows = Math.max(4, (int) (h / cellHeight));
                this.session.resize(rows, cols, (int) cellWidth, (int) cellHeight);
            }
            invalidate();
        }
    }

    public TerminalSession getSession() {
        return session;
    }

    private boolean isEscActive = false;
    private final Handler escHandler = new Handler(Looper.getMainLooper());
    private final Runnable escTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isEscActive) {
                isEscActive = false;
                notifyModifierState();
                if (session != null) {
                    session.write("\u001b");
                }
            }
        }
    };

    private void scheduleEscTimeout() {
        escHandler.removeCallbacks(escTimeoutRunnable);
        escHandler.postDelayed(escTimeoutRunnable, 350);
    }

    private void cancelEscTimeout() {
        escHandler.removeCallbacks(escTimeoutRunnable);
    }

    /**
     * Route a touch-scroll of {@code lines} rows (positive = finger drag down /
     * scroll up content... matching viewport semantics):
     * <ul>
     *   <li>App enabled mouse reporting -> wheel events (buttons 4/5)</li>
     *   <li>Alternate screen (fullscreen TUI, no mouse) -> arrow keys</li>
     *   <li>Otherwise -> normal scrollback viewport movement</li>
     * </ul>
     */
    private void dispatchTouchScroll(int lines, float x, float y) {
        if (session == null || lines == 0 || cellWidth <= 0 || cellHeight <= 0) return;
        int state = session.getScreenState();
        boolean altScreen = (state & 1) != 0;
        boolean mouse = (state & 2) != 0;

        // Sign convention from GestureDetector.onScroll: distanceY (and thus
        // lines) is POSITIVE when the finger moves UP the screen. Natural
        // scrolling: finger moves up -> content moves up -> reveal LATER
        // output; finger moves down -> reveal earlier output.
        boolean revealLater = lines > 0;

        if (mouse) {
            // Wheel events at the touch position; the ghostty encoder emits
            // whatever mode/format the application negotiated (SGR etc.)
            int n = Math.min(Math.abs(lines), getRows() * 2);
            session.sendMouseWheel(n, !revealLater, x, y,
                (int) cellWidth, (int) cellHeight, getWidth(), getHeight());
        } else if (altScreen) {
            // Fullscreen TUI without mouse reporting: arrows drive scrolling
            int key = revealLater ? KeyCodes.GHOSTTY_KEY_ARROW_DOWN : KeyCodes.GHOSTTY_KEY_ARROW_UP;
            int n = Math.min(Math.abs(lines), 20);
            for (int i = 0; i < n; i++) {
                sendSpecialKey(key, 0, null);
            }
        } else {
            session.scroll(2, lines);
        }
    }

    private int getRows() {
        return Math.max(1, (int) (getHeight() / cellHeight));
    }

    private void setEscPending(boolean pending) {        if (this.isEscActive != pending) {
            this.isEscActive = pending;
            notifyModifierState();
        }
        if (pending) {
            scheduleEscTimeout();
        } else {
            cancelEscTimeout();
        }
    }

    private void handleEscPress() {
        if (isEscActive) {
            // Flush previous pending ESC as standalone, then start new pending
            cancelEscTimeout();
            isEscActive = false;
            notifyModifierState();
            if (session != null) {
                session.write("\u001b");
            }
            // Start new pending for this ESC press
            isEscActive = true;
            notifyModifierState();
            scheduleEscTimeout();
        } else {
            setEscPending(true);
        }
        // Haptic
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public interface ModifierStateListener {
        void onModifierStateChanged(boolean ctrlActive, boolean altActive, boolean escActive);
    }

    private ModifierStateListener modifierStateListener;

    public void setModifierStateListener(ModifierStateListener listener) {
        this.modifierStateListener = listener;
    }

    private void notifyModifierState() {
        if (modifierStateListener != null) {
            modifierStateListener.onModifierStateChanged(isCtrlActive, isAltActive, isEscActive);
        }
    }

    public void setCtrlActive(boolean active) {
        if (this.isCtrlActive != active) {
            this.isCtrlActive = active;
            notifyModifierState();
        }
    }

    public boolean isCtrlActive() {
        return isCtrlActive;
    }

    public void setAltActive(boolean active) {
        if (this.isAltActive != active) {
            this.isAltActive = active;
            notifyModifierState();
        }
    }

    public boolean isAltActive() {
        return isAltActive;
    }

    public void setEscActive(boolean active) {
        // Use pending logic with timeout
        if (active) {
            if (!isEscActive) {
                setEscPending(true);
            }
        } else {
            if (isEscActive) {
                setEscPending(false);
            }
        }
    }

    public boolean isEscActive() {
        return isEscActive;
    }

    public void showIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            // Focus first: showSoftInput silently fails when this view doesn't
            // hold focus (common right after menus/fullscreen transitions).
            requestFocus();
            imm.showSoftInput(this, 0);
            // The first attempt can be swallowed while the window/insets state
            // settles (e.g. just entered fullscreen); try once more shortly.
            postDelayed(() -> {
                if (!isKeyboardActuallyVisible()) {
                    requestFocus();
                    imm.showSoftInput(this, 0);
                }
            }, 150);
            keyboardVisible = true;
            if (keyboardVisibilityListener != null) keyboardVisibilityListener.onKeyboardVisibilityChanged(true);
        }
    }

    private boolean isKeyboardActuallyVisible() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = getRootWindowInsets();
            return insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        }
        return keyboardVisible;
    }

    public void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
            keyboardVisible = false;
            if (keyboardVisibilityListener != null) keyboardVisibilityListener.onKeyboardVisibilityChanged(false);
        }
    }

    public void toggleKeyboard() {
        // Use the real insets state: the tracked flag can go stale when the IME
        // appears via focus or is dismissed by the system BACK key.
        if (isKeyboardActuallyVisible()) {
            hideIme();
        } else {
            showIme();
        }
    }

    public boolean isKeyboardVisible() {
        return keyboardVisible;
    }

    // ---- Mouse handling ----
    private void sendMouseClick(float x, float y) {
        if (session == null) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        session.sendMouseClick(x, y, (int) cellWidth, (int) cellHeight, w, h);
        // Also ensure viewport is at bottom after click? Termux-like: click should bring back to active area if scrolled?
        // We do not automatically scroll to bottom; user can scroll manually. But many terms auto-scroll on input.
        // Let's not auto scroll; keep history visible.
    }

    public boolean scrollToPreviousPrompt() {
        if (session == null) return false;
        boolean ok = session.scrollToPreviousPrompt();
        if (ok) postInvalidate();
        return ok;
    }

    public boolean scrollToNextPrompt() {
        if (session == null) return false;
        boolean ok = session.scrollToNextPrompt();
        if (ok) postInvalidate();
        return ok;
    }

    public boolean copyLastCommandOutput() {
        if (session == null) return false;
        String text = session.getLastCommandOutput();
        if (text == null || text.isEmpty()) {
            Toast.makeText(getContext(), "No command output", Toast.LENGTH_SHORT).show();
            return false;
        }
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Tinyhack SSH output", text));
            Toast.makeText(getContext(), "Output copied (" + text.length() + " chars)", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private boolean tryOpenHyperlink(float x, float y) {
        if (session == null) return false;
        int col = clampCol(x);
        int row = clampRow(y);
        String url = session.getHyperlinkUri(col, row);
        if (url == null || url.isEmpty()) return false;
        android.util.Log.i(TAG, "Hyperlink tapped at " + col + "," + row + ": " + url);

        boolean directOpen = false;
        try {
            directOpen = getContext().getSharedPreferences("tinyhack_ssh_prefs", Context.MODE_PRIVATE)
                .getBoolean("confirm_url_click", false);
        } catch (Exception ignored) {}
        // per spec: checked -> directly open, unchecked -> show dialog with Open/Copy/Cancel
        if (directOpen) {
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                Toast.makeText(getContext(), url, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(getContext(), "Cannot open link: " + url, Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        // Show confirmation dialog with truncated URL
        String displayUrl = url;
        if (url.length() > 80) {
            displayUrl = url.substring(0, 40) + "\u2026\n\u2026" + url.substring(url.length() - 30);
        }
        try {
            new AlertDialog.Builder(getContext())
                .setTitle("Open link?")
                .setMessage(displayUrl)
                .setPositiveButton("Open", (d, w) -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(getContext(), "Cannot open link: " + url, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Copy Link", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("URL", url));
                        Toast.makeText(getContext(), "Link copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Exception e) {
            // Fallback: open directly if dialog fails (wrong context)
            try {
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(getContext(), "Cannot open link: " + url, Toast.LENGTH_SHORT).show();
            }
        }
        return true;
    }

    // ---- Selection handling ----
    private int clampCol(float x) {
        int cols = renderFrame.cols > 0 ? renderFrame.cols : (int) (getWidth() / cellWidth);
        int c = (int) (x / cellWidth);
        if (c < 0) c = 0;
        if (cols > 0 && c >= cols) c = cols - 1;
        return c;
    }

    private int clampRow(float y) {
        int rows = renderFrame.rows > 0 ? renderFrame.rows : (int) (getHeight() / cellHeight);
        int r = (int) (y / cellHeight);
        if (r < 0) r = 0;
        if (rows > 0 && r >= rows) r = rows - 1;
        return r;
    }

    private void clearSelectionInternal() {
        if (session != null) {
            session.clearSelection();
        }
        setSelectionActive(false);
    }

    public void clearSelection() {
        clearSelectionInternal();
    }

    /** True while a text selection with handles is shown. */
    public boolean hasSelection() {
        return selectionActive || (session != null && session.hasSelection());
    }

    private void setSelectionActive(boolean active) {
        if (selectionActive == active) return;
        selectionActive = active;
        if (!active) {
            stopHandleDrag();
            selStartHandleVisible = false;
            selEndHandleVisible = false;
        }
        if (selectionListener != null) {
            selectionListener.onSelectionActiveChanged(active);
        }
        invalidate();
    }

    /** Long-press entry point: select the word under (x, y) and show handles. */
    private void startTextSelection(float x, float y) {
        if (session == null) return;
        int col = clampCol(x);
        int row = clampRow(y);
        int[] sel = session.selectWord(col, row);
        if (sel == null) {
            // Whitespace / unresolvable cell: anchor an empty selection so the
            // handles appear and can still be dragged.
            session.setSelection(col, row, col, row, false);
        }
        setSelectionActive(true);
    }

    private float handleRadiusPx() {
        return dpToPx(11);
    }

    private float handleTouchRadiusPx() {
        return handleRadiusPx() + dpToPx(16);
    }

    private int hitTestSelectionHandle(float x, float y) {
        if (!selectionActive) return HANDLE_NONE;
        float r = handleTouchRadiusPx();
        if (selStartHandleVisible) {
            float dx = x - selStartHandleX;
            float dy = y - selStartHandleY;
            if (dx * dx + dy * dy <= r * r) return HANDLE_START;
        }
        if (selEndHandleVisible) {
            float dx = x - selEndHandleX;
            float dy = y - selEndHandleY;
            if (dx * dx + dy * dy <= r * r) return HANDLE_END;
        }
        return HANDLE_NONE;
    }

    private void startHandleDrag(int handle, float x, float y) {
        draggingHandle = handle;
        float cx = (handle == HANDLE_START) ? selStartHandleX : selEndHandleX;
        float cy = (handle == HANDLE_START) ? selStartHandleY : selEndHandleY;
        handleGrabOffsetX = x - cx;
        handleGrabOffsetY = y - cy;
        handleDragX = x;
        handleDragY = y;
        lastHandleAutoScrollMs = 0;
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        removeCallbacks(handleAutoScrollRunnable);
        postDelayed(handleAutoScrollRunnable, HANDLE_AUTO_SCROLL_INTERVAL_MS);
        invalidate();
    }

    private void stopHandleDrag() {
        removeCallbacks(handleAutoScrollRunnable);
        if (draggingHandle == HANDLE_NONE) return;
        draggingHandle = HANDLE_NONE;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }

    /**
     * Move the dragged selection endpoint to the finger position, scrolling
     * the viewport when the handle is held at the top/bottom edge so the
     * selection can extend into scrollback history.
     */
    private void applyHandleDrag() {
        if (session == null || draggingHandle == HANDLE_NONE) return;

        float cx = handleDragX - handleGrabOffsetX;
        float cy = handleDragY - handleGrabOffsetY;

        // Edge auto-scroll (primary screen only; alt screen has no scrollback)
        if (!renderFrame.altScreenActive) {
            float topEdge = cellHeight * 1.5f;
            float bottomEdge = getHeight() - cellHeight * 1.5f;
            long now = android.os.SystemClock.uptimeMillis();
            if ((cy < topEdge || cy > bottomEdge)
                    && now - lastHandleAutoScrollMs >= HANDLE_AUTO_SCROLL_INTERVAL_MS) {
                lastHandleAutoScrollMs = now;
                if (cy < topEdge) {
                    session.scroll(2, -HANDLE_AUTO_SCROLL_STEP_ROWS); // up into history
                } else {
                    session.scroll(2, HANDLE_AUTO_SCROLL_STEP_ROWS);  // down toward active area
                }
            }
        }

        // The handle circle sits on the bottom corner of its endpoint cell:
        // nudge by half a cell so the corner maps back to the cell itself.
        int col = clampCol(draggingHandle == HANDLE_START ? cx + cellWidth * 0.5f : cx - cellWidth * 0.5f);
        int row = clampRow(cy - cellHeight * 0.5f);

        int[] sel = session.getSelectionViewport();
        if (sel == null) {
            setSelectionActive(false);
            return;
        }
        if (draggingHandle == HANDLE_START) {
            session.setSelection(col, row, sel[2], sel[3], false);
        } else {
            session.setSelection(sel[0], sel[1], col, row, false);
        }
        invalidate();
    }

    /**
     * Refresh selection handle anchors from the (text-anchored, tracked)
     * native selection, and keep the viewport pinned while selecting so new
     * output does not scroll the selection away.
     */
    private void updateSelectionState() {
        if (selectionActive && session != null) {
            if (prevScrollTotal > 0
                    && renderFrame.scrollTotal > prevScrollTotal
                    && prevScrollOffset + prevScrollLen >= prevScrollTotal) {
                // Viewport was following the output; pin it to keep the same
                // content (and selection) on screen.
                long target = Math.min(prevScrollOffset,
                        Math.max(0, renderFrame.scrollTotal - renderFrame.scrollLen));
                session.scrollToRow((int) target);
            }

            int[] sel = session.getSelectionViewport();
            if (sel == null) {
                // Native selection disappeared (cleared by the app, pruned
                // history, screen switch) — dismiss the handles.
                setSelectionActive(false);
            } else {
                selStartHandleX = sel[0] * cellWidth;
                selStartHandleY = (sel[1] + 1) * cellHeight;
                selEndHandleX = (sel[2] + 1) * cellWidth;
                selEndHandleY = (sel[3] + 1) * cellHeight;
                selStartHandleVisible = (sel[4] & 1) != 0;
                selEndHandleVisible = (sel[4] & 2) != 0;
            }
        }
        prevScrollTotal = renderFrame.scrollTotal;
        prevScrollOffset = renderFrame.scrollOffset;
        prevScrollLen = renderFrame.scrollLen;
    }

    private void drawSelectionHandles(Canvas canvas) {
        if (!selectionActive) return;
        float r = handleRadiusPx();
        if (draggingHandle != HANDLE_NONE) r *= 1.15f;
        float inner = r - dpToPx(1);
        if (selStartHandleVisible) {
            canvas.drawCircle(selStartHandleX, selStartHandleY, r, handlePaint);
            canvas.drawCircle(selStartHandleX, selStartHandleY, inner, handleBorderPaint);
        }
        if (selEndHandleVisible) {
            canvas.drawCircle(selEndHandleX, selEndHandleY, r, handlePaint);
            canvas.drawCircle(selEndHandleX, selEndHandleY, inner, handleBorderPaint);
        }
    }

    /**
     * Copy current selection to clipboard and dismiss the selection.
     * Returns true if something was copied.
     */
    public boolean copySelection() {
        if (session == null) return false;
        String text = session.getSelectionText();
        if (text == null || text.isEmpty()) {
            // Try hasSelection check to avoid empty copy
            if (!session.hasSelection()) {
                Toast.makeText(getContext(), "No selection", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        if (text != null && !text.isEmpty()) {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Tinyhack SSH selection", text));
                Toast.makeText(getContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show();
            }
            clearSelectionInternal();
            return true;
        } else {
            // Empty but had selection -> just clear
            clearSelectionInternal();
            return false;
        }
    }

    /**
     * Text currently on the clipboard, or null if empty/unavailable.
     */
    public String getClipboardText() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        return text != null && text.length() > 0 ? text.toString() : null;
    }

    /**
     * Paste text into the terminal and dismiss the selection.
     */
    public boolean pasteText(String text) {
        if (session == null || text == null || text.isEmpty()) return false;
        session.writePaste(text);
        clearSelectionInternal();
        return true;
    }

    private boolean handleThreeFinger(MotionEvent event) {
        int count = event.getPointerCount();
        int action = event.getActionMasked();
        // Trigger on third finger down
        if (count == 3 && action == MotionEvent.ACTION_POINTER_DOWN) {
            // Ensure it's the third pointer that caused this
            int index = event.getActionIndex();
            // Optionally check that the three pointers are relatively stable (not huge move)
            showThreeFingerMenu();
            threeFingerConsumed = true;
            return true;
        }
        // Reset flag when all fingers lifted
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            threeFingerConsumed = false;
        }
        // If we consumed three finger, ignore rest of gesture until lift
        if (threeFingerConsumed && count >= 2) {
            // If user still has 2-3 fingers, consume
            if (action == MotionEvent.ACTION_MOVE) return true;
        }
        return false;
    }

    public void showThreeFingerMenu() {
        Context ctx = getContext();
        // Need activity context for dialog
        if (ctx == null) return;
        // Ensure we run on UI thread
        post(() -> {
            final AlertDialog[] dlg = new AlertDialog[1];
            Runnable dismiss = () -> { if (dlg[0] != null) dlg[0].dismiss(); };

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int padH = dpToPx(20);
            root.setPadding(padH, dpToPx(4), padH, dpToPx(4));

            // --- Icon row: fullscreen / keyboard / drawer / settings ---
            LinearLayout iconRow = new LinearLayout(ctx);
            iconRow.setOrientation(LinearLayout.HORIZONTAL);
            iconRow.setGravity(android.view.Gravity.CENTER);
            iconRow.setPadding(0, dpToPx(12), 0, dpToPx(4));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_fullscreen,
                    terminalMenuActionListener != null && terminalMenuActionListener.isFullscreen()
                            ? "Exit fullscreen" : "Fullscreen",
                    v -> {
                        if (terminalMenuActionListener != null) terminalMenuActionListener.toggleFullscreen();
                    }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_keyboard,
                    isKeyboardActuallyVisible() ? "Hide keyboard" : "Show keyboard",
                    v -> { dismiss.run(); toggleKeyboard(); }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_drawer_menu,
                    "Open drawer", v -> {
                        dismiss.run();
                        if (terminalMenuActionListener != null) terminalMenuActionListener.openDrawer();
                    }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_add_session,
                    "New from profile", v -> {
                        dismiss.run();
                        showProfilePicker();
                    }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_profiles,
                    "Manage Profiles", v -> {
                        dismiss.run();
                        startActivityFromMenu(ctx, com.tinyhack.ssh.ui.ConnectionProfilesActivity.class, "Cannot open Profiles");
                    }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_keys,
                    "SSH Keys", v -> {
                        dismiss.run();
                        startActivityFromMenu(ctx, com.tinyhack.ssh.ssh.SshKeysActivity.class, "Cannot open SSH Keys");
                    }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_agent,
                    "SSH Agent", v -> {
                        dismiss.run();
                        startActivityFromMenu(ctx, com.tinyhack.ssh.ssh.SshAgentActivity.class, "Cannot open SSH Agent");
                    }));
            iconRow.addView(makeMenuIconButton(ctx, com.tinyhack.ssh.R.drawable.ic_settings_gear,
                    "Settings", v -> {
                        dismiss.run();
                        startActivityFromMenu(ctx, com.tinyhack.ssh.ui.SettingsActivity.class, "Cannot open Settings");
                    }));
            android.widget.HorizontalScrollView iconScroll = new android.widget.HorizontalScrollView(ctx);
            iconScroll.setHorizontalScrollBarEnabled(false);
            iconScroll.addView(iconRow);
            root.addView(iconScroll);

            // --- Sessions: tap to switch ---
            root.addView(makeMenuHeader(ctx, "SESSIONS"));
            java.util.List<TerminalSession> sessions = terminalMenuActionListener != null
                    ? terminalMenuActionListener.getSessionsForMenu()
                    : java.util.Collections.emptyList();
            if (sessions.isEmpty()) {
                TextView empty = new TextView(ctx);
                empty.setText("No sessions");
                empty.setTextColor(0xFF888888);
                empty.setTextSize(13);
                empty.setPadding(0, dpToPx(8), 0, dpToPx(8));
                root.addView(empty);
            }
            for (TerminalSession s : sessions) {
                boolean current = s == session;
                root.addView(makeMenuSessionRow(ctx, s, current, clicked -> {
                    dismiss.run();
                    if (terminalMenuActionListener != null) {
                        terminalMenuActionListener.switchToSession(clicked);
                    }
                }));
            }

            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.addView(root);

            dlg[0] = new AlertDialog.Builder(ctx)
                .setTitle("Quick menu")
                .setView(sv)
                .setNegativeButton("Close", null)
                .show();
        });
        // Haptic feedback
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    private void startActivityFromMenu(Context ctx, Class<?> cls, String errorToast) {
        try {
            android.content.Intent intent = new android.content.Intent(ctx, cls);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(ctx, errorToast, Toast.LENGTH_SHORT).show();
        }
    }

    private TextView makeMenuHeader(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.05f);
        tv.setPadding(0, dpToPx(12), 0, 0);
        return tv;
    }

    private android.widget.ImageButton makeMenuIconButton(Context ctx, int iconRes, String desc, View.OnClickListener listener) {
        android.widget.ImageButton btn = new android.widget.ImageButton(ctx);
        btn.setImageResource(iconRes);
        btn.setBackgroundColor(0x00000000);
        btn.setContentDescription(desc);
        btn.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
        btn.setOnClickListener(v -> {
            Toast.makeText(ctx, desc, Toast.LENGTH_SHORT).show();
            listener.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(48));
        lp.setMargins(dpToPx(6), 0, dpToPx(6), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private View makeMenuSessionRow(Context ctx, TerminalSession s, boolean current,
                                     java.util.function.Consumer<TerminalSession> onPick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));

        TextView title = new TextView(ctx);
        String t = s.getDisplayTitle() != null ? s.getDisplayTitle() : "session";
        title.setText((current ? "▶ " : "   ") + t);
        title.setTextColor(current ? 0xFF7DA9FF : 0xFFE0E0E0);
        title.setTextSize(15);
        title.setTypeface(current ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        row.addView(title);

        TextView status = new TextView(ctx);
        status.setText(s.isRunning() ? "running" : "closed");
        status.setTextColor(s.isRunning() ? 0xFF7DFF9A : 0xFFFF7D7D);
        status.setTextSize(11);
        status.setPadding(dpToPx(18), 0, 0, 0);
        row.addView(status);

        row.setOnClickListener(v -> onPick.accept(s));
        // Rounded-ish feedback: highlight on press is theme-default; add a divider
        View divider = new View(ctx);
        divider.setBackgroundColor(0xFF333333);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpToPx(1) / 2)));

        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);
        wrapper.addView(divider);
        return wrapper;
    }

    /** Picker shown from the Quick menu's "+" icon: start a new session from a profile. */
    private void showProfilePicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        java.util.List<com.tinyhack.ssh.model.ConnectionProfile> profiles =
                terminalMenuActionListener != null ? terminalMenuActionListener.getProfilesForMenu()
                        : java.util.Collections.emptyList();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dpToPx(20);
        root.setPadding(padH, dpToPx(4), padH, dpToPx(4));

        if (profiles.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("No profiles — create one via Manage Profiles");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(13);
            empty.setPadding(0, dpToPx(8), 0, dpToPx(8));
            root.addView(empty);
        }
        for (com.tinyhack.ssh.model.ConnectionProfile p : profiles) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));

            TextView badge = new TextView(ctx);
            badge.setText(p.getTypeLabel());
            badge.setTextSize(11);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
            int badgeColor;
            switch (p.getType()) {
                case SSH: badgeColor = 0xFF7DA9FF; break;
                case MOSH: badgeColor = 0xFFB07DFF; break;
                default: badgeColor = 0xFF7DFF9A; break;
            }
            badge.setTextColor(badgeColor);
            badge.setBackgroundColor(0xFF2E2E2E);
            row.addView(badge);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(dpToPx(10), 0, 0, 0);
            texts.setLayoutParams(tp);

            TextView name = new TextView(ctx);
            name.setText(p.getName());
            name.setTextColor(0xFFE0E0E0);
            name.setTextSize(15);
            texts.addView(name);

            TextView sub = new TextView(ctx);
            sub.setText(p.getDisplaySubtitle());
            sub.setTextColor(0xFF888888);
            sub.setTextSize(11);
            texts.addView(sub);
            row.addView(texts);

            final com.tinyhack.ssh.model.ConnectionProfile picked = p;
            row.setOnClickListener(v -> {
                if (pickerDialog[0] != null) pickerDialog[0].dismiss();
                if (terminalMenuActionListener != null) {
                    terminalMenuActionListener.startSessionWithProfile(picked);
                }
            });

            root.addView(row);

            View divider = new View(ctx);
            divider.setBackgroundColor(0xFF333333);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpToPx(1) / 2)));
            root.addView(divider);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(root);

        pickerDialog[0] = new AlertDialog.Builder(ctx)
                .setTitle("Start profile")
                .setView(sv)
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final AlertDialog[] pickerDialog = new AlertDialog[1];

    public void showFontSelectionDialog() {
        Context ctx = getContext();
        if (ctx == null) return;
        post(() -> {
            String[] displayNames = new String[FONT_FAMILIES.length];
            int checkedIdx = 0;
            for (int i = 0; i < FONT_FAMILIES.length; i++) {
                displayNames[i] = getFontDisplayName(FONT_FAMILIES[i]);
                if (FONT_FAMILIES[i].equals(currentFontFamily)) {
                    checkedIdx = i;
                    displayNames[i] = displayNames[i] + " ✓";
                }
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setTitle("Select Font")
                .setSingleChoiceItems(displayNames, checkedIdx, (dialog, which) -> {
                    String selected = FONT_FAMILIES[which];
                    setFontFamily(selected);
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        blinkHandler.postDelayed(blinkRunnable, 500);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        blinkHandler.removeCallbacks(blinkRunnable);
        escHandler.removeCallbacks(escTimeoutRunnable);
        stopHandleDrag();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && session != null) {
            int cols = Math.max(10, (int) (w / cellWidth));
            int rows = Math.max(4, (int) (h / cellHeight));
            session.resize(rows, cols, (int) cellWidth, (int) cellHeight);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (session != null) {
            session.updateRenderFrame(renderFrame);
            updateSelectionState();
        }

        // Kitty keyboard spec: flags must be reset when an application exits
        // the alternate screen. Some TUIs (e.g. herdr) forget to pop them, so
        // the shell afterwards receives CSI-u encodings it cannot parse. Force
        // flags off once when we observe the alt -> primary transition.
        if (session != null) {
            boolean alt = renderFrame.altScreenActive;
            if (lastAltScreenActive && !alt && renderFrame.kittyKeyboardFlags != 0) {
                session.feedVt("\u001b[=0;1u".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            lastAltScreenActive = alt;
        }

        int cols = renderFrame.cols;
        int rows = renderFrame.rows;
        if (cols <= 0 || rows <= 0) return;

        // Draw default background
        canvas.drawColor(renderFrame.defaultBgColor);

        char[] chars = renderFrame.chars;
        int[] fgColors = renderFrame.fgColors;
        int[] bgColors = renderFrame.bgColors;
        int[] styleFlags = renderFrame.styleFlags;

        if (chars == null || fgColors == null || bgColors == null || styleFlags == null) {
            return;
        }

        // Negative-z Kitty placements sit above the terminal's default
        // background but below glyphs and explicit cell backgrounds.
        drawKittyPlacements(canvas, true);

        for (int r = 0; r < rows; r++) {
            float rowTop = r * cellHeight;
            float rowBottom = rowTop + cellHeight;
            float textY = rowTop + fontBaseline;

            // First pass: Draw cell backgrounds where different from default
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= bgColors.length) break;

                int bg = bgColors[idx];
                int flags = styleFlags[idx];
                boolean isSelected = (flags & (1 << 8)) != 0;
                boolean isInverse = (flags & (1 << 5)) != 0;

                if (isInverse) {
                    bg = fgColors[idx];
                }

                if (bg != renderFrame.defaultBgColor) {
                    bgPaint.setColor(bg);
                    canvas.drawRect(c * cellWidth, rowTop, (c + 1) * cellWidth, rowBottom, bgPaint);
                }

                if (isSelected) {
                    canvas.drawRect(c * cellWidth, rowTop, (c + 1) * cellWidth, rowBottom, selectionPaint);
                }
            }

            // Second pass: Draw text runs batching by style & fg color
            int[] underlineStyles = renderFrame.underlineStyles;
            int[] underlineColors = renderFrame.underlineColors;
            int runStart = 0;
            while (runStart < cols) {
                int startIdx = r * cols + runStart;
                if (startIdx >= chars.length) break;

                int runFg = fgColors[startIdx];
                int runFlags = styleFlags[startIdx];
                if ((runFlags & (1 << 5)) != 0) { // Inverse
                    runFg = bgColors[startIdx];
                }
                int runUlStyle = (underlineStyles != null && startIdx < underlineStyles.length) ? underlineStyles[startIdx] : 0;
                int runUlColor = (underlineColors != null && startIdx < underlineColors.length) ? underlineColors[startIdx] : 0;
                boolean runHyperlink = (runFlags & STYLE_HYPERLINK) != 0;
                if (runHyperlink && runUlStyle == 0) runUlStyle = 1; // hyperlink forces single underline

                int runEnd = runStart + 1;
                while (runEnd < cols) {
                    int nextIdx = r * cols + runEnd;
                    if (nextIdx >= chars.length) break;

                    int nextFg = fgColors[nextIdx];
                    int nextFlags = styleFlags[nextIdx];
                    if ((nextFlags & (1 << 5)) != 0) {
                        nextFg = bgColors[nextIdx];
                    }
                    int nextUlStyle = (underlineStyles != null && nextIdx < underlineStyles.length) ? underlineStyles[nextIdx] : 0;
                    int nextUlColor = (underlineColors != null && nextIdx < underlineColors.length) ? underlineColors[nextIdx] : 0;
                    boolean nextHyperlink = (nextFlags & STYLE_HYPERLINK) != 0;
                    if (nextHyperlink && nextUlStyle == 0) nextUlStyle = 1;

                    if (nextFg != runFg || nextFlags != runFlags || nextUlStyle != runUlStyle || nextUlColor != runUlColor) {
                        break;
                    }
                    runEnd++;
                }

                // Apply styles to paint
                boolean bold = (runFlags & (1 << 0)) != 0;
                boolean italic = (runFlags & (1 << 1)) != 0;
                // underline handled via styled custom drawing, not Paint underline
                boolean hyperlink = (runFlags & STYLE_HYPERLINK) != 0;
                int effectiveUlStyle = runUlStyle;
                if (hyperlink && effectiveUlStyle == 0) effectiveUlStyle = 1;
                boolean strike = (runFlags & (1 << 3)) != 0;
                boolean dim = (runFlags & (1 << 4)) != 0;
                boolean invisible = (runFlags & (1 << 7)) != 0;

                if (!invisible) {
                    if (bold && italic) {
                        textPaint.setTypeface(boldItalicTypeface);
                    } else if (bold) {
                        textPaint.setTypeface(boldTypeface);
                    } else if (italic) {
                        textPaint.setTypeface(italicTypeface);
                    } else {
                        textPaint.setTypeface(regularTypeface);
                    }

                    int color = runFg;
                    if (dim) {
                        int a = (color >>> 24) / 2;
                        color = (a << 24) | (color & 0x00FFFFFF);
                    }
                    textPaint.setColor(color);
                    textPaint.setUnderlineText(false);
                    textPaint.setStrikeThruText(strike);

                    int runLen = runEnd - runStart;
                    float left = runStart * cellWidth;
                    float expectedWidth = runLen * cellWidth;
                    float actualWidth = textPaint.measureText(chars, startIdx, runLen);

                    if (Math.abs(actualWidth - expectedWidth) > 0.5f && actualWidth > 0) {
                        canvas.save();
                        canvas.scale(expectedWidth / actualWidth, 1.0f, left, textY);
                        canvas.drawText(chars, startIdx, runLen, left, textY, textPaint);
                        canvas.restore();
                    } else {
                        canvas.drawText(chars, startIdx, runLen, left, textY, textPaint);
                    }

                    if (effectiveUlStyle != 0) {
                        int ulColor = runUlColor != 0 ? runUlColor : color;
                        float ulLeft = runStart * cellWidth;
                        float ulRight = runEnd * cellWidth;
                        drawStyledUnderline(canvas, ulLeft, ulRight, rowTop, rowBottom, textY, effectiveUlStyle, ulColor);
                    }
                }

                runStart = runEnd;
            }
        }

        // z >= 0 is the normal Kitty graphics layer. Apps such as
        // terminal-browser/tode render their entire UI through this path.
        drawKittyPlacements(canvas, false);

        // Draw cursor (only in normal mode or when not selecting? Keep always)
        if (renderFrame.cursorVisible && (!renderFrame.cursorBlinking || cursorBlinkState)) {
            int cx = renderFrame.cursorX;
            int cy = renderFrame.cursorY;
            if (cx >= 0 && cx < cols && cy >= 0 && cy < rows) {
                float curLeft = cx * cellWidth;
                float curTop = cy * cellHeight;
                float curRight = curLeft + cellWidth;
                float curBottom = curTop + cellHeight;

                cursorPaint.setColor(renderFrame.cursorColor);

                switch (renderFrame.cursorStyle) {
                    case 0: // Bar cursor
                        canvas.drawRect(curLeft, curTop, curLeft + 3f, curBottom, cursorPaint);
                        break;
                    case 2: // Underline cursor
                        canvas.drawRect(curLeft, curBottom - 3f, curRight, curBottom, cursorPaint);
                        break;
                    case 3: // Hollow block cursor
                        cursorPaint.setStyle(Paint.Style.STROKE);
                        cursorPaint.setStrokeWidth(2f);
                        canvas.drawRect(curLeft + 1, curTop + 1, curRight - 1, curBottom - 1, cursorPaint);
                        cursorPaint.setStyle(Paint.Style.FILL);
                        break;
                    case 1: // Block cursor
                    default:
                        cursorPaint.setStyle(Paint.Style.FILL);
                        canvas.drawRect(curLeft, curTop, curRight, curBottom, cursorPaint);

                        // Invert text inside block cursor for readability
                        int cIdx = cy * cols + cx;
                        if (cIdx < chars.length && chars[cIdx] != ' ' && chars[cIdx] != 0) {
                            textPaint.setTypeface(regularTypeface);
                            textPaint.setColor(renderFrame.defaultBgColor);
                            textPaint.setUnderlineText(false);
                            textPaint.setStrikeThruText(false);
                            float cActualWidth = textPaint.measureText(chars, cIdx, 1);
                            if (Math.abs(cActualWidth - cellWidth) > 0.5f && cActualWidth > 0) {
                                canvas.save();
                                canvas.scale(cellWidth / cActualWidth, 1.0f, curLeft, curTop + fontBaseline);
                                canvas.drawText(chars, cIdx, 1, curLeft, curTop + fontBaseline, textPaint);
                                canvas.restore();
                            } else {
                                canvas.drawText(chars, cIdx, 1, curLeft, curTop + fontBaseline, textPaint);
                            }
                        }
                        break;
                }
            }
        }

        // Selection handles (drawn above glyphs, below the session-closed overlay)
        drawSelectionHandles(canvas);

        // Dead session indicator: the process behind this terminal has exited
        // (shell exit, Ctrl-D, SSH disconnect); make that obvious. After the
        // user taps "Dismiss" the overlay stays away so the final screen can
        // be inspected and copied.
        if (session != null && !session.isRunning() && !sessionClosedDismissed) {
            drawSessionClosedOverlay(canvas);
        }
    }

    private void drawSessionClosedOverlay(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawColor(scrimPaint.getColor());

        int exitCode = session.getExitCode();
        String title = "<session closed>";
        String subtitle = (exitCode >= 0)
            ? ("exit code " + exitCode + " — dismiss to inspect/copy the screen")
            : "dismiss to inspect/copy the screen";

        String reopenLabel = "Reopen";
        String closeLabel = "Close session";
        String dismissLabel = "Dismiss";

        float padH = dpToPx(24);
        float padV = dpToPx(16);
        float gap = dpToPx(6);
        float btnTopGap = dpToPx(14);
        float availW = w - dpToPx(32);
        float maxTextW = availW - padH * 2;

        // Wrap the subtitle so long exit-code lines are never trimmed
        java.util.List<String> subLines = wrapText(subtitle, maxTextW, bannerSubPaint);
        float titleWidth = bannerTitlePaint.measureText(title);
        float subWidth = 0f;
        for (String line : subLines) {
            subWidth = Math.max(subWidth, bannerSubPaint.measureText(line));
        }
        float textWidth = Math.max(titleWidth, subWidth);

        float btnH = dpToPx(36);
        float btnGap = dpToPx(12);
        float btnPadH = dpToPx(20);
        float reopenW = bannerBtnTextPaint.measureText(reopenLabel) + btnPadH * 2;
        float closeW = Math.max(bannerBtnTextPaint.measureText(closeLabel) + btnPadH * 2, dpToPx(120));
        float dismissW = bannerBtnTextPaint.measureText(dismissLabel) + btnPadH * 2;
        float buttonsRowW = reopenW + btnGap + closeW + btnGap + dismissW;
        // Narrow screens: shrink the close label before letting the row clip
        if (buttonsRowW > maxTextW) {
            closeLabel = "Close";
            closeW = bannerBtnTextPaint.measureText(closeLabel) + btnPadH * 2;
            buttonsRowW = reopenW + btnGap + closeW + btnGap + dismissW;
        }

        float cardW = Math.min(availW,
            Math.max(textWidth + padH * 2, buttonsRowW + padH * 2));
        float titleH = bannerTitlePaint.descent() - bannerTitlePaint.ascent();
        float subH = bannerSubPaint.descent() - bannerSubPaint.ascent();
        float cardH = padV * 2 + titleH + gap + subH * subLines.size() + gap * Math.max(0, subLines.size() - 1)
            + btnTopGap + btnH;

        float left = (w - cardW) / 2f;
        float top = (h - cardH) / 2f;
        float right = left + cardW;
        float bottom = top + cardH;
        float radius = dpToPx(12);

        canvas.drawRoundRect(left, top, right, bottom, radius, radius, cardPaint);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, cardBorderPaint);

        float titleY = top + padV - bannerTitlePaint.ascent();
        canvas.drawText(title, w / 2f, titleY, bannerTitlePaint);
        float subY = titleY;
        for (String line : subLines) {
            subY += bannerTitlePaint.descent() + gap - bannerSubPaint.ascent();
            canvas.drawText(line, w / 2f, subY, bannerSubPaint);
        }

        // Buttons: [Reopen] [Close session] [Dismiss]
        float btnTop = subY + bannerSubPaint.descent() + btnTopGap;
        float btnRadius = dpToPx(8);
        float closeLeft = left + (cardW - buttonsRowW) / 2f;
        float btnTextY = btnTop + btnH / 2f - (bannerBtnTextPaint.descent() + bannerBtnTextPaint.ascent()) / 2f;

        sessionClosedBtnReopenBounds.set(closeLeft, btnTop, closeLeft + reopenW, btnTop + btnH);
        canvas.drawRoundRect(sessionClosedBtnReopenBounds, btnRadius, btnRadius, bannerBtnFillPaint);
        canvas.drawText(reopenLabel, sessionClosedBtnReopenBounds.centerX(), btnTextY, bannerBtnTextPaint);

        sessionClosedBtnCloseBounds.set(
            sessionClosedBtnReopenBounds.right + btnGap, btnTop,
            sessionClosedBtnReopenBounds.right + btnGap + closeW, btnTop + btnH);
        int saveColor = bannerBtnTextPaint.getColor();
        bannerBtnTextPaint.setColor(0xFFE0E0E0);
        canvas.drawRoundRect(sessionClosedBtnCloseBounds, btnRadius, btnRadius, cardPaint);
        canvas.drawRoundRect(sessionClosedBtnCloseBounds, btnRadius, btnRadius, bannerBtnBorderPaint);
        canvas.drawText(closeLabel, sessionClosedBtnCloseBounds.centerX(), btnTextY, bannerBtnTextPaint);

        sessionClosedBtnDismissBounds.set(
            sessionClosedBtnCloseBounds.right + btnGap, btnTop,
            sessionClosedBtnCloseBounds.right + btnGap + dismissW, btnTop + btnH);
        canvas.drawRoundRect(sessionClosedBtnDismissBounds, btnRadius, btnRadius, cardPaint);
        canvas.drawRoundRect(sessionClosedBtnDismissBounds, btnRadius, btnRadius, bannerBtnBorderPaint);
        canvas.drawText(dismissLabel, sessionClosedBtnDismissBounds.centerX(), btnTextY, bannerBtnTextPaint);
        bannerBtnTextPaint.setColor(saveColor);
    }

    /** Greedy word-wrap used by the session-closed overlay subtitle. */
    private static java.util.List<String> wrapText(String text, float maxWidth, Paint paint) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (maxWidth <= 0 || paint.measureText(text) <= maxWidth) {
            lines.add(text);
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) <= maxWidth || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }

    private void drawKittyPlacements(Canvas canvas, boolean negativeZ) {
        int count = Math.min(renderFrame.kittyPlacementCount, RenderFrame.MAX_KITTY_PLACEMENTS);
        for (int i = 0; i < count; i++) {
            if ((renderFrame.kittyZ[i] < 0) != negativeZ) continue;
            android.graphics.Bitmap bitmap = renderFrame.kittyBitmaps[i];
            if (bitmap == null || bitmap.isRecycled()) continue;

            int srcWidth = renderFrame.kittySrcWidth[i];
            int srcHeight = renderFrame.kittySrcHeight[i];
            int dstWidth = renderFrame.kittyDstWidth[i];
            int dstHeight = renderFrame.kittyDstHeight[i];
            if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) continue;

            int srcLeft = renderFrame.kittySrcLeft[i];
            int srcTop = renderFrame.kittySrcTop[i];
            kittySourceRect.set(srcLeft, srcTop, srcLeft + srcWidth, srcTop + srcHeight);
            int dstLeft = renderFrame.kittyDstLeft[i];
            int dstTop = renderFrame.kittyDstTop[i];
            kittyDestinationRect.set(dstLeft, dstTop, dstLeft + dstWidth, dstTop + dstHeight);
            canvas.drawBitmap(bitmap, kittySourceRect, kittyDestinationRect, kittyImagePaint);
        }
    }

    private void drawStyledUnderline(Canvas canvas, float left, float right, float rowTop, float rowBottom, float textY, int style, int color) {
        float density = getResources().getDisplayMetrics().density;
        float thickness = Math.max(1.5f * density, 1f);
        float y = textY + 3f * density;
        // Clamp inside cell
        if (y > rowBottom - 1f) y = rowBottom - 1f;
        if (y < rowTop + 1f) y = rowTop + 1f;

        underlinePaint.setColor(color);
        underlinePaint.setStrokeWidth(thickness);
        underlinePaint.setStrokeCap(Paint.Cap.ROUND);
        underlinePaint.setStrokeJoin(Paint.Join.ROUND);
        underlinePaint.setPathEffect(null);

        switch (style) {
            case 1: // SINGLE
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
            case 2: // DOUBLE
                canvas.drawLine(left, y, right, y, underlinePaint);
                float y2 = y + 3f * density;
                if (y2 < rowBottom - 1f) canvas.drawLine(left, y2, right, y2, underlinePaint);
                break;
            case 4: // DOTTED
                underlinePaint.setPathEffect(new DashPathEffect(new float[]{1f * density, 2.5f * density}, 0));
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
            case 5: // DASHED
                underlinePaint.setPathEffect(new DashPathEffect(new float[]{6f * density, 3f * density}, 0));
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
            case 3: // CURLY (wavy)
                underlinePaint.setStyle(Paint.Style.STROKE);
                Path path = new Path();
                float waveLen = 8f * density;
                float amp = 2f * density;
                path.moveTo(left, y);
                for (float x = left; x < right; x += waveLen) {
                    float nextX = Math.min(x + waveLen, right);
                    float midX = x + waveLen * 0.5f;
                    boolean up = ((int) (x / waveLen) % 2) == 0;
                    float ctrlY = up ? y - amp : y + amp;
                    // Use quad for single hump per half-wave, or two quads per wave
                    path.quadTo(midX, ctrlY, nextX, y);
                }
                canvas.drawPath(path, underlinePaint);
                break;
            default: // NONE or unknown: single
                canvas.drawLine(left, y, right, y, underlinePaint);
                break;
        }
        underlinePaint.setPathEffect(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Handle three-finger menu first
        // An active selection-handle drag consumes the whole gesture stream.
        if (draggingHandle != HANDLE_NONE) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_CANCEL:
                    stopHandleDrag();
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    handleDragX = event.getX();
                    handleDragY = event.getY();
                    applyHandleDrag();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        stopHandleDrag();
                    }
                    break;
            }
            return true;
        }

        if (handleThreeFinger(event)) {
            // Still feed to scale detector to avoid stuck state? No need
            return true;
        }

        // <session closed> overlay is modal: only its buttons are tappable.
        // After "Dismiss" the overlay disappears and normal interaction
        // (scroll, selection, copy) works on the final screen.
        if (isSessionClosedOverlayShowing()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    overlayDownOnReopen = sessionClosedBtnReopenBounds.contains(event.getX(), event.getY());
                    overlayDownOnClose = sessionClosedBtnCloseBounds.contains(event.getX(), event.getY());
                    overlayDownOnDismiss = sessionClosedBtnDismissBounds.contains(event.getX(), event.getY());
                    break;
                case MotionEvent.ACTION_UP:
                    if (overlayDownOnReopen && sessionClosedBtnReopenBounds.contains(event.getX(), event.getY())) {
                        if (reopenSessionRequestedListener != null) {
                            reopenSessionRequestedListener.onReopenSessionRequested();
                        }
                    } else if (overlayDownOnClose && sessionClosedBtnCloseBounds.contains(event.getX(), event.getY())) {
                        if (closeSessionRequestedListener != null) {
                            closeSessionRequestedListener.onCloseSessionRequested();
                        }
                    } else if (overlayDownOnDismiss && sessionClosedBtnDismissBounds.contains(event.getX(), event.getY())) {
                        sessionClosedDismissed = true;
                        invalidate();
                    }
                    overlayDownOnReopen = false;
                    overlayDownOnClose = false;
                    overlayDownOnDismiss = false;
                    break;
            }
            return true;
        }

        // Always feed scale detector
        scaleGestureDetector.onTouchEvent(event);

        // Grabbing a selection handle starts a handle drag instead of the
        // normal tap/scroll gesture handling.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int handle = hitTestSelectionHandle(event.getX(), event.getY());
            if (handle != HANDLE_NONE) {
                startHandleDrag(handle, event.getX(), event.getY());
                return true;
            }
        }

        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new TerminalInputConnection(this, true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return onKeyDown(event.getKeyCode(), event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session == null) return super.onKeyDown(keyCode, event);

        // Never consume system navigation keys: BACK must reach the activity
        // (dismiss selection, close drawer, move task) instead of the PTY.
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
                return super.onKeyDown(keyCode, event);
        }

        // Semantic prompt navigation: Ctrl+Shift+Up / Down
        if (event.isCtrlPressed() && event.isShiftPressed()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                if (scrollToPreviousPrompt()) return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                if (scrollToNextPrompt()) return true;
            }
        }

        // Handle ESC key with desktop-like timeout behavior
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            handleEscPress();
            return true;
        }

        int mods = 0;
        if (event.isShiftPressed()) mods |= KeyCodes.MODS_SHIFT;
        if (event.isCtrlPressed() || isCtrlActive) mods |= KeyCodes.MODS_CTRL;
        if (event.isAltPressed() || isAltActive) mods |= KeyCodes.MODS_ALT;
        if (event.isMetaPressed()) mods |= KeyCodes.MODS_SUPER;

        int ghosttyKey = KeyCodes.mapAndroidKeyCode(keyCode);
        int unicodeChar = event.getUnicodeChar(event.getMetaState());
        String text = null;
        if (unicodeChar > 0 && unicodeChar < 0x10000 && !Character.isISOControl(unicodeChar)) {
            text = String.valueOf((char) unicodeChar);
        }

        boolean wasEsc = isEscActive;

        // Auto-reset sticky modifiers after key press, cancelling ESC timeout if needed
        if (isCtrlActive || isAltActive || isEscActive) {
            isCtrlActive = false;
            isAltActive = false;
            if (isEscActive) {
                cancelEscTimeout();
                isEscActive = false;
            }
            notifyModifierState();
        }

        if (wasEsc) {
            if (text != null && !text.isEmpty()) {
                // ESC + key atomically within timeout -> F10 etc. (ESC + text)
                session.write("\u001b" + text);
                return true;
            } else {
                // Was ESC pending but next key has no text (e.g., arrow, F-key), send ESC then the key normally
                // First flush the pending ESC as standalone, then process current key as normal
                // We already cleared isEscActive, so flush pending ESC
                if (session != null) {
                    session.write("\u001b");
                }
                // Fall through to handle current key normally
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DEL && mods == 0) {
            session.write("\u007f");
            return true;
        }

        session.writeKey(ghosttyKey, KeyCodes.ACTION_PRESS, mods, text);
        return true;
    }

    public void sendSpecialKey(int ghosttyKey, int mods, String text) {
        if (session != null) {
            if (ghosttyKey == KeyCodes.GHOSTTY_KEY_ESCAPE && mods == 0) {
                handleEscPress();
                return;
            }

            int finalMods = mods;
            if (isCtrlActive) finalMods |= KeyCodes.MODS_CTRL;
            if (isAltActive) finalMods |= KeyCodes.MODS_ALT;
            boolean wasEsc = isEscActive;

            if (isCtrlActive || isAltActive || isEscActive) {
                isCtrlActive = false;
                isAltActive = false;
                if (isEscActive) {
                    cancelEscTimeout();
                    isEscActive = false;
                }
                notifyModifierState();
            }

            if (wasEsc) {
                if (text != null && !text.isEmpty()) {
                    session.write("\u001b" + text);
                    return;
                } else {
                    // ESC was pending but next key has no text (e.g., arrow/F-key), flush ESC then handle key normally
                    session.write("\u001b");
                    // Fall through to writeKey for current ghosttyKey
                }
            }
            session.writeKey(ghosttyKey, KeyCodes.ACTION_PRESS, finalMods, text);
        }
    }

    /**
     * Write raw bytes to the PTY bypassing sticky-modifier processing.
     * Used by one-shot key-bar buttons (C-x, tmux prefixes, …) that carry
     * their own control bytes.
     */
    public void sendRawBytes(byte[] data) {
        if (session == null || data == null || data.length == 0) return;
        session.write(data);
    }

    public void sendText(String text) {
        if (session == null || text == null || text.isEmpty()) return;        boolean ctrl = isCtrlActive;
        boolean alt = isAltActive;
        boolean wasEsc = isEscActive;

        // Sticky modifiers are consumed by the next input (same as key events)
        if (ctrl || alt || wasEsc) {
            isCtrlActive = false;
            isAltActive = false;
            if (isEscActive) {
                cancelEscTimeout();
                isEscActive = false;
            }
            notifyModifierState();
        }

        String out = applyModifiers(text, ctrl, alt);
        if (wasEsc && !text.isEmpty()) {
            // ESC arrived within timeout: send ESC before the text
            session.write("\u001b" + out);
            return;
        }
        if (!out.isEmpty()) {
            session.write(out);
        }
    }

    /**
     * Apply sticky soft-keyboard modifiers to committed IME text:
     * CTRL maps chars to their control codes (a-z -> 0x01..0x1A etc.),
     * ALT prefixes ESC. Unmapped characters pass through unchanged.
     */
    private static String applyModifiers(String text, boolean ctrl, boolean alt) {
        if (!ctrl && !alt) return text;
        StringBuilder sb = new StringBuilder(text.length() + 1);
        if (alt) sb.append('\u001b');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ctrl) {
                int code = controlCharOf(ch);
                if (code >= 0 && code != 127) { // DEL handled via 0x7f mapping below
                    sb.append((char) code);
                    continue;
                }
                if (code == 127) {
                    sb.append('\u007f');
                    continue;
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** C0 control-code equivalent of a character under CTRL, or -1 if none. */
    private static int controlCharOf(char c) {
        if (c >= '@' && c <= '_') return c - '@';       // @ A-Z [ \ ] ^ _
        char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'z') return lower - 'a' + 1;
        switch (c) {
            case ' ': return 0;   // C-space = NUL
            case '?': return 127; // C-? = DEL
            case '/': return 31;  // C-/ = US (also DEL)
            default: return -1;
        }
    }

    @Override
    public void onDataAvailable() {
        postInvalidate();
    }

    @Override
    public void onTitleChanged(String title) {
        postInvalidate();
    }

    @Override
    public void onBell() {
        // Haptic feedback
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @Override
    public void onClipboardWrite(String text) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && text != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Tinyhack SSH", text));
        }
    }

    @Override
    public void onSessionClosed(int exitCode) {
        sessionClosedDismissed = false;
        postInvalidate();
        // The view owns the session listener slot; re-publish the death event
        // so the host activity can react (close button, drawer refresh).
        if (sessionClosedListener != null) {
            sessionClosedListener.onSessionClosed(exitCode);
        }
    }

    /** Notified when the attached session's process exits. */
    public interface SessionClosedListener {
        void onSessionClosed(int exitCode);
    }

    private SessionClosedListener sessionClosedListener;

    public void setSessionClosedListener(SessionClosedListener listener) {
        this.sessionClosedListener = listener;
    }

    /** Fired when the user taps "Close session" on the <session closed> overlay. */
    public interface OnCloseSessionRequested {
        void onCloseSessionRequested();
    }

    public void setOnCloseSessionRequested(OnCloseSessionRequested listener) {
        this.closeSessionRequestedListener = listener;
    }

    /** Fired when the user taps "Reopen" on the <session closed> overlay. */
    public interface OnReopenSessionRequested {
        void onReopenSessionRequested();
    }

    public void setOnReopenSessionRequested(OnReopenSessionRequested listener) {
        this.reopenSessionRequestedListener = listener;
    }

    /** True when the <session closed> overlay is currently shown (modal). */
    public boolean isSessionClosedOverlayShowing() {
        return session != null && !session.isRunning() && !sessionClosedDismissed;
    }
}
