/*
 * hardkey —— 用 uinput 造一个「外部实体键盘」设备，注入真实设备按键事件。
 *
 * 为什么需要它：`input keyevent` 注入的事件来自虚拟键盘（deviceId=-1），
 * 输入法对它的处理与真实蓝牙键盘不同（实测：同一个搜索框，注入直出字母、
 * 真键却组词），因此注入键不能当作硬件键的替身。本工具直接向
 * /dev/input 层创建一个 bustype=BLUETOOTH 的键盘设备，事件走 InputReader
 * 的真实设备路径，与手敲蓝牙键盘同一条链路。
 *
 * 编译（NDK，arm64）：
 *   $NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android21-clang \
 *       -O2 -o hardkey tools/hardkey.c
 * 用法：
 *   adb push hardkey /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/hardkey
 *   adb shell /data/local/tmp/hardkey n i h a o space enter
 *   adb shell /data/local/tmp/hardkey --settle 1500 ctrl c : 200 esc
 *   adb shell /data/local/tmp/hardkey --stdin        # 设备常驻，逐行从 stdin 读 token
 *   adb shell /data/local/tmp/hardkey ctrl shift '#'     # 真同时按下的 Ctrl+Shift
 * token：单字符（字母/数字/常见标点）、键名（见 keys[]）、`:毫秒` 暂停、
 * `#` 把前面挂着的修饰键当成一次组合键按下再松开。
 * 修饰键会一直挂到下一个键（`ctrl c` 是组合键）；要「单独点一下 Shift」
 * （输入法的中英切换键位）就在它后面放一个 `:毫秒` 暂停，暂停会把挂着的
 * 修饰键当成独立一次点击松开。
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define BITMAP_SIZE(n) (((n) / (8 * sizeof(long))) + 1)

static int test_bit(const unsigned long *bits, int code) {
    int word = code / (8 * (int) sizeof(long));
    int bit = code % (8 * (int) sizeof(long));
    return (bits[word] & (1UL << bit)) != 0;
}

struct key_def {
    const char *name;
    int code;
    int shift;
};

static const struct key_def keys[] = {
    {"esc", KEY_ESC, 0}, {"escape", KEY_ESC, 0},
    {"tab", KEY_TAB, 0},
    {"enter", KEY_ENTER, 0}, {"return", KEY_ENTER, 0},
    {"space", KEY_SPACE, 0},
    {"backspace", KEY_BACKSPACE, 0}, {"del", KEY_DELETE, 0},
    {"ins", KEY_INSERT, 0},
    {"home", KEY_HOME, 0}, {"end", KEY_END, 0},
    {"pgup", KEY_PAGEUP, 0}, {"pgdn", KEY_PAGEDOWN, 0},
    {"up", KEY_UP, 0}, {"down", KEY_DOWN, 0},
    {"left", KEY_LEFT, 0}, {"right", KEY_RIGHT, 0},
    {"f1", KEY_F1, 0}, {"f2", KEY_F2, 0}, {"f3", KEY_F3, 0}, {"f4", KEY_F4, 0},
    {"f5", KEY_F5, 0}, {"f6", KEY_F6, 0}, {"f7", KEY_F7, 0}, {"f8", KEY_F8, 0},
    {"f9", KEY_F9, 0}, {"f10", KEY_F10, 0}, {"f11", KEY_F11, 0}, {"f12", KEY_F12, 0},
    {"ctrl", KEY_LEFTCTRL, 0}, {"shift", KEY_LEFTSHIFT, 0}, {"alt", KEY_LEFTALT, 0},
    {"meta", KEY_LEFTMETA, 0}, {"super", KEY_LEFTMETA, 0},
    {"minus", KEY_MINUS, 0}, {"equal", KEY_EQUAL, 0},
    {"comma", KEY_COMMA, 0}, {"dot", KEY_DOT, 0}, {"slash", KEY_SLASH, 0},
    {"semi", KEY_SEMICOLON, 0}, {"quote", KEY_APOSTROPHE, 0},
    {"lbrk", KEY_LEFTBRACE, 0}, {"rbrk", KEY_RIGHTBRACE, 0}, {"bslash", KEY_BACKSLASH, 0},
    {"grave", KEY_GRAVE, 0},
    {NULL, 0, 0}
};

static int resolve(const char *tok, int *code, int *shift) {
    for (int i = 0; keys[i].name; i++) {
        if (strcmp(keys[i].name, tok) == 0) {
            *code = keys[i].code;
            *shift = keys[i].shift;
            return 1;
        }
    }
    if (tok[1] != '\0' || tok[0] == '\0') {
        return 0;
    }
    char c = tok[0];
    /* evdev 字母键位沿 QWERTY 行排布（q=16 … m=50），按字母表偏移推算会整体错位。 */
    static const struct {
        char ch;
        int code;
    } letter[] = {
        {'q', KEY_Q}, {'w', KEY_W}, {'e', KEY_E}, {'r', KEY_R},
        {'t', KEY_T}, {'y', KEY_Y}, {'u', KEY_U}, {'i', KEY_I},
        {'o', KEY_O}, {'p', KEY_P},
        {'a', KEY_A}, {'s', KEY_S}, {'d', KEY_D}, {'f', KEY_F},
        {'g', KEY_G}, {'h', KEY_H}, {'j', KEY_J}, {'k', KEY_K},
        {'l', KEY_L},
        {'z', KEY_Z}, {'x', KEY_X}, {'c', KEY_C}, {'v', KEY_V},
        {'b', KEY_B}, {'n', KEY_N}, {'m', KEY_M},
    };
    for (size_t i = 0; i < sizeof(letter) / sizeof(letter[0]); i++) {
        if (letter[i].ch == (char)tolower((unsigned char)c)) {
            *code = letter[i].code;
            *shift = c >= 'A' && c <= 'Z';
            return 1;
        }
    }
    if (c >= '1' && c <= '9') {
        *code = KEY_1 + (c - '1');
        *shift = 0;
        return 1;
    }
    if (c == '0') {
        *code = KEY_0;
        *shift = 0;
        return 1;
    }
    const struct {
        char ch;
        int code;
        int shift;
    } punct[] = {
        {' ', KEY_SPACE, 0}, {'-', KEY_MINUS, 0}, {'=', KEY_EQUAL, 0},
        {',', KEY_COMMA, 0}, {'.', KEY_DOT, 0}, {'/', KEY_SLASH, 0},
        {';', KEY_SEMICOLON, 0}, {'\'', KEY_APOSTROPHE, 0},
        {'[', KEY_LEFTBRACE, 0}, {']', KEY_RIGHTBRACE, 0}, {'\\', KEY_BACKSLASH, 0},
        {'`', KEY_GRAVE, 0},
        {'!', KEY_1, 1}, {'@', KEY_2, 1}, {'#', KEY_3, 1}, {'$', KEY_4, 1},
        {'%', KEY_5, 1}, {'^', KEY_6, 1}, {'&', KEY_7, 1}, {'*', KEY_8, 1},
        {'(', KEY_9, 1}, {')', KEY_0, 1}, {':', KEY_SEMICOLON, 1},
        {'<', KEY_COMMA, 1}, {'>', KEY_DOT, 1}, {'?', KEY_SLASH, 1},
        {'"', KEY_APOSTROPHE, 1}, {'{', KEY_LEFTBRACE, 1}, {'}', KEY_RIGHTBRACE, 1},
        {'|', KEY_BACKSLASH, 1}, {'~', KEY_GRAVE, 1},
        {'\n', KEY_ENTER, 0}, {'\t', KEY_TAB, 0},
        {0, 0, 0}
    };
    for (unsigned i = 0; punct[i].ch; i++) {
        if (punct[i].ch == c) {
            *code = punct[i].code;
            *shift = punct[i].shift;
            return 1;
        }
    }
    return 0;
}

