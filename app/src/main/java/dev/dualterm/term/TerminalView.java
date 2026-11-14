package dev.dualterm.term;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * 终端渲染 + 输入。
 *
 * 渲染：按「同色同属性的连续单元格」合并成一次 drawText，避免每格一次绘制。
 * 输入：自己实现 InputConnection（IME 走 commitText），硬件键走 onKeyDown。
 * 滚动：上下拖动看 scrollback，点一下回到光标/弹出键盘。
 */
public class TerminalView extends View {

    public interface GridListener {
        void onGridChanged(int rows, int cols);
    }

    public interface ModifierWatcher {
        void onModifiersChanged();
    }

    /** 长按终端（全屏模式下没有顶栏按钮，靠它打开菜单）。 */
    public interface LongPressListener {
        void onLongPress();
    }

    private static final int LONG_PRESS_MS = 450;

    private static final int COLOR_BG = 0xFF101014;
    private static final int COLOR_FG = 0xFFE6E6E6;
    private static final int COLOR_CURSOR = 0xFF7CE38B;
    private static final int COLOR_HINT = 0xFF8A90A0;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private TermSession session;
    private GridListener gridListener;
    private ModifierWatcher modifierWatcher;
    private LongPressListener longPressListener;
    private boolean longPressFired;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (longPressListener != null) {
                longPressFired = true;
                longPressListener.onLongPress();
            }
        }
    };

    public void setLongPressListener(LongPressListener listener) {
        this.longPressListener = listener;
    }

    private float cellWidth = 8f;
    private float cellHeight = 16f;
    private float ascent = -12f;
    private float padX;
    private float padY;

    private int gridRows = 24;
    private int gridCols = 80;

    private int textSizeSp = 13;

    private int scrollOffset;
    private boolean ctrlDown;
    private boolean altDown;

    private float downY;
    private float lastY;
    private boolean dragging;

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        padX = dp(4);
        padY = dp(4);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setColor(COLOR_FG);
        fillPaint.setStyle(Paint.Style.FILL);
        setBackgroundColor(COLOR_BG);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /* -------------------- 外部配置 -------------------- */

    public void setGridListener(GridListener listener) {
        this.gridListener = listener;
    }

    public void setModifierWatcher(ModifierWatcher watcher) {
        this.modifierWatcher = watcher;
    }

    public void setSession(TermSession value) {
        this.session = value;
        this.scrollOffset = 0;
        if (value != null) {
            invalidate();
        }
    }

    public TermSession getSession() {
        return session;
    }

    public int getGridRows() {
        return gridRows;
    }

    public int getGridCols() {
        return gridCols;
    }

    public int getTextSizeSp() {
        return textSizeSp;
    }

    public void setTextSizeSp(int sp) {
        sp = Math.max(6, Math.min(28, sp));
        if (sp == textSizeSp) {
            return;
        }
        textSizeSp = sp;
        computeGrid(getWidth(), getHeight());
        invalidate();
    }

    public boolean isCtrlDown() {
        return ctrlDown;
    }

    public boolean isAltDown() {
        return altDown;
    }

    public void setCtrlDown(boolean value) {
        ctrlDown = value;
        if (modifierWatcher != null) {
            modifierWatcher.onModifiersChanged();
        }
    }

    public void setAltDown(boolean value) {
        altDown = value;
        if (modifierWatcher != null) {
            modifierWatcher.onModifiersChanged();
        }
    }

    public void showKeyboard() {
        requestFocus();
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    /** 发送文本到 PTY（会应用一次性的 Ctrl/Alt 修饰）。 */
    public void sendText(String text) {
        if (session == null || text == null || text.isEmpty()) {
            return;
        }
        boolean consumed = ctrlDown || altDown;
        session.write(applyModifiers(text));
        scrollOffset = 0;
        invalidate();
        if (consumed && modifierWatcher != null) {
            modifierWatcher.onModifiersChanged();
        }
    }

    private String applyModifiers(String text) {
        if (!ctrlDown && !altDown) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (ctrlDown) {
                if (cp >= '@' && cp <= '_') {
                    cp = cp & 0x1F;
                } else if (cp >= 'a' && cp <= 'z') {
                    cp = cp - 'a' + 1;
                } else if (cp == ' ') {
                    cp = 0;
                }
            }
            if (altDown) {
                sb.append('\u001b');
            }
            sb.appendCodePoint(cp);
        }
        ctrlDown = false;
        altDown = false;
        return sb.toString();
    }

    /* -------------------- 尺寸与网格 -------------------- */

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGrid(w, h);
    }

    private void computeGrid(int width, int height) {
        textPaint.setTextSize(sp(textSizeSp));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        cellHeight = Math.max(1f, (float) Math.ceil(fm.descent - fm.ascent + fm.leading));
        ascent = fm.ascent;
        cellWidth = Math.max(1f, textPaint.measureText("M"));

        int usableW = Math.max(1, width - (int) (padX * 2));
        int usableH = Math.max(1, height - (int) (padY * 2));
        int newCols = Math.max(10, (int) (usableW / cellWidth));
        int newRows = Math.max(3, (int) (usableH / cellHeight));

        if (newCols != gridCols || newRows != gridRows) {
            gridCols = newCols;
            gridRows = newRows;
            if (gridListener != null) {
                gridListener.onGridChanged(gridRows, gridCols);
            }
        }
    }

    /* -------------------- 绘制 -------------------- */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (session == null) {
            drawHint(canvas);
            return;
        }
        TerminalEmulator emulator = session.getEmulator();
        int scrollbackCount = emulator.getScrollbackCount();
        if (scrollOffset > scrollbackCount) {
            scrollOffset = scrollbackCount;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }

        textPaint.setTextSize(sp(textSizeSp));
        textPaint.setTypeface(Typeface.MONOSPACE);

        for (int row = 0; row < gridRows; row++) {
            int virtualRow = row - scrollOffset;
            TerminalEmulator.Line line;
            if (virtualRow >= 0) {
                line = emulator.getLine(virtualRow);
            } else {
                int index = scrollbackCount + virtualRow;
                line = index >= 0 ? emulator.getScrollbackLine(index) : null;
            }
            if (line != null) {
                drawLine(canvas, line, row);
            }
        }

        if (scrollOffset == 0 && emulator.isCursorVisible()) {
            drawCursor(canvas, emulator);
        }
    }

    private void drawLine(Canvas canvas, TerminalEmulator.Line line, int row) {
        float top = padY + row * cellHeight;
        float baseline = top - ascent;
        int cells = Math.min(gridCols, line.text.length);
        int col = 0;

        while (col < cells) {
            int fg = line.fg[col];
            int bg = line.bg[col];
            int attr = line.attr[col];
            int start = col;
            StringBuilder sb = new StringBuilder();

            while (col < cells && line.fg[col] == fg && line.bg[col] == bg && line.attr[col] == attr) {
                int cp = line.text[col];
                if (cp != 0) {
                    sb.appendCodePoint(cp);
                } else {
                    sb.append(' ');
                }
                col++;
            }

            boolean reverse = (attr & TerminalEmulator.ATTR_REVERSE) != 0;
            int drawBg = reverse ? fg : bg;
            int drawFg = reverse ? bg : fg;
            if ((attr & TerminalEmulator.ATTR_DIM) != 0 && !reverse) {
                drawFg = blend(drawFg, drawBg, 0.45f);
            }

            if ((drawBg & 0xFFFFFF) != (COLOR_BG & 0xFFFFFF)) {
                fillPaint.setColor(drawBg);
                rect.set(padX + start * cellWidth, top,
                        padX + col * cellWidth, top + cellHeight);
                canvas.drawRect(rect, fillPaint);
            }

            textPaint.setColor(drawFg);
            textPaint.setFakeBoldText((attr & TerminalEmulator.ATTR_BOLD) != 0);
            textPaint.setUnderlineText((attr & TerminalEmulator.ATTR_UNDERLINE) != 0);
            textPaint.setStrikeThruText((attr & TerminalEmulator.ATTR_STRIKE) != 0);

            if ((attr & TerminalEmulator.ATTR_INVISIBLE) == 0) {
                canvas.drawText(sb.toString(), padX + start * cellWidth, baseline, textPaint);
            }
        }
    }

    private void drawCursor(Canvas canvas, TerminalEmulator emulator) {
        int row = emulator.getCursorRow();
        int col = emulator.getCursorCol();
        if (row < 0 || row >= gridRows || col < 0 || col >= gridCols) {
            return;
        }
        float top = padY + row * cellHeight;
        fillPaint.setColor(COLOR_CURSOR);
        rect.set(padX + col * cellWidth, top,
                padX + (col + 1) * cellWidth, top + cellHeight);
        canvas.drawRect(rect, fillPaint);

        TerminalEmulator.Line line = emulator.getLine(row);
        if (line != null && col < line.text.length && line.text[col] != 0 && line.text[col] != ' ') {
            textPaint.setColor(COLOR_BG);
            textPaint.setFakeBoldText(false);
            textPaint.setUnderlineText(false);
            textPaint.setStrikeThruText(false);
            canvas.drawText(new String(Character.toChars(line.text[col])),
                    padX + col * cellWidth, top - ascent, textPaint);
        }
    }

    private void drawHint(Canvas canvas) {
        textPaint.setTextSize(sp(12));
        textPaint.setColor(COLOR_HINT);
        textPaint.setFakeBoldText(false);
        textPaint.setUnderlineText(false);
        textPaint.setStrikeThruText(false);
        String hint = "选择上方「本地」或「容器」开始终端会话";
        float w = textPaint.measureText(hint);
        canvas.drawText(hint, Math.max(padX, (getWidth() - w) / 2f),
                getHeight() / 2f, textPaint);
    }

    /* -------------------- 触摸滚动 -------------------- */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                lastY = downY;
                dragging = false;
                longPressFired = false;
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - lastY;
                if (!dragging && Math.abs(event.getY() - downY) > cellHeight) {
                    dragging = true;
                    removeCallbacks(longPressRunnable);
                }
                if (dragging) {
                    int lines = (int) (dy / cellHeight);
                    if (lines != 0) {
                        int max = session != null ? session.getEmulator().getScrollbackCount() : 0;
                        scrollOffset = Math.max(0, Math.min(max, scrollOffset + lines));
                        lastY = event.getY();
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (!dragging && !longPressFired) {
                    showKeyboard();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /* -------------------- 键盘与 IME -------------------- */

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        out.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                sendText(text.toString());
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // 中文输入法的预编辑串不进 PTY，等 commitText 才发
                return true;
            }

            @Override
            public boolean finishComposingText() {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < beforeLength; i++) {
                    sb.append('\u007f');
                }
                sendText(sb.toString());
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && handleKeyCode(event)) {
                    return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleKeyCode(event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleKeyCode(KeyEvent event) {
        if (session == null) {
            return false;
        }
        TerminalEmulator emulator = session.getEmulator();
        boolean appCursor = emulator.isAppCursorKeys();
        String sequence = null;

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                sequence = "\r";
                break;
            case KeyEvent.KEYCODE_DEL:
                sequence = "\u007f";
                break;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                sequence = "\u001b[3~";
                break;
            case KeyEvent.KEYCODE_ESCAPE:
                sequence = "\u001b";
                break;
            case KeyEvent.KEYCODE_TAB:
                sequence = "\t";
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                sequence = appCursor ? "\u001bOA" : "\u001b[A";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sequence = appCursor ? "\u001bOB" : "\u001b[B";
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sequence = appCursor ? "\u001bOC" : "\u001b[C";
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                sequence = appCursor ? "\u001bOD" : "\u001b[D";
                break;
            case KeyEvent.KEYCODE_MOVE_HOME:
                sequence = "\u001b[H";
                break;
            case KeyEvent.KEYCODE_MOVE_END:
                sequence = "\u001b[F";
                break;
            case KeyEvent.KEYCODE_PAGE_UP:
                sequence = "\u001b[5~";
                break;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                sequence = "\u001b[6~";
                break;
            case KeyEvent.KEYCODE_BACK:
                return false;
            default:
                break;
        }

        if (sequence != null) {
            sendText(sequence);
            return true;
        }

        int unicode = event.getUnicodeChar(event.getMetaState());
        if (unicode > 0) {
            sendText(new String(Character.toChars(unicode)));
            return true;
        }
        return false;
    }

    /* -------------------- 小工具 -------------------- */

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private static int blend(int fg, int bg, float ratio) {
        int a = (fg >>> 24) & 0xFF;
        int fr = (fg >> 16) & 0xFF;
        int fgc = (fg >> 8) & 0xFF;
        int fb = fg & 0xFF;
        int br = (bg >> 16) & 0xFF;
        int bgc = (bg >> 8) & 0xFF;
        int bb = bg & 0xFF;
        int r = (int) (br + (fr - br) * ratio);
        int g = (int) (bgc + (fgc - bgc) * ratio);
        int b = (int) (bb + (fb - bb) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
