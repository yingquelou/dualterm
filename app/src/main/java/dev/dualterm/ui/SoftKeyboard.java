package dev.dualterm.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import dev.dualterm.R;
import dev.dualterm.term.TerminalView;

/**
 * 自绘辅助键盘：只放系统输入法不提供、或要翻好几页才找得到的键。
 *
 * 输入方案是「系统输入法（主键盘）+ 自绘键盘（特殊键）」：
 * 字母/数字/常用标点在系统键盘上打更顺手（还有中文、剪贴板粘贴），
 * 这里补终端才需要的键，分两个区——
 * <ul>
 *   <li><b>固定区（不可自定义）</b>：编辑键 HOME/END/PGUP/PGDN/DEL/INS、
 *       F1–F12。系统键盘没有、也没有「换个按法」的余地，原「⋯」面板的
 *       键整体并入（⋯ 键与独立展开取消，⌨ 一开全都在）。</li>
 *   <li><b>可自定义区（默认键集可增删、可恢复）</b>：控制字符 ^A ^D ^E ^K ^L
 *       ^R ^U ^W ^Y ^Z ^\——系统键盘没有，键条的 CTRL 锁要两步；shell 符号
 *       | &amp; &lt; &gt; ( ) [ ] { } $ ` ~ \ ; : " ' = * /——输入法符号页翻找
 *       成本高、终端里高频。长按任意键可删；「＋」添加自定义键；
 *       长按「＋」恢复默认键集。</li>
 * </ul>
 *
 * <h2>自定义键 = 步骤序列</h2>
 * 一个自定义键由若干<b>步骤</b>按序构成（不再限定单一模式），每步六选一：
 * 文本（UTF-8 原样，任意 Unicode/整段）、^X 控制符、ESC 序列、字节
 * （1–2 位十六进制的单个原始字节，覆盖 NUL/DEL/裸 ESC）、回车（CR）、
 * 延迟（毫秒）。发送时按序攒字节、遇延迟分批：先冲刷已攒字节，
 * 等 N 毫秒后继续——由此可以拼宏，比如 ^U + 命令 + 回车 = 「清行并执行」。
 *
 * 键集持久化在 SharedPreferences（Host 转交）：未自定义时为 null，直接用
 * 默认键集；一旦增删就落盘完整列表，「恢复默认」即清掉这份记录。旧格式
 * {label,mode,value}（单模式键）读取时自动转成单步骤序列。
 *
 * 不在这里重复的：ESC / TAB / ^C / 方向键在常驻键条（一步可达）；
 * 粘贴走系统输入法的剪贴板子菜单；空格 / 退格 / 回车 / 数字 / 字母 /
 * 常用标点交给系统键盘。
 *
 * 行高与主界面同档（横 27dp / 竖 34dp），随 updateChrome / 转屏刷新。
 */
public final class SoftKeyboard {

    /** 主界面提供的依赖：按键工厂、终端输出、键集持久化与状态联动。 */
    public interface Host {
        TerminalView terminal();

        Button makeKeyButton(String label);

        int dp(int value);

        boolean hardKeyboard();

        boolean sessionEnded();

        /** 持久化的自定义键集 JSON；null = 未自定义，用默认键集。 */
        String storedKeysJson();

        /** 保存键集 JSON；传 null 表示清除（恢复默认）。 */
        void storeKeysJson(String json);

        /**
         * 可自定义区最多占几行；≤0 表示不限（键多则键盘一直变高）。
         * 超过上限时自定义区竖向滚动，固定区与键条高度不受影响。
         */
        int maxCustomRows();

        /** 键盘显隐/键集变化后：主界面刷 chrome/键行布局/状态行/⌨ 键颜色。 */
        void onSoftKbdVisibilityChanged(boolean visible);
    }

    /**
     * 序列中的一个步骤：mode 决定 value 怎么发（行为集中在 StepMode 枚举上）。
     */
    private static final class Step {
        final StepMode mode;
        final String value;

