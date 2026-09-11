/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.root.mediacard.notification

import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * SystemUI dozeTimeTick 脱糖 Runnable 的实例化策略。
 *
 * 两种已在原始 DEX 中核实的形态（以 dexdump 为准，不以反编译器显示为准）：
 * - 旧脱糖：合成 Runnable 声明 `(DozeServiceHost)` 构造器，捕获在构造时完成；
 * - 新脱糖（issue #16 报告者 HyperOS 4.0.0.25 移植包 classes2.dex 的
 *   `Lcom/android/systemui/doze/DozeUi$$ExternalSyntheticLambda0;`：PUBLIC FINAL
 *   SYNTHETIC、公有字段 `f$0 : DozeServiceHost`、`run()V`，构造器为默认无参，
 *   捕获方在调用点为 `f$0` 赋值）：本模块同样先写 `f$0` 再把 runnable 交给
 *   SystemUI 执行，跨线程可见性由后续 Handler 投递的同步边界保证。
 *
 * 旧模式优先：命中即保持既有行为不变；两种模式都不满足时按原样抛出，
 * 由上层记录"初始化 AOD 原生刷新接口失败"。
 */
internal interface DozeTickRunnableFactory {
    fun create(host: Any): Runnable

    companion object {
        const val CAPTURED_RECEIVER_FIELD = "f\$0"

        fun resolve(tickRunnableClass: Class<*>, hostClass: Class<*>): DozeTickRunnableFactory {
            val constructors = tickRunnableClass.declaredConstructors
            val signatures = constructors.joinToString(prefix = "[", separator = ", ", postfix = "]") {
                it.toString()
            }
            // 形态一：单参构造器捕获 receiver。参数类型允许是 host 的基类/接口，
            // 因为不同 ROM 的脱糖结果捕获的可能是 DozeServiceHost 的父类型。
            val legacy = constructors.firstOrNull { ctor ->
                ctor.parameterTypes.size == 1 && ctor.parameterTypes[0].isAssignableFrom(hostClass)
            }
            if (legacy != null) {
                legacy.isAccessible = true
                return LegacyConstructor(legacy)
            }
            // 形态二：无参构造器 + 捕获字段（优先 f$0，退而接受任何兼容类型的实例字段）。
            val noArg = constructors.firstOrNull { it.parameterTypes.isEmpty() }
            if (noArg != null) {
                val captured = tickRunnableClass.declaredFields
                    .firstOrNull { it.name == CAPTURED_RECEIVER_FIELD }
                    ?: tickRunnableClass.declaredFields.firstOrNull { field ->
                        !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                            field.type.isAssignableFrom(hostClass)
                    }
                    ?: throw IllegalStateException(
                        "脱糖 Runnable ${tickRunnableClass.name} 无 $CAPTURED_RECEIVER_FIELD " +
                            "兼容捕获字段: constructors=$signatures",
                    )
                require(captured.type.isAssignableFrom(hostClass)) {
                    "脱糖 Runnable 的捕获字段类型 ${captured.type.name} 与 ${hostClass.name} 不兼容"
                }
                noArg.isAccessible = true
                captured.isAccessible = true
                return CapturedField(noArg, captured)
            }
            // 形态三：部分 ROM 的合成类反射拿不到任何构造器（declaredConstructors 为空），
            // 退化为 Unsafe 分配实例 + 写捕获字段，运行行为与形态二一致。
            val capturedForUnsafe = tickRunnableClass.declaredFields
                .firstOrNull { it.name == CAPTURED_RECEIVER_FIELD }
                ?: tickRunnableClass.declaredFields.firstOrNull { field ->
                    !java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                        field.type.isAssignableFrom(hostClass)
                }
            val unsafe = unsafeInstance()
            if (unsafe != null && capturedForUnsafe != null &&
                capturedForUnsafe.type.isAssignableFrom(hostClass)
            ) {
                capturedForUnsafe.isAccessible = true
                return UnsafeAllocation(unsafe, tickRunnableClass, capturedForUnsafe)
            }
            throw IllegalStateException(
                "脱糖 Runnable ${tickRunnableClass.name} 无可用构造器: constructors=$signatures",
            )
        }

        /** 反射获取 sun.misc.Unsafe，避免编译期直接依赖隐藏 API。 */
        private fun unsafeInstance(): Any? = runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    private class UnsafeAllocation(
        private val unsafe: Any,
        private val tickRunnableClass: Class<*>,
        private val field: Field,
    ) : DozeTickRunnableFactory {
        override fun create(host: Any): Runnable {
            val instance = unsafe.javaClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, tickRunnableClass) as Runnable
            field.set(instance, host)
            return instance
        }
    }

    private class LegacyConstructor(
        private val constructor: Constructor<*>,
    ) : DozeTickRunnableFactory {
        override fun create(host: Any): Runnable = constructor.newInstance(host) as Runnable
    }

    private class CapturedField(
        private val constructor: Constructor<*>,
        private val field: Field,
    ) : DozeTickRunnableFactory {
        override fun create(host: Any): Runnable =
            (constructor.newInstance() as Runnable).also { field.set(it, host) }
    }
}
