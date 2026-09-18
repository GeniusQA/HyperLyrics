/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.root.island

import android.graphics.Bitmap
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Runtime descriptors for the Dynamic Island music-wave color input path. */
internal object IslandMusicWaveMethodProfile {
    const val LEGACY_COLOR_METHOD = "setLottieColor"
    const val OS4_COLOR_METHOD = "getLottieColor"
    const val OS4_COLOR_RETURN_TYPE = "H0.f"

    fun isLegacyColorMethod(method: Method): Boolean {
        return method.name == LEGACY_COLOR_METHOD &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(Bitmap::class.java))
    }

    fun isOs4ColorMethod(method: Method): Boolean {
        return method.name == OS4_COLOR_METHOD &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(Bitmap::class.java)) &&
            method.returnType.name == OS4_COLOR_RETURN_TYPE
    }

    internal fun isOs4ColorMethod(
        name: String,
        returnTypeName: String,
        parameterTypeNames: List<String>,
        isStatic: Boolean = false,
    ): Boolean {
        return name == OS4_COLOR_METHOD &&
            !isStatic &&
            returnTypeName == OS4_COLOR_RETURN_TYPE &&
            parameterTypeNames == listOf(Bitmap::class.java.name)
    }

    /**
     * 形态兜底匹配：部分 ROM 构建混淆更彻底，取色入口方法名（setLottieColor/getLottieColor）
     * 也被改掉，按名字匹配会整体失效。但入口的签名形态不变——非静态、Bitmap 为首参。
     * Hook 仅在原方法执行后读取结果（不改写返回值/参数），按形态兜底挂载是安全的。
     */
    fun isFallbackColorMethod(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers)) return false
        if (method.parameterTypes.isEmpty()) return false
        return method.parameterTypes[0] == Bitmap::class.java
    }

    fun describe(method: Method): String {
        return "${method.name}(" +
            method.parameterTypes.joinToString(",") { it.name } +
            "):${method.returnType.name}"
    }
}
