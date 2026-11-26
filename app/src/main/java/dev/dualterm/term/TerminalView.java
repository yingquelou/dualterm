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
import android.view.VelocityTracker;
import android.widget.Scroller;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.ScaleGestureDetector;

/**
 * 终端渲染 + 输入。
 *
 * 渲染：按「同色同属性的连续单元格」合并成一次 drawText，避免每格一次绘制。
 * 输入：自己实现 InputConnection（IME 走 commitText），硬件键走 onKeyDown。
 * 滚动：上下拖动看 scrollback，点一下回到光标/弹出键盘。
 *
 * 触摸端的四个约定（都在手机单手握持的范围内）：
 * <ul>
 *   <li>单指拖动 = 看历史；右下角浮出「回到底部」胶囊，点它一键回游标（否则用户会被留在历史里）</li>
 *   <li>长按拖动 = 选区（复制 / 全选 / 取消浮动条），跨屏幕与 scrollback；鼠标上报模式归程序</li>
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

    /** 长按钩子。非鼠标模式下长按已归选区（见 longPressRunnable）；此钩子只在
     *  鼠标上报模式下触发——触摸归程序，长按留给将来的浮层菜单。 */
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
            // 选区接管长按（非鼠标模式，或 Shift 直通的手势）：
            // 锚点落在长按处，后续 MOVE 扩展、UP 固定并弹浮动条
            if (longPressForSelect && session != null && session.isRunning()) {
                startSelectionAt(selTouchX, selTouchY);
                return;
            }
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

    /**
     * 字号区间下限/上限。默认 6..28 是 2024 年主流机型的经验值，但竖屏
     * 80 列能不能放下是「屏宽 × 字号」的联合结果：同为最小字号，Note 8
     * 富余、Note 9 溢出折行——固定下限对窄屏机不够用。菜单「设置 →
     * 终端字号范围」可改，捏合与字号 ± 一律走这里的钳制。
     */
    private int minSizeSp = 6;
    private int maxSizeSp = 28;

    private int scrollOffset;
    private boolean ctrlDown;
    private boolean altDown;

    /** 外接全尺寸键盘（活动层探测后告知）——输入法接管硬键盘的前提。 */
    private boolean externalKbd;
    /** 接管中：硬键盘字母进输入法组词，回车等键在组词期间一并让路。 */
    private boolean imeTakeover;
    /** 输入法正在组词（composing 非空），由 InputConnection 的 composing 回调维护。 */
    private boolean imeComposing;
    /** 接管策略，见 {@link #setImeTakeoverMode}。 */
    static final int TAKEOVER_AUTO = 0;
    static final int TAKEOVER_ALWAYS = 1;
    static final int TAKEOVER_NEVER = 2;
    private int imeTakeoverMode = TAKEOVER_AUTO;

    /** 双击 Shift 切中/英：两次 Shift 按下的最大间隔（毫秒）。 */
    private static final long SHIFT_TOGGLE_MS = 500;
    private long lastShiftDownMs;
    /** 上次 Shift 之后没插过别的键——连打大写（A、B 各按一次 Shift）不算双击。 */
    private boolean shiftChainClean;
    /**
     * 判定成双击的那一次 Shift 要整对（按下+松开）一起吞掉：只吞按下、把松开漏给输入法，
     * 输入法会把它当成一次没有配对的点击自己切一遍中英，回来时就成了「nihao」直出。
     */
    private boolean swallowShiftUp;

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

    /**
     * 惯性滑动：抬手速度交给 Scroller 逐帧推进 scrollOffset。
     * Termux 同款做法——「丝滑」来自抬手后的减速，而不是像素级滚动（两边都是整行步进）。
     */
    private Scroller scroller;
    private VelocityTracker velocityTracker;
    /** fling 速度缩放：0.25 让一次快滑能走完大半屏历史，又不至于冲到顶（同 Termux）。 */
    private static final float FLING_SCALE = 0.25f;
    /** 低于这个速度不算 fling，避免轻点拖动后自己滑一段。 */
    private static final float FLING_MIN_VELOCITY = 180f;

    /**
     * 上一帧的「全局总行数」（逐出行 + scrollback）。视口在历史里时，用它把新到达的
     * 输出换算成 offset 增量，从而锚住用户正在看的内容——否则后台每来一行就顶走一行。
     */
    private long lastTotalLines = -1;

    /** 鼠标上报进行中（vim 里正在拖选/拖拽）。 */
    private boolean mouseDown;
    private int lastMouseCol = 1;
    private int lastMouseRow = 1;
    /** 双指滑动 → 滚轮上报。 */
    private boolean wheelMode;
    private float lastWheelY;

    /* -------------------- 选区（roadmap 第一节） -------------------- */

    /*
     * 坐标系：选区端点用「全局行号 = evictedRows + 绝对行号」（0 基列）。
     * 新输出把历史行挤出 scrollback 时，全局行号不受影响（逐出只从头部裁、
     * clearScreen 计入逐出），选区不会漂移；被逐出的部分高亮/取文本时自动消失。
     */
    private int selAnchorRow, selAnchorCol;     // 长按锚点（拖动期间固定）
    private int selMoveRow, selMoveCol;         // 活动端（随手指）
    private int selStartRow, selStartCol, selEndRow, selEndCol; // 归一化后
    private boolean selecting;                  // 长按拖选进行中
    private boolean selActive;                  // 有生效中的选区
    private float selTouchX, selTouchY;         // 手指位置（边缘自动滚动用）
    /** DOWN 时刻快照：长按是否允许转成选区（鼠标上报模式下归程序）。 */
    private boolean longPressForSelect;
    /** 硬件键盘 Shift+按下：本手势不再上报程序，走本地选区（xterm shift override）。 */
    private boolean shiftSelect;

    private final RectF selBarRect = new RectF();
    private final RectF[] selBarButton = {new RectF(), new RectF(), new RectF()};
    private boolean selBarVisible;
    private static final int SEL_BAR_COPY = 0;
    private static final int SEL_BAR_ALL = 1;
    private static final int SEL_BAR_CANCEL = 2;

    /** 视口第 0 行对应的全局行号（绘制时更新；手柄、浮窗、触摸换算共用）。 */
    private int viewportTopGlobal;
    /** 选区辅助游标：1 = 拖起点，2 = 拖终点，0 = 未拖。 */
    private int handleDrag;
    private float startHandleX, startHandleY, endHandleX, endHandleY;
    private static final float HANDLE_STEM_DP = 9f;
    private static final float HANDLE_RADIUS_DP = 7f;
    private static final float HANDLE_TOUCH_DP = 22f;

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
        scroller = new Scroller(getContext());

        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        scaling = true;
                        scaleStartSize = textSizeSp;
                        scaleAccum = 1f;
                        removeCallbacks(longPressRunnable);
                        // 拖选进行中转捏合：选区手势让位，状态收干净
                        if (selecting) {
                            clearSelection();
                        }
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
        // 换会话后总行数没有可比性，下一帧重新起算锚点
        this.lastTotalLines = -1;
        abortFling();
        clearSelection();
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
        sp = Math.max(minSizeSp, Math.min(maxSizeSp, sp));
        if (sp == textSizeSp) {
            return;
        }
        textSizeSp = sp;
        computeGrid(getWidth(), getHeight());
        invalidate();
    }

    /**
     * 设置字号区间。区间收窄时把当前字号一并夹回（比如把最小字号从 6
     * 调到 9，而当前正停在 6，就升到 9），调用方不用再自己补一次。
     */
    public void setFontRange(int min, int max) {
        if (min < 2) {
            min = 2;
        }
        if (max > 72) {
            max = 72;
        }
        if (min > max) {
            min = max;
        }
        minSizeSp = min;
        maxSizeSp = max;
        int cur = Math.max(min, Math.min(max, textSizeSp));
        if (cur != textSizeSp) {
            setTextSizeSp(cur);
        }
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
     * 点终端的回调：主界面借它处理「面板开着先关面板」的层级语义。
     *
     * 输入采用「系统输入法（主键盘）+ 自绘辅助键盘（特殊键）」组合，所以
     * 这里不再直接开关自绘键盘：点终端 = 要打字，由活动层决定弹系统输入法。
     */
    public interface TapListener {
        void onTap();
    }

    private TapListener tapListener;

    public void setTapListener(TapListener listener) {
        this.tapListener = listener;
    }

    /** 确保系统键盘可见（会话启动时用）。 */
    public void showKeyboard() {
        requestFocus();
        showIme();
    }

    public void hideKeyboard() {
        hideIme();
    }

    /** 活动层探测到外接全尺寸键盘插拔时调用。 */
    public void setExternalKeyboard(boolean value) {
        if (externalKbd == value) {
            return;
        }
        externalKbd = value;
        refreshTakeover();
    }

    /**
     * 接管策略：
     * <ul>
     * <li>{@code AUTO}——自动，只在输入法界面真的可见时接管（默认）；</li>
     * <li>{@code ALWAYS}——有外接键盘就交给输入法，用于输入法把候选画在自己的窗口里
     * 或系统 API 低于 30 取不到 IME inset 的情形（盲打组词的风险由用户自担）；</li>
     * <li>{@code NEVER}——硬键盘字母永远逐键直发 PTY，保留「硬键盘敲命令 +
     * 软键盘点拼音」的混用通路。</li>
     * </ul>
     */
    public void setImeTakeoverMode(int mode) {
        if (imeTakeoverMode == mode) {
            return;
        }
        imeTakeoverMode = mode;
        refreshTakeover();
    }

    public int getImeTakeoverMode() {
        return imeTakeoverMode;
    }

    /** 输入法此刻是否接管硬键盘（菜单提示与调试用）。 */
    public boolean isImeTakeover() {
        return imeTakeover;
    }

    /**
     * 判定当前该不该把硬键盘交给输入法。
     *
     * 自动模式要求输入法窗口可见：候选栏不可见时组词是盲打——用户不知道自己挂着
     * 拼音，之后它以错词或原始拼音漏进命令行（实测上条命令残留的 {@code ls} 会在
     * 下次空格时以「老师」冒出来），比不组词更危险。系统「使用屏幕键盘」
     * （{@code show_ime_with_hard_keyboard}）关着时输入法不出窗口，所以这个判定
     * 等价于「用户明确要在外接键盘上用输入法」。API 30 以下没有 IME inset 可查，
     * 自动模式退化成 NEVER（保持历史的直发行为）。
     */
    private boolean computeTakeover() {
        if (!externalKbd || imeTakeoverMode == TAKEOVER_NEVER) {
            return false;
        }
        if (imeTakeoverMode == TAKEOVER_ALWAYS) {
            return true;
        }
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return false;
        }
        android.view.WindowInsets in = getRootWindowInsets();
        return in != null && in.isVisible(android.view.WindowInsets.Type.ime());
    }

    /**
     * 接管状态变了要让输入法重取 EditorInfo：它按创建会话时的 inputType 决定
     * 硬键盘字母是自己组词还是回退给应用，不 restartInput 就永远停在旧语义上。
     */
    private void refreshTakeover() {
        if (!applyTakeover(computeTakeover())) {
            return;
        }
        InputMethodManager imm = imm();
        if (imm != null && hasFocus()) {
            imm.restartInput(this);
        }
    }

    /**
     * 接管态的唯一写入口（refreshTakeover 与 onCreateInputConnection 共用）。
     * 返回是否发生了变化；真变了才需要让输入法重取 EditorInfo。
     */
    private boolean applyTakeover(boolean takeover) {
        if (imeTakeover == takeover) {
            return false;
        }
        imeTakeover = takeover;
        imeComposing = false;
        return true;
    }

    /**
     * 键盘侧的中/英切换（双击 Shift）。实测搜狗对硬键盘的 Shift、Ctrl+空格、
     * Ctrl+Shift、Alt+Shift、Shift+空格全都不作中英切换，所以这个开关只能由本应用
     * 来做；做法是开关输入法窗口——窗口可见就是自动模式的接管条件，于是切换不需要
     * 另立状态，也不越过用户在菜单里定的策略（ALWAYS/NEVER 下本键不生效）。
     *
     * @return 是否消费了这次按键
     */
    private boolean toggleImeTakeover() {
        if (!externalKbd || imeTakeoverMode != TAKEOVER_AUTO) {
            return false;
        }
        if (imeTakeover) {
            hideIme();
            hint("英文：字母逐键直发");
        } else {
            showIme();
            hint("中文：输入法接管键盘组词");
        }
        return true;
    }

    private InputMethodManager imm() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    /** 窗口没焦点时 showSoftInput 会被丢弃：挂起，等 onWindowFocusChanged 补发。 */
    private boolean pendingImeShow;

    private void showIme() {
        InputMethodManager imm = imm();
        if (imm == null) {
            return;
        }
        if (!hasWindowFocus()) {
            pendingImeShow = true;
            return;
        }
        pendingImeShow = false;
        // 有外接键盘时 SHOW_IMPLICIT 会被判定为「用户没主动要键盘」而丢弃，
        // 输入法界面出不来（实测搜狗只回 RESULT_NOT_SHOWN）；FORCED 才叫得动。
        int flags = externalKbd ? InputMethodManager.SHOW_FORCED
                : InputMethodManager.SHOW_IMPLICIT;
        imm.showSoftInput(this, flags);
    }

    private void hideIme() {
        // 输入法收起 = 组词到此为止；不清这个标志，抢键判定会一直让路，
        // 表现为回车/ESC/方向键全体失灵。
        imeComposing = false;
        InputMethodManager imm = imm();
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    /**
     * 窗口焦点变化。程序开了 DECSET 1004 时（vim 的 FocusGained/FocusLost）要上报，
     * 否则切出去再切回来 vim 不会重绘。
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // 会话启动时窗口往往还没拿到焦点，showSoftInput 会被系统静默丢弃；
        // 等焦点回来补发一次
        if (hasWindowFocus && pendingImeShow) {
            pendingImeShow = false;
            post(new Runnable() {
                @Override
                public void run() {
                    showIme();
                }
            });
        }
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
        boolean truncated = dev.dualterm.util.PasteSanitize.wasTruncated(text);
        text = dev.dualterm.util.PasteSanitize.sanitize(text);
        if (text.isEmpty()) {
            hint("剪贴板里没有可粘贴的文本");
            return;
        }
        if (dev.dualterm.util.PasteSanitize.countLines(text) > 1) {
            // 多行粘贴必须确认：每一行都会作为独立输入立即发给 PTY（相当于
            // 逐行按回车）。实测把整屏复制再粘贴，shell 就把 63 行提示符当
            // 命令逐条"执行"了——屏幕内容若含 rm -rf 之类的行就是事故。
            // Windows Terminal / PuTTY 对多行粘贴都有确认框，这里对齐。
            confirmMultilinePaste(text);
        } else {
            doPaste(text);
        }
        if (truncated) {
            hint("内容过大，已截断到 512KB");
        }
    }

    private void confirmMultilinePaste(final String text) {
        int lineCount = dev.dualterm.util.PasteSanitize.countLines(text);
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

    /**
     * 粘贴落地：后台线程分段写 PTY。
     *
     * <p>不走 sendText——粘贴内容不能被按住的 Ctrl/Alt 修饰键改写。
     * 写入必须离开 UI 线程：PTY 缓冲区满（目标进程不读，比如 cat、sleep）
     * 时 write 会阻塞，在主线程上就是 ANR。单线程执行器保证多次粘贴按序落盘；
     * 分段 8KB 让读线程有机会穿插处理输出（长输出不至于整段憋到最后才刷屏）。
     */
    private static final java.util.concurrent.ExecutorService PASTE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void doPaste(String text) {
        final TermSession s = session;
        if (s == null) {
            return;
        }
        final boolean bracketed = s.getEmulator().isBracketedPaste();
        final String payload = bracketed ? "\u001b[200~" + text + "\u001b[201~" : text;
        PASTE_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int off = 0;
                while (off < data.length && s.isRunning()) {
                    int n = Math.min(8192, data.length - off);
                    s.writeBytes(data, off, n);
                    off += n;
                }
            }
        });
        scrollOffset = 0;
        // xterm：key press discards selection——粘贴同属写 PTY 的输入，弃选区
        if (selActive || selecting) {
            clearSelection();
        }
        invalidate();
    }

    /** 硬件键盘 Ctrl+Shift+V：从系统剪贴板粘贴（走 pasteText，带 bracketed paste）。 */
    private void pasteFromClipboard() {
        String text = readClipboardText();
        if (text == null) {
            return;
        }
        pasteText(text);
        hint("已粘贴 " + text.length() + " 个字符");
    }

    /**
     * 读剪贴板文本，失败返回 null。
     *
     * <p>必须只调一次 getPrimaryClip 并判空：MIUI 弹「允许读取剪贴板？」，
     * 用户拒绝后 hasPrimaryClip 可能仍为 true 而 getPrimaryClip 返回 null——
     * 曾经在两次调用之间返回了 null 直接 NPE 崩溃（crash buffer 实证）。
     * 服务异常（SecurityException / DeadObject）也一并兜住。
     */
    private String readClipboardText() {
        ClipboardManager cm =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            hint("剪贴板不可用");
            return null;
        }
        try {
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                hint("剪贴板是空的（或读取被拒绝）");
                return null;
            }
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item == null ? null : item.coerceToText(getContext());
            return text == null ? null : text.toString();
        } catch (Exception e) {
            hint("读取剪贴板失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 硬件键盘 Ctrl+Shift+C 无选区时的退化目标：复制当前屏（不含 scrollback）。
     * 有选区时走 copySelection()，与 GNOME Terminal 语义一致。
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
        try {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            hint("已复制当前屏 " + text.length() + " 个字符");
        } catch (Exception e) {
            hint("复制失败：" + e.getMessage());
        }
    }

    private void hint(String message) {
        android.widget.Toast.makeText(getContext(), message, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 视口整体平移 delta 行（负 = 看更早的内容），夹在 scrollback 范围内。 */
    private void scrollbackByLines(int delta) {
        if (session == null) {
            return;
        }
        int max = session.getEmulator().getScrollbackCount();
        scrollOffset = Math.max(0, Math.min(max, scrollOffset + delta));
        invalidate();
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
        // xterm：key press discards selection——任何往 PTY 写内容都结束选区
        if (selActive || selecting) {
            clearSelection();
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
        if (selActive || selecting) {
            clearSelection();
        }
        session.write(text);
        scrollOffset = 0;
        clearModifiers();
        invalidate();
    }

    /**
     * 原样写字节。sendRaw 走 UTF-8 编码，发不了 NUL/DEL/裸 ESC 这类
     * 不是合法 UTF-8 序列的单字节——字节模式自定义键（hex）走这里。
     */
    public void sendRawBytes(byte[] data) {
        if (session == null || data == null || data.length == 0) {
            return;
        }
        if (!session.isRunning()) {
            notifyDead();
            return;
        }
        if (selActive || selecting) {
            clearSelection();
        }
        session.writeBytes(data);
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

    /* -------------------- 选区 -------------------- */

    /** 触点 → {全局行号, 0 基列}，行列都钳制到现有内容范围。 */
    private int[] cellAt(float x, float y) {
        TerminalEmulator e = session.getEmulator();
        int row = (int) ((y - padY) / cellHeight);
        int abs = e.getScrollbackCount() - scrollOffset + row;
        int total = e.getEvictedRows() + e.getTotalLineCount();
        int global = Math.max(e.getEvictedRows(), Math.min(total - 1, e.getEvictedRows() + abs));
        int col = Math.max(0, Math.min(gridCols - 1, (int) ((x - padX) / cellWidth)));
        return new int[]{global, col};
    }

    private void startSelectionAt(float x, float y) {
        int[] cell = cellAt(x, y);
        if (selActive) {
            // xterm extend-selection 语义：已有选区时长按 = 挪离触点最近的那个
            // 端点（锚固定在较远端）。往选区外拖是扩，往里拖是缩——增减同一手势。
            anchorAtEndpointFartherFrom(cell[0], cell[1]);
        } else {
            selAnchorRow = cell[0];
            selAnchorCol = cell[1];
        }
        selMoveRow = cell[0];
        selMoveCol = cell[1];
        selecting = true;
        selActive = false;
        selBarVisible = false;
        normalizeSelection();
        invalidate();
    }

    /** 把锚移到离触点更远的选区端点：全局行距为主，同行再比列。 */
    private void anchorAtEndpointFartherFrom(int row, int col) {
        long dStart = distanceTo(row, col, selStartRow, selStartCol);
        long dEnd = distanceTo(row, col, selEndRow, selEndCol);
        if (dStart >= dEnd) {
            selAnchorRow = selStartRow;
            selAnchorCol = selStartCol;
        } else {
            selAnchorRow = selEndRow;
            selAnchorCol = selEndCol;
        }
    }

    private static long distanceTo(int row, int col, int endRow, int endCol) {
        long dr = Math.abs((long) row - endRow);
        long dc = Math.abs((long) col - endCol);
        return dr * 100000L + dc;
    }

    private void extendSelectionTo(float x, float y) {
        int[] cell = cellAt(x, y);
        selMoveRow = cell[0];
        selMoveCol = cell[1];
        normalizeSelection();
        invalidate();
    }

    private void normalizeSelection() {
        if (selAnchorRow < selMoveRow
                || (selAnchorRow == selMoveRow && selAnchorCol <= selMoveCol)) {
            selStartRow = selAnchorRow;
            selStartCol = selAnchorCol;
            selEndRow = selMoveRow;
            selEndCol = selMoveCol;
        } else {
            selStartRow = selMoveRow;
            selStartCol = selMoveCol;
            selEndRow = selAnchorRow;
            selEndCol = selAnchorCol;
        }
    }

    private boolean selectionEmpty() {
        return selStartRow == selEndRow && selStartCol == selEndCol;
    }

    private void clearSelection() {
        selecting = false;
        handleDrag = 0;
        selActive = false;
        selBarVisible = false;
        removeCallbacks(edgeScrollRunnable);
        invalidate();
    }

    /**
     * 拖选到屏幕上下边缘时的自动滚动：手指停在边缘就每 40ms 走一行，
     * 选区端点跟着新进入视口的内容延伸（xterm 拖出窗口自动滚的同款语义）。
     */
    private final Runnable edgeScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!selecting || session == null) {
                return;
            }
            float topEdge = padY + cellHeight;
            float bottomEdge = getHeight() - padY - cellHeight;
            int dir;
            if (selTouchY < topEdge) {
                dir = 1;        // 顶边 = 向更早的内容滚（scrollOffset 增）
            } else if (selTouchY > bottomEdge) {
                dir = -1;
            } else {
                return;
            }
            int max = session.getEmulator().getScrollbackCount();
            int next = Math.max(0, Math.min(max, scrollOffset + dir));
            if (next != scrollOffset) {
                scrollOffset = next;
                extendSelectionToTouch();
            }
            postDelayed(this, 40);
        }
    };

    private void scheduleEdgeScroll() {
        removeCallbacks(edgeScrollRunnable);
        float topEdge = padY + cellHeight;
        float bottomEdge = getHeight() - padY - cellHeight;
        if (selTouchY < topEdge || selTouchY > bottomEdge) {
            postDelayed(edgeScrollRunnable, 40);
        }
    }

    /* -------------------- 选区辅助游标 -------------------- */

    /**
     * 重算两个端点手柄的位置（圆头中心，视图坐标）。
     * 手柄画在行底再往下 stem + r，手指按圆头时边界线在指尖上方，不会被遮挡。
     * 端点滚出视口时贴边，保证还能把它拖回来。
     */
    private void updateHandlePositions() {
        TermSession s = session;
        if (!selActive || s == null) {
            return;
        }
        TerminalEmulator e = s.getEmulator();
        synchronized (e) {
            viewportTopGlobal = e.getEvictedRows() + e.getScrollbackCount() - scrollOffset;
        }
        float stem = dp(HANDLE_STEM_DP);
        float r = dp(HANDLE_RADIUS_DP);
        int startRow = Math.max(0, Math.min(gridRows - 1, selStartRow - viewportTopGlobal));
        int endRow = Math.max(0, Math.min(gridRows - 1, selEndRow - viewportTopGlobal));
        float maxY = getHeight() - r - dp(2);
        startHandleX = padX + selStartCol * cellWidth + dp(1);
        startHandleY = Math.min(maxY, padY + (startRow + 1) * cellHeight + stem + r);
        endHandleX = padX + (selEndCol + 1) * cellWidth;
        endHandleY = Math.min(maxY, padY + (endRow + 1) * cellHeight + stem + r);
    }

    /** 命中哪个手柄：1 = 起点，2 = 终点，0 = 都没有。 */
    private int hitsHandle(float x, float y) {
        updateHandlePositions();
        float r = dp(HANDLE_TOUCH_DP);
        if (Math.hypot(x - startHandleX, y - startHandleY) <= r) {
            return 1;
        }
        if (Math.hypot(x - endHandleX, y - endHandleY) <= r) {
            return 2;
        }
        return 0;
    }

    private void beginHandleDrag(int which) {
        handleDrag = which;
        selecting = true;
        selBarVisible = false;
        abortFling();
        // 锚固定在对侧端点：拖起点就是改 selStart，拖终点就是改 selEnd
        if (which == 1) {
            selAnchorRow = selEndRow;
            selAnchorCol = selEndCol;
            selTouchX = startHandleX;
            selTouchY = startHandleY;
        } else {
            selAnchorRow = selStartRow;
            selAnchorCol = selStartCol;
            selTouchX = endHandleX;
            selTouchY = endHandleY;
        }
        normalizeSelection();
        invalidate();
    }

    /** 手柄拖拽时把触点抬高到提示杆顶部，再取格——否则会多选一行。 */
    private void extendSelectionToTouch() {
        float y = selTouchY;
        if (handleDrag != 0) {
            y -= dp(HANDLE_STEM_DP + HANDLE_RADIUS_DP);
        }
        extendSelectionTo(selTouchX, y);
    }

    private void drawSelectionHandle(Canvas canvas, float x, float y) {
        float r = dp(HANDLE_RADIUS_DP);
        fillPaint.setColor(COLOR_CURSOR);
        rect.set(x - dp(1.5f), y - r - dp(HANDLE_STEM_DP), x + dp(1.5f), y);
        canvas.drawRect(rect, fillPaint);
        canvas.drawCircle(x, y, r, fillPaint);
    }

    private void copySelection() {
        TerminalEmulator e = session.getEmulator();
        String text = e.getSelectionText(selStartRow, selStartCol, selEndRow, selEndCol);
        if (text.isEmpty()) {
            hint("选区里没有文本");
            return;
        }
        ClipboardManager cm =
                (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            hint("已复制 " + text.length() + " 个字符");
        } catch (Exception ex) {
            hint("复制失败：" + ex.getMessage());
        }
        clearSelection();
    }

    private void selectAllSelection() {
        TerminalEmulator e = session.getEmulator();
        selAnchorRow = e.getEvictedRows();
        selAnchorCol = 0;
        selMoveRow = e.getEvictedRows() + e.getTotalLineCount() - 1;
        selMoveCol = gridCols - 1;
        normalizeSelection();
        selActive = true;
        invalidate();
    }

    private void handleSelectionBarTap(float x) {
        for (int i = 0; i < selBarButton.length; i++) {
            if (x >= selBarButton[i].left && x <= selBarButton[i].right) {
                if (i == SEL_BAR_COPY) {
                    copySelection();
                } else if (i == SEL_BAR_ALL) {
                    selectAllSelection();
                } else {
                    clearSelection();
                }
                return;
            }
        }
    }

    /* -------------------- 尺寸与网格 -------------------- */

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGrid(w, h);
        // adjustResize 下输入法窗口的出现/消失就是本视图的高度变化，
        // 借同一个时机复核接管判定（自动模式跟输入法可见性走）
        refreshTakeover();
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

    /**
     * 视口顶部对应的全局行号；没有会话时返回 -1。
     * 会话是进程级资产，阅读位置要跟着会话走（Activity 重建、切 tab 后能回到原段落）。
     */
    public int getViewportAnchor() {
        TermSession s = session;
        if (s == null) {
            return -1;
        }
        TerminalEmulator emulator = s.getEmulator();
        synchronized (emulator) {
            long anchor = (long) emulator.getEvictedRows() + emulator.getScrollbackCount() - scrollOffset;
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, anchor));
        }
    }

    /** 恢复阅读位置；anchor &lt; 0 表示没有记录，回到最底部。 */
    public void setViewportAnchor(int anchor) {
        TermSession s = session;
        if (s == null) {
            return;
        }
        TerminalEmulator emulator = s.getEmulator();
        synchronized (emulator) {
            int count = emulator.getScrollbackCount();
            long total = (long) emulator.getEvictedRows() + count;
            if (anchor < 0) {
                scrollOffset = 0;
            } else {
                scrollOffset = (int) Math.max(0, Math.min(count, total - anchor));
            }
            // 以当前总行数作为锚定基准，避免下一次绘制再补一次位移
            lastTotalLines = total;
        }
        invalidate();
    }

    /**
     * 输出到达时锚住内容：把 offset 按「总行数增量」同步推进。
     *
     * offset 是「离底部的行数」，新输出会让底部移动；不补偿的话，用户停在历史里时
     * 内容每来一行就被顶走一行（实测后台 8 秒漂移 116 行）。offset == 0 表示跟随
     * 底部，此时不做补偿。行被逐出/裁剪导致总数变小时同样反向补偿；锚点被裁掉则
     * 自然落到保留区最早一行。
     */
    private void anchorToContent(int evicted, int scrollbackCount) {
        long total = (long) evicted + scrollbackCount;
        if (lastTotalLines >= 0 && total != lastTotalLines && scrollOffset > 0) {
            long shifted = scrollOffset + (total - lastTotalLines);
            scrollOffset = (int) Math.max(0, Math.min(scrollbackCount, shifted));
        }
        lastTotalLines = total;
        if (scrollOffset > scrollbackCount) {
            scrollOffset = scrollbackCount;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (session == null) {
            drawHint(canvas);
            return;
        }
        TerminalEmulator emulator = session.getEmulator();
        textPaint.setTextSize(sp(textSizeSp));
        textPaint.setTypeface(Typeface.MONOSPACE);

        // Line 是可变对象（读线程 append 会原地改写字符/属性数组），锁外遍历会出现
        // 一帧里半行旧半行新的撕裂。绘制整帧持 emulator 的监视器锁拿一致性视图：
        // 写入端按 4~16KB 块持锁，这里最多等一个块的处理时间（毫秒级），可接受。
        // 注意 getLine/getScrollbackLine 是 synchronized 方法，监视器可重入，无死锁面。
        synchronized (emulator) {
            int evicted = emulator.getEvictedRows();
            int scrollbackCount = emulator.getScrollbackCount();
            anchorToContent(evicted, scrollbackCount);
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
                // 选区高亮：半透明叠层压在文字上。行号换算与绘制共用同一把锁，
                // 不会拿到撕裂的坐标
                if (selecting || selActive) {
                    int global = evicted + scrollbackCount - scrollOffset + row;
                    if (global >= selStartRow && global <= selEndRow) {
                        int from = global == selStartRow ? selStartCol : 0;
                        int to = global == selEndRow ? selEndCol : gridCols - 1;
                        from = Math.max(0, from);
                        to = Math.min(gridCols - 1, to);
                        if (from <= to) {
                            fillPaint.setColor(0x597CE38B);
                            rect.set(padX + from * cellWidth, padY + row * cellHeight,
                                    padX + (to + 1) * cellWidth, padY + (row + 1) * cellHeight);
                            canvas.drawRect(rect, fillPaint);
                        }
                    }
                }
            }

            viewportTopGlobal = evicted + scrollbackCount - scrollOffset;
            if (scrollOffset == 0 && emulator.isCursorVisible()) {
                drawCursor(canvas, emulator);
            }
        }

        // 选区辅助游标先画，浮动条压在最上层
        if (selActive && !selectionEmpty()) {
            updateHandlePositions();
            drawSelectionHandle(canvas, startHandleX, startHandleY);
            drawSelectionHandle(canvas, endHandleX, endHandleY);
        }

        // 选区浮动条：长按拖完/Shift 拖完出现，复制 / 全选 / 取消
        selBarVisible = selActive && !selecting;
        if (selBarVisible) {
            drawSelectionBar(canvas);
        }

        scrollPillVisible = scrollOffset > 0;
        if (scrollPillVisible) {
            drawScrollPill(canvas);
        }
    }

    /**
     * 选区浮动条（自绘，不用 ActionMode：终端全屏自绘 View 上弹系统浮层
     * 会被 stateAlwaysHidden 的窗口配置和焦点问题反复折腾）。三个按钮
     * 命中区各自记 rect，onTouchEvent 的 DOWN 在最前面拦截。
     */
    private void drawSelectionBar(Canvas canvas) {
        String[] labels = {"复制", "全选", "取消"};
        textPaint.setTextSize(sp(12));
        textPaint.setFakeBoldText(false);
        textPaint.setUnderlineText(false);
        textPaint.setStrikeThruText(false);

        float h = dp(30);
        float gap = dp(16);
        float padH = dp(10);
        float w = 0;
        float[] tw = new float[labels.length];
        for (int i = 0; i < labels.length; i++) {
            tw[i] = textPaint.measureText(labels[i]);
            w += tw[i] + padH * 2;
            if (i < labels.length - 1) {
                w += gap;
            }
        }
        // 跟随选区：优先贴在选区上方；选区从顶部开始时转到下方，并给手柄留出高度。
        // 水平中心对齐选区中点，贴边时夹紧，保证浮窗总在手指附近、不压住选中的文字。
        int barStartRow = Math.max(0, Math.min(gridRows - 1, selStartRow - viewportTopGlobal));
        int barEndRow = Math.max(0, Math.min(gridRows - 1, selEndRow - viewportTopGlobal));
        float selTop = padY + barStartRow * cellHeight;
        float selBottom = padY + (barEndRow + 1) * cellHeight;
        float handleSpace = dp(HANDLE_STEM_DP + HANDLE_RADIUS_DP * 2 + 6);
        float top = selTop - h - dp(6);
        if (top < dp(4)) {
            top = Math.min(getHeight() - h - dp(4), selBottom + handleSpace);
        }
        float centerX = padX + ((selStartCol + selEndCol + 1) / 2f) * cellWidth;
        float left = Math.max(dp(6), Math.min(getWidth() - w - dp(6), centerX - w / 2f));
        selBarRect.set(left, top, left + w, top + h);

        fillPaint.setColor(0xEE2A2A33);
        rect.set(selBarRect);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f;
        float x = left;
        for (int i = 0; i < labels.length; i++) {
            selBarButton[i].set(x, top, x + tw[i] + padH * 2, top + h);
            textPaint.setColor(COLOR_FG);
            canvas.drawText(labels[i], x + padH, baseline, textPaint);
            x += tw[i] + padH * 2;
            if (i < labels.length - 1) {
                x += gap;
            }
        }
        textPaint.setTextSize(sp(textSizeSp));
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

    /**
     * drawText 用的复用字符缓冲。
     *
     * 一帧最多几十行 × 每行几段，原来每段都 new StringBuilder + toString()，
     * 持续输出时每秒上千次短命 String，GC 常客。改走 Canvas.drawText(char[], …) 后
     * 这一路完全不分配（宽字符/组合字符那条支路同理）。
     */
    private char[] drawBuf = new char[256];

    private void ensureDrawBuf(int need) {
        if (drawBuf.length >= need) {
            return;
        }
        int size = drawBuf.length;
        while (size < need) {
            size <<= 1;
        }
        drawBuf = new char[size];
    }

    private static int extrasLen(String extra) {
        return extra == null ? 0 : extra.length();
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
                    ensureDrawBuf(extrasLen(extra) + 2);
                    int n = Character.toChars(cp, drawBuf, 0);
                    if (extra != null) {
                        extra.getChars(0, extra.length(), drawBuf, n);
                        n += extra.length();
                    }
                    canvas.drawText(drawBuf, 0, n, padX + i * cellWidth, baseline, textPaint);
                    i++;
                    continue;
                }
                int runStart = i;
                // 每格最多展开成 2 个 char（CJK 以外的补充平面字符走上面的分支，这里只可能是 BMP）
                ensureDrawBuf((end - i) * 2);
                int n = 0;
                while (i < end) {
                    int c2 = line.text[i];
                    if (c2 == 0 || TerminalEmulator.charWidth(c2) == 2 || line.extraAt(i) != null) {
                        break;
                    }
                    n += Character.toChars(c2, drawBuf, n);
                    i++;
                }
                canvas.drawText(drawBuf, 0, n, padX + runStart * cellWidth, baseline, textPaint);
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
        // 选中态的浮动条最优先：复制 / 全选 / 取消
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && selBarVisible && selBarRect.contains(event.getX(), event.getY())) {
            handleSelectionBarTap(event.getX());
            return true;
        }
        // 先看是不是点在「回到底部」胶囊上：上滚状态下的这一下不该弹键盘
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && scrollPillVisible && scrollPill.contains(event.getX(), event.getY())) {
            scrollOffset = 0;
            scrollPillVisible = false;
            invalidate();
            return true;
        }
        // 选区端点手柄：按住圆头拖，边界线在手指上方，不会被手掌挡住
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && selActive && !selecting && !isDead()) {
            int handle = hitsHandle(event.getX(), event.getY());
            if (handle != 0) {
                beginHandleDrag(handle);
                return true;
            }
        }
        // 捏合改字号；进入缩放后这一串事件都归手势，不再当作拖动。
        // 鼠标上报模式（vim/htop）下同样要喂：否则双指捏合会被整段当
        // 鼠标事件吞掉，字号永远调不了（实测复现）。双指纯上下滑跨度
        // 不变，不会误触发缩放，滚轮模式照常工作。
        scaleDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            abortFling();
            startVelocityTracking(event);
        } else if (velocityTracker != null) {
            velocityTracker.addMovement(event);
        }
        if (scaling) {
            removeCallbacks(longPressRunnable);
            dragging = true;
            return true;
        }
        if (tracking) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // xterm「shift override」惯例：硬件键盘 Shift+按下 → 本手势
                // 不上报程序，改走本地选区；没有 Shift 就照旧全归程序
                shiftSelect = (event.getMetaState() & KeyEvent.META_SHIFT_MASK) != 0;
                if (shiftSelect) {
                    removeCallbacks(longPressRunnable);
                    longPressFired = true;
                    downY = event.getY();
                    lastY = downY;
                    dragging = false;
                    selTouchX = event.getX();
                    selTouchY = event.getY();
                    startSelectionAt(selTouchX, selTouchY);
                    return true;
                }
            }
            if (!shiftSelect) {
                return handleMouseTouch(event, emulator);
            }
            // Shift 直通手势落到下面的通用分支里走选区扩展
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                lastY = downY;
                dragging = false;
                longPressFired = false;
                selTouchX = event.getX();
                selTouchY = event.getY();
                // 硬件键盘 Shift+按下 → 选区手势（xterm shift override 同款，
                // 与鼠标上报模式的分支一致）：有选区挪最近端点（增减），没有
                // 则从触点起一个新选区
                if ((event.getMetaState() & KeyEvent.META_SHIFT_MASK) != 0 && !isDead()) {
                    removeCallbacks(longPressRunnable);
                    longPressFired = true;
                    startSelectionAt(selTouchX, selTouchY);
                    return true;
                }
                // 鼠标上报模式下长按归程序（程序优先），不转选区
                longPressForSelect = !tracking && !isDead();
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：无论接下来是捏合还是滚轮，长按都不该再触发，
                // 否则双指手势的准备阶段就会弹菜单（用户实测复现）
                removeCallbacks(longPressRunnable);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (selecting) {
                    selTouchX = event.getX();
                    selTouchY = event.getY();
                    extendSelectionToTouch();
                    scheduleEdgeScroll();
                    return true;
                }
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
                removeCallbacks(edgeScrollRunnable);
                if (selecting) {
                    // 长按没拖动 = 空选区，照旧吃掉手势但不进入选中态
                    selecting = false;
                    handleDrag = 0;
                    if (!selectionEmpty()) {
                        selActive = true;
                        selBarVisible = true;
                    } else {
                        clearSelection();
                    }
                    invalidate();
                    return true;
                }
                if (dragging) {
                    // 拖动结束 → 按手势速度惯性滑一段；点按（未拖动）仍走原来的点击路径
                    startFling();
                    return true;
                }
                recycleVelocityTracker();
                if (!dragging && !longPressFired) {
                    if (selActive) {
                        // xterm 惯例：单击清除选区（这一下不弹键盘）
                        clearSelection();
                        return true;
                    }                    if (isDead()) {
                        // 键盘弹出来也没处输入，直接引导去重启
                        notifyDead();
                    } else if (tapListener != null) {
                        tapListener.onTap();
                    } else {
                        showKeyboard();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                removeCallbacks(edgeScrollRunnable);
                abortFling();
                recycleVelocityTracker();
                selecting = false;
                handleDrag = 0;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /* -------------------- 惯性滑动 -------------------- */

    /** 速度上限（px/s）：再快也不让一次 fling 直接冲到历史顶端。 */
    private static final float MAX_FLING_VELOCITY = 8000f;

    private void startVelocityTracking(MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
        velocityTracker.addMovement(event);
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /**
     * 抬手：按手势速度启动惯性滑动，上限是当前 scrollback 行数。
     * 手指下滑（dy &gt; 0）= offset 增大 = 看更早的内容，与 Scroller 的 y 增长同向。
     */
    private void startFling() {
        if (velocityTracker == null || session == null) {
            recycleVelocityTracker();
            return;
        }
        velocityTracker.computeCurrentVelocity(1000, MAX_FLING_VELOCITY);
        float vy = velocityTracker.getYVelocity();
        recycleVelocityTracker();
        if (Math.abs(vy) < FLING_MIN_VELOCITY) {
            return;
        }
        int max = session.getEmulator().getScrollbackCount();
        if (max <= 0) {
            return;
        }
        scroller.fling(0, scrollOffset, 0, (int) (vy * FLING_SCALE), 0, 0, 0, max);
        post(flingRunnable);
    }

    private void abortFling() {
        if (scroller != null && !scroller.isFinished()) {
            scroller.abortAnimation();
        }
        removeCallbacks(flingRunnable);
    }

    private final Runnable flingRunnable = new Runnable() {
        @Override
        public void run() {
            TermSession s = session;
            if (s == null) {
                return;
            }
            if (!scroller.computeScrollOffset()) {
                return;
            }
            // 历史行数会在惯性期间变化（新输出把旧行挤出），每帧重新夹紧
            int max = s.getEmulator().getScrollbackCount();
            int next = Math.max(0, Math.min(max, scroller.getCurrY()));
            if (next != scrollOffset) {
                scrollOffset = next;
                invalidate();
            }
            if (!scroller.isFinished()) {
                post(this);
            }
        }
    };

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
                // 鼠标上报模式：长按与选区都归程序
                longPressForSelect = false;
                shiftSelect = false;
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
     * 终端是文本编辑器：系统输入法负责字母/数字/常用标点（主键盘），
     * 自绘键盘只补系统键盘没有的特殊键（见 ui/SoftKeyboard）。
     *
     * 历史教训（别再改回去）：早前为了避免联想词、也为了不让焦点自动弹键盘，
     * 这里返回 false 并把 inputType 写成 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD。
     * 后果是——false 让系统键盘彻底叫不出来；password 变体让中文输入法判定
     * 为密码框、切成安全键盘（无联想、键位残缺），输入能力反而被砍掉大半。
     * 正确做法是标准文本输入 + 不联想，弹键盘的时机由活动层显式控制
     * （manifest 的 stateAlwaysHidden）。
     */
    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        // 两种形态：
        // - 常规（含软键盘打中文）：TYPE_NULL，安卓终端模拟器的标准做法（Termux 同款），
        //   告诉输入法「这里不是普通文本框」，压掉联想/自动纠错；中文输入不受影响——
        //   拼音组词仍在输入法内部，选词上屏走 commitText。
        // - 外接键盘 + 输入法界面可见（见 computeTakeover）：必须是文本框语义，输入法才会
        //   接管硬键盘字母组词；用 NO_SUGGESTIONS 压联想，避免把敲的命令当成词条候选。
        // 学习词库两种形态都不进：终端里敲的是命令/密码，不该留给输入法做个性化语料。
        applyTakeover(computeTakeover());
        // 新会话不存在旧组词状态，必须清（applyTakeover 只在接管态变化时清）
        imeComposing = false;
        out.inputType = imeTakeover
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                : InputType.TYPE_NULL;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        // fullEditor=true（Termux 同款）：composing 文本由 BaseInputConnection 默认实现
        // 落进内部 Editable 并维护 composing span，IME 回读状态自洽——待选区才能正常收尾。
        // 之前 fullEditor=false + 吞掉 setComposingText/finishComposingText 是反例：
        // IME 的 composing 永远「落不了地」，表现为键入全程无响应、点选待选区也不消失。
        return new BaseInputConnection(this, true) {
            /** 只记录「输入法正在组词」，文本落盘照旧交基类——抢键判定要用（见 shouldBypassIme）。 */
            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                imeComposing = text != null && text.length() > 0;
                return super.setComposingText(text, newCursorPosition);
            }

            @Override
            public boolean setComposingRegion(int start, int end) {
                imeComposing = end > start;
                return super.setComposingRegion(start, end);
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                imeComposing = false;
                super.commitText(text, newCursorPosition);
                if (session == null) {
                    return true;
                }
                // 只发输入法这次提交的内容：Editable 里可能还留着输入法挂着的
                // composing 文本，整体发出会把拼音一起漏进命令行（实测「你好你好」）
                sendCommitted(text == null ? null : text.toString());
                getEditable().clear();
                return true;
            }

            @Override
            public boolean finishComposingText() {
                imeComposing = false;
                super.finishComposingText();
                if (session == null) {
                    return true;
                }
                // IME 强制收尾（切焦点/按实体键等）：composing 里的内容照常提交
                sendCommitted(getEditable().toString());
                getEditable().clear();
                return true;
            }

            /**
             * 输入法「复制」靠这里取选区：返回 null 时它拿不到文本，
             * 浮动工具条的复制/剪切会保持禁用（本次改造前的状态）。
             */
            @Override
            public CharSequence getSelectedText(int flags) {
                if (!selActive || selecting || session == null) {
                    return null;
                }
                String text = session.getEmulator()
                        .getSelectionText(selStartRow, selStartCol, selEndRow, selEndCol);
                return text.isEmpty() ? null : text;
            }

            /** 输入法的「全选」会传 0..N；终端没有字符偏移模型，按语义落到终端选区。 */
            @Override
            public boolean setSelection(int start, int end) {
                if (start == 0 && end > 0) {
                    selectAllSelection();
                    return true;
                }
                if (start == end) {
                    clearSelection();
                    return true;
                }
                return false;
            }

            /** 输入法浮动工具条：复制 / 剪切 / 全选。粘贴走 commitText，本来就是通的。 */
            @Override
            public boolean performContextMenuAction(int id) {
                if (id == android.R.id.copy || id == android.R.id.cut) {
                    // 终端里没有「剪切」语义（不能删掉已输出的文本），按复制处理
                    copySelection();
                    return true;
                }
                if (id == android.R.id.selectAll) {
                    selectAllSelection();
                    return true;
                }
                return false;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                // 有的键盘（三星等开启自动纠错时）一次报多个字符的删除
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++) {
                    sendKeyEvent(deleteKey);
                }
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
     *
     * 双击 Shift 也在这层判：接管态下修饰键先过输入法，只有 PreIme 是两种模式下都一定
     * 收得到的一次（{@link #toggleImeTakeover}）。
     */
    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (isShiftKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (swallowShiftUp) {
                    swallowShiftUp = false;
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                boolean dbl = shiftChainClean
                        && event.getEventTime() - lastShiftDownMs <= SHIFT_TOGGLE_MS;
                lastShiftDownMs = event.getEventTime();
                shiftChainClean = true;
                if (dbl && toggleImeTakeover()) {
                    lastShiftDownMs = 0;
                    shiftChainClean = false;
                    swallowShiftUp = true;
                    return true;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
            shiftChainClean = false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && shouldBypassIme(event)) {
            if (handleKeyCode(event)) {
                return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    private boolean shouldBypassIme(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (letsImeGoFirst(keyCode, event.isCtrlPressed(), event.isAltPressed(),
                event.isShiftPressed())) {
            return false;
        }
        if (event.isCtrlPressed() || event.isAltPressed()) {
            return true;
        }
        // 接管态把「终端语义键」全数交给输入法：空闲时它用 sendKeyEvent 原样退回来
        // （实测回车、空格、方向键都退得回来），挂着拼音时由它决定选词还是取消。
        // 反过来抢在输入法前面发 CR 的旧做法实测会留下挂在输入法里的拼音——命令带着
        // CR 执行了，下一行凭空冒出一个词。
        if (imeTakeover) {
            return false;
        }
        if (imeComposing) {
            return false;
        }
        return isTerminalSemanticKey(keyCode);
    }

    /**
     * 输入方案切换键让输入法先手（纯函数，KbdTest 覆盖；KeyEvent 常量编译期内联，
     * 桌面运行不需要 android 运行时）：
     * <ul>
     * <li>只按修饰键（含 Alt+Shift 组合）：部分输入法（搜狗 PC 键位等）用修饰键
     * 单独或组合切换中英文；</li>
     * <li>Ctrl+空格：Windows/macOS/Linux 都通的输入法切换桌面标准，中文用户靠它
     * 在中/英文之间切；被 PreIme 抢走就永远切不了。</li>
     * </ul>
     * 两类都不在 PreIme 抢——输入法不消费时落到 onKeyDown：Ctrl+空格照样发
     * 0x00（readline set-mark），修饰键被 {@link #isModifierKey} 静默吃掉，语义不变。
     */
    public static boolean letsImeGoFirst(int keyCode, boolean ctrl, boolean alt, boolean shift) {
        return isModifierKey(keyCode)
                || (ctrl && !alt && !shift && keyCode == KeyEvent.KEYCODE_SPACE);
    }

    /**
     * 终端语义键表（纯函数，KbdTest 覆盖）：非接管态下由 PreIme 抢下来直接进 PTY
     * 的键。字母、空格、退格不在表内——退格靠 IME 退回后的 onKeyDown 路径，
     * 字母按当前输入方案处理。
     */
    public static boolean isTerminalSemanticKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
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
            // 有选区复制选区（GNOME Terminal 语义），没有则退化为复制当前屏
            if (selActive && !selectionEmpty()) {
                copySelection();
            } else {
                copyScreenToClipboard();
            }
            return true;
        }
        // Ctrl+Shift+A 全选：GNOME Terminal 的标准键位，与浮动条「全选」同一入口。
        // xterm 里 Ctrl+Shift+A 与 Ctrl+A 同为 0x01，终端没有任何程序依赖它的输入语义。
        if (ctrl && shift && keyCode == KeyEvent.KEYCODE_A) {
            selectAllSelection();
            return true;
        }
        // Shift+PgUp/PgDn 本地滚动一页：xterm 默认 translation（Shift <Prior> =
        // scroll-back(1,page)）、GNOME Terminal / Windows Terminal 同款。滚动是终端
        // 自己的视口操作，不发给程序；带 Ctrl/Alt 时仍按修饰序列发出去（CSI 5;<m>~），
        // 那才是程序在等的东西。
        if (shift && !ctrl && !alt
                && (keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            // scrollOffset 增大 = 看更早的内容（与触摸下滑、滚轮上滚同向）
            scrollbackByLines(keyCode == KeyEvent.KEYCODE_PAGE_UP ? gridRows : -gridRows);
            return true;
        }
        // Shift+Insert 粘贴：xterm 默认 translation（Shift <Insert> = insert-selection），
        // 与 Ctrl+Shift+V 等价。Alt/Ctrl+Ins 仍发修饰序列（CSI 2;<m>~）。
        if (shift && !ctrl && !alt && keyCode == KeyEvent.KEYCODE_INSERT) {
            pasteFromClipboard();
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

    private static boolean isShiftKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
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
