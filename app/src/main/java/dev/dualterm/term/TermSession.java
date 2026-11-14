package dev.dualterm.term;

import java.nio.charset.StandardCharsets;

/**
 * 一个终端会话 = 一个 PTY + 一个子进程 + 一块屏幕模型。
 *
 * 生命周期：start() 建立 PTY 并 fork 子进程；读线程把 PTY 输出喂给屏幕模型；
 * stop() 只需给子进程发信号，读线程在 read 返回 <=0 后自己收尾。
 */
public final class TermSession {

    public interface Listener {
        void onOutput(TermSession session);

        void onExit(TermSession session, int status);
    }

    private static final int SIG_HUP = 1;
    private static final int SIG_KILL = 9;

    private final LaunchSpec spec;
    private final TerminalEmulator emulator;
    private final Listener listener;

    private final byte[] buffer = new byte[8192];

    private volatile int fd = -1;
    private volatile int pid = -1;
    private volatile boolean running;
    private volatile boolean exitNotified;
    private int loggedChunks;

    /** 调试用：把前 N 段 PTY 输出打到 logcat，便于无截图环境下远程验证。 */
    public static volatile int logChunkLimit = 3;

    private Thread readerThread;

    public TermSession(LaunchSpec spec, int rows, int cols, int scrollback, Listener listener) {
        this.spec = spec;
        this.listener = listener;
        this.emulator = new TerminalEmulator(cols, rows, scrollback, 0xFFE6E6E6, 0xFF101014);
    }

    public LaunchSpec getSpec() {
        return spec;
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPid() {
        return pid;
    }

    public void start() {
        int[] pidOut = new int[1];
        int master = NativePty.forkExec(spec.argv, spec.envp, spec.cwd,
                emulator.getRows(), emulator.getCols(), pidOut);
        if (master < 0) {
            throw new IllegalStateException("无法创建伪终端（errno " + (-master) + "）");
        }
        android.util.Log.i("DualTerm", "start pid=" + pidOut[0]
                + " cwd=" + spec.cwd
                + " argv=" + android.text.TextUtils.join(" ", spec.argv));
        fd = master;
        pid = pidOut[0];
        running = true;

        final int sessionPid = pid;
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                int status = NativePty.waitPid(sessionPid);
                running = false;
                notifyExit(status);
            }
        }, "pty-wait");
        waiter.setDaemon(true);
        waiter.start();

        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "pty-read");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            while (true) {
                int n = NativePty.read(fd, buffer, buffer.length);
                if (n <= 0) {
                    break;
                }
                emulator.append(buffer, n);
                if (loggedChunks < logChunkLimit) {
                    loggedChunks++;
                    String text = new String(buffer, 0, n, StandardCharsets.UTF_8)
                            .replace("\u001b", "<ESC>");
                    if (text.length() > 600) {
                        text = text.substring(0, 600);
                    }
                    android.util.Log.i("DualTerm", "out#" + loggedChunks + ": " + text.trim());
                }
                listener.onOutput(this);
            }
        } finally {
            running = false;
            int f = fd;
            fd = -1;
            if (f >= 0) {
                NativePty.close(f);
            }
        }
    }

    public void write(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public void writeBytes(byte[] data) {
        int f = fd;
        if (f < 0 || data == null || data.length == 0) {
            return;
        }
        NativePty.write(f, data, 0, data.length);
    }

    /** UI 尺寸变化时调用，同步 PTY 行列数并调整屏幕模型。 */
    public void resize(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            return;
        }
        emulator.resize(cols, rows);
        int f = fd;
        if (f >= 0) {
            NativePty.setWindowSize(f, rows, cols);
        }
    }

    public void stop() {
        running = false;
        int p = pid;
        if (p > 0) {
            NativePty.sendSignal(p, SIG_HUP);
        }
        if (p > 0) {
            NativePty.sendSignal(p, SIG_KILL);
        }
    }

    private void notifyExit(int status) {
        if (exitNotified) {
            return;
        }
        exitNotified = true;
        android.util.Log.i("DualTerm", "exit pid=" + pid + " status=" + status);
        listener.onExit(this, status);
    }
}
