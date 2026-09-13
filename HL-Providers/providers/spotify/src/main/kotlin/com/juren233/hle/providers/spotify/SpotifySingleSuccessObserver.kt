/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import java.lang.reflect.Proxy

/**
 * 在不订阅 Single 的前提下附加成功观察器。
 *
 * RxJava 由 Spotify 进程提供，Provider Pack 不携带或链接第二份 RxJava。
 * 动态代理实现目标 ClassLoader 中的 Consumer，并返回 doOnSuccess 生成的
 * 同类型 Single，从而保留原有的延迟执行、调度、取消和错误语义。
 */
internal object SpotifySingleSuccessObserver {
    const val SINGLE_CLASS_NAME = "io.reactivex.rxjava3.core.Single"
    const val CONSUMER_CLASS_NAME = "io.reactivex.rxjava3.functions.Consumer"

    fun wrap(
        result: Any?,
        trackUri: String?,
        onSuccess: (trackUri: String, value: Any) -> Unit,
        onObserverFailure: (Throwable) -> Unit = {},
    ): Any? {
        val original = result ?: return null
        val boundTrackUri = trackUri
            ?.trim()
            ?.takeIf { it.startsWith("spotify:track:") }
            ?: return original

        return runCatching {
            val classLoader = original.javaClass.classLoader
                ?: Thread.currentThread().contextClassLoader
            val consumerClass = Class.forName(
                CONSUMER_CLASS_NAME,
                false,
                classLoader,
            )
            val doOnSuccess = original.javaClass.getMethod("doOnSuccess", consumerClass)
            check(doOnSuccess.returnType.name == SINGLE_CLASS_NAME) {
                "Unexpected doOnSuccess return type: ${doOnSuccess.returnType.name}"
            }
            val observer = Proxy.newProxyInstance(
                consumerClass.classLoader,
                arrayOf(consumerClass),
            ) { proxy, method, arguments ->
                when {
                    method.name == "accept" && method.parameterCount == 1 -> {
                        arguments?.getOrNull(0)?.let { value ->
                            runCatching { onSuccess(boundTrackUri, value) }
                                .onFailure(onObserverFailure)
                        }
                        null
                    }
                    method.name == "toString" && method.parameterCount == 0 ->
                        "HLESpotifySingleSuccessObserver"
                    method.name == "hashCode" && method.parameterCount == 0 ->
                        System.identityHashCode(proxy)
                    method.name == "equals" && method.parameterCount == 1 ->
                        proxy === arguments?.getOrNull(0)
                    else -> null
                }
            }
            doOnSuccess.invoke(original, observer) ?: original
        }.onFailure(onObserverFailure).getOrDefault(original)
    }
}
