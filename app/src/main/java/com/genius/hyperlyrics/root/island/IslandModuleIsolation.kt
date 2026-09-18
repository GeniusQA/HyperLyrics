package com.genius.hyperlyrics.root.island

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.root.HookEntry
import com.genius.hyperlyrics.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * 模块隔离：屏蔽其它 Xposed 模块对超级岛「背景」资源的写入，保证本模块的样式最终生效。
 *
 * 背景资源在两个层面上会被争抢：
 * 1. 其它模块（如 HyperIsland）用反射直接写 `DynamicIslandBackgroundView` 的私有 background 字段；
 * 2. 其它实现可能改走 `View.setBackground / setBackgroundColor`。
 *
 * 因此这里做两件事：
 * - **写入来源过滤**：hook 上述写入入口，按调用方来源（调用栈中的模块类名前缀）决定放行还是吞掉；
 *   只有 [RootConstants.KEY_HOOK_ISLAND_BLOCKED_MODULES] 里勾选的模块才会被屏蔽，
 *   未勾选时仅记录日志（便于用户拿到前缀后再勾选）。
 * - **绘制兜底**：见 [ensureDrawOverHook]，在岛背景视图绘制完成后补画本模块的渐变，
 *   即使有模块绕过上述入口（直接改 drawable 内部状态），本模块样式依然可见。
 *
 * 所有探测均容错：任何异常都放行，绝不因为第三方模块或系统版本差异影响系统本身。
 */
internal object IslandModuleIsolation {
    private const val TAG = "IslandModuleIsolation"
    private const val SELF_PREFIX = "com.genius.hyperlyrics"

    /** 调用栈中出现这些前缀说明是系统/框架/框架自身，不是第三方美化模块。 */
    private val NON_MODULE_PREFIXES = arrayOf(
        "com.genius.hyperlyrics",
        "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.",
        "libcore.", "com.android.", "miui.", "com.miui.", "org.apache.", "org.json.",
        "com.google.", "io.github.libxposed.", "org.lsposed.", "de.robv.android.xposed.",
    )

    @Volatile
    private var module: XposedModule? = null

