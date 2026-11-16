package dev.dualterm.term;

import java.nio.charset.StandardCharsets;

/**
 * 一个终端会话 = 一个 PTY + 一个子进程 + 一块屏幕模型。
 *
 * 生命周期：start() 建立 PTY 并 fork 子进程；读线程把 PTY 输出喂给屏幕模型；
 * stop() 给整个进程组发信号（不留孤儿），读线程在 read 返回 <=0 后自己收尾。
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

    /**
     * 终端应答（CPR/DA/颜色查询）先攒在这里，等 append() 退出、离开仿真器的锁之后再写回 PTY。
     * 直接在 ReplySink 里写 PTY 的话，一旦子进程暂时不读，读线程会卡在自己的写操作上，谁也动不了。
     */
    private final StringBuilder pendingReply = new StringBuilder();

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
        this.emulator.setReplySink(new TerminalEmulator.ReplySink() {
            @Override
            public void onReply(String reply) {
                if (pendingReply.length() < 8192) {
                    pendingReply.append(reply);
                }
            }
        });
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
                flushReply();
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

    /** 把本批解析中攒下的终端应答写回 PTY（只在读线程调用）。 */
    private void flushReply() {
        if (pendingReply.length() == 0) {
            return;
        }
        String reply = pendingReply.toString();
        pendingReply.setLength(0);
        write(reply);
    }

    public void writeBytes(byte[] data) {
        writeBytes(data, 0, data == null ? 0 : data.length);
    }

    /** 分段写（粘贴大文本时按块喂，读线程有机会穿插刷输出）。 */
    public void writeBytes(byte[] data, int offset, int len) {
        int f = fd;
        if (f < 0 || data == null || len <= 0) {
            return;
        }
        NativePty.write(f, data, offset, len);
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

    /**
     * 结束会话。先给进程组发 SIGHUP（子进程是 setsid 出来的会话首，pgid == pid），
     * 再在后台线程把 /proc 里同会话（sid == pid）的成员全部找出来补 HUP、
     * 停 150ms 后还没退的逐个 SIGKILL——交互式 shell 开作业控制后前台/后台任务
     * 各有独立 pgid，单靠进程组信号杀不到（比如 `sleep 300 &` 会成孤儿）。
     * 不在这里关 master fd：成员死透后 slave 端关闭，read 自然返回 EIO/0，
     * 读线程自己收尾。
     */
    public void stop() {
        running = false;
        final int p = pid;
        if (p <= 0) {
            return;
        }
        NativePty.sendSignal(-p, SIG_HUP);
        Thread killer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int m : sessionMembers(p)) {
                    NativePty.sendSignal(m, SIG_HUP);
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                for (int m : sessionMembers(p)) {
                    NativePty.sendSignal(m, SIG_KILL);
                }
                NativePty.sendSignal(-p, SIG_KILL);
            }
        }, "pty-stop");
        killer.setDaemon(true);
        killer.start();
    }

    /**
     * /proc 扫描：控制终端会话号等于 sid 的所有进程（含 sid 自己）。
     * pty 子进程与 App 同 uid，/proc/&lt;pid&gt;/stat 可读；其他应用的进程读不到，
     * 解析失败就跳过。
     */
    private static int[] sessionMembers(int sid) {
        java.util.ArrayList<Integer> found = new java.util.ArrayList<>();
        java.io.File[] procs = new java.io.File("/proc").listFiles();
        if (procs != null) {
            for (java.io.File f : procs) {
                int p;
                try {
                    p = Integer.parseInt(f.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                try {
                    byte[] raw = java.nio.file.Files.readAllBytes(
                            f.toPath().resolve("stat"));
                    String s = new String(raw, StandardCharsets.US_ASCII);
                    int r = s.lastIndexOf(')');
                    if (r < 0) {
                        continue;
                    }
                    // stat 去掉 "(comm)" 后依次是 state ppid pgrp session …
                    String[] tok = s.substring(r + 2).split(" ");
                    if (tok.length > 3 && Integer.parseInt(tok[3]) == sid) {
                        found.add(p);
                    }
                } catch (Exception ignored) {
                    // 进程刚好退出或不可读，跳过
                }
            }
        }
        int[] out = new int[found.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = found.get(i);
        }
        return out;
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
