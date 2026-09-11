/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.genius.hyperlyrics.lyric.model.interfaces

import com.genius.hyperlyrics.lyric.model.LyricWord

interface IRichLyricLine : ILyricLine {
    var secondary: String?
    var secondaryWords: List<LyricWord>?
    var translation: String?
    var translationWords: List<LyricWord>?
    var roma: String?
}
