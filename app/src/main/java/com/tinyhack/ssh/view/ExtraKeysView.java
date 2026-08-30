package com.tinyhack.ssh.view;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.tinyhack.ssh.R;
import com.tinyhack.ssh.terminal.KeyCodes;

/**
 * Key bar above the soft keyboard.
 *
 * Keyboard hidden: one compact row [show-keyboard] ESC ENTER SPACE ↑ ↓ ← →
 * Keyboard shown: two rows, Termux-style:
 *   top:    MOD | SYM | FN | NAV | VIM | EMACS | TMUX | OSC133 (one active)
 *   bottom: keys for the selected mode
 *     MOD    ESC TAB CTRL ALT INS DEL CTRL-C CTRL-\
 *     SYM    - _ / ' " ; \ | ~ ( ) * > < #
 *     FN     F1..F12
 *     NAV    ↑ ↓ ← → HOME END PGUP PGDN
 *     VIM    ESC : / $ ^ % :q :w :wq :q!  (":"-commands send ESC first)
 *     EMACS  CTRL ALT C-x C-g Alt-X C-c C-u
 *     TMUX   CTRL-B ↑ ↓ ← → NextWin PrevWin Tree
 *     OSC133 PrevPrompt NextPrompt Copy Output
 */
public class ExtraKeysView extends LinearLayout implements TerminalView.ModifierStateListener {
    /** Selected bottom-row layout while the keyboard is visible. */
    private enum KeyMode { MOD, SYM, FN, NAV, VIM, EMACS, TMUX, OSC133 }

    /**
     * Gap between ESC and the ":"-command text. busybox vi's read_key waits
     * 50 ms for escape-sequence continuation bytes and discards the whole
     * unmatched batch, so the text must arrive after that window.
     */
    private static final long ESC_FOLLOWUP_DELAY_MS = 80;

    private TerminalView terminalView;
    private HorizontalScrollView modeScroll;
    private LinearLayout modeRow;
    private LinearLayout keysRow;
    private HorizontalScrollView keysScroll;

    private Button escButton;
    private Button ctrlButton;
    private Button altButton;
    private boolean isCtrlActive = false;
    private boolean isAltActive = false;
    private boolean isEscActive = false;

    private KeyMode currentMode = KeyMode.MOD;

    /** Soft keyboard visibility; hidden → compact single-row layout. */
    private boolean imeVisible = false;

    public ExtraKeysView(Context context) {
        super(context);
        init();
    }

    public ExtraKeysView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ExtraKeysView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(Color.parseColor("#1F1F1F"));