        Step(StepMode mode, String value) {
            this.mode = mode;
            this.value = value;
        }
    }

    /** 一个自定义键：label 显示、steps 按序发送。 */
    private static final class KeySpec {
        final String label;
        final List<Step> steps;

        KeySpec(String label, List<Step> steps) {
            this.label = label;
            this.steps = steps;
        }
    }

    /**
     * 固定区（不可自定义）。第一行编辑键，第二行 F 键——F 键按序号找，
     * 一行排开视线不用跨行跳；12 键一行是原「⋯」面板验证过的宽度。
     */
    private static final String[][] FIXED_ROWS = {
            {"HOME", "\u001b[H"}, {"END", "\u001b[F"}, {"PGUP", "\u001b[5~"},
            {"PGDN", "\u001b[6~"}, {"DEL", "\u001b[3~"}, {"INS", "\u001b[2~"},
    };
    private static final String[][] FIXED_FROW = {
            {"F1", "\u001bOP"}, {"F2", "\u001bOQ"}, {"F3", "\u001bOR"},
            {"F4", "\u001bOS"}, {"F5", "\u001b[15~"}, {"F6", "\u001b[17~"},
            {"F7", "\u001b[18~"}, {"F8", "\u001b[19~"}, {"F9", "\u001b[20~"},
            {"F10", "\u001b[21~"}, {"F11", "\u001b[23~"}, {"F12", "\u001b[24~"},
    };

    /** 可自定义区的默认键集：控制字符一行 + shell 符号两行（均为单步骤键）。 */
    private static final Object[][] DEFAULT_CUSTOM = {
            {"^A", StepMode.CTRL, "A"}, {"^D", StepMode.CTRL, "D"}, {"^E", StepMode.CTRL, "E"},
            {"^K", StepMode.CTRL, "K"}, {"^L", StepMode.CTRL, "L"}, {"^R", StepMode.CTRL, "R"},
            {"^U", StepMode.CTRL, "U"}, {"^W", StepMode.CTRL, "W"}, {"^Y", StepMode.CTRL, "Y"},
            {"^Z", StepMode.CTRL, "Z"}, {"^\\", StepMode.CTRL, "\\"},
            {"|", StepMode.TEXT, "|"}, {"&", StepMode.TEXT, "&"}, {"<", StepMode.TEXT, "<"},
            {">", StepMode.TEXT, ">"}, {"(", StepMode.TEXT, "("}, {")", StepMode.TEXT, ")"},
            {"[", StepMode.TEXT, "["}, {"]", StepMode.TEXT, "]"}, {"{", StepMode.TEXT, "{"},
            {"}", StepMode.TEXT, "}"}, {"$", StepMode.TEXT, "$"}, {"`", StepMode.TEXT, "`"},
            {"~", StepMode.TEXT, "~"},
            {"\\", StepMode.TEXT, "\\"}, {";", StepMode.TEXT, ";"}, {":", StepMode.TEXT, ":"},
            {"\"", StepMode.TEXT, "\""}, {"'", StepMode.TEXT, "'"}, {"=", StepMode.TEXT, "="},
            {"*", StepMode.TEXT, "*"}, {"/", StepMode.TEXT, "/"},
    };

    /** 可自定义区每行最多几键：12 键一行与固定区 F 键行同宽，键不至太窄。 */
    private static final int CUSTOM_PER_ROW = 12;
    /** 「＋」添加键的标签。 */
    private static final String K_ADD = "＋";

    private final Host host;

    private ViewGroup kbd;
    /** 所有按键行，横竖屏切换时统一改行高。 */
    private final List<LinearLayout> kbdRows = new ArrayList<>();
    /** 可自定义区的滚动容器与内容盒（键多超过行数上限时竖向滚动）。 */
    private ScrollView customScroll;
    /** 固定区行数（编辑键一行 + F 键一行），行高计算用。 */
    private int fixedRowCount = 2;
    /** 可自定义区实际行数（含「＋」所在行）。 */
    private int customRowCount;
    private boolean kbdVisible;
    private boolean landscape;

