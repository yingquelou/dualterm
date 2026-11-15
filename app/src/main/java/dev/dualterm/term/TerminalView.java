package dev.dualterm.term;

import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.ScaleGestureDetector;

/**
 * 终端渲染 + 输入。
 *
 * 渲染：按「同色同属性的连续单元格」合并成一次 drawText，避免每格一次绘制。
 * 输入：自己实现 InputConnection（IME 走 commitText），硬件键走 onKeyDown。
 * 滚动：上下拖动看 scrollback，点一下回到光标/弹出键盘。
 *
 * 触摸端的三个约定（都在手机单手握持的范围内）：
 * <ul>
 *   <li>单指拖动 = 看历史；右下角浮出「回到底部」胶囊，点它一键回游标（否则用户会被留在历史里）</li>
 *   <li>双指捏合 = 改字号（桌面终端靠 Ctrl+滚轮，触屏用捏合最直觉）</li>
 *   <li>鼠标滚轮 / 触控板 = 滚屏；程序开了鼠标上报就转成滚轮序列交给它（vim、htop）</li>
 * </ul>
 */
public class TerminalView extends View {

    public interface GridListener {
        void onGridChanged(int rows, int cols);
    }

    public interface ModifierWatcher {
        void onModifiersChanged();
    }

    /** 捏合改字号后回调，交给 Activity 持久化。 */
    public interface FontSizeListener {
        void onFontSizeChanged(int sp);
    }

    /**
     * 会话已经退出、用户还在往里敲。PTY 已经死了，写进去只是丢字节，
     * 所以这里只通知一次（有节流），让界面提示「重启」，而不是默默吞掉按键。
     */
    public interface DeadInputListener {
        void onInputWhileDead();
    }

    /** 长按钩子。菜单已经从这个手势上挪走了（见 MainActivity），现在保留它是给
     *  将来的「长按选词 → 复制」用：选区一旦落地，长按必须先归选区。 */
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
            // 不管有没有监听者，长按都算「被长按吃掉」：否则按住稍久再松手会
            // 退化成一次点击（点终端 = 切键盘），手感上像误触
            longPressFired = true;
            if (longPressListener == null) {
                return;
            }
            // 鼠标模式下长按是「打开菜单」，得先给程序补一个松开，
            // 否则 vim 会一直以为左键按着不放
            if (mouseDown && session != null) {
                mouseDown = false;
                send(session.getEmulator().mouseReport(0, lastMouseCol, lastMouseRow, true, false));
            }
            longPressListener.onLongPress();
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

    private FontSizeListener fontListener;
    private DeadInputListener deadListener;
    private long lastDeadNotice;
    private ScaleGestureDetector scaleDetector;
    private boolean scaling;
    /** 捏合起点字号（锚点），配合 {@link #scaleAccum} 累计比例算目标字号。 */
    private float scaleStartSize;
    /** getScaleFactor() 是逐事件增量（相对上一次回调），必须累乘才有「相对起点」的比例。 */
    private float scaleAccum;

    /** 「回到底部」胶囊的命中区（scrollOffset > 0 时才有意义）。 */
    private final RectF scrollPill = new RectF();
    private boolean scrollPillVisible;

    private float downY;
    private float lastY;
    private boolean dragging;

