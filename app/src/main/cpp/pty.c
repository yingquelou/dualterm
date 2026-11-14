/*
 * pty.c —— DualTerm 的原生终端层。
 *
 * 只做四件事：
 *   1. forkExec : openpty + fork + setsid + TIOCSCTTY + dup2 + execvp，返回 master fd
 *   2. read/write : 直接对 master fd 做阻塞 IO（避免 Java 侧包装 FileDescriptor 的坑）
 *   3. setWindowSize : TIOCSWINSZ，让 vi/top 之类的程序拿到正确行列数
 *   4. sendSignal / waitPid : 结束会话、回收子进程
 *
 * bionic 从 API 23 起提供 <pty.h> 的 openpty()，本项目 minSdk 26，可以直接用。
 */

#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TAG "ptyterm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* 工具函数                                                            */
/* ------------------------------------------------------------------ */

static char **to_cstring_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, array);
    char **out = (char **) calloc((size_t) len + 1, sizeof(char *));
    if (out == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < len; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *c = (s != NULL) ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
        out[i] = strdup(c != NULL ? c : "");
        if (c != NULL) {
            (*env)->ReleaseStringUTFChars(env, s, c);
        }
        if (s != NULL) {
            (*env)->DeleteLocalRef(env, s);
        }
    }
    out[len] = NULL;
    return out;
}

static void free_cstring_array(char **array) {
    if (array == NULL) {
        return;
    }
    for (char **p = array; *p != NULL; p++) {
        free(*p);
    }
    free(array);
}

/* ------------------------------------------------------------------ */
/* 1. 创建伪终端并 exec 子进程                                         */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_dev_dualterm_NativePty_forkExec(JNIEnv *env, jclass clazz,
                                     jobjectArray argv, jobjectArray envp, jstring jcwd,
                                     jint rows, jint cols, jintArray out_pid) {
    if (argv == NULL || out_pid == NULL) {
        return -EINVAL;
    }

    char **c_argv = to_cstring_array(env, argv);
    if (c_argv == NULL || c_argv[0] == NULL) {
        free_cstring_array(c_argv);
        return -EINVAL;
    }
    char **c_envp = to_cstring_array(env, envp);

    const char *cwd = NULL;
    if (jcwd != NULL) {
        cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master = -1;
    int slave = -1;
    if (openpty(&master, &slave, NULL, NULL, &ws) < 0) {
        int err = errno;
        LOGE("openpty failed: %d (%s)", err, strerror(err));
        if (cwd != NULL) {
            (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        }
        free_cstring_array(c_argv);
        free_cstring_array(c_envp);
        return -err;
    }

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        LOGE("fork failed: %d (%s)", err, strerror(err));
        close(master);
        close(slave);
        if (cwd != NULL) {
            (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        }
        free_cstring_array(c_argv);
        free_cstring_array(c_envp);
        return -err;
    }

    if (pid == 0) {
        /* ---- 子进程 ---- */
        close(master);

        setsid();
        ioctl(slave, TIOCSCTTY, 0);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) {
            close(slave);
        }

        if (cwd != NULL) {
            if (chdir(cwd) != 0) {
                chdir("/");
            }
        } else {
            chdir("/");
        }

        if (c_envp != NULL) {
            for (int i = 0; c_envp[i] != NULL; i++) {
                /* 形如 "KEY=VALUE"，putenv 接管字符串所有权 */
                char *kv = c_envp[i];
                char *eq = strchr(kv, '=');
                if (eq != NULL) {
                    *eq = '\0';
                    setenv(kv, eq + 1, 1);
                    *eq = '=';
                }
            }
        }

        execvp(c_argv[0], c_argv);
        /* exec 失败：把原因写到伪终端上，用户能看到，而不是一片空白后退出 */
        {
            char msg[512];
            int len = snprintf(msg, sizeof(msg),
                               "\r\n[dualterm] 无法执行 %s: %s (errno=%d)\r\n",
                               c_argv[0], strerror(errno), errno);
            if (len > 0) {
                ssize_t ignored = write(STDOUT_FILENO, msg, (size_t) len);
                (void) ignored;
            }
            LOGE("exec failed: %s (%d)", c_argv[0], errno);
        }
        _exit(127);
    }

    /* ---- 父进程 ---- */
    close(slave);
    fcntl(master, F_SETFD, FD_CLOEXEC);

    if (cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    }
    free_cstring_array(c_argv);
    free_cstring_array(c_envp);

    jint pid_value = (jint) pid;
    (*env)->SetIntArrayRegion(env, out_pid, 0, 1, &pid_value);

    LOGI("session started: pid=%d fd=%d %dx%d", (int) pid, master, (int) cols, (int) rows);
    return (jint) master;
}

/* ------------------------------------------------------------------ */
/* 2. 读写                                                             */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_dev_dualterm_NativePty_read(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint len) {
    if (fd < 0 || len <= 0) {
        return -EBADF;
    }
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buf == NULL) {
        return -ENOMEM;
    }
    ssize_t n;
    do {
        n = read((int) fd, buf, (size_t) len);
    } while (n < 0 && errno == EINTR);

    int err = errno;
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    if (n < 0) {
        return -err;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_dev_dualterm_NativePty_write(JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer,
                                  jint offset, jint len) {
    if (fd < 0 || len <= 0) {
        return -EBADF;
    }
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buf == NULL) {
        return -ENOMEM;
    }
    jint written = 0;
    while (written < len) {
        ssize_t n = write((int) fd, buf + offset + written, (size_t) (len - written));
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            int err = errno;
            (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
            return written > 0 ? written : -err;
        }
        written += (jint) n;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
    return written;
}

/* ------------------------------------------------------------------ */
/* 3. 窗口尺寸                                                         */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_dev_dualterm_NativePty_setWindowSize(JNIEnv *env, jclass clazz, jint fd, jint rows,
                                          jint cols) {
    if (fd < 0) {
        return -EBADF;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    if (ioctl((int) fd, TIOCSWINSZ, &ws) < 0) {
        return -errno;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* 4. 信号与回收                                                       */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_dev_dualterm_NativePty_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    if (pid <= 0) {
        return -EINVAL;
    }
    if (kill((pid_t) pid, sig) < 0) {
        return -errno;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_dualterm_NativePty_waitPid(JNIEnv *env, jclass clazz, jint pid) {
    if (pid <= 0) {
        return -1;
    }
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (r < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_dualterm_NativePty_close(JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) {
        close((int) fd);
    }
}