    private val writeGuardHandles = Collections.synchronizedList(mutableListOf<HookHandle>())
    private val drawOverHandles =
        Collections.synchronizedMap(WeakHashMap<Class<*>, HookHandle>())
    private val initializedLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val loggedForeignWrites = Collections.synchronizedSet(mutableSetOf<String>())

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule, classLoader: ClassLoader) {
        module = xposedModule
        if (!initializedLoaders.add(classLoader)) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RootConstants.KEY_HOOK_ISLAND_BACKGROUND_ISOLATION) {
                if (isEnabled()) installWriteGuardHooks() else uninstallWriteGuardHooks()
            }
        }
        prefsListener = listener
        runCatching { prefs?.registerOnSharedPreferenceChangeListener(listener) }
        if (isEnabled()) installWriteGuardHooks()
    }

    fun isEnabled(): Boolean = runCatching {
        prefs?.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_BACKGROUND_ISOLATION,
            RootConstants.DEFAULT_HOOK_ISLAND_BACKGROUND_ISOLATION,
        )
    }.getOrNull() ?: RootConstants.DEFAULT_HOOK_ISLAND_BACKGROUND_ISOLATION

    private fun blockedModules(): Set<String> = runCatching {
        prefs?.getStringSet(RootConstants.KEY_HOOK_ISLAND_BLOCKED_MODULES, null)
    }.getOrNull().orEmpty()

    /**
     * 安装岛背景写入的来源过滤。只在「岛背景以本模块为准」开启时安装，避免无谓开销。
     * 注意：过滤失败一律放行，仅记录日志。
     */
    private fun installWriteGuardHooks() {
        val xposedModule = module ?: return
        if (writeGuardHandles.isNotEmpty()) return

        runCatching {
            val fieldSet = Field::class.java.getDeclaredMethod(
                "set",
                Any::class.java,
                Any::class.java,
            )
            xposedModule.deoptimize(fieldSet)
            writeGuardHandles += xposedModule.hook(fieldSet)
                .intercept(IslandBackgroundFieldSetHooker())
        }.onFailure { error ->
            HookLogger.e(TAG, "安装岛背景字段写入过滤失败", error)
        }

        runCatching {
            val setBackground = View::class.java.getDeclaredMethod(
                "setBackground",
                Drawable::class.java,
            )
            xposedModule.deoptimize(setBackground)
            writeGuardHandles += xposedModule.hook(setBackground)
                .intercept(IslandBackgroundSetterHooker("setBackground"))
        }.onFailure { error ->
            HookLogger.e(TAG, "安装 setBackground 过滤失败", error)
        }

        runCatching {
            @Suppress("DEPRECATION")
            val setBackgroundDrawable = View::class.java.getDeclaredMethod(
                "setBackgroundDrawable",
                Drawable::class.java,
            )
            xposedModule.deoptimize(setBackgroundDrawable)
            writeGuardHandles += xposedModule.hook(setBackgroundDrawable)
                .intercept(IslandBackgroundSetterHooker("setBackgroundDrawable"))
        }.onFailure { error ->
            HookLogger.w(TAG, "安装 setBackgroundDrawable 过滤失败: reason=${error.message}")
        }

        runCatching {
            val setBackgroundColor = View::class.java.getDeclaredMethod(
                "setBackgroundColor",
                Int::class.javaPrimitiveType,
            )
            xposedModule.deoptimize(setBackgroundColor)
            writeGuardHandles += xposedModule.hook(setBackgroundColor)
                .intercept(IslandBackgroundSetterHooker("setBackgroundColor"))
        }.onFailure { error ->
            HookLogger.e(TAG, "安装 setBackgroundColor 过滤失败", error)
        }

        HookLogger.i(TAG, "岛背景写入来源过滤已安装: handles=${writeGuardHandles.size}")
    }

    private fun uninstallWriteGuardHooks() {
        runCatching {
            writeGuardHandles.forEach { it.unhook() }
        }
        synchronized(writeGuardHandles) { writeGuardHandles.clear() }
        HookLogger.i(TAG, "岛背景写入来源过滤已移除")
    }

    /**
     * 为岛背景视图类安装「绘制兜底」：draw 完成后，若当前背景已被其它模块替换，
     * 就把本模块的渐变再画一遍，保证本模块样式最终可见。
     */
    fun ensureDrawOverHook(viewClass: Class<*>) {
        val xposedModule = module ?: return
        if (!isEnabled()) return
        if (drawOverHandles.containsKey(viewClass)) return
        // 自身未声明 draw 时沿父类查找，但不挂到 android.view.View 上（那会波及全部 View）。
        var current: Class<*>? = viewClass
        while (current != null && current != View::class.java) {
            val draw = runCatching {
                current.getDeclaredMethod("draw", Canvas::class.java)
            }.getOrNull()
            if (draw != null) {
                runCatching {
                    xposedModule.deoptimize(draw)
                    drawOverHandles[viewClass] =
                        xposedModule.hook(draw).intercept(IslandDrawOverHooker())
                    HookLogger.i(TAG, "岛背景绘制兜底已安装: ${current.name}")
                }.onFailure { error ->
                    HookLogger.w(TAG, "安装岛背景绘制兜底失败: reason=${error.message}")
                }
                return
            }
            current = current.superclass
        }
        HookLogger.w(TAG, "未找到可挂载的 draw 方法，改用背景重assert兜底: ${viewClass.name}")
    }

    /**
     * 是否为「超级岛背景字段」的写入。
     *
     * 注意：`background` 字段实际声明在 `android.view.View`（`mBackground`）上，
     * 因此**不能**用字段的 declaringClass 判断，必须看写入目标实例是不是岛背景视图。
     */
    fun isIslandBackgroundFieldWrite(field: Field, target: Any?): Boolean {
        if (field.isSynthetic) return false
        val name = field.name
        if (!name.equals("background", ignoreCase = true) &&
            !name.equals("mBackground", ignoreCase = true)
        ) {
            return false
        }
        val targetName = target?.javaClass?.name ?: return false
        return targetName.contains("dynamicisland", ignoreCase = true)
    }

    fun isIslandBackgroundView(view: View): Boolean =
        view.javaClass.name.contains("dynamicisland", ignoreCase = true)

    /** 从调用栈解析写入方模块前缀；返回 null 表示是系统/框架/本模块自身。 */
    fun resolveCallerModule(): String? {
        val stack = runCatching { Thread.currentThread().stackTrace }.getOrNull() ?: return null
        for (element in stack) {
            val className = element.className
            if (NON_MODULE_PREFIXES.any { className.startsWith(it) }) continue
            val parts = className.split('.')
            return if (parts.size >= 3) parts.take(3).joinToString(".") else className
        }
        return null
    }

    /**
     * @return true 表示这次写入应被屏蔽（命中用户勾选的模块）。
     */
    fun shouldBlock(caller: String?, resource: String): Boolean {
        if (caller == null) return false
        val blocked = blockedModules()
        val hit = blocked.any { caller.startsWith(it) }
        logForeignWrite(caller, resource, blocked = hit)
        return hit
    }

    private fun logForeignWrite(caller: String, resource: String, blocked: Boolean) {
        val signature = "$blocked|$caller|$resource"
        val isNew = synchronized(loggedForeignWrites) {
            if (loggedForeignWrites.size > 64) loggedForeignWrites.clear()
            loggedForeignWrites.add(signature)
        }
        // 首次出现一定要报（release 也报），便于用户直接从 logcat 拿到模块前缀；之后仅在 debug 重复报。
        if (!isNew && !BuildConfig.DEBUG) return
        if (blocked) {
            HookLogger.i(TAG, "已屏蔽第三方模块的岛背景写入: module=$caller, resource=$resource")
        } else {
            HookLogger.i(
                TAG,
                "检测到第三方模块写入岛背景（未屏蔽，可在「模块隔离」勾选 $caller）: resource=$resource",
            )
        }
    }

    /** HyperIsland 走的是反射直写私有 background 字段。 */
    private class IslandBackgroundFieldSetHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val field = chain.thisObject as? Field ?: return chain.proceed()
            val target = chain.args.firstOrNull()
            if (!isIslandBackgroundFieldWrite(field, target)) return chain.proceed()
            val caller = resolveCallerModule()
            val resource = "field:${field.declaringClass.simpleName}#${field.name}"
            if (!shouldBlock(caller, resource)) return chain.proceed()
            return null
        }
    }

    /** 兜底拦截直接调用 setBackground / setBackgroundColor 的实现。 */
    private class IslandBackgroundSetterHooker(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val view = chain.thisObject as? View ?: return chain.proceed()
            if (!isIslandBackgroundView(view)) return chain.proceed()
            val caller = resolveCallerModule()
            if (!shouldBlock(caller, "view:$methodName")) return chain.proceed()
            return null
        }
    }

    private class IslandDrawOverHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val view = chain.thisObject as? View ?: return result
            val canvas = chain.args.firstOrNull() as? Canvas ?: return result
            IslandLinearGradientBackgroundApplier.drawOverIfOverridden(view, canvas)
            return result
        }
    }
}
