package com.mslx.console.localengine

/**
 * 进程内 JVM 桥接（native，见 src/main/cpp/mslxvm.c）。
 *
 * Android 10+ 对 targetSdk >= 29 的应用禁止 exec 自己 data 目录中的文件，
 * 因此不能 fork `bin/java`，只能在 App 进程内 dlopen(libjvm.so) 并 JNI_CreateJavaVM。
 */
internal object NativeVm {

    init {
        System.loadLibrary("mslxvm")
    }

    /**
     * 接管进程 fd 0/1/2：返回 [命令写入 fd, 日志读取 fd]，
     * 之后 JVM 里 System.in/out/err 都会走这两个管道。
     */
    external fun setupStdio(): IntArray?

    /** 创建 JVM；[options] 为 JVM 启动参数（-D/-X 等）。返回 0 表示成功。 */
    external fun createJvm(libjvmPath: String, jreRoot: String, options: Array<String>): Int

    /** 在当前线程调用 [mainClass] 的 main(String[])；阻塞到主类返回。 */
    external fun callMain(mainClass: String, args: Array<String>): Int

    /** JVM 是否已经创建（一个进程只能创建一次）。 */
    external fun isJvmCreated(): Boolean

    /** 尽力销毁 JVM（正常流程用不到：JVM 创建后进程内无法真正重启）。 */
    external fun destroyJvm()
}
