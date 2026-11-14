package dev.dualterm.term;

/** 一次会话的启动参数：谁、在哪、带什么环境变量。 */
public final class LaunchSpec {

    public final String title;
    public final String[] argv;
    public final String[] envp;
    public final String cwd;

    public LaunchSpec(String title, String[] argv, String[] envp, String cwd) {
        this.title = title;
        this.argv = argv;
        this.envp = envp;
        this.cwd = cwd;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(title).append("  |  ");
        for (String a : argv) {
            sb.append(a).append(' ');
        }
        return sb.toString().trim();
    }
}
