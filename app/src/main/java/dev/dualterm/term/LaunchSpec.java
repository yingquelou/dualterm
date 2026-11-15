package dev.dualterm.term;

/**
 * 一次会话的启动参数：谁、在哪、带什么环境变量。
 *
 * 除了启动进程要用的 argv/envp，还捎带两个**会话身份标记**（root / docker），
 * 给顶栏 chip 显示用。以前这两个信息藏在 title 字符串里（「本地 Shell (root)」、
 * 「容器 · v24（Docker）」），要显示就得正则在标题里抠——标记随会话一起来，
 * 比事后解析标题可靠。
 */
public final class LaunchSpec {

    public final String title;
    public final String[] argv;
    public final String[] envp;
    public final String cwd;

    /** 本地会话是否以 su 提权启动。容器会话恒 false。 */
    public final boolean root;
    /** 容器会话是否走 docker 运行时。本地会话恒 false。 */
    public final boolean docker;

    public LaunchSpec(String title, String[] argv, String[] envp, String cwd) {
        this(title, argv, envp, cwd, false, false);
    }

    public LaunchSpec(String title, String[] argv, String[] envp, String cwd,
                      boolean root, boolean docker) {
        this.title = title;
        this.argv = argv;
        this.envp = envp;
        this.cwd = cwd;
        this.root = root;
        this.docker = docker;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(title).append("  |  ");
        for (String a : argv) {
            sb.append(a).append(' ');
        }
        return sb.toString().trim();
    }
}