        modeScroll = new HorizontalScrollView(getContext());
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeRow = new LinearLayout(getContext());
        modeRow.setOrientation(HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(dpToPx(4), dpToPx(2), dpToPx(4), 0);
        modeScroll.addView(modeRow);
        addView(modeScroll);

        keysScroll = new HorizontalScrollView(getContext());
        keysScroll.setHorizontalScrollBarEnabled(false);
        keysRow = new LinearLayout(getContext());
        keysRow.setOrientation(HORIZONTAL);
        keysRow.setGravity(Gravity.CENTER_VERTICAL);
        keysRow.setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));
        keysScroll.addView(keysRow);
        addView(keysScroll);

        rebuildButtons();
    }

    private void rebuildButtons() {
        modeRow.removeAllViews();
        keysRow.removeAllViews();
        modeScroll.setVisibility(imeVisible ? VISIBLE : GONE);

        if (!imeVisible) {
            // Compact layout for when the soft keyboard is hidden:
            // ESC | ENTER | SPACE | [show-keyboard icon] | ↑ ↓ ← →
            // The keyboard button sits after SPACE so the primary keys stay
            // visible even when narrow screens scroll the arrow keys off.
            escButton = addKeyButton(keysRow, "ESC", v -> {
                if (terminalView != null) {
                    terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ESCAPE, 0, "\u001b");
                }
            });
            updateButtonVisual(escButton, isEscActive);

            addKeyButton(keysRow, "ENTER", v -> {
                if (terminalView != null) {
                    terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ENTER, 0, "\r");
                }
            });

            Button spaceButton = addKeyButton(keysRow, "SPACE", v -> {
                if (terminalView != null) terminalView.sendText(" ");
            });
            spaceButton.setMinWidth(dpToPx(64));
            spaceButton.setMinimumWidth(dpToPx(64));

            addKeyboardShowButton(keysRow);
            addArrowButtons(keysRow);
            return;
        }

        // --- Mode selector row (only one mode active at a time) ---
        addModeButton("MOD", KeyMode.MOD);
        addModeButton("SYM", KeyMode.SYM);
        addModeButton("FN", KeyMode.FN);
        addModeButton("NAV", KeyMode.NAV);
        addModeButton("VIM", KeyMode.VIM);
        addModeButton("EMACS", KeyMode.EMACS);
        addModeButton("TMUX", KeyMode.TMUX);
        addModeButton("OSC133", KeyMode.OSC133);

        // --- Bottom row follows the selected mode ---
        switch (currentMode) {
            case SYM:
                buildSymRow();
                break;
            case FN:
                buildFnRow();
                break;
            case NAV:
                buildNavRow();
                break;
            case VIM:
                buildVimRow();
                break;
            case EMACS:
                buildEmacsRow();
                break;
            case TMUX:
                buildTmuxRow();
                break;
            case OSC133:
                buildOscRow();
                break;
            case MOD:
            default:
                buildModRow();
                break;
        }
    }

    private void addModeButton(String label, KeyMode mode) {
        Button btn = addKeyButton(modeRow, label, v -> {
            if (currentMode != mode) {
                currentMode = mode;
                rebuildButtons();
            }
        });
        updateButtonVisual(btn, currentMode == mode);
    }

    /** ESC TAB CTRL ALT INS DEL CTRL-C CTRL-\ */
    private void buildModRow() {
        escButton = addKeyButton(keysRow, "ESC", v -> {
            if (terminalView != null) {
                terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ESCAPE, 0, "\u001b");
            }
        });
        updateButtonVisual(escButton, isEscActive);

        addKeyButton(keysRow, "TAB", v -> {
            if (terminalView != null) {
                terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_TAB, 0, "\t");
            }
        });

        ctrlButton = addKeyButton(keysRow, "CTRL", v -> {
            isCtrlActive = !isCtrlActive;
            updateButtonVisual(ctrlButton, isCtrlActive);
            if (terminalView != null) terminalView.setCtrlActive(isCtrlActive);
        });
        updateButtonVisual(ctrlButton, isCtrlActive);

        altButton = addKeyButton(keysRow, "ALT", v -> {
            isAltActive = !isAltActive;
            updateButtonVisual(altButton, isAltActive);
            if (terminalView != null) terminalView.setAltActive(isAltActive);
        });
        updateButtonVisual(altButton, isAltActive);

        addKeyButton(keysRow, "INS", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_INSERT, 0, null);
        });
        addKeyButton(keysRow, "DEL", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_DELETE, 0, null);
        });
        addKeyButton(keysRow, "CTRL-C", v -> sendBytes(0x03));
        addKeyButton(keysRow, "CTRL-\\", v -> sendBytes(0x1c));
    }

    /** - _ / ' " ; \ | ~ ( ) * > < # (shell-history frequency order) */
    private void buildSymRow() {
        for (String sym : new String[]{"-", "_", "/", "'", "\"", ";", "\\", "|", "~", "(", ")", "*", ">", "<", "#"}) {
            addKeyButton(keysRow, sym, v -> {
                if (terminalView != null) terminalView.sendText(sym);
            });
        }
    }

    /** F1..F12 */
    private void buildFnRow() {
        for (int i = 1; i <= 12; i++) {
            final int fKey = KeyCodes.GHOSTTY_KEY_F1 + (i - 1);
            addKeyButton(keysRow, "F" + i, v -> {
                if (terminalView != null) terminalView.sendSpecialKey(fKey, 0, null);
            });
        }
    }

    /** ↑ ↓ ← → HOME END PGUP PGDN */
    private void buildNavRow() {
        addArrowButtons(keysRow);
        addKeyButton(keysRow, "HOME", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_HOME, 0, null);
        });
        addKeyButton(keysRow, "END", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_END, 0, null);
        });
        addKeyButton(keysRow, "PGUP", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_PAGE_UP, 0, null);
        });
        addKeyButton(keysRow, "PGDN", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_PAGE_DOWN, 0, null);
        });
    }

    /**
     * ESC : / $ ^ % :q :w :wq :q!
     * Text starting with ":" is prefixed by ESC (leave insert mode first).
     */
    private void buildVimRow() {
        escButton = addKeyButton(keysRow, "ESC", v -> {
            if (terminalView != null) {
                terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ESCAPE, 0, "\u001b");
            }
        });
        updateButtonVisual(escButton, isEscActive);

        addVimText(":");
        addVimText("/");
        addVimText("$");
        addVimText("^");
        addVimText("%");
        addVimText(":q");
        addVimText(":w");
        addVimText(":wq");
        addVimText(":q!");
    }

    private void addVimText(String text) {
        addKeyButton(keysRow, text, v -> {
            if (terminalView == null) return;
            if (text.startsWith(":")) {
                // ESC first, then the text after a gap: busybox vi reads
                // ESC+following bytes arriving within its 50 ms escape-sequence
                // window as one unknown sequence and drops them all
                // (libbb/read_key.c). Real vim handles either order, but the
                // delay is required for the bundled busybox vi.
                terminalView.sendRawBytes(new byte[]{0x1b});
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    byte[] data = new byte[text.length()];
                    for (int i = 0; i < text.length(); i++) data[i] = (byte) text.charAt(i);
                    terminalView.sendRawBytes(data);
                }, ESC_FOLLOWUP_DELAY_MS);
            } else {
                terminalView.sendText(text);
            }
        });
    }

    /** CTRL ALT (sticky) + one-shot C-x C-g Alt-X C-c C-u */
    private void buildEmacsRow() {
        ctrlButton = addKeyButton(keysRow, "CTRL", v -> {
            isCtrlActive = !isCtrlActive;
            updateButtonVisual(ctrlButton, isCtrlActive);
            if (terminalView != null) terminalView.setCtrlActive(isCtrlActive);
        });
        updateButtonVisual(ctrlButton, isCtrlActive);

        altButton = addKeyButton(keysRow, "ALT", v -> {
            isAltActive = !isAltActive;
            updateButtonVisual(altButton, isAltActive);
            if (terminalView != null) terminalView.setAltActive(isAltActive);
        });
        updateButtonVisual(altButton, isAltActive);

        addKeyButton(keysRow, "C-x", v -> sendBytes(0x18));
        addKeyButton(keysRow, "C-g", v -> sendBytes(0x07));
        addKeyButton(keysRow, "Alt-X", v -> sendBytes(0x1b, 'x'));
        addKeyButton(keysRow, "C-c", v -> sendBytes(0x03));
        addKeyButton(keysRow, "C-u", v -> sendBytes(0x15));
    }

    /** CTRL-B, arrows, NextWin/PrevWin/Tree (prefix = 0x02 + key) */
    private void buildTmuxRow() {
        addKeyButton(keysRow, "CTRL-B", v -> sendBytes(0x02));
        addArrowButtons(keysRow);
        addKeyButton(keysRow, "NextWin", v -> sendBytes(0x02, 'n'));
        addKeyButton(keysRow, "PrevWin", v -> sendBytes(0x02, 'p'));
        addKeyButton(keysRow, "Tree", v -> sendBytes(0x02, 'w'));
        addKeyButton(keysRow, "Detach", v-> sendBytes(0x02, 'd'));
    }

    /** Write raw control bytes to the terminal. */
    private void sendBytes(int... vals) {
        if (terminalView == null || vals.length == 0) return;
        byte[] data = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) data[i] = (byte) vals[i];
        terminalView.sendRawBytes(data);
    }

    /** OSC 133 semantic prompt navigation + copy */
    private void buildOscRow() {
        addKeyButton(keysRow, "◀ Prompt", v -> jumpPrompt(false));
        addKeyButton(keysRow, "Prompt ▶", v -> jumpPrompt(true));
        addKeyButton(keysRow, "Copy Output", v -> {
            if (terminalView == null || !terminalView.copyLastCommandOutput()) {
                toast("No output to copy");
            }
        });
    }

    private void jumpPrompt(boolean next) {
        if (terminalView == null) return;
        boolean ok = next ? terminalView.scrollToNextPrompt()
                          : terminalView.scrollToPreviousPrompt();
        if (!ok) toast(next ? "No next prompt" : "No previous prompt");
    }

    private void toast(String msg) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private void addArrowButtons(LinearLayout parent) {
        addKeyButton(parent, "↑", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ARROW_UP, 0, null);
        });
        addKeyButton(parent, "↓", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ARROW_DOWN, 0, null);
        });
        addKeyButton(parent, "←", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ARROW_LEFT, 0, null);
        });
        addKeyButton(parent, "→", v -> {
            if (terminalView != null) terminalView.sendSpecialKey(KeyCodes.GHOSTTY_KEY_ARROW_RIGHT, 0, null);
        });
    }

    private void addKeyboardShowButton(LinearLayout parent) {
        android.widget.ImageButton kbdButton = new android.widget.ImageButton(getContext());
        kbdButton.setImageResource(R.drawable.ic_keyboard);
        kbdButton.setBackgroundColor(Color.parseColor("#2D2D2D"));
        kbdButton.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
        kbdButton.setContentDescription("Show keyboard");

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dpToPx(44),
                dpToPx(36)
        );
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        kbdButton.setLayoutParams(lp);

        kbdButton.setOnClickListener(v -> {
            if (terminalView != null) terminalView.showIme();
        });
        parent.addView(kbdButton);
    }

    public void setTerminalView(TerminalView terminalView) {
        this.terminalView = terminalView;
        if (terminalView != null) {
            terminalView.setModifierStateListener(this);
            // Note: the host activity wires KeyboardVisibilityListener →
            // setKeyboardVisible() on APIs without inset callbacks. On API 30+
            // the key bar is driven by real WindowInsets only, so a failed
            // showIme() attempt cannot leave it in the wrong layout.
        }
    }

    /**
     * Switch between the full two-row key bar (keyboard shown) and the compact
     * bar (keyboard hidden). Rebuilds buttons when the mode changes.
     */
    public void setKeyboardVisible(boolean visible) {
        if (this.imeVisible == visible) return;
        this.imeVisible = visible;
        post(this::rebuildButtons);
    }

    @Override
    public void onModifierStateChanged(boolean ctrlActive, boolean altActive, boolean escActive) {
        post(() -> {
            this.isCtrlActive = ctrlActive;
            this.isAltActive = altActive;
            this.isEscActive = escActive;
            updateButtonVisual(ctrlButton, isCtrlActive);
            updateButtonVisual(altButton, isAltActive);
            updateButtonVisual(escButton, isEscActive);
        });
    }

    private void updateButtonVisual(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundColor(Color.parseColor("#4D90FE"));
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackgroundColor(Color.parseColor("#2D2D2D"));
            btn.setTextColor(Color.parseColor("#E0E0E0"));
        }
    }

    private Button addKeyButton(LinearLayout parent, String text, OnClickListener listener) {
        Button btn = new Button(getContext());
        btn.setText(text);
        btn.setTextColor(Color.parseColor("#E0E0E0"));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setBackgroundColor(Color.parseColor("#2D2D2D"));
        btn.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        btn.setMinWidth(dpToPx(38));
        btn.setMinHeight(dpToPx(36));
        btn.setMinimumWidth(dpToPx(38));
        btn.setMinimumHeight(dpToPx(36));
        btn.setAllCaps(false);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(36)
        );
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        btn.setLayoutParams(lp);

        btn.setOnClickListener(listener);
        parent.addView(btn);
        return btn;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }
}
