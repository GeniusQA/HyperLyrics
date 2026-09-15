package com.genius.hyperlyrics.common.dexkit

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import java.io.File

/**
 * 解析目标进程应当加载的 libdexkit.so 所在目录。
 *
 * 模块 APK 同时打包 arm64-v8a 与 armeabi-v7a，但
 * [ApplicationInfo.nativeLibraryDir] 只指向**设备主 ABI** 目录（64 位机型上是
 * `lib/arm64`）。Xposed 代码运行在目标 App 进程内，若该进程是 32 位
 * （例如酷狗概念版 `com.kugou.android.lite`、部分车机/精简包、用户手动安装的
 * 老版本 APK），从 `lib/arm64` 加载会直接失败：
 *
 * ```
 * dlopen failed: ".../lib/arm64/libdexkit.so" is 64-bit instead of 32-bit
 * ```
 *
 * 因此 32 位进程必须改从同一 `lib/` 根目录下的 32 位 ISA 子目录加载。ISA 子目录
 * 命名与系统 `NativeLibraryHelper` 保持一致：armeabi-v7a/armeabi → `arm`，
 * x86 → `x86`，arm64-v8a → `arm64`。
 */
object DexKitNativeLibrary {
    private const val LIBRARY_NAME = "libdexkit.so"

    /**
     * 返回与当前进程 ABI 匹配、且确实存在 [LIBRARY_NAME] 的模块 native 库目录；
     * 目录缺失时退回系统给出的主 ABI 目录（保持原有报错路径，便于定位安装问题）。
     */
    fun resolveDirectory(moduleInfo: ApplicationInfo?): String? {
        val primaryDir = moduleInfo?.nativeLibraryDir?.takeIf { it.isNotBlank() } ?: return null
        if (Process.is64Bit()) return primaryDir
        val libRoot = File(primaryDir).parentFile ?: return primaryDir
        val candidates = (Build.SUPPORTED_32_BIT_ABIS.map(::isaSubdir) + listOf("arm", "x86"))
            .distinct()
            .map { File(libRoot, it) }
        return candidates.firstOrNull { File(it, LIBRARY_NAME).isFile }?.absolutePath
            ?: primaryDir
    }

    /** 与系统安装器一致：armeabi 系列落盘到 `arm`，其余使用 ABI 原名。 */
    private fun isaSubdir(abi: String): String =
        if (abi.startsWith("armeabi")) "arm" else abi
}
