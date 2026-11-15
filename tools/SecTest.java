import dev.dualterm.container.Containers;
import dev.dualterm.container.Volumes;
import dev.dualterm.term.Shells;

/**
 * P0 注入链修复的回归测试（桌面 JVM + android.jar 桩，只测纯逻辑不碰 Context）。
 *
 * 背景：Docker 后端把容器名 / 镜像 ref / 绑定路径拼进 `su -c` 字符串，其中镜像
 * ref 来自外部 tar 的 manifest.json（RepoTags）——导入一个恶意 tar 曾可以 root
 * 执行任意命令。修复三道闸：pickRef 白名单 → launchSpec checkSafeName → 全部
 * 外插值 shQuote。本文件验证这三道闸本身的行为。
 */
public class SecTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testShQuote();
        testCheckSafeName();
        testValidImageRef();
        testDockerVolumeArg();
        testScriptAssembly();

        System.out.println("----");
        System.out.println(pass + " PASS, " + fail + " FAIL");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* -------------------- shQuote -------------------- */

    static void testShQuote() {
        // 普通值
        eq("shQuote 简单值", "'abc'", Shells.shQuote("abc"));
        // 单引号转义后闭合正确：'a'\''b' 拆开是 'a' + \' + 'b'，shell 里仍是字面 a'b
        eq("shQuote 含单引号", "'a'\\''b'", Shells.shQuote("a'b"));
        // 关键攻击载荷：引号包裹后 $() `` ; 空格全部失去语法意义
        String evil = "$(rm -rf /sdcard);`id` a b";
        String q = Shells.shQuote(evil);
        boolean safe = q.startsWith("'") && q.endsWith("'")
                && !q.substring(1, q.length() - 1).contains("'");
        ok("shQuote 包住 $();` 空格载荷", safe);
        // 转义后的内容能原样还原（手动模拟 shell 去引号：'\'' -> '）
        eq("shQuote 还原一致", "a'b", Shells.shQuote("a'b")
                .substring(1, Shells.shQuote("a'b").length() - 1).replace("'\\''", "'"));
    }

    /* -------------------- checkSafeName -------------------- */

    static void testCheckSafeName() {
        // 合法：容器名、镜像 ref 的正常形态
        for (String good : new String[]{"v24", "alpine:latest", "my.registry.io/app:v1",
                "c_1.2-3", "imported:1731234567"}) {
            try {
                Shells.checkSafeName("名字", good);
                pass++;
            } catch (RuntimeException e) {
                fail("checkSafeName 应接受 " + good);
            }
        }
        // 非法：shell 语法字符、路径穿越、空、以符号开头
        for (String bad : new String[]{"$(id)", "a;b", "a b", "../etc/passwd", "'quote'",
                "-flag", "", "\n", null}) {
            try {
                Shells.checkSafeName("名字", bad);
                fail("checkSafeName 应拒绝 " + bad);
            } catch (RuntimeException expected) {
                pass++;
            }
        }
    }

    /* -------------------- validImageRef -------------------- */

    static void testValidImageRef() {
        for (String good : new String[]{"alpine:latest", "alpine:3.21", "imported:1731234567",
                "ghcr.io/owner/img:v2"}) {
            ok("validImageRef 应接受 " + good, Containers.validImageRef(good));
        }
        // 恶意 tar 的 RepoTags 各种花样
        for (String bad : new String[]{"evil$(rm -rf ~):latest", "x`;reboot`y:1", "a b:latest",
                "x;y:1", "x|y:1", "x&y:1", "x>y:1", "'inject':1", "\"inject\":1",
                "$PATH:1", ":leading-colon", ".dot-start", "-dash-start", "", null}) {
            ok("validImageRef 应拒绝 " + bad, !Containers.validImageRef(bad));
        }
        // 超长 ref（目录名/脚本长度炸弹）
        StringBuilder huge = new StringBuilder("a:");
        for (int i = 0; i < 300; i++) {
            huge.append('a');
        }
        ok("validImageRef 应拒绝超长 ref", !Containers.validImageRef(huge.toString()));
    }

    /* -------------------- dockerVolumeArg -------------------- */

    static void testDockerVolumeArg() {
        // 空容器内路径回退为原路径
        eq("volume 空侧回退", "/sdcard:/sdcard",
                Volumes.dockerVolumeArg(new String[]{"/sdcard", ""}));
        eq("volume 双侧", "/sdcard:/mnt/sdcard",
                Volumes.dockerVolumeArg(new String[]{"/sdcard", "/mnt/sdcard"}));
    }

    /* -------------------- 拼装层（launchSpec 的拼法复刻） -------------------- */

    static void testScriptAssembly() {
        // 复刻 DockerRuntime.launchSpec 的拼装方式，验证恶意 ref 在拼进脚本前就被拦截
        String malicious = "evil$(rm -rf /sdcard):latest";
        try {
            Shells.checkSafeName("镜像名", malicious);
            fail("拼装层应拦截恶意镜像名");
        } catch (RuntimeException expected) {
            pass++;
        }
        // 合法值经 shQuote 后拼进脚本，不再改变脚本语法
        String image = "alpine:latest";
        String script = "exec docker run -it --name " + Shells.shQuote("c1")
                + " -v " + Shells.shQuote("/sdcard:/mnt/sdcard")
                + " " + Shells.shQuote(image) + " /bin/sh";
        ok("拼装脚本包含引用后的名字",
                script.contains("'c1'") && script.contains("'/sdcard:/mnt/sdcard'")
                        && script.contains("'alpine:latest'"));
    }

    /* -------------------- 骨架（与 EmuTest 同款） -------------------- */

    static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static void eq(String what, String expect, String got) {
        if (expect.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what + "\n  expect: " + expect + "\n  got:    " + got);
        }
    }

    static void fail(String what) {
        fail++;
        System.out.println("FAIL " + what);
    }
}
