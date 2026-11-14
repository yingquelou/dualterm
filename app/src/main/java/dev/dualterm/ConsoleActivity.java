package dev.dualterm;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器 / 镜像管理控制台。
 *
 * 这里放的是「管理」这件事的唯一入口：docker 语法的子集，命令由 App 自己解析执行。
 * 容器 rootfs 里不再有任何 docker 脚本——那样既会盖掉镜像自带的真 docker，
 * 也可能被 apk add docker 顶掉，行为和预期完全不可控。
 */
public class ConsoleActivity extends Activity {

    private static final String PROMPT = "docker> ";
    private static final int MAX_HISTORY = 50;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private final List<String> history = new ArrayList<>();

    private TextView transcript;
    private ScrollView scroll;
    private EditText input;
    private Button runBtn;
    private Button prevBtn;

    private int historyCursor;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_console);

        transcript = findViewById(R.id.transcript);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        runBtn = findViewById(R.id.btn_run);
        prevBtn = findViewById(R.id.btn_prev);

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log.setLength(0);
                transcript.setText("");
            }
        });
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(input.getText().toString());
            }
        });
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recallPrevious();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEND) {
                    run(input.getText().toString());
                    return true;
                }
                return false;
            }
        });
        // 物理回车也执行（外接键盘 / 有些输入法不发 IME action）
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    run(input.getText().toString());
                    return true;
                }
                return false;
            }
        });

        append("DualTerm 容器管理控制台（docker 子集）\n"
                + "容器里没有 docker 命令，所有管理操作都在这里完成。\n"
                + "输入 help 看命令，输入 clear 清屏，exit 关闭控制台。\n\n");

        // 调试/自动化入口：am start ... --es cmd "ps -a" 直接执行一条命令
        final String auto = getIntent() == null ? null : getIntent().getStringExtra("cmd");
        if (auto != null && !auto.isEmpty()) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    ConsoleActivity.this.run(auto);
                }
            }, 400);
        }
    }

    /* -------------------- 执行 -------------------- */

    private void run(String raw) {
        final String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || busy) {
            return;
        }
        input.setText("");
        append(PROMPT + line + "\n");
        remember(line);

        if ("clear".equalsIgnoreCase(line)) {
            log.setLength(0);
            transcript.setText("");
            append(PROMPT);
            return;
        }
        if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
            finish();
            return;
        }

        String[] argv = tokenize(line);
        if (argv.length > 0 && "docker".equalsIgnoreCase(argv[0])) {
            String[] rest = new String[argv.length - 1];
            System.arraycopy(argv, 1, rest, 0, rest.length);
            argv = rest;
        }
        if (argv.length == 0) {
            return;
        }
        final String[] args = argv;
        setBusy(true);

        // 长任务（导入镜像 / 拉取镜像）会边做边回显进度
        DockerCli.setProgressSink(new DockerCli.ProgressSink() {
            @Override
            public void line(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        append(message + "\n");
                    }
                });
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    out = DockerCli.handle(getApplicationContext(), args);
                } catch (Throwable t) {
                    out = "错误：" + describe(t) + "\n";
                } finally {
                    DockerCli.setProgressSink(null);
                }
                final String text = out == null ? "" : out;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        append(text);
                        setBusy(false);
                    }
                });
            }
        }, "console-cmd").start();
    }

    private void setBusy(boolean value) {
        busy = value;
        runBtn.setEnabled(!value);
        runBtn.setText(value ? "执行中…" : "执行");
        input.setEnabled(!value);
        if (!value) {
            input.requestFocus();
        }
    }

    private void append(String text) {
        log.append(text);
        transcript.setText(log);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void remember(String line) {
        if (!history.isEmpty() && history.get(history.size() - 1).equals(line)) {
            historyCursor = history.size();
            return;
        }
        history.add(line);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        historyCursor = history.size();
    }

    private void recallPrevious() {
        if (history.isEmpty()) {
            return;
        }
        historyCursor = Math.max(0, Math.min(historyCursor, history.size()) - 1);
        String cmd = history.get(historyCursor);
        input.setText(cmd);
        input.setSelection(cmd.length());
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isEmpty()) ? t.toString() : message;
    }

    /**
     * 按 shell 习惯切参数：单引号 / 双引号成组，反斜杠转义。
     * 只要求够用（路径里可能有空格），不追求完整 POSIX 语义。
     */
    static String[] tokenize(String source) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean started = false;
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < source.length()) {
                    cur.append(source.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (c == '\\' && i + 1 < source.length()) {
                cur.append(source.charAt(++i));
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
            } else {
                cur.append(c);
                started = true;
            }
        }
        if (started || cur.length() > 0) {
            out.add(cur.toString());
        }
        return out.toArray(new String[0]);
    }
}