static void sleep_ms(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long) (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

static int g_fd;

static void emit(int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    if (write(g_fd, &ev, sizeof(ev)) < 0) {
        fprintf(stderr, "write: %s\n", strerror(errno));
        exit(1);
    }
}

static int is_modifier(int code) {
    switch (code) {
        case KEY_LEFTCTRL: case KEY_RIGHTCTRL:
        case KEY_LEFTSHIFT: case KEY_RIGHTSHIFT:
        case KEY_LEFTALT: case KEY_RIGHTALT:
        case KEY_LEFTMETA: case KEY_RIGHTMETA:
            return 1;
        default:
            return 0;
    }
}

/* mods[] 是已经按下、尚未松开的修饰键；本键敲完再把它们统一松开，
 * 这样 "ctrl c" 两个 token 才是真组合键，而不是「点一下 Ctrl」+「普通 c」。 */
static void tap(int code, int shift, int gap, const int *mods, int nmods) {
    int use_shift = shift && nmods == 0;
    if (use_shift) {
        emit(EV_KEY, KEY_LEFTSHIFT, 1);
        emit(EV_SYN, SYN_REPORT, 0);
        sleep_ms(gap / 2 > 5 ? 5 : 1);
    }
    for (int i = 0; i < nmods; i++) {
        if (mods[i]) {
            emit(EV_KEY, mods[i], 1);
            emit(EV_SYN, SYN_REPORT, 0);
            sleep_ms(gap / 2 > 5 ? 5 : 1);
        }
    }
    emit(EV_KEY, code, 1);
    emit(EV_SYN, SYN_REPORT, 0);
    sleep_ms(gap > 5 ? gap : 5);
    emit(EV_KEY, code, 0);
    emit(EV_SYN, SYN_REPORT, 0);
    for (int i = 0; i < nmods; i++) {
        if (mods[i]) {
            emit(EV_KEY, mods[i], 0);
            emit(EV_SYN, SYN_REPORT, 0);
        }
    }
    if (use_shift) {
        emit(EV_KEY, KEY_LEFTSHIFT, 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }
}

/* 挂着的修饰键单独点一次（输入法的中英切换、Shift 组合的「点一下」语义）。 */
static void tap_alone(int code, int gap) {
    emit(EV_KEY, code, 1);
    emit(EV_SYN, SYN_REPORT, 0);
    sleep_ms(gap > 5 ? gap : 5);
    emit(EV_KEY, code, 0);
    emit(EV_SYN, SYN_REPORT, 0);
}

/* 修饰键组合：按住全部、松开全部（`ctrl shift #` 是真同时按下的 Ctrl+Shift，
 * 而 `:毫秒` 停顿会把挂着的修饰键拆成两次独立单击，测不出组合键）。 */
static void chord(int *mods, int nmods, int gap) {
    for (int i = 0; i < nmods; i++) {
        emit(EV_KEY, mods[i], 1);
        emit(EV_SYN, SYN_REPORT, 0);
        sleep_ms(gap / 2 > 5 ? 5 : 1);
    }
    sleep_ms(gap > 5 ? gap : 5);
    for (int i = nmods - 1; i >= 0; i--) {
        if (mods[i]) {
            emit(EV_KEY, mods[i], 0);
            emit(EV_SYN, SYN_REPORT, 0);
        }
    }
}

static void emit_seq(int *codes, int *shifts, int *pauses, int n, int gap) {
    int mods[4];
    int nmods = 0;
    int maxmods = (int) (sizeof(mods) / sizeof(mods[0]));
    for (int j = 0; j < n; j++) {
        if (pauses[j] < 0) {
            chord(mods, nmods, gap);
            nmods = 0;
            continue;
        }
        if (pauses[j] > 0) {
            /* 暂停先把挂着的修饰键当成独立点击松开，否则「shift 停顿 字母」
             * 会变成 Shift+字母，测不出「单击 Shift 切中英」 */
            for (int i = 0; i < nmods; i++) {
                tap_alone(mods[i], gap);
            }
            nmods = 0;
            sleep_ms(pauses[j]);
            continue;
        }
        if (is_modifier(codes[j])) {
            if (nmods < maxmods) {
                mods[nmods++] = codes[j];
            }
            continue;
        }
        tap(codes[j], shifts[j], gap, mods, nmods);
        nmods = 0;
    }
    /* 收尾：只给了修饰键、后面没跟字，也要把它松开，否则设备侧修饰态一直挂着 */
    for (int i = 0; i < nmods; i++) {
        tap_alone(mods[i], gap);
    }
}

int main(int argc, char **argv) {
    int settle = 1200, gap = 40, hold = 600;
    int stdin_mode = 0;
    char mirror[128] = "";
    int i = 1;
    while (i < argc && argv[i][0] == '-' && argv[i][1] == '-') {
        const char *opt = argv[i] + 2;
        if (strcmp(opt, "mirror") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "--mirror 需要跟设备节点\n");
                return 2;
            }
            snprintf(mirror, sizeof(mirror), "%s", argv[i + 1]);
            i += 2;
            continue;
        }
        if (strcmp(opt, "stdin") == 0) {
            stdin_mode = 1;
            i += 1;
            continue;
        }
        if (i + 1 >= argc
                || (strcmp(opt, "settle") != 0 && strcmp(opt, "gap") != 0
                        && strcmp(opt, "hold") != 0)) {
            fprintf(stderr,
                    "用法: hardkey [--mirror /dev/input/eventN] [--stdin] [--settle ms]"
                    " [--gap ms] [--hold ms] <token>...\n"
                    "      token = 单字符 / 键名(esc tab enter space del up down pgup home"
                    " end ins f1-f12 ctrl shift alt meta ...) / :毫秒 暂停 / # 组合键\n");
            return 2;
        }
        int v = atoi(argv[i + 1]);
        if (strcmp(opt, "settle") == 0) {
            settle = v;
        } else if (strcmp(opt, "gap") == 0) {
            gap = v;
        } else {
            hold = v;
        }
        i += 2;
    }
    if (!stdin_mode && i >= argc) {
        fprintf(stderr, "没有按键 token\n");
        return 2;
    }

    /* 先解析全部 token，避免设备建好了才发现参数写错 */
    int n = stdin_mode ? 0 : argc - i;
    int *codes = calloc((size_t) (n > 0 ? n : 1), sizeof(int));
    int *shifts = calloc((size_t) (n > 0 ? n : 1), sizeof(int));
    int *pauses = calloc((size_t) (n > 0 ? n : 1), sizeof(int));
    for (int j = 0; j < n; j++) {
        const char *tok = argv[i + j];
        if (tok[0] == ':' && tok[1] == '\0') {
            fprintf(stderr, "暂停要写成 :毫秒\n");
            return 2;
        }
        if (tok[0] == '#') {
            pauses[j] = -1;
            continue;
        }
        if (tok[0] == ':') {
            pauses[j] = atoi(tok + 1);
            continue;
        }
        if (!resolve(tok, &codes[j], &shifts[j])) {
            fprintf(stderr, "无法识别的按键: %s\n", tok);
            return 2;
        }
    }

    g_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_fd < 0) {
        fprintf(stderr, "打开 /dev/uinput 失败: %s\n", strerror(errno));
        return 1;
    }
    if (ioctl(g_fd, UI_SET_EVBIT, EV_KEY) < 0) {
        fprintf(stderr, "UI_SET_EVBIT: %s\n", strerror(errno));
        return 1;
    }
    /* 只声明 1..255：KEY_MAX 是 0x2ff，把 BTN_TOOL_PEN/BTN_STYLUS 一并声明会让
     * EventHub 把设备分类成 EXTERNAL_STYLUS，按键根本不会投递给焦点窗口。
     * --mirror 用于照抄真实键盘的 EV_KEY 位图，让键位集合与手边的键盘一致。 */
    unsigned long bits[BITMAP_SIZE(KEY_MAX)] = {0};
    int mirrored = 0;
    if (mirror[0]) {
        int rfd = open(mirror, O_RDONLY | O_NONBLOCK);
        if (rfd < 0) {
            fprintf(stderr, "打开 %s 失败: %s\n", mirror, strerror(errno));
            return 1;
        }
        if (ioctl(rfd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0) {
            fprintf(stderr, "读 %s 键位图失败: %s\n", mirror, strerror(errno));
            return 1;
        }
        close(rfd);
        mirrored = 1;
    }
    int declared = 0;
    for (int code = 1; code <= 255; code++) {
        if (mirrored && !test_bit(bits, code)) {
            continue;
        }
        if (ioctl(g_fd, UI_SET_KEYBIT, code) == 0) {
            declared++;
        }
    }
    if (mirrored) {
        ioctl(g_fd, UI_SET_MSCBIT, MSC_SCAN);
    }
    /* 不置 INPUT_PROP_VIRTUAL：框架据此判定 isVirtual，保持与真实外接键盘一致 */
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, sizeof(dev.name), "%s", "DT HardKey");
    dev.id.bustype = BUS_BLUETOOTH;
    dev.id.vendor = 0x1234;
    dev.id.product = 0x5678;
    dev.id.version = 1;
    if (write(g_fd, &dev, sizeof(dev)) < 0) {
        fprintf(stderr, "写设备描述失败: %s\n", strerror(errno));
        return 1;
    }
    if (ioctl(g_fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "UI_DEV_CREATE: %s\n", strerror(errno));
        return 1;
    }
    sleep_ms(settle);

    int total_tokens = 0;
    if (stdin_mode) {
        /* 设备常驻：输入法会话与「有外接键盘」的判定不会因每次注入重建设备而重置，
         * 一整串测试动作在同一个键盘会话里跑完。每行一组 token，回显 ok 便于脚本对齐。 */
        char line[1024];
        int lc[64], ls[64], lp[64];
        while (fgets(line, sizeof(line), stdin)) {
            int cnt = 0;
            char *save = NULL;
            for (char *tok = strtok_r(line, " \t\r\n", &save);
                    tok != NULL && cnt < 64; tok = strtok_r(NULL, " \t\r\n", &save)) {
                lc[cnt] = 0;
                ls[cnt] = 0;
                lp[cnt] = 0;
                if (tok[0] == '#') {
                    lp[cnt] = -1;
                } else if (tok[0] == ':') {
                    lp[cnt] = atoi(tok + 1);
                } else if (!resolve(tok, &lc[cnt], &ls[cnt])) {
                    fprintf(stderr, "无法识别的按键: %s\n", tok);
                }
                cnt++;
            }
            emit_seq(lc, ls, lp, cnt, gap);
            total_tokens += cnt;
            printf("ok %d\n", cnt);
            fflush(stdout);
        }
    } else {
        emit_seq(codes, shifts, pauses, n, gap);
        sleep_ms(hold);
    }

    ioctl(g_fd, UI_DEV_DESTROY);
    close(g_fd);
    if (stdin_mode) {
        /* --stdin 下 argv 计数恒为 0，报「已注入 0 个按键」会误导取证 */
        printf("stdin 模式共处理 %d 个 token（声明键位 %d 个%s）\n", total_tokens, declared,
                mirrored ? "，镜像自真实键盘" : "");
    } else {
        printf("已注入 %d 个按键（声明键位 %d 个%s）\n", n, declared,
                mirrored ? "，镜像自真实键盘" : "");
    }
    return 0;
}
