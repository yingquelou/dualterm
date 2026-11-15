import dev.dualterm.container.Ports;

import java.util.ArrayList;
import java.util.List;

/**
 * Ports 解析与提示文案的回归测试（桌面 JVM + android.jar 桩，只测纯逻辑不碰 Context）。
 *
 * 背景：本项目两条后端都没有网络隔离（PRoot 直接共享宿主网络栈，Docker 固定 --net=host），
 * 所以 -p 只能同端口直通，不能重映射。这个测试盯两件事：解析不能把非法写法当合法吞下去；
 * 提示文案必须把「监听 0.0.0.0」「低端口要 root」说到位。
 */
public class PortsTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testPassthrough();
        testRemap();
        testReject();
        testPrivileged();
        testHint();

        System.out.println("----");
        System.out.println(pass + " PASS, " + fail + " FAIL");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* -------------------- 合法且直通 -------------------- */

    static void testPassthrough() {
        Ports.Mapping m = Ports.parse("8080");
        eq("单端口 → 同端口", "8080|8080", m.toString());
        ok("单端口 passthrough", m.passthrough());

        m = Ports.parse("8080:8080");
        eq("h:c 同值", "8080|8080", m.toString());

        m = Ports.parse("127.0.0.1:8080:8080");
        eq("带绑定 IP 三段", "8080|8080", m.toString());

        m = Ports.parse("8080/tcp");
        eq("tcp 后缀", "8080|8080", m.toString());

        m = Ports.parse(" 8080:8080/TCP ");
        eq("大写协议 + 前后空格", "8080|8080", m.toString());

        m = Ports.parse("1:1");
        eq("最小端口", "1|1", m.toString());

        m = Ports.parse("65535:65535");
        eq("最大端口", "65535|65535", m.toString());
    }

    /* -------------------- 合法但需要重映射（调用方负责拒绝） -------------------- */

    static void testRemap() {
        Ports.Mapping m = Ports.parse("8080:80");
        eq("重映射解析", "8080|80", m.toString());
        ok("重映射 passthrough=false", !m.passthrough());
    }

    /* -------------------- 非法写法一律拒绝 -------------------- */

    static void testReject() {
        String[] bad = {
                null, "", "   ", "abc", "0", "65536", "-1", "8080-8085:8080",
                "8080:80/udp", "[::1]:8080:8080", "1:2:3:4", "8080:", ":8080",
                "8080:http", "8 080",
        };
        for (String s : bad) {
            boolean threw = false;
            try {
                Ports.parse(s);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            ok("拒绝非法写法 [" + s + "]", threw);
        }
    }

    /* -------------------- 低端口标记 -------------------- */

    static void testPrivileged() {
        ok("80 是特权端口", Ports.parse("80").privileged());
        ok("1023 是特权端口", Ports.parse("1023").privileged());
        ok("1024 不是", !Ports.parse("1024").privileged());
        ok("8080 不是", !Ports.parse("8080").privileged());
    }

    /* -------------------- 提示文案 -------------------- */

    static void testHint() {
        List<Ports.Mapping> one = new ArrayList<>();
        one.add(Ports.parse("8080"));
        String h = Ports.hint(one);
        ok("提示含 0.0.0.0", h.contains("0.0.0.0:8080"));
        ok("提示含 127.0.0.1", h.contains("http://127.0.0.1:8080"));
        ok("提示含局域网", h.contains("Wi-Fi IP"));
        ok("普通端口不提 root", !h.contains("Root Shell"));

        List<Ports.Mapping> low = new ArrayList<>();
        low.add(Ports.parse("80"));
        ok("低端口提示 root", Ports.hint(low).contains("Root Shell"));

        ok("空列表无提示", Ports.hint(new ArrayList<Ports.Mapping>()).isEmpty());

        List<Ports.Mapping> two = new ArrayList<>();
        two.add(Ports.parse("8080"));
        two.add(Ports.parse("9090"));
        eq("describe 多端口", "8080、9090", Ports.describe(two));
    }

    /* -------------------- 骨架 -------------------- */

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
}
