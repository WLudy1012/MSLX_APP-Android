/*
 * mslxnotag —— 堆打标签（heap tagging）垫片，仅供 LD_PRELOAD 注入 java 子进程使用。
 *
 * 背景：Android 12+ 的 bionic Scudo 分配器会给堆指针打上 top-byte tag 并在 free() 时校验
 * 标签；而上游自制旧 OpenJDK（PojavLauncher 2021 的 jre17 构建等）内部会截断指针高位，
 * 于是稍重的负载必然 SIGABRT：`Pointer tag for 0x... was truncated`（GC Thread）。
 * 本垫片在 java 的 main 之前把本进程的堆打标签关掉，绕开该冲突。
 *
 * 代价：关闭后**本进程**失去 tag/MTE 类的内存安全缓解，仅在执行期内生效。
 *
 * 约束（改动前务必读完，都是真机上踩过的坑）：
 *  1. 必须 `--target=<arch>-linux-android26` 及以上：mallopt 自 API 26 起导出，
 *     两个常量自 API 31 起进公共头文件；android24 下 `malloc.h` 不声明 mallopt。
 *  2. 必须 `-fvisibility=hidden` 且**不得导出任何可能与被注入进程重名的符号**：
 *     曾额外导出 Agent_OnLoad 做对照实验，LD_PRELOAD 的全局符号插入抢走了
 *     libinstrument.so 的同名实现，导致 Paper 启动 SIGSEGV。
 *  3. 产物按 ABI 命名放进 `app/src/main/assets/mslx/`，由 ShizukuController.ensureShimStaged
 *     推到 /data/local/tmp/mslx/lib/libmslxnotag.so 后作为 LD_PRELOAD 使用；
 *     重新编译请执行仓库根目录的 `.\build-shim.ps1`。
 */
#include <malloc.h>

/* M_BIONIC_SET_HEAP_TAGGING_LEVEL / M_HEAP_TAGGING_LEVEL_NONE 在部分 NDK 头文件里缺失，
 * 这里按 bionic 公开值兜底定义（值不会变）。 */
#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif
#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE (0)
#endif

__attribute__((constructor)) static void mslx_disable_heap_tagging(void) {
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
}
