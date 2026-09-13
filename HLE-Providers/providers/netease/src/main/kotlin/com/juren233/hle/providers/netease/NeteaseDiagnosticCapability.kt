/* Copyright 2026 juren233. Licensed under the Apache License, Version 2.0. */
package com.juren233.hle.providers.netease

internal object NeteaseDiagnosticCapability {
    // Direct interface call, no guessed reflective names. Old core DEX may lack the method.
    fun resolve(query: () -> Boolean): Boolean = try {
        query()
    } catch (_: LinkageError) {
        false
    } catch (_: Exception) {
        false
    }
}
