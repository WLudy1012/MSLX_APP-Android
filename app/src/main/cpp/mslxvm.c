/*
 * MSLX-Android 本机开服：进程内 JVM 桥接
 *
 * 为什么不是 ProcessBuilder exec java：
 *   Android 10+ 对 targetSdk >= 29 的应用禁止 execve() 自己 data 目录里的文件（SELinux），
 *   所以「解压一个 JRE，然后 fork/exec bin/java」这条路在现代 targetSdk 下是死的。
 *   PojavLauncher 的做法是 dlopen(libjvm.so) + JNI_CreateJavaVM，在 App 进程内把 JVM 起起来，
 *   本文件即该做法的实现。
 *
 * 提供的能力：
 *   setupStdio()  —— pipe + dup2 接管 fd 0/1/2，返回 [命令写入 fd, 日志读取 fd] 给 Kotlin
 *   createJvm()   —— dlopen libjvm.so、dlsym JNI_CreateJavaVM、按给定 options 创建 VM
 *   callMain()    —— 在当前线程调用服务端主类的 main(String[])（阻塞到主类返回）
 *   destroyJvm()  —— 尽力销毁 VM（正常情况下用不到，JVM 一旦创建进程内无法真正重启）
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "MslxVm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (*CreateJavaVM_t)(JavaVM **pvm, void **penv, void *args);

static JavaVM *g_vm = NULL;
static void *g_jvmHandle = NULL;

/* ---------- stdio 重定向 ---------- */

JNIEXPORT jintArray JNICALL
Java_com_mslx_console_localengine_NativeVm_setupStdio(JNIEnv *env, jclass clazz) {
    int inPipe[2] = {-1, -1};   /* [0] 读端 -> fd0；[1] 写端交给 Kotlin 发命令 */
    int outPipe[2] = {-1, -1};  /* [0] 读端交给 Kotlin 收日志；[1] 写端 -> fd1/fd2 */

    if (pipe(inPipe) != 0 || pipe(outPipe) != 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        return NULL;
    }
    if (dup2(inPipe[0], STDIN_FILENO) < 0 || dup2(outPipe[1], STDOUT_FILENO) < 0 ||
        dup2(outPipe[1], STDERR_FILENO) < 0) {
        LOGE("dup2() failed: %s", strerror(errno));
        return NULL;
    }
    /* 原始 fd 已复制，关掉多余副本，避免日志读取端永远等不到 EOF */
    close(inPipe[0]);
    close(outPipe[1]);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    jint fds[2];
    fds[0] = inPipe[1];   /* Kotlin 侧：写命令（stop 等） */
    fds[1] = outPipe[0];  /* Kotlin 侧：读服务端 stdout/stderr */

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) return NULL;
    (*env)->SetIntArrayRegion(env, result, 0, 2, fds);
    LOGI("stdio redirected: cmdWriteFd=%d logReadFd=%d", fds[0], fds[1]);
    return result;
}

/* ---------- 创建 JVM ---------- */

