/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.common.extensions

import io.github.proify.extensions.json
import kotlinx.serialization.encodeToString

inline fun <reified T> T.toJson(): String {
    return json.encodeToString(this)
}