    public SoftKeyboard(Host host) {
        this.host = host;
    }

    /** 是否已构建（布局里没有 kbd 容器时为 false）。 */
    public boolean isBuilt() {
        return kbd != null;
    }

    public boolean isVisible() {
        return kbdVisible;
    }

    /** 建自绘键盘：固定区（编辑键/F 键）+ 可自定义区（控制键/符号/自定义键）。 */
    public void build(ViewGroup container, boolean landscape) {
        if (container == null) {
            return;
        }
        kbd = container;
        this.landscape = landscape;
        renderAll();
        applyMetrics(landscape);
    }

    /** 当前键集：未自定义用默认；持久化坏了也退回默认（不崩）。 */
    private List<KeySpec> currentKeys() {
        String json = host.storedKeysJson();
        if (json != null && !json.isEmpty()) {
            List<KeySpec> keys = parseKeys(json);
            if (!keys.isEmpty()) {
                return keys;
            }
        }
        List<KeySpec> keys = new ArrayList<>();
        for (Object[] spec : DEFAULT_CUSTOM) {
            List<Step> steps = new ArrayList<>();
            steps.add(new Step((StepMode) spec[1], (String) spec[2]));
            keys.add(new KeySpec((String) spec[0], steps));
        }
        return keys;
    }

    /**
     * 解析持久化键集。兼容两代格式：旧 {label,mode,value}（单模式键）
     * 转成单步骤序列；新 {label,steps:[{mode,value}...]}。坏条目跳过，
     * 全坏或空列表返回空（上层退回默认键集）。
     */
    private static List<KeySpec> parseKeys(String json) {
        List<KeySpec> keys = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String label = o.optString("label", "");
                List<Step> steps = new ArrayList<>();
                if (o.has("steps")) {
                    JSONArray sa = o.getJSONArray("steps");
                    for (int j = 0; j < sa.length(); j++) {
                        JSONObject s = sa.getJSONObject(j);
                        StepMode m = StepMode.from(s.getString("mode"));
                        if (m == null) {
                            continue;   // 未知模式（prefs 被改坏）：跳过该步
                        }
                        steps.add(new Step(m, s.optString("value", "")));
                    }
                } else if (o.has("mode")) {
                    // 旧格式（v1 单模式单值）：单步骤序列
                    StepMode m = StepMode.from(o.getString("mode"));
                    if (m != null) {
                        steps.add(new Step(m, o.optString("value", "")));
                    }
                }
                if (!label.isEmpty() && !steps.isEmpty()) {
                    keys.add(new KeySpec(label, steps));
                }
            }
        } catch (Exception ignored) {
            // 落盘内容被改坏：返回已解析部分（可能为空 → 上层用默认）
        }
        return keys;
    }

    private static String toJson(List<KeySpec> keys) {
        try {
            JSONArray arr = new JSONArray();
            for (KeySpec k : keys) {
                JSONObject o = new JSONObject();
                o.put("label", k.label);
                JSONArray steps = new JSONArray();
                for (Step s : k.steps) {
                    JSONObject so = new JSONObject();
                    so.put("mode", s.mode.name());
                    so.put("value", s.value);
                    steps.put(so);
                }
                o.put("steps", steps);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 键集变更后：落盘 + 整体重建（行数会变，高度必须跟着重算）。 */
    private void applyKeys(List<KeySpec> keys) {
        host.storeKeysJson(toJson(keys));
        refresh();
    }

    public void restoreDefaults() {
        host.storeKeysJson(null);
        refresh();
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(host.terminal().getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 同行禁止基线对齐：混排 11sp/14sp 时默认对齐会把小字键块下推，
        // 底部被下一行盖住（键行圆角残缺的根因）
        row.setBaselineAligned(false);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        kbdRows.add(row);
        return row;
    }

    private void addKey(LinearLayout row, final String label, View.OnClickListener click,
                        View.OnLongClickListener longClick, int textSize) {
        Button b = host.makeKeyButton(label);
        b.setTextSize(textSize);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, host.dp(34), 1f);
        lp.setMargins(host.dp(2), host.dp(2), host.dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        if (longClick != null) {
            b.setOnLongClickListener(longClick);
        }
        row.addView(b);
    }

    /** 固定区按键：直发转义序列（与原「⋯」面板同语义）。 */
    private void addFixedKey(LinearLayout row, final String label, final String sequence) {
        addKey(row, label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.terminal().sendRaw(sequence);
            }
        }, null, 11);
    }

    private void renderAll() {
        if (kbd == null) {
            return;
        }
        kbd.removeAllViews();
        kbdRows.clear();
        customScroll = null;
        customRowCount = 0;

        // ---- 固定区：编辑键一行 + F 键一行（不可自定义，无长按） ----
        LinearLayout row = makeRow();
        for (String[] spec : FIXED_ROWS) {
            addFixedKey(row, spec[0], spec[1]);
        }
        kbd.addView(row);
        row = makeRow();
        for (String[] spec : FIXED_FROW) {
            addFixedKey(row, spec[0], spec[1]);
        }
        kbd.addView(row);

        // ---- 可自定义区：12 键换行，「＋」固定挂在最后一行末尾 ----
        // 键的身份 = 在键集列表里的下标（不是内容相等——两个内容相同的键
        // 必须能各自被编辑/删除，内容匹配会永远命中第一个）
        //
        // 自定义区装进 ScrollView：超过行数上限（Host.maxCustomRows）时
        // 只让这一块滚，固定区和键条不被顶走。键的数量本身不设上限。
        Context ctx = host.terminal().getContext();
        LinearLayout customBox = new LinearLayout(ctx);
        customBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(false);
        scroll.addView(customBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        kbd.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        customScroll = scroll;

        final List<KeySpec> keys = currentKeys();
        int rows = (keys.size() + CUSTOM_PER_ROW - 1) / CUSTOM_PER_ROW;
        for (int r = 0; r < rows; r++) {
            LinearLayout crow = makeRow();
            int from = r * CUSTOM_PER_ROW;
            int to = Math.min(keys.size(), from + CUSTOM_PER_ROW);
            for (int i = from; i < to; i++) {
                final int keyIndex = i;
                final KeySpec key = keys.get(i);
                final boolean custom = !isDefaultKey(key);
                addKey(crow, key.label, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onKey(key);
                    }
                }, new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (custom) {
                            showEditDialog(keyIndex, key);
                        } else {
                            confirmRemoveDefaultKey(keyIndex, key);
                        }
                        return true;
                    }
                }, 14);
            }
            if (r == rows - 1) {
                addAddKey(crow);
            }
            customBox.addView(crow);
        }
        if (rows == 0) {
            LinearLayout addRow = makeRow();
            addAddKey(addRow);
            customBox.addView(addRow);
            rows = 1;
        }
        customRowCount = rows;
        fixedRowCount = 2;
    }

    private boolean isDefaultKey(KeySpec key) {
        if (key.steps.size() != 1) {
            return false;
        }
        for (Object[] spec : DEFAULT_CUSTOM) {
            if (spec[0].equals(key.label)
                    && spec[1] == key.steps.get(0).mode
                    && spec[2].equals(key.steps.get(0).value)) {
                return true;
            }
        }
        return false;
    }

    private void addAddKey(LinearLayout row) {
        addKey(row, K_ADD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditDialog(-1, null);
            }
        }, new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmRestoreDefaults();
                return true;
            }
        }, 12);
        // ＋ 是功能键（打开添加对话框，不发字符）：绿描边与输入键区分，
        // 同款样式也用于键条的 ⌨ / ⛶
        row.getChildAt(row.getChildCount() - 1)
                .setBackgroundResource(R.drawable.key_bg_func);
    }

    private void confirmRestoreDefaults() {
        new AlertDialog.Builder(host.terminal().getContext())
                .setTitle("恢复默认键集")
                .setMessage("辅助键盘恢复出厂键集：删除过的默认键找回，添加的自定义键清空。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        restoreDefaults();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveDefaultKey(final int keyIndex, final KeySpec key) {
        new AlertDialog.Builder(host.terminal().getContext())
                .setTitle("删除「" + key.label + "」")
                .setMessage("这是默认键集里的键，删除后可长按「＋」恢复默认键集找回。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        removeKey(keyIndex);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按下标删除键；下标越界（键集在会话间隙被改）静默忽略。 */
    private void removeKey(int keyIndex) {
        List<KeySpec> keys = currentKeys();
        if (keyIndex >= 0 && keyIndex < keys.size()) {
            keys.remove(keyIndex);
            applyKeys(keys);
        }
    }

    /* -------------------- 自定义键编辑对话框（步骤序列） -------------------- */

    /**
     * 添加/编辑自定义键对话框：标签 + 步骤列表。每步一行
     * （模式 Spinner + 内容输入框 + 删除「×」），「＋ 步骤」追加，
     * 按行序执行：如 ^U + ls -la + 回车 = 清行并执行。
     * existingIndex = 被编辑键在键集里的下标（-1 = 添加）。
     * 确定按钮的点击先置空，onShow 里挂校验：非法输入时留在对话框里改。
     */
    private void showEditDialog(final int existingIndex, final KeySpec existing) {
        final Context ctx = host.terminal().getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(host.dp(20), host.dp(8), host.dp(20), 0);

        final EditText etLabel = new EditText(ctx);
        etLabel.setHint("标签（可留空自动生成）");
        etLabel.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etLabel.setSingleLine(true);
        box.addView(etLabel);

        final LinearLayout stepsBox = new LinearLayout(ctx);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(stepsBox);

        if (existing != null) {
            etLabel.setText(existing.label);
            for (Step s : existing.steps) {
                stepsBox.addView(makeStepRow(ctx, stepsBox, s));
            }
        } else {
            // 新键默认给一个文本步骤，最常见的起点
            stepsBox.addView(makeStepRow(ctx, stepsBox, null));
        }

        Button addStep = new Button(ctx);
        addStep.setText("＋ 步骤");
        addStep.setTextSize(14);
        addStep.setAllCaps(false);
        addStep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepsBox.addView(makeStepRow(ctx, stepsBox, null));
            }
        });
        box.addView(addStep);

        // 步骤可以很多：整个对话框可滚，内容矮时 ScrollView 不抢高度
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle(existing == null ? "添加自定义键（步骤序列）" : "编辑自定义键（步骤序列）")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final AlertDialog dialog = (AlertDialog) d;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                List<Step> steps = collectSteps(stepsBox);
                                if (steps == null) {
                                    return;
                                }
                                commitEdit(existingIndex, buildLabel(etLabel, steps), steps);
                                dialog.dismiss();
                            }
                        });
            }
        });
        dlg.show();
    }

    /**
     * 一个步骤行：模式 Spinner + 内容输入框 + 删除「×」。
     * 回车步骤没有内容（隐藏输入框）；延迟步骤内容 = 毫秒数。
     * 输入框通过 row.tag 供收集方取值。
     */
    private View makeStepRow(final Context ctx, final LinearLayout stepsBox, Step existing) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // 自定义 item 布局：simple_spinner_item 的主题色解析在小米 HyperOS 上
        // 会解析出与深色对话框同色的文字（收起态五选项隐形、只有 ^X 可见），
        // spinner_item.xml 写死颜色绕开；数据层文本一直是对的（uiautomator 实证）
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx,
                R.layout.spinner_item, modeLabels());
        adapter.setDropDownViewResource(R.layout.spinner_item);
        final Spinner sp = new Spinner(ctx);
        sp.setAdapter(adapter);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                host.dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setLayoutParams(spLp);

        final EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setSingleLine(true);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        et.setLayoutParams(etLp);

        Button del = new Button(ctx);
        del.setText("×");
        del.setTextSize(14);
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                host.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
        del.setLayoutParams(delLp);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (stepsBox.getChildCount() > 1) {
                    stepsBox.removeView(row);   // 至少留一步
                }
            }
        });

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                applyStepHint(StepMode.values()[position], et);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (existing != null) {
            sp.setSelection(existing.mode.ordinal());
            et.setText(existing.value);
        }
        applyStepHint(StepMode.values()[sp.getSelectedItemPosition()], et);

        row.addView(sp);
        row.addView(et);
        row.addView(del);
        row.setTag(et);
        return row;
    }

    /** Spinner 显示名数组（与 StepMode.values() 下标一一对应）。 */
    private static String[] modeLabels() {
        StepMode[] modes = StepMode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modes[i].label;
        }
        return labels;
    }

    /** 模式切换时更新输入框提示与可见性（回车步骤没有内容可填）。 */
    private static void applyStepHint(StepMode mode, EditText et) {
        if (!mode.hasInput()) {
            et.setVisibility(View.GONE);
            return;
        }
        et.setVisibility(View.VISIBLE);
        et.setHint(mode.hint());
    }

    /** 收集对话框里的全部步骤；任一步非法时在对应输入框标错并返回 null。 */
    private static List<Step> collectSteps(LinearLayout stepsBox) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < stepsBox.getChildCount(); i++) {
            View rowView = stepsBox.getChildAt(i);
            if (!(rowView instanceof LinearLayout)) {
                continue;
            }
            LinearLayout row = (LinearLayout) rowView;
            if (row.getChildCount() < 3
                    || !(row.getChildAt(0) instanceof Spinner)
                    || !(row.getTag() instanceof EditText)) {
                continue;
            }
            Spinner sp = (Spinner) row.getChildAt(0);
            EditText et = (EditText) row.getTag();
            StepMode mode = StepMode.values()[sp.getSelectedItemPosition()];
            String value = et.getVisibility() == View.GONE
                    ? "" : et.getText().toString();
            String err = mode.validate(value);
            if (err != null) {
                et.setError(err);
                return null;
            }
            steps.add(new Step(mode, mode.normalize(value)));
        }
        return steps;
    }

    /**
     * 标签兜底：没填标签时按步骤内容生成一个（如 "^U"、"ls↵"、"⏱500"）；
     * 单 ^X 步骤且标签形如 "^a" 时规范化成 "^A"，与内置控制键外观一致。
     * 超长标签截断到 12 字符（键宽有限）。
     */
    private static String buildLabel(EditText etLabel, List<Step> steps) {
        String label = etLabel.getText().toString().trim();
        Step first = steps.get(0);
        if (label.isEmpty()) {
            label = first.mode.autoLabel(first.value);
            if (steps.size() > 1) {
                boolean hasRet = false;
                for (Step s : steps) {
                    if (s.mode == StepMode.RET) {
                        hasRet = true;
                        break;
                    }
                }
                if (hasRet) {
                    label = label + "↵";
                }
            }
        } else if (steps.size() == 1 && first.mode == StepMode.CTRL
                && label.length() == 2 && label.charAt(0) == '^') {
            label = "^" + first.value.toUpperCase();
        }
        return label.length() > 12 ? label.substring(0, 12) : label;
    }

    /** 编辑 = 原位替换；添加 = 追加到末尾（「＋」之前那行自然接纳）。 */
    private void commitEdit(int existingIndex, String label, List<Step> steps) {
        List<KeySpec> keys = currentKeys();
        KeySpec spec = new KeySpec(label, steps);
        if (existingIndex >= 0 && existingIndex < keys.size()) {
            keys.set(existingIndex, spec);
        } else {
            keys.add(spec);
        }
        applyKeys(keys);
        if (existingIndex < 0 && customScroll != null) {
            // 新键排在键集末尾：超过行数上限时滚到底，刚加的键才看得见
            final ScrollView sv = customScroll;
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    /* -------------------- 按键发送：步骤序列 -------------------- */

    /**
     * 顺序执行步骤：攒字节批量直发（无延迟的相邻步骤合成一次 write，
     * 保原子性）；遇「延迟」先冲刷已攒字节，等 N 毫秒后继续剩下的步骤。
     * 会话死亡后 sendRawBytes 自动变 no-op，宏自然中止。
     */
    private void onKey(KeySpec key) {
        runSteps(key.steps, 0, new ByteArrayOutputStream());
    }

    private void runSteps(final List<Step> steps, final int index,
                          final ByteArrayOutputStream buf) {
        for (int i = index; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.mode == StepMode.DELAY) {
                flush(buf);
                final int next = i + 1;
                final long ms;
                try {
                    ms = Long.parseLong(s.value.trim());
                } catch (NumberFormatException e) {
                    return;   // 落盘内容被改坏的兜底，不应该走到
                }
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        runSteps(steps, next, new ByteArrayOutputStream());
                    }
                }, ms);
                return;
            }
            byte[] b = s.mode.bytesOf(s.value);
            buf.write(b, 0, b.length);
        }
        flush(buf);
    }

    /** 攒好的字节一次写进 PTY（sendRawBytes 同时清粘滞修饰/回滚/重绘）。 */
    private void flush(ByteArrayOutputStream buf) {
        if (buf.size() > 0) {
            host.terminal().sendRawBytes(buf.toByteArray());
        }
    }

    /**
     * 横竖屏、键盘弹收时统一调行高：横屏一行 27dp 已经够点，省下的全给终端。
     *
     * 键盘自身高度写死（行数 × 行高），不用 wrap_content：三层嵌套的
     * wrap_content 在首次布局时会被测成「差一行」，最后一行整行被裁掉
     * （实测 ⏎ 键边界是 [0,0][0,0]，点了没反应）。
     */
    public void applyMetrics(boolean landscape) {
        if (kbd == null) {
            return;
        }
        this.landscape = landscape;
        int h = host.dp(landscape ? 27 : 34);
        int margin = host.dp(2);
        for (LinearLayout row : kbdRows) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp.height != h) {
                    lp.height = h;
                    c.setLayoutParams(lp);
                }
            }
        }
        ViewGroup.LayoutParams klp = kbd.getLayoutParams();
        if (klp != null) {
            // 自定义区可能被行数上限截短（超出部分在 ScrollView 里滚），
            // 键盘总高按「固定行 + 可见自定义行」算，不能按 kbdRows.size()
            int visibleCustom = KbdLayout.visibleCustomRows(customRowCount, host.maxCustomRows());
            if (customScroll != null) {
                ViewGroup.LayoutParams slp = customScroll.getLayoutParams();
                int scrollH = visibleCustom * (h + margin);
                if (slp.height != scrollH) {
                    slp.height = scrollH;
                    customScroll.setLayoutParams(slp);
                }
            }
            int kbdH = (fixedRowCount + visibleCustom) * (h + margin);
            if (klp.height != kbdH) {
                klp.height = kbdH;
                kbd.setLayoutParams(klp);
            }
        }
    }

    /** 键集或行数上限变化后重建布局并重算高度（显隐状态不变）。 */
    public void refresh() {
        renderAll();
        applyMetrics(landscape);
        host.onSoftKbdVisibilityChanged(kbdVisible);
    }

    public void toggle() {
        setVisible(!kbdVisible);
    }

    /**
     * 自绘键盘显隐。终端尺寸变化会走 onSizeChanged → computeGrid → TIOCSWINSZ，
     * 所以 vim/htop 会跟着新行数重排，不需要额外通知。
     */
    public void setVisible(boolean show) {
        if (kbd == null || show == kbdVisible) {
            return;
        }
        if (show && (host.hardKeyboard() || host.sessionEnded())) {
            return;
        }
        kbdVisible = show;
        kbd.setVisibility(show ? View.VISIBLE : View.GONE);
        host.onSoftKbdVisibilityChanged(show);
    }
}
