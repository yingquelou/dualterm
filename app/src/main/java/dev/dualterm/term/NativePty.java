package dev.dualterm.term;

/** JNI 入口。方法名必须与 pty.c 里的符号一一对应。 */
public final class NativePty {

    static {
        System.loadLibrary("ptyterm");
    }

    private NativePty() {
    }

    /**
     * 创建伪终端并 exec 目标程序。
     *
     * @return master fd（>=0），失败返回 -errno
     */
    public static native int forkExec(String[] argv, String[] envp, String cwd,
                                     int rows, int cols, int[] pidOut);

    /** @return 读到的字节数，0 表示 EOF，负数表示 -errno */
    public static native int read(int fd, byte[] buffer, int length);

    /** @return 实际写入字节数，负数表示 -errno */
    public static native int write(int fd, byte[] buffer, int offset, int length);

    public static native int setWindowSize(int fd, int rows, int cols);

    public static native int sendSignal(int pid, int signal);

    /** 阻塞等待子进程退出，返回退出码（信号退出时为 128+signo）。 */
    public static native int waitPid(int pid);

    public static native void close(int fd);
}