JNIEXPORT jint JNICALL
Java_com_mslx_console_localengine_NativeVm_createJvm(JNIEnv *env, jclass clazz,
                                                    jstring libjvmPath, jstring jreRoot,
                                                    jobjectArray options) {
    if (g_vm != NULL) {
        LOGI("JVM already created, skip");
        return 0;
    }
    const char *path = (*env)->GetStringUTFChars(env, libjvmPath, NULL);
    const char *jre = (*env)->GetStringUTFChars(env, jreRoot, NULL);
    if (path == NULL || jre == NULL) {
        LOGE("null path");
        return -1;
    }

    /* libjvm 的依赖库（libjava/libjli/...）在 jre/lib 与 jre/lib/server 下 */
    char libPath[4096];
    snprintf(libPath, sizeof(libPath), "%s/lib:%s/lib/server", jre, jre);
    const char *oldLd = getenv("LD_LIBRARY_PATH");
    char newLd[8192];
    snprintf(newLd, sizeof(newLd), "%s%s%s", libPath, oldLd ? ":" : "", oldLd ? oldLd : "");
    setenv("LD_LIBRARY_PATH", newLd, 1);
    setenv("JAVA_HOME", jre, 1);

    g_jvmHandle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (g_jvmHandle == NULL) {
        LOGE("dlopen(%s) failed: %s", path, dlerror());
        (*env)->ReleaseStringUTFChars(env, libjvmPath, path);
        (*env)->ReleaseStringUTFChars(env, jreRoot, jre);
        return -2;
    }
    CreateJavaVM_t createJavaVM = (CreateJavaVM_t) dlsym(g_jvmHandle, "JNI_CreateJavaVM");
    if (createJavaVM == NULL) {
        LOGE("dlsym(JNI_CreateJavaVM) failed: %s", dlerror());
        return -3;
    }

    jsize count = (*env)->GetArrayLength(env, options);
    JavaVMOption *opts = (JavaVMOption *) calloc(count > 0 ? count : 1, sizeof(JavaVMOption));
    const char **held = (const char **) calloc(count > 0 ? count : 1, sizeof(char *));
    if (opts == NULL || held == NULL) return -4;
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, options, i);
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        held[i] = chars;
        opts[i].optionString = (char *) chars;
    }

    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_6;  /* NDK jni.h 最高只定义到 1.6 */
    args.nOptions = count;
    args.options = opts;
    args.ignoreUnrecognized = JNI_FALSE;

    JNIEnv *jenv = NULL;
    jint rc = createJavaVM(&g_vm, (void **) &jenv, &args);
    LOGI("JNI_CreateJavaVM rc=%d options=%d", rc, (int) count);

    for (jsize i = 0; i < count; i++) {
        if (held[i] != NULL) {
            jstring item = (jstring) (*env)->GetObjectArrayElement(env, options, i);
            (*env)->ReleaseStringUTFChars(env, item, held[i]);
            (*env)->DeleteLocalRef(env, item);
        }
    }
    free(held);
    free(opts);
    (*env)->ReleaseStringUTFChars(env, libjvmPath, path);
    (*env)->ReleaseStringUTFChars(env, jreRoot, jre);
    return rc;
}

/* ---------- 调用服务端主类 ---------- */

JNIEXPORT jint JNICALL
Java_com_mslx_console_localengine_NativeVm_callMain(JNIEnv *env, jclass clazz,
                                                   jstring mainClass, jobjectArray args) {
    if (g_vm == NULL) {
        LOGE("JVM not created");
        return -10;
    }
    JNIEnv *e = NULL;
    if ((*g_vm)->AttachCurrentThread(g_vm, &e, NULL) != JNI_OK) {
        LOGE("AttachCurrentThread failed");
        return -11;
    }
    const char *name = (*env)->GetStringUTFChars(env, mainClass, NULL);
    LOGI("calling main: %s", name);
    jclass cls = (*e)->FindClass(e, name);
    if (cls == NULL) {
        LOGE("FindClass(%s) failed", name);
        (*e)->ExceptionDescribe(e);
        (*e)->ExceptionClear(e);
        return -12;
    }
    jmethodID main = (*e)->GetStaticMethodID(e, cls, "main", "([Ljava/lang/String;)V");
    if (main == NULL) {
        LOGE("no main(String[]) in %s", name);
        (*e)->ExceptionDescribe(e);
        (*e)->ExceptionClear(e);
        return -13;
    }
    (*e)->CallStaticVoidMethod(e, cls, main, args);
    if ((*e)->ExceptionCheck(e)) {
        LOGE("main threw");
        (*e)->ExceptionDescribe(e);
        (*e)->ExceptionClear(e);
        (*g_vm)->DetachCurrentThread(g_vm);
        return -14;
    }
    LOGI("main returned");
    (*g_vm)->DetachCurrentThread(g_vm);
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_mslx_console_localengine_NativeVm_isJvmCreated(JNIEnv *env, jclass clazz) {
    return g_vm != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mslx_console_localengine_NativeVm_destroyJvm(JNIEnv *env, jclass clazz) {
    if (g_vm != NULL) {
        (*g_vm)->DestroyJavaVM(g_vm);
        g_vm = NULL;
        LOGI("DestroyJavaVM done");
    }
}
