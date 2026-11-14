package dev.dualterm.util;

import java.io.File;

/** 文件系统小工具。放这里而不是塞进某个业务类，避免下层反向依赖上层。 */
public final class Fs {

    private Fs() {
    }

    /** 尽力创建目录；失败不抛异常，由后续真正使用它的操作给出更具体的错误。 */
    public static void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            // 忽略：后续操作会给出更具体的错误
        }
    }
}