    /** 鼠标上报进行中（vim 里正在拖选/拖拽）。 */
    private boolean mouseDown;
    private int lastMouseCol = 1;
    private int lastMouseRow = 1;
    /** 双指滑动 → 滚轮上报。 */
    private boolean wheelMode;
    private float lastWheelY;

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

        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        scaling = true;
                        scaleStartSize = textSizeSp;
                        scaleAccum = 1f;
                        removeCallbacks(longPressRunnable);
                        // 鼠标上报模式：第一根手指落下时多半已给程序发了「按下」，
                        // 捏合抢走手势后必须补一个释放，否则 vim 卡在拖拽选中态
                        if (mouseDown && session != null) {
                            mouseDown = false;
                            wheelMode = false;
                            send(session.getEmulator().mouseReport(
                                    0, lastMouseCol, lastMouseRow, true, false));
                        }
                        android.util.Log.i("DualTerm", "pinch begin: " + textSizeSp + "sp");
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (!scaling) {
                            return false;
                        }
                        // 锚点 × 累计比例。直接用 getScaleFactor() 是错的：它是
                        // 「本事件相对上一事件」的瞬时值（≈1.0 抖动），拿它乘锚点
                        // 会导致字号在大一级和原值之间来回跳（实测复现过）。
                        scaleAccum *= detector.getScaleFactor();
                        int target = Math.round(scaleStartSize * scaleAccum);
                        if (target != textSizeSp) {
                            setTextSizeSp(target);
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        scaling = false;
                        android.util.Log.i("DualTerm",
                                "pinch end: " + scaleStartSize + "sp -> " + textSizeSp + "sp");
                        if (fontListener != null) {
                            fontListener.onFontSizeChanged(textSizeSp);
                        }
                    }
                });
    }

    /* -------------------- 外部配置 -------------------- */

    public void setGridListener(GridListener listener) {
        this.gridListener = listener;
    }

    public void setModifierWatcher(ModifierWatcher watcher) {
        this.modifierWatcher = watcher;
    }

    public void setFontSizeListener(FontSizeListener listener) {
        this.fontListener = listener;
    }

    public void setDeadInputListener(DeadInputListener listener) {
        this.deadListener = listener;
    }

    /** 会话进程还在不在。退出后屏幕内容仍可读，但输入已经没有意义。 */
    private boolean isDead() {
        return session != null && !session.isRunning();
    }

    private void notifyDead() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastDeadNotice < 1500) {
            return;
        }
        lastDeadNotice = now;
        if (deadListener != null) {
            deadListener.onInputWhileDead();
        }
    }

    public void setSession(TermSession value) {
        this.session = value;
        this.scrollOffset = 0;
        if (value != null) {
            pushTextMetrics(value);
            invalidate();
        }
    }

    /** 把像素度量同步给仿真器，程序查询 XTWINOPS（CSI 14/16/18 t）时要用。 */
    private void pushTextMetrics(TermSession target) {
        if (target == null || gridCols <= 0) {
            return;
        }
        target.getEmulator().setTextMetrics(
                (int) (cellWidth * gridCols),
                (int) (cellHeight * gridRows),
                Math.round(cellWidth),
                Math.round(cellHeight));
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

    /**
     * 自绘键盘的开关回调。
     *
     * 不再调系统输入法：MIUI 的 IME 横屏要占 67% 屏幕，还带候选栏留白，
     * 而终端要的是「字母 + CTRL/ALT/ESC/方向键」，自己画一套只有一半高。
     * 活动层负责真正的显隐（键盘挂在键条里，属于布局的一部分）。
     */
    public interface KeypadListener {
        /** 点终端：切换自绘键盘。 */
        void onToggleKeypad();

        /** 会话启动、恢复输入时需要键盘（幂等，已经开着就什么都不做）。 */
        void onShowKeypad();

        /** 需要收起时（返回键、点击外部等）。 */
        void onHideKeypad();
    }

    private KeypadListener keypadListener;

    public void setKeypadListener(KeypadListener listener) {
        this.keypadListener = listener;
    }

    /** 确保键盘可见（会话启动时用；已经开着不会把它关掉）。 */
    public void showKeyboard() {
        requestFocus();
        if (keypadListener != null) {
            keypadListener.onShowKeypad();
        }
    }

    /** 点击终端来的：切换，开着的再点一下收起。 */
    public void toggleKeyboard() {
        requestFocus();
        if (keypadListener != null) {
            keypadListener.onToggleKeypad();
        }
    }

    public void hideKeyboard() {
        if (keypadListener != null) {
            keypadListener.onHideKeypad();
        }
    }

    /**
     * 窗口焦点变化。程序开了 DECSET 1004 时（vim 的 FocusGained/FocusLost）要上报，
     * 否则切出去再切回来 vim 不会重绘。
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (session == null) {
            return;
        }
        TerminalEmulator emulator = session.getEmulator();
        if (!emulator.isFocusReport()) {
            return;
        }
        session.write(hasWindowFocus ? "\u001b[I" : "\u001b[O");
    }

    /**
     * 粘贴文本。程序若开了括号粘贴（DECSET 2004），就用 ESC[200~/ESC[201~ 把内容包起来——
     * 否则多行内容会被当成多次回车逐行执行（粘贴 token、粘贴多行命令时尤其危险）。
     */
    public void pasteText(String text) {
        if (session == null || text == null || text.isEmpty()) {
            return;
        }
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            // 多行粘贴必须确认：每一行都会作为独立输入立即发给 PTY（相当于
            // 逐行按回车）。实测把整屏复制再粘贴，shell 就把 63 行提示符当
            // 命令逐条"执行"了——屏幕内容若含 rm -rf 之类的行就是事故。
            // Windows Terminal / PuTTY 对多行粘贴都有确认框，这里对齐。
            confirmMultilinePaste(text);
            return;
        }
        doPaste(text);
    }

    private void confirmMultilinePaste(final String text) {
        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                lineCount++;
            }
        }
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("粘贴多行文本")
                .setMessage("内容包含约 " + lineCount + " 行。每一行都会作为独立输入"
                        + "\n立即发给终端（相当于逐行按回车）。\n\n确定粘贴？")
                .setNegativeButton("取消", null)
                .setPositiveButton("粘贴", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        doPaste(text);
                    }
                })
                .show();
    }

    private void doPaste(String text) {
        boolean bracketed = session.getEmulator().isBracketedPaste();
        sendText(bracketed ? "\u001b[200~" + text + "\u001b[201~" : text);
    }

    /** 硬件键盘 Ctrl+Shift+V：从系统剪贴板粘贴（走 pasteText，带 bracketed paste）。 */
    private void pasteFromClipboard() {
        ClipboardManager cm =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            hint("剪贴板是空的");
            return;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item == null ? null : item.coerceToText(getContext());
        if (text == null || text.length() == 0) {
            return;
        }
        pasteText(text.toString());
        hint("已粘贴 " + text.length() + " 个字符");
    }

    /**
     * 硬件键盘 Ctrl+Shift+C：复制当前屏（不含 scrollback）。
     * 全部输出动辄几万行，真要复制某一屏时那个入口用不上；选区的完整支持
     * （手柄 + 高亮 + 自动滚动）还没有，当前屏是最实用的粒度。
     */
    private void copyScreenToClipboard() {
        if (session == null) {
            return;
        }
        String text = session.getEmulator().getScreenText();
        ClipboardManager cm =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        hint("已复制当前屏 " + text.length() + " 个字符");
    }

    private void hint(String message) {
        android.widget.Toast.makeText(getContext(), message, android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 输入法提交的文本。软键盘回车通常送 '\n'，但终端里的回车是 CR(0x0D)：
     * 不转的话 vim 会把它当 &lt;NL&gt;（等于 &lt;C-J&gt;），插入模式下只是换行、命令模式下还会触发别的映射。
     */
    private void sendCommitted(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.indexOf('\n') >= 0) {
            text = text.replace("\r\n", "\r").replace('\n', '\r');
        }
        sendText(text);
    }

    /** 发送文本到 PTY（会应用一次性的 Ctrl/Alt 修饰）。 */
    public void sendText(String text) {
        if (session == null || text == null || text.isEmpty()) {
            return;
        }
        if (!session.isRunning()) {
            notifyDead();
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

    /**
     * 发送固定转义序列（键条上的 ESC / TAB / ^C / 符号）。
     *
     * 不走 applyModifiers：这些序列本身就是终端语义，再叠一次粘滞 Ctrl 只会把
     * ESC 前缀搅坏（Ctrl 一旦锁上，连按 ^C 都发不出去）。发完顺手解锁，
     * 免得用户忘了自己按过 Ctrl。
     */
    public void sendRaw(String text) {
        if (session == null || text == null || text.isEmpty()) {
            return;
        }
        if (!session.isRunning()) {
            notifyDead();
            return;
        }
        session.write(text);
        scrollOffset = 0;
        clearModifiers();
        invalidate();
    }

    /**
     * 键条上的方向键。程序切到应用光标模式（vim）时要发 SS3；粘滞 Ctrl/Alt 锁着时
     * 要发 CSI 1 ; m D —— 否则 Ctrl+← 在 vim 里不会按词跳。
     */
    public void sendCursor(char dir) {
        if (session == null) {
            return;
        }
        int mod = (ctrlDown ? M_CTRL : 0) | (altDown ? M_ALT : 0);
        sendRaw(cursorKey(dir, mod, session.getEmulator().isAppCursorKeys()));
    }

    private void clearModifiers() {
        if (!ctrlDown && !altDown) {
            return;
        }
        ctrlDown = false;
        altDown = false;
        if (modifierWatcher != null) {
            modifierWatcher.onModifiersChanged();
        }
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
        // 字号/视口变了，单元格像素尺寸跟着变，及时同步（XTWINOPS 应答依赖）
        pushTextMetrics(session);
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

        scrollPillVisible = scrollOffset > 0;
        if (scrollPillVisible) {
            drawScrollPill(canvas);
        }
    }

    /**
     * 上滚时右下角浮出的胶囊：告诉你「现在看的是历史、往上偏了多少行」，
     * 点一下直接回游标。没有它的话，用户拖一下手指就停在历史里，
     * 光标不画、新输出看不见，很容易以为程序卡死。
     */
    private void drawScrollPill(Canvas canvas) {
        String label = "↓ " + scrollOffset + " 行 · 回到底部";
        textPaint.setTextSize(sp(11));
        textPaint.setFakeBoldText(false);
        textPaint.setUnderlineText(false);
        textPaint.setStrikeThruText(false);
        textPaint.setColor(0xFF101014);

        float padH = dp(10);
        float h = dp(26);
        float w = textPaint.measureText(label) + padH * 2;
        float left = Math.max(dp(4), getWidth() - w - dp(6));
        float top = Math.max(dp(4), getHeight() - h - dp(6));
        scrollPill.set(left, top, left + w, top + h);

        fillPaint.setColor(COLOR_CURSOR);
        rect.set(scrollPill);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, left + padH, baseline, textPaint);

        textPaint.setColor(COLOR_FG);
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
            while (col < cells && line.fg[col] == fg && line.bg[col] == bg && line.attr[col] == attr) {
                col++;
            }
            int end = col;

            boolean reverse = (attr & TerminalEmulator.ATTR_REVERSE) != 0;
            int drawBg = reverse ? fg : bg;
            int drawFg = reverse ? bg : fg;
            if ((attr & TerminalEmulator.ATTR_DIM) != 0 && !reverse) {
                drawFg = blend(drawFg, drawBg, 0.45f);
            }

            if ((drawBg & 0xFFFFFF) != (COLOR_BG & 0xFFFFFF)) {
                fillPaint.setColor(drawBg);
                rect.set(padX + start * cellWidth, top,
                        padX + end * cellWidth, top + cellHeight);
                canvas.drawRect(rect, fillPaint);
            }

            if ((attr & TerminalEmulator.ATTR_INVISIBLE) != 0) {
                continue;
            }

            textPaint.setColor(drawFg);
            textPaint.setFakeBoldText((attr & TerminalEmulator.ATTR_BOLD) != 0);
            textPaint.setUnderlineText((attr & TerminalEmulator.ATTR_UNDERLINE) != 0);
            textPaint.setStrikeThruText((attr & TerminalEmulator.ATTR_STRIKE) != 0);

            // 逐格推进：宽字符单独画并占两列，窄字符攒成一段一次画。
            // 不能把宽字符和它后面的字符塞进同一个 drawText——宽字形会自己撑开两倍宽，
            // 后面所有字符就整体右移一格。带组合字符（é、泰文、emoji 修饰）的格子也要单独画，
            // 否则附加符号会被当成独立字符排到下一格。
            int i = start;
            while (i < end) {
                int cp = line.text[i];
                if (cp == 0) {
                    // 宽字符的占位尾格
                    i++;
                    continue;
                }
                String extra = line.extraAt(i);
                if (TerminalEmulator.charWidth(cp) == 2 || extra != null) {
                    StringBuilder one = new StringBuilder(4);
                    one.appendCodePoint(cp);
                    if (extra != null) {
                        one.append(extra);
                    }
                    canvas.drawText(one.toString(), padX + i * cellWidth, baseline, textPaint);
                    i++;
                    continue;
                }
                int runStart = i;
                StringBuilder sb = new StringBuilder(end - i);
                while (i < end) {
                    int c2 = line.text[i];
                    if (c2 == 0 || TerminalEmulator.charWidth(c2) == 2 || line.extraAt(i) != null) {
                        break;
                    }
                    sb.appendCodePoint(c2);
                    i++;
                }
                canvas.drawText(sb.toString(), padX + runStart * cellWidth, baseline, textPaint);
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
        float left = padX + col * cellWidth;
        int shape = emulator.getCursorShape();
        float thick = Math.max(2f, cellHeight / 12f);

        fillPaint.setColor(COLOR_CURSOR);
        if (shape == 3 || shape == 4) {
            // DECSCUSR 3/4：下划线光标（vim 的 guicursor / 部分程序在非插入模式用它）
            rect.set(left, top + cellHeight - thick, left + cellWidth, top + cellHeight);
            canvas.drawRect(rect, fillPaint);
            return;
        }
        if (shape == 5 || shape == 6) {
            // 5/6：竖线光标
            rect.set(left, top, left + thick, top + cellHeight);
            canvas.drawRect(rect, fillPaint);
            return;
        }

        rect.set(left, top, left + cellWidth, top + cellHeight);
        canvas.drawRect(rect, fillPaint);

        TerminalEmulator.Line line = emulator.getLine(row);
        if (line != null && col < line.text.length && line.text[col] != 0 && line.text[col] != ' ') {
            textPaint.setColor(COLOR_BG);
            textPaint.setFakeBoldText(false);
            textPaint.setUnderlineText(false);
            textPaint.setStrikeThruText(false);
            canvas.drawText(new String(Character.toChars(line.text[col])),
                    left, top - ascent, textPaint);
        }
    }

    private void drawHint(Canvas canvas) {
        textPaint.setTextSize(sp(12));
        textPaint.setColor(COLOR_HINT);
        textPaint.setFakeBoldText(false);
        textPaint.setUnderlineText(false);
        textPaint.setStrikeThruText(false);
        // 长按不再绑菜单（长按留给将来的选区），所以这里只教「怎么起会话」，
        // 菜单入口在可见控件上：顶栏 ⋮ / 长按键行的 ⋯
        String hint = "点左上角「本地 / 容器」开始 · 菜单在顶栏 ⋮";
        float w = textPaint.measureText(hint);
        canvas.drawText(hint, Math.max(padX, (getWidth() - w) / 2f),
                getHeight() / 2f, textPaint);
    }

    /* -------------------- 触摸：本地滚动 / 鼠标上报 -------------------- */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        TerminalEmulator emulator = session == null ? null : session.getEmulator();
        // vim 的 set mouse=a 会开鼠标上报；这时触摸要变成鼠标事件交给它，而不是本地滚屏
        boolean tracking = emulator != null && emulator.isMouseTracking();
        // 先看是不是点在「回到底部」胶囊上：上滚状态下的这一下不该弹键盘
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && scrollPillVisible && scrollPill.contains(event.getX(), event.getY())) {
            scrollOffset = 0;
            scrollPillVisible = false;
            invalidate();
            return true;
        }
        // 捏合改字号；进入缩放后这一串事件都归手势，不再当作拖动。
        // 鼠标上报模式（vim/htop）下同样要喂：否则双指捏合会被整段当
        // 鼠标事件吞掉，字号永远调不了（实测复现）。双指纯上下滑跨度
        // 不变，不会误触发缩放，滚轮模式照常工作。
        scaleDetector.onTouchEvent(event);
        if (scaling) {
            removeCallbacks(longPressRunnable);
            dragging = true;
            return true;
        }
        if (tracking) {
            return handleMouseTouch(event, emulator);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                lastY = downY;
                dragging = false;
                longPressFired = false;
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：无论接下来是捏合还是滚轮，长按都不该再触发，
                // 否则双指手势的准备阶段就会弹菜单（用户实测复现）
                removeCallbacks(longPressRunnable);
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
                    if (isDead()) {
                        // 键盘弹出来也没处输入，直接引导去重启
                        notifyDead();
                    } else {
                        toggleKeyboard();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * 鼠标 / 触控板。Android 把滚轮做成 ACTION_SCROLL 走这里而不是触摸事件——
     * 不实现的话，接了蓝牙鼠标也只能点不能滚。程序开了鼠标上报就转成 64/65
     * 滚轮序列（vim 的 &lt;ScrollWheelUp/Down&gt;、htop 的翻页都靠它）。
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        TerminalEmulator emulator = session == null ? null : session.getEmulator();
        if (emulator == null) {
            return super.onGenericMotionEvent(event);
        }
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0f) {
                return false;
            }
            int lines = Math.max(1, Math.round(Math.abs(v) * 3f));
            if (emulator.isMouseTracking()) {
                int button = v > 0 ? 64 : 65;
                int col = (int) ((event.getX() - padX) / cellWidth) + 1;
                int row = (int) ((event.getY() - padY) / cellHeight) + 1;
                for (int i = 0; i < lines; i++) {
                    send(emulator.mouseReport(button, col, row, false, false));
                }
                return true;
            }
            int max = emulator.getScrollbackCount();
            int delta = v > 0 ? lines : -lines;    // 滚轮往上 = 看更早的内容
            int next = Math.max(0, Math.min(max, scrollOffset + delta));
            if (next != scrollOffset) {
                scrollOffset = next;
                invalidate();
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_HOVER_MOVE && emulator.isMouseTracking()) {
            int col = (int) ((event.getX() - padX) / cellWidth) + 1;
            int row = (int) ((event.getY() - padY) / cellHeight) + 1;
            send(emulator.mouseReport(0, col, row, false, true));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean handleMouseTouch(MotionEvent event, TerminalEmulator emulator) {
        int col = (int) ((event.getX() - padX) / cellWidth) + 1;
        int row = (int) ((event.getY() - padY) / cellHeight) + 1;
        lastMouseCol = col;
        lastMouseRow = row;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                lastY = downY;
                dragging = false;
                wheelMode = false;
                longPressFired = false;
                mouseDown = true;
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                send(emulator.mouseReport(0, col, row, false, false));
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：切到滚轮模式。vim 里双指上下滑就是滚屏（<ScrollWheelUp/Down>）。
                removeCallbacks(longPressRunnable);
                if (mouseDown) {
                    mouseDown = false;
                    send(emulator.mouseReport(0, col, row, true, false));
                }
                wheelMode = true;
                lastWheelY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (wheelMode) {
                    float dy = event.getY() - lastWheelY;
                    int lines = (int) (dy / cellHeight);
                    if (lines != 0) {
                        int button = lines > 0 ? 65 : 64;   // 65 = 下滚，64 = 上滚
                        int repeat = Math.min(3, Math.abs(lines));
                        for (int i = 0; i < repeat; i++) {
                            send(emulator.mouseReport(button, col, row, false, false));
                        }
                        lastWheelY += lines * cellHeight;
                    }
                    return true;
                }
                if (!mouseDown) {
                    return true;
                }
                if (!dragging && Math.abs(event.getY() - downY) > cellHeight / 2f) {
                    dragging = true;
                    removeCallbacks(longPressRunnable);
                }
                if (dragging) {
                    // 1000 模式不报移动，mouseReport 内部会返回 null
                    send(emulator.mouseReport(0, col, row, false, true));
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                wheelMode = false;
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                if (mouseDown) {
                    mouseDown = false;
                    send(emulator.mouseReport(0, col, row, true, false));
                }
                wheelMode = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                if (mouseDown) {
                    mouseDown = false;
                    send(emulator.mouseReport(0, col, row, true, false));
                }
                wheelMode = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void send(String sequence) {
        if (sequence == null || session == null || !session.isRunning()) {
            return;
        }
        session.write(sequence);
    }

    /* -------------------- 键盘与 IME -------------------- */

    /**
     * 终端不再用系统输入法（键盘是自绘的），所以对系统声明「我不是文本编辑器」。
     *
     * 返回 true 时系统会在视图获得焦点时自动把 IME 推上来——实测 MIUI 启动就把
     * 窗口从 2340 压到 1314，横屏更狠（1080 里只剩 276）。返回 false 之后
     * 聚焦不再触发 IME，硬件键盘的按键仍然照常走 onKeyDown。
     */
    @Override
    public boolean onCheckIsTextEditor() {
        return false;
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
                sendCommitted(text == null ? null : text.toString());
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

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        String chars = event.getCharacters();
        if (chars != null && !chars.isEmpty()) {
            sendText(chars);
            return true;
        }
        return super.onKeyMultiple(keyCode, repeatCount, event);
    }

    /**
     * 硬件键盘的关键一步。外接键盘（蓝牙/USB）的按键默认先交给输入法：中文输入法不认识的
     * 组合（比如 Ctrl+C）会把它吞掉或转成候选操作，根本到不了 onKeyDown。
     * 所以在 IME 之前把「终端语义键」抢下来。
     */
    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && shouldBypassIme(event)) {
            if (handleKeyCode(event)) {
                return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    private boolean shouldBypassIme(KeyEvent event) {
        if (event.isCtrlPressed() || event.isAltPressed()) {
            return true;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_INSERT:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_F1:
            case KeyEvent.KEYCODE_F2:
            case KeyEvent.KEYCODE_F3:
            case KeyEvent.KEYCODE_F4:
            case KeyEvent.KEYCODE_F5:
            case KeyEvent.KEYCODE_F6:
            case KeyEvent.KEYCODE_F7:
            case KeyEvent.KEYCODE_F8:
            case KeyEvent.KEYCODE_F9:
            case KeyEvent.KEYCODE_F10:
            case KeyEvent.KEYCODE_F11:
            case KeyEvent.KEYCODE_F12:
                return true;
            default:
                return false;
        }
    }

    /** 修饰位：与 xterm 的 CSI 1 ; <m> 参数一致（m = 1 + shift + 2*alt + 4*ctrl）。 */
    private static final int M_SHIFT = 1;
    private static final int M_ALT = 2;
    private static final int M_CTRL = 4;

    private boolean handleKeyCode(KeyEvent event) {
        if (session == null) {
            return false;
        }
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        // 单独按下修饰键不应该往 PTY 写任何字节
        if (isModifierKey(keyCode)) {
            return true;
        }

        TerminalEmulator emulator = session.getEmulator();
        boolean ctrl = event.isCtrlPressed();
        boolean alt = event.isAltPressed();
        boolean shift = event.isShiftPressed();
        int mod = (shift ? M_SHIFT : 0) | (alt ? M_ALT : 0) | (ctrl ? M_CTRL : 0);

        // Ctrl+Shift+C / Ctrl+Shift+V 是终端的标准复制/粘贴键位（xterm、GNOME Terminal、
        // Windows Terminal 都这么定）。必须先拦：Ctrl+Shift+C 漏下去就成了 Ctrl+C =
        // SIGINT，用户想复制却把正在跑的编译打断了；Ctrl+Shift+V 漏下去是 0x16，程序收到乱码。
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_V) {
            pasteFromClipboard();
            return true;
        }
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_C) {
            copyScreenToClipboard();
            return true;
        }

        String sequence = specialKey(keyCode, mod, emulator.isAppCursorKeys());
        if (sequence != null) {
            sendText(sequence);
            return true;
        }

        // 取基础字符时要摘掉 Ctrl 位：getUnicodeChar 遇到 Ctrl 组合通常直接返回 0
        int meta = event.getMetaState()
                & ~(KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON);
        int cp = event.getUnicodeChar(meta);
        if (cp <= 0) {
            cp = event.getUnicodeChar(0);
        }
        if (cp <= 0) {
            return false;
        }

        String out = withModifiers(cp, ctrl, alt);
        if (out == null) {
            return false;
        }
        sendText(out);
        return true;
    }

    /**
     * Ctrl/Alt 组合到字节的映射。
     *
     * Ctrl 走控制字符：Ctrl+A..Z → 0x01..0x1A，Ctrl+@/空格 → 0x00，Ctrl+[ \ ] ^ _ → 0x1B..0x1F，
     * Ctrl+? → 0x7F。Alt 走「ESC 前缀」（ESC+c 就是 Meta-c，vim/emacs 的 M-x 靠它）。
     * 两者可以叠加，Alt+Ctrl+A 就是 ESC 0x01。
     */
    private static String withModifiers(int cp, boolean ctrl, boolean alt) {
        int base = cp;
        if (ctrl) {
            int upper = cp;
            if (upper >= 'a' && upper <= 'z') {
                upper = upper - 'a' + 'A';
            }
            if (upper >= '@' && upper <= '_') {
                base = upper & 0x1F;
            } else if (cp == ' ') {
                base = 0x00;
            } else if (cp == '?') {
                base = 0x7F;
            } else if (cp >= 0xE000 && cp <= 0xE07F) {
                // 某些键盘把 Ctrl+字母报成私用区码点
                base = cp - 0xE000;
            }
            // 其余组合（Ctrl+数字、Ctrl+标点）在 xterm 里多数不发控制字符，保留原字符
        }
        StringBuilder sb = new StringBuilder(2);
        if (alt) {
            sb.append('\u001b');
        }
        sb.appendCodePoint(base);
        return sb.toString();
    }

    private static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_FUNCTION:
                return true;
            default:
                return false;
        }
    }

    /**
     * 特殊键 → 转义序列。带修饰时按 xterm 的 CSI 1 ; <m> 形式（vim 的 terminfo 里
     * kUP5 / kHOM5 之类就是指这些）。
     */
    private static String specialKey(int keyCode, int mod, boolean appCursor) {
        boolean plain = mod == 0;
        String csi = "\u001b[";
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 没开 modifyOtherKeys 的 xterm 里，Shift/Ctrl+Enter 仍然是 CR。
                // 原来一律加 ESC 前缀，vim 会先被 ESC 拉回普通模式再回车——插进半个命令。
                if ((mod & M_ALT) != 0) {
                    return "\u001b\r";
                }
                return "\r";
            case KeyEvent.KEYCODE_DEL:
                // Ctrl+Backspace 在 xterm 里是 BS(0x08)，普通退格是 DEL(0x7f)
                if ((mod & M_CTRL) != 0) {
                    return mod == M_CTRL ? "\u0008" : "\u001b\u0008";
                }
                return mod == 0 ? "\u007f" : "\u001b\u007f";
            case KeyEvent.KEYCODE_ESCAPE:
                return "\u001b";
            case KeyEvent.KEYCODE_TAB:
                // Shift+Tab 是 CBT（反向制表），vim 里就是往回跳缩进
                if ((mod & M_SHIFT) != 0) {
                    return csi + "Z";
                }
                return "\t";
            case KeyEvent.KEYCODE_DPAD_UP:
                return cursorKey('A', mod, appCursor);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return cursorKey('B', mod, appCursor);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return cursorKey('C', mod, appCursor);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return cursorKey('D', mod, appCursor);
            case KeyEvent.KEYCODE_MOVE_HOME:
                if (plain) {
                    return appCursor ? "\u001bOH" : csi + "H";
                }
                return csi + "1;" + (mod + 1) + "H";
            case KeyEvent.KEYCODE_MOVE_END:
                if (plain) {
                    return appCursor ? "\u001bOF" : csi + "F";
                }
                return csi + "1;" + (mod + 1) + "F";
            case KeyEvent.KEYCODE_INSERT:
                return plain ? csi + "2~" : csi + "2;" + (mod + 1) + "~";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return plain ? csi + "3~" : csi + "3;" + (mod + 1) + "~";
            case KeyEvent.KEYCODE_PAGE_UP:
                return plain ? csi + "5~" : csi + "5;" + (mod + 1) + "~";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return plain ? csi + "6~" : csi + "6;" + (mod + 1) + "~";
            case KeyEvent.KEYCODE_F1:
                return plain ? "\u001bOP" : csi + "1;" + (mod + 1) + "P";
            case KeyEvent.KEYCODE_F2:
                return plain ? "\u001bOQ" : csi + "1;" + (mod + 1) + "Q";
            case KeyEvent.KEYCODE_F3:
                return plain ? "\u001bOR" : csi + "1;" + (mod + 1) + "R";
            case KeyEvent.KEYCODE_F4:
                return plain ? "\u001bOS" : csi + "1;" + (mod + 1) + "S";
            case KeyEvent.KEYCODE_F5:
                return fKey(15, mod);
            case KeyEvent.KEYCODE_F6:
                return fKey(17, mod);
            case KeyEvent.KEYCODE_F7:
                return fKey(18, mod);
            case KeyEvent.KEYCODE_F8:
                return fKey(19, mod);
            case KeyEvent.KEYCODE_F9:
                return fKey(20, mod);
            case KeyEvent.KEYCODE_F10:
                return fKey(21, mod);
            case KeyEvent.KEYCODE_F11:
                return fKey(23, mod);
            case KeyEvent.KEYCODE_F12:
                return fKey(24, mod);
            default:
                return null;
        }
    }

    private static String cursorKey(char finalChar, int mod, boolean appCursor) {
        if (mod == 0) {
            return appCursor ? "\u001bO" + finalChar : "\u001b[" + finalChar;
        }
        return "\u001b[1;" + (mod + 1) + finalChar;
    }

    private static String fKey(int code, int mod) {
        if (mod == 0) {
            return "\u001b[" + code + "~";
        }
        return "\u001b[" + code + ";" + (mod + 1) + "~";
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
