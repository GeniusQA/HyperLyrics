@file:Suppress("PrivateApi")

package com.genius.hyperlyrics.root.mediacard.notification

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.content.SharedPreferences
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.common.ClassicAodSongInfoConfig
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.common.lyric.CjkLyricWhitespacePolicy
import com.genius.hyperlyrics.common.lyric.LyricMetadataKeys
import com.genius.hyperlyrics.common.media.MediaMetadataHelper
import com.genius.hyperlyrics.lyric.view.SongPreprocessor
import com.genius.hyperlyrics.root.ClassicAodFocusNotificationRecovery
import com.genius.hyperlyrics.root.HookEntry
import com.genius.hyperlyrics.lyric.model.interfaces.IRichLyricLine
import com.genius.hyperlyrics.root.LyriconDataBridge
import com.genius.hyperlyrics.root.mediacard.LyricGestureHelper
import com.genius.hyperlyrics.root.utils.CoverColorHelper
import com.genius.hyperlyrics.root.utils.DisplayDiagnosticLogger
import com.genius.hyperlyrics.root.utils.HookLogger
import com.genius.hyperlyrics.root.utils.OverlayFontColorApplier
import com.genius.hyperlyrics.root.utils.MediaCardDiagnosticLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal data class AodLyricContent(
    val main: String,
    val translation: String,
    val backing: String,
    val backingTranslation: String,
    val overlappingMain: String,
    val overlappingTranslation: String,
    val overlappingBacking: String,
    val overlappingBackingTranslation: String,
    val next: String,
    val mainAlignment: AodLyricAlignment,
    val backingAlignment: AodLyricAlignment,
    val overlappingAlignment: AodLyricAlignment,
    val overlappingBackingAlignment: AodLyricAlignment,
    val nextAlignment: AodLyricAlignment,
    /** 等待歌词匹配中：主行用动态圆点占位（与摘要态一致）。 */
    val waitingForLyrics: Boolean = false,
)

internal enum class AodLyricAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

internal enum class AodLyricRow {
    MAIN,
    TRANSLATION,
    BACKING,
    BACKING_TRANSLATION,
    OVERLAPPING_MAIN,
    OVERLAPPING_TRANSLATION,
    OVERLAPPING_BACKING,
    OVERLAPPING_BACKING_TRANSLATION,
    NEXT,
}

internal data class AodTextStyleConfig(
    val mainTextSize: Int,
    val backingTextSize: Int,
    val translationTextSize: Int,
    val showNextLyric: Boolean,
    val nextLyricStyle: Int,
    val duetLyrics: Boolean,
    val centerNonDuetSong: Boolean,
    val centerGroupVocals: Boolean,
    val pauseStyle: Int,
    val translationDisplayMode: Int,
    val translationFallback: Boolean,
    val swapTranslation: Boolean,
    val nextSongPreview: Boolean,
    val nextSongPreviewPosition: Int,
    /**
     * 歌词块总行数上限：直接以行数为准，不再按高度/字号反推。
     * 当前句/翻译最多折 [AodMediaLyricPolicy.MAIN_LYRIC_LINES_RESERVED] 行，剩余行数分给后续歌词。
     */
    val lyricMaxLines: Int = RootConstants.DEFAULT_HOOK_LYRIC_MAX_LINES,
) {
    val translationDisplay: Boolean
        get() = translationDisplayMode != RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
}

internal data class AodHorizontalMargins(
    val left: Int,
    val right: Int,
)

/**
 * 锁屏 AOD 歌词居中布局结果（单位：px，坐标相对 player）。
 *
 * - [rootTop]：歌词根容器顶部（歌曲信息下缘 + 最小间距）。
 * - [rootHeight]：歌词根容器高度（歌词容器 + 进度条块）。
 * - [lyricContainerHeight]：歌词容器高度，歌词行在其中垂直居中。
 * - [targetCardHeight]：卡片目标高度（原生不够时向下撑高）。
 */
internal data class LockScreenLyricLayout(
    val targetCardHeight: Int,
    val rootTop: Int,
    val rootHeight: Int,
    val lyricContainerHeight: Int,
)

internal object AodMediaLyricPolicy {
    private const val NO_LYRIC_PREVIEW_DURATION_MS = 5_000L

    /** 多行歌词里为主句预留的行数（主句最长折两行）。 */
    private const val MAIN_LYRIC_LINES_RESERVED = 2

    fun embeddedSongInfoGravity(position: Int): Int = when (position) {
        RootConstants.AOD_SONG_INFO_POSITION_LEFT ->
            Gravity.LEFT or Gravity.CENTER_VERTICAL
        RootConstants.AOD_SONG_INFO_POSITION_RIGHT ->
            Gravity.RIGHT or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    fun shouldShow(
        enabled: Boolean,
        fullAod: Boolean,
        playing: Boolean,
        hasLyric: Boolean,
        packageMatches: Boolean,
        pauseStyle: Int = RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
        lockScreenLyrics: Boolean = false,
        notificationCenter: Boolean = false,
    ): Boolean =
        (
            (enabled && fullAod) ||
                lockScreenLyrics ||
                notificationCenter
            ) &&
        (playing || pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS) &&
        hasLyric &&
        packageMatches

    fun shouldShowNextSongPreview(
        enabled: Boolean,
        positionMs: Long,
        durationMs: Long,
        hasActualLyrics: Boolean,
        lastLyricStartMs: Long,
    ): Boolean {
        if (!enabled || positionMs < 0L || durationMs <= 0L) return false
        val previewStartMs = if (hasActualLyrics) {
            if (lastLyricStartMs < 0L) return false
            lastLyricStartMs
        } else {
            (durationMs - NO_LYRIC_PREVIEW_DURATION_MS).coerceAtLeast(0L)
        }
        return positionMs >= previewStartMs && positionMs < durationMs
    }

    fun formatNextSongPreview(title: String, artist: String): String {
        val songInfo = listOf(title.trim(), artist.trim())
            .filter { it.isNotBlank() }
            .joinToString("-")
        return songInfo.takeIf { it.isNotBlank() }?.let { "下一首：$it" }.orEmpty()
    }

    fun sanitizeNextSongPreviewPosition(value: Int): Int =
        value.takeIf {
            it in RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT..
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT
        } ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION

    fun nextSongPreviewAlignment(position: Int): AodLyricAlignment =
        when (sanitizeNextSongPreviewPosition(position)) {
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT -> AodLyricAlignment.LEFT
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT -> AodLyricAlignment.RIGHT
            else -> AodLyricAlignment.CENTER
        }

    fun shouldSuppressNoLyricPlaceholder(
        isTextMode: Boolean,
        hasActualLyrics: Boolean,
    ): Boolean = !isTextMode && !hasActualLyrics

    fun shouldCompactClassicMain(lineCount: Int): Boolean = lineCount <= 1

    fun requiredCardHeight(
        nativeCardHeight: Int,
        lyricBottom: Int,
        bottomPadding: Int
    ): Int = maxOf(
        nativeCardHeight.coerceAtLeast(0),
        lyricBottom.coerceAtLeast(0) + bottomPadding.coerceAtLeast(0)
    )

    fun lockScreenTargetCardHeight(
        nativeCardHeight: Int,
        lyricBottom: Int,
        bottomPadding: Int,
    ): Int = requiredCardHeight(
        nativeCardHeight = nativeCardHeight,
        lyricBottom = lyricBottom,
        bottomPadding = bottomPadding,
    )

    fun lockScreenBackgroundTargetHeight(
        targetCardHeight: Int,
    ): Int = targetCardHeight.coerceAtLeast(0)

    fun lockScreenNativeCardHeight(
        fullAod: Boolean,
        fullAodBaseHeight: Int,
        playerBaseHeight: Int
    ): Int = if (fullAod) {
        fullAodBaseHeight.coerceAtLeast(0)
    } else {
        playerBaseHeight.coerceAtLeast(0)
    }

    fun lockScreenHeightNeedsReassert(
        appliedHeight: Int?,
        currentLayoutHeight: Int?
    ): Boolean = appliedHeight != null &&
        appliedHeight > 0 &&
        currentLayoutHeight != null &&
        currentLayoutHeight != appliedHeight

    fun lockScreenHeightNeedsRestore(
        appliedHeight: Int?,
        heightAnimationActive: Boolean,
    ): Boolean = appliedHeight != null || heightAnimationActive

    fun lockScreenLyricTop(
        anchorBottom: Int,
        topGap: Int
    ): Int = anchorBottom.coerceAtLeast(0) + topGap.coerceAtLeast(0)

    fun contentAnchorBottom(
        albumBottom: Int,
        artistBottom: Int,
        actionBottom: Int = 0,
        seekBarBottom: Int = 0,
        actionVisible: Boolean = true,
        seekBarVisible: Boolean = true,
    ): Int = maxOf(
        albumBottom.coerceAtLeast(0),
        artistBottom.coerceAtLeast(0),
        if (actionVisible) actionBottom.coerceAtLeast(0) else 0,
        if (seekBarVisible) seekBarBottom.coerceAtLeast(0) else 0,
    )

    /**
     * 全屏 AOD（锁屏 AOD）专用锚点：专辑封面在该模式下是整卡背景（full-bleed），
     * 以 album.bottom 参与取 max 会把歌词推到卡片中部甚至更低，卡片被大幅撑高。
     * 改为锚定标题/歌手文本块底部，仅叠加**当前可见**的按钮/进度条，
     * 隐藏（INVISIBLE/GONE）或缺失的控件不再把歌词往下顶，
     * 呈现「紧凑卡片 + 歌词紧贴歌手下方」的效果。
     */
    fun fullScreenAodContentAnchorBottom(
        artistBottom: Int,
        actionBottom: Int = 0,
        seekBarBottom: Int = 0,
        actionVisible: Boolean = true,
        seekBarVisible: Boolean = true,
    ): Int {
        var anchor = artistBottom.coerceAtLeast(0)
        if (actionVisible) anchor = maxOf(anchor, actionBottom.coerceAtLeast(0))
        if (seekBarVisible) anchor = maxOf(anchor, seekBarBottom.coerceAtLeast(0))
        return anchor
    }

    /**
     * 计算锁屏 AOD 歌词区的居中布局。
     *
     * 上边界取可见歌曲信息下缘 [anchorBottom]，下边界取进度条上缘；
     * 歌词容器在两者之间垂直居中，进度条贴卡片底部（仅保留 [bottomGap]）。
     * 歌词容器高度固定为原生卡片剩余空间，超出部分由内部 TextView 的 maxLines 截断，
     * 不再向下撑高卡片，避免多行歌词/翻译压住进度条或溢出屏幕。
     */
    fun lockScreenCenteredLyricLayout(
        nativeCardHeight: Int,
        anchorBottom: Int,
        lyricContentHeight: Int,
        progressRowHeight: Int,
        progressRowTopMargin: Int,
        bottomGap: Int,
        minTopGap: Int,
    ): LockScreenLyricLayout {
        val safeNative = nativeCardHeight.coerceAtLeast(0)
        val safeAnchor = anchorBottom.coerceAtLeast(0)
        val safeProgress = progressRowHeight.coerceAtLeast(0)
        val safeProgressMargin = progressRowTopMargin.coerceAtLeast(0)
        val safeBottomGap = bottomGap.coerceAtLeast(0)
        val safeMinTopGap = minTopGap.coerceAtLeast(0)

        val rootTop = safeAnchor + safeMinTopGap
        val progressBlock = safeProgressMargin + safeProgress
        val availableInNative = (safeNative - safeBottomGap - progressBlock - rootTop).coerceAtLeast(0)

        return LockScreenLyricLayout(
            targetCardHeight = safeNative,
            rootTop = rootTop,
            rootHeight = safeNative - safeBottomGap - rootTop,
            lyricContainerHeight = availableInNative,
        )
    }

    fun lockScreenHorizontalMargins(
        playerWidth: Int,
        cardLeft: Int,
        cardRight: Int,
        albumLeft: Int,
        extraInset: Int = 0,
    ): AodHorizontalMargins {
        val safePlayerWidth = playerWidth.coerceAtLeast(0)
        val safeCardLeft = cardLeft.coerceIn(0, safePlayerWidth)
        val safeCardRight = cardRight.coerceIn(safeCardLeft, safePlayerWidth)
        val cardWidth = safeCardRight - safeCardLeft
        val inset = (albumLeft - safeCardLeft + extraInset.coerceAtLeast(0))
            .coerceIn(0, cardWidth / 2)
        return AodHorizontalMargins(
            left = safeCardLeft + inset,
            right = safePlayerWidth - safeCardRight + inset,
        )
    }

    fun classicOverlayHeight(contentHeight: Int, availableHeight: Int): Int =
        minOf(
            contentHeight.coerceAtLeast(1),
            availableHeight.coerceAtLeast(1)
        )

    fun isLockScreenAodActive(
        fullAod: Boolean,
        interactive: Boolean,
        playerShown: Boolean
    ): Boolean = fullAod || (!interactive && playerShown)

    fun isLockScreenLyricsActive(
        interactive: Boolean,
        keyguardLocked: Boolean,
        playerShown: Boolean,
        featureEnabled: Boolean,
    ): Boolean = featureEnabled && interactive && keyguardLocked && playerShown

    fun isNotificationCenterLyricsActive(
        interactive: Boolean,
        keyguardLocked: Boolean,
        playerShown: Boolean,
        featureEnabled: Boolean,
    ): Boolean = featureEnabled && interactive && !keyguardLocked && playerShown

    fun readTranslationPronunciationMode(
        prefs: SharedPreferences?,
        key: String,
        defaultValue: Int = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE
    ): Int {
        if (prefs == null) return defaultValue
        val raw = try {
            prefs.all[key]
        } catch (_: Exception) {
            null
        }
        return when (raw) {
            is Int -> raw.coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            )
            is Number -> raw.toInt().coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            )
            is Boolean -> if (raw) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
            else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF
            is String -> raw.toIntOrNull()?.coerceIn(
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
                RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION
            ) ?: defaultValue
            else -> defaultValue
        }
    }

    fun assembleContent(
        main: String?,
        translation: String?,
        backing: String?,
        backingTranslation: String?,
        roma: String?,
        overlappingMain: String? = null,
        overlappingTranslation: String? = null,
        overlappingBacking: String? = null,
        overlappingBackingTranslation: String? = null,
        next: String? = null,
        showNext: Boolean = false,
        mainAlignedRight: Boolean = false,
        backingAlignedRight: Boolean = mainAlignedRight,
        overlappingAlignedRight: Boolean = mainAlignedRight,
        overlappingBackingAlignedRight: Boolean = overlappingAlignedRight,
        mainGroupVocals: Boolean = false,
        nextAlignedRight: Boolean = false,
        nextGroupVocals: Boolean = false,
        duetLyrics: Boolean = RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        translationDisplayMode: Int = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE,
        translationFallback: Boolean = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
    ): AodLyricContent {
        val normalizedMain = main.normalized()
        val rawTranslation = translation.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val rawBacking = backing.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val rawBackingTranslation = backingTranslation.normalized()
            .takeIf { rawBacking.isNotBlank() && it != rawBacking }
            .orEmpty()
        val rawOverlappingMain = overlappingMain.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()
        val rawOverlappingTranslation = overlappingTranslation.normalized()
            .takeIf {
                rawOverlappingMain.isNotBlank() &&
                    it != rawOverlappingMain
            }
            .orEmpty()
        val rawOverlappingBacking = overlappingBacking.normalized()
            .takeIf {
                rawOverlappingMain.isNotBlank() &&
                    it != rawOverlappingMain
            }
            .orEmpty()
        val rawOverlappingBackingTranslation = overlappingBackingTranslation.normalized()
            .takeIf {
                rawOverlappingBacking.isNotBlank() &&
                    it != rawOverlappingBacking
            }
            .orEmpty()
        val rawRoma = roma.normalized()
            .takeUnless { it == normalizedMain }
            .orEmpty()

        var finalTranslation = ""
        var finalBackingTranslation = ""
        var finalOverlappingTranslation = ""
        var finalOverlappingBackingTranslation = ""

        when (translationDisplayMode) {
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION -> {
                finalTranslation = rawTranslation
                finalBackingTranslation = rawBackingTranslation
                finalOverlappingTranslation = rawOverlappingTranslation
                finalOverlappingBackingTranslation = rawOverlappingBackingTranslation
                if (finalTranslation.isBlank() && translationFallback && rawRoma.isNotBlank() && rawBacking.isBlank()) {
                    finalTranslation = rawRoma
                }
            }
            RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION -> {
                finalTranslation = if (rawRoma.isNotBlank() && rawBacking.isBlank()) rawRoma else ""
                if (finalTranslation.isBlank() && translationFallback) {
                    finalTranslation = rawTranslation
                    finalBackingTranslation = rawBackingTranslation
                    finalOverlappingTranslation = rawOverlappingTranslation
                    finalOverlappingBackingTranslation = rawOverlappingBackingTranslation
                }
            }
        }

        val hasDisplayedTranslation = finalTranslation.isNotBlank() ||
            finalBackingTranslation.isNotBlank()
        val normalizedNext = next.normalized()
            .takeIf {
                showNext &&
                    !hasDisplayedTranslation &&
                    it != normalizedMain
            }
            .orEmpty()
        return AodLyricContent(
            main = normalizedMain,
            translation = finalTranslation,
            backing = rawBacking,
            backingTranslation = finalBackingTranslation,
            overlappingMain = rawOverlappingMain,
            overlappingTranslation = finalOverlappingTranslation,
            overlappingBacking = rawOverlappingBacking,
            overlappingBackingTranslation = finalOverlappingBackingTranslation,
            next = normalizedNext,
            mainAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = mainAlignedRight,
                groupVocals = mainGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
            backingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = backingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            overlappingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = overlappingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            overlappingBackingAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = overlappingBackingAlignedRight,
                groupVocals = false,
                centerGroupVocals = centerGroupVocals,
            ),
            nextAlignment = lyricAlignment(
                duetLyrics = duetLyrics,
                centerNonDuetSong = centerNonDuetSong,
                alignedRight = nextAlignedRight,
                groupVocals = nextGroupVocals,
                centerGroupVocals = centerGroupVocals,
            ),
        )
    }

    fun assembleContent(
        main: String?,
        translation: String?,
        backing: String?,
        backingTranslation: String?,
        roma: String?,
        overlappingMain: String? = null,
        overlappingTranslation: String? = null,
        overlappingBacking: String? = null,
        overlappingBackingTranslation: String? = null,
        next: String? = null,
        showNext: Boolean = false,
        mainAlignedRight: Boolean = false,
        backingAlignedRight: Boolean = mainAlignedRight,
        overlappingAlignedRight: Boolean = mainAlignedRight,
        overlappingBackingAlignedRight: Boolean = overlappingAlignedRight,
        mainGroupVocals: Boolean = false,
        nextAlignedRight: Boolean = false,
        nextGroupVocals: Boolean = false,
        duetLyrics: Boolean = RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        translationDisplay: Boolean,
    ): AodLyricContent = assembleContent(
        main = main,
        translation = translation,
        backing = backing,
        backingTranslation = backingTranslation,
        roma = roma,
        overlappingMain = overlappingMain,
        overlappingTranslation = overlappingTranslation,
        overlappingBacking = overlappingBacking,
        overlappingBackingTranslation = overlappingBackingTranslation,
        next = next,
        showNext = showNext,
        mainAlignedRight = mainAlignedRight,
        backingAlignedRight = backingAlignedRight,
        overlappingAlignedRight = overlappingAlignedRight,
        overlappingBackingAlignedRight = overlappingBackingAlignedRight,
        mainGroupVocals = mainGroupVocals,
        nextAlignedRight = nextAlignedRight,
        nextGroupVocals = nextGroupVocals,
        duetLyrics = duetLyrics,
        centerNonDuetSong = centerNonDuetSong,
        centerGroupVocals = centerGroupVocals,
        translationDisplayMode = if (translationDisplay) RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION
        else RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
        translationFallback = false,
    )

    fun orderedLyricRows(swapTranslation: Boolean): List<AodLyricRow> =
        if (swapTranslation) {
            listOf(
                AodLyricRow.TRANSLATION,
                AodLyricRow.MAIN,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.NEXT,
            )
        } else {
            listOf(
                AodLyricRow.MAIN,
                AodLyricRow.TRANSLATION,
                AodLyricRow.BACKING,
                AodLyricRow.BACKING_TRANSLATION,
                AodLyricRow.OVERLAPPING_MAIN,
                AodLyricRow.OVERLAPPING_TRANSLATION,
                AodLyricRow.OVERLAPPING_BACKING,
                AodLyricRow.OVERLAPPING_BACKING_TRANSLATION,
                AodLyricRow.NEXT,
            )
        }

    fun lyricAlignment(
        duetLyrics: Boolean,
        centerNonDuetSong: Boolean = RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        alignedRight: Boolean,
        groupVocals: Boolean,
        centerGroupVocals: Boolean,
    ): AodLyricAlignment = when {
        !duetLyrics -> AodLyricAlignment.CENTER
        centerNonDuetSong -> AodLyricAlignment.CENTER
        groupVocals && centerGroupVocals -> AodLyricAlignment.CENTER
        alignedRight -> AodLyricAlignment.RIGHT
        else -> AodLyricAlignment.LEFT
    }

    fun sanitizeTextSize(value: Int, defaultValue: Int, min: Int, max: Int): Int =
        value.takeIf { it in min..max } ?: defaultValue

    /**
     * 归一化「歌词总行数上限」：钳制到 [MIN_LYRIC_MAX_LINES, MAX_LYRIC_MAX_LINES]，
     * 非法或越界值回落到默认。偏好里可能是 Int / Long / Float / String（跨进程同步会丢类型），这里统一兜底。
     */
    fun sanitizeLyricAreaHeight(value: Any?): Int = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }?.coerceIn(
        RootConstants.MIN_LYRIC_MAX_LINES,
        RootConstants.MAX_LYRIC_MAX_LINES
    ) ?: RootConstants.DEFAULT_HOOK_LYRIC_MAX_LINES

    /**
     * 在总行数上限 [maxLines] 下，后续歌词还能占几条。
     *
     * 主句最多折两行，先为它预留 [MAIN_LYRIC_LINES_RESERVED] 行，剩下的才分给后续歌词。
     */
    fun upcomingLineBudget(maxLines: Int): Int =
        (maxLines - MAIN_LYRIC_LINES_RESERVED).coerceAtLeast(0)

    /**
     * 单行歌词（主句 / 翻译）允许的最大折行数：主句最多两行，
     * 同时不能超过总行数上限，避免长句无限换行把卡片撑爆。
     */
    fun lyricRowMaxLines(maxLines: Int): Int =
        minOf(MAIN_LYRIC_LINES_RESERVED, maxLines)

    fun sanitizeNextLyricStyle(value: Int): Int = value.takeIf {
        it == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING ||
            it == RootConstants.AOD_NEXT_LYRIC_STYLE_TRANSLATION
    } ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE

    private fun String?.normalized(): String = this?.trim().orEmpty()
}

object NotificationMediaAodLyricHooker {
    private const val TAG = "NotificationMediaAodLyricHooker"
    private const val VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    private const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    private const val MEDIA_HEADER_VIEW_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
    private const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    private const val DOZE_SERVICE_HOST_CLASS =
        "com.android.systemui.statusbar.phone.DozeServiceHost"
    // 原始 DEX 锁定的双 $ 脱糖类名（DozeUi$$ExternalSyntheticLambda0）；
    // 单 $ 的 jadx 风格别名是错误标识，回归测试锁定全名。
    internal const val DOZE_TICK_RUNNABLE_CLASS =
        "com.android.systemui.doze.DozeUi\$\$ExternalSyntheticLambda0"
    private const val AOD_PLUGIN_VIEW_CLASS = "com.miui.aod.AODView"
    private const val OVERLAY_TAG = "hyperlyrics_aod_media_lyrics"
    private const val AOD_PLUGIN_OVERLAY_TAG = "hyperlyrics_aod_notification_lyrics"
    private const val POSITION_POLL_INTERVAL_MS = 100L
    private const val NO_LYRIC_PREVIEW_REFRESH_INTERVAL_MS = 500L
    private const val DRAW_WAKE_LOCK_TIMEOUT_MS = 1_000L
    private const val AOD_PLUGIN_GAP_DP = 14f
    private const val AOD_PLUGIN_SIDE_MARGIN_DP = 24f
    private const val AOD_PLUGIN_MAX_WIDTH_DP = 360f
    private const val AOD_PLUGIN_BOTTOM_SAFE_DP = 24f
    private const val AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP = 2f
    private const val AOD_PLUGIN_MULTI_LINE_LYRIC_GAP_DP = 10f
    private const val AOD_PLUGIN_SONG_INFO_ICON_DP = 18f
    private const val AOD_PLUGIN_SONG_INFO_ICON_GAP_DP = 6f
    private val AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS = longArrayOf(0L, 100L, 300L, 700L, 1_500L)
    private val LOCK_SCREEN_LYRICS_INITIAL_REFRESH_DELAYS_MS =
        longArrayOf(0L, 150L, 400L, 900L, 1_800L)
    private const val LOCK_SCREEN_AOD_LINE_GAP_DP = 4f
    private const val LOCK_SCREEN_AOD_GROUP_GAP_DP = 8f
    private const val LOCK_SCREEN_AOD_TOP_GAP_DP = 17f
    // 紧凑模式（亮屏锁屏/通知中心）：歌词区与按钮行/进度条行的最小间距
    private const val COMPACT_LYRIC_TOP_GAP_DP = 4f
    private const val SEEK_BAR_CLASS_HINT = "HyperProgressSeekBar"
    // 锁屏 AOD 自绘进度行：左右内边距、轨道高度、时间与轨道的间距
    private const val AOD_PROGRESS_H_PADDING_DP = 6f
    private const val AOD_PROGRESS_TRACK_HEIGHT_DP = 3f
    private const val AOD_PROGRESS_TRACK_GAP_DP = 8f
    private const val LOCK_SCREEN_AOD_SIDE_MARGIN_EXTRA_DP = 1f
    private const val LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS = 160L
    // Hidden PowerManager level that permits frame submission while the display is dozing.
    private const val DRAW_WAKE_LOCK_LEVEL = 0x80

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val hookedAodPluginClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val states = Collections.synchronizedMap(WeakHashMap<Any, ControllerState>())
    private val aodPluginStates = Collections.synchronizedMap(
        WeakHashMap<Any, AodPluginState>()
    )
    private val nativeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, NativeApi>())
    private val aodPluginApis = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, AodPluginApi>()
    )
    private val dozeRefreshApis = Collections.synchronizedMap(
        WeakHashMap<ClassLoader, DozeRefreshApi>()
    )
    private var positionPollScheduled = false
    private var lastNoLyricPreviewRefreshAt = 0L
    private val positionPollRunnable = object : Runnable {
        override fun run() {
            positionPollScheduled = false
            val controllerEntries = synchronized(states) { states.entries.toList() }
            val keepPolling = controllerEntries.any { shouldPollPosition(it.value) } ||
                hasActiveAodPluginState()
            if (keepPolling) {
                val mediaPosition = controllerEntries.firstNotNullOfOrNull { (controller, state) ->
                    if (!state.playing) return@firstNotNullOfOrNull null
                    resolveApi(controller.javaClass.classLoader)
                        ?.currentPlaybackPosition(controller)
                }
                val position = LyriconDataBridge.estimatedPosition() ?: mediaPosition
                if (position != null) {
                    val lyricChanged = LyriconDataBridge.updateEstimatedPosition(position)
                    // 全屏 AOD 进度行：每个轮询节拍轻量刷新时间与轨道，不触发整卡重排。
                    updateFullScreenAodProgressRows()
                    val refreshNoLyricPreview = shouldRefreshNoLyricPreview(position)
                    if (lyricChanged || refreshNoLyricPreview) {
                        controllerEntries.forEach { (controller, state) ->
                            if (state.fullAod || state.lockScreenLyricsActive ||
                                state.notificationCenterLyricsActive
                            ) {
                                safeApply(controller, state)
                            }
                        }
                        refreshAodPluginStates()
                    }
                }
            }
            if (keepPolling) schedulePositionPoll()
        }
    }

    @Volatile
    private var module: XposedModule? = null

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun initialize(xposedModule: XposedModule) {
        module = xposedModule
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        if (!hookedClassLoaders.add(classLoader)) return

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过息屏歌词 Hook: reason=native_api_unavailable")
            return
        }
        val handles = mutableListOf<HookHandle>()
        var controllerHookCount = 0
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                handles += xposedModule.hook(method).intercept(hooker)
                controllerHookCount++
            }.onFailure {
                HookLogger.e(TAG, "安装息屏歌词 Hook 失败: method=${method.name}", it)
            }
        }
        var dozeConstructorHookCount = 0
        val dozeRefreshApi = runCatching { DozeRefreshApi.create(classLoader) }
            .onFailure { HookLogger.e(TAG, "初始化 AOD 原生刷新接口失败", it) }
            .getOrNull()
        dozeRefreshApi?.let { refreshApi ->
            refreshApi.hostConstructors.forEach { constructor ->
                runCatching {
                    xposedModule.deoptimize(constructor)
                    handles += xposedModule.hook(constructor)
                        .intercept(DozeHostConstructorHook(refreshApi))
                    dozeConstructorHookCount++
                }.onFailure {
                    HookLogger.e(TAG, "安装 DozeServiceHost 捕获 Hook 失败", it)
                }
            }
            dozeRefreshApis[classLoader] = refreshApi
        }
        var headerVisibilityHookCount = 0
        runCatching { classLoader.loadClass(MEDIA_HEADER_VIEW_CLASS) }
            .onSuccess { headerClass ->
                runCatching {
                    val setVisibility = headerClass.getDeclaredMethod(
                        "setVisibility",
                        Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                    xposedModule.deoptimize(setVisibility)
                    handles += xposedModule.hook(setVisibility)
                        .intercept(HeaderVisibilityHook())
                    headerVisibilityHookCount++
                }.onFailure {
                    HookLogger.w(TAG, "安装锁屏媒体头可见性 Hook 失败", it)
                }
            }
        if (controllerHookCount != api.hookMethods.size) {
            handles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            dozeRefreshApis.remove(classLoader)
            HookLogger.w(TAG, "息屏歌词 Hook 安装不完整")
        } else {
            HookLogger.i(
                TAG,
                "息屏歌词 Hook 已初始化: methods=$controllerHookCount, " +
                    "dozeConstructors=$dozeConstructorHookCount, " +
                    "headerVisibility=$headerVisibilityHookCount"
            )
        }
    }

    fun hookAodPlugin(xposedModule: XposedModule, classLoader: ClassLoader) {
        initialize(xposedModule)
        val api = runCatching { AodPluginApi.create(classLoader) }.getOrNull() ?: return
        if (!hookedAodPluginClassLoaders.add(classLoader)) return

        var installedCount = 0
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No AOD plugin hooker for ${method.name}")
                xposedModule.hook(method).intercept(hooker)
                installedCount++
            }.onFailure {
                HookLogger.e(TAG, "安装通知图标式息屏歌词 Hook 失败: method=${method.name}", it)
            }
        }
        if (installedCount == api.hookMethods.size) {
            aodPluginApis[classLoader] = api
            HookLogger.i(TAG, "通知图标式息屏歌词 Hook 已初始化: methods=$installedCount")
        } else {
            hookedAodPluginClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "通知图标式息屏歌词 Hook 安装不完整")
        }
    }

    fun refresh() = runOnMain {
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "refresh_begin",
            details = "controllers=${synchronized(states) { states.size }},aodViews=${synchronized(aodPluginStates) { aodPluginStates.size }}",
        )
        synchronized(states) { states.entries.toList() }.forEach { (controller, state) ->
            runCatching { applyState(controller, state) }
                .onFailure { HookLogger.e(TAG, "刷新息屏歌词失败", it) }
        }
        refreshAodPluginStates()
        updatePositionPolling()
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "refresh_complete",
        )
    }

    fun onLyricChanged() {
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "lyric_changed_refresh_requested",
            details = "controllers=${synchronized(states) { states.size }},aodViews=${synchronized(aodPluginStates) { aodPluginStates.size }}",
        )
        refresh()
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> when (method.name) {
                "attach", "bindMediaData" -> method.parameterCount == 1
                "detach" -> method.parameterCount == 0
                "onFullAodStateChanged" -> method.parameterCount == 1
                else -> false
            }
            AOD_PLUGIN_VIEW_CLASS -> when (method.name) {
                "makeNormalPanel", "onAttachedToWindow", "onDetachedFromWindow",
                "onUpdatePositionTimer" -> method.parameterCount == 0
                "onAodContentLayoutChange" -> method.parameterCount == 3
                else -> false
            }
            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> ControllerHook(method.name)
            AOD_PLUGIN_VIEW_CLASS -> AodPluginHook(method.name)
            else -> null
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) = runOnMain {
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "playback_state_refresh_begin",
            details = "isPlaying=$isPlaying,controllers=${synchronized(states) { states.size }},aodViews=${synchronized(aodPluginStates) { aodPluginStates.size }}",
        )
        val lyricPackage = LyriconDataBridge.currentLyricPackageName
        synchronized(states) { states.entries.toList() }.forEach { (controller, state) ->
            val api = resolveApi(controller.javaClass.classLoader) ?: return@forEach
            val mediaPackage = api.packageName(state.mediaData ?: api.getMediaData(controller))
            if (lyricPackage.isNullOrBlank() || mediaPackage.isNullOrBlank() || mediaPackage == lyricPackage) {
                state.playing = isPlaying
                safeApply(controller, state)
            }
        }
        synchronized(aodPluginStates) { aodPluginStates.entries.toList() }
            .forEach { (aodView, state) ->
                state.playing = isPlaying
                safeApplyAodPlugin(aodView, state)
            }
        updatePositionPolling()
        MediaCardDiagnosticLogger.log(
            stage = "aod",
            event = "playback_state_refresh_complete",
            details = "isPlaying=$isPlaying",
        )
    }

    fun releaseAll() = runOnMain {
        synchronized(states) { states.entries.toList() }.forEach { (_, state) ->
            restoreActions(state)
            removeOverlay(state)
        }
        states.clear()
        synchronized(aodPluginStates) { aodPluginStates.values.toList() }.forEach {
            removeAodPluginOverlay(it)
        }
        aodPluginStates.clear()
        nativeApis.clear()
        aodPluginApis.clear()
        dozeRefreshApis.clear()
        mainHandler.removeCallbacks(positionPollRunnable)
        positionPollScheduled = false
    }

    fun hideLockScreenOverlays() = runOnMain {
        synchronized(states) { states.values.toList() }.forEach { state ->
            val overlay = state.overlay ?: return@forEach
            if (overlay.root.isShown) {
                overlay.root.visibility = View.GONE
                HookLogger.i(TAG, "亮屏事件已隐藏锁屏 AOD 歌词覆盖层")
            }
        }
    }

    /**
     * 亮屏回调：启用「锁屏歌词」时在锁屏界面上继续显示歌词
     * （复用锁屏 AOD 覆盖层与布局逻辑），否则按原有行为隐藏覆盖层。
     */
    fun onScreenInteractive() {
        if (!isLockScreenLyricsEnabled()) {
            hideLockScreenOverlays()
            return
        }
        HookLogger.i(TAG, "亮屏且启用锁屏歌词，尝试在锁屏媒体卡片下方显示歌词")
        // 亮屏瞬间锁屏媒体卡片可能尚未完成布局，分多次延迟刷新等待 player 就绪。
        LOCK_SCREEN_LYRICS_INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
            mainHandler.postDelayed({ refresh() }, delay)
        }
    }

    /** 灭屏回调：启用锁屏歌词时立即按息屏策略重新评估，避免覆盖层残留。 */
    fun onScreenNonInteractive() {
        if (isLockScreenLyricsEnabled()) {
            refresh()
        }
    }

    private class ControllerHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            val api = resolveApi(controller.javaClass.classLoader) ?: return chain.proceed()
            val state = states.getOrPut(controller) { ControllerState() }
            MediaCardDiagnosticLogger.log(
                stage = "aod_media_controller",
                event = "callback_begin",
                details = "method=$methodName,controller=${MediaCardDiagnosticLogger.identity(controller)},state=${MediaCardDiagnosticLogger.identity(state)},arg0=${MediaCardDiagnosticLogger.identity(chain.args.firstOrNull())},fullAod=${state.fullAod},playing=${state.playing}",
            )

            if (methodName == "bindMediaData" || methodName == "detach") {
                restoreActions(state)
                if (methodName == "detach") removeOverlay(state)
            }

            val result = chain.proceed()
            when (methodName) {
                "attach" -> {
                    state.holder = chain.args.firstOrNull() ?: api.getHolder(controller)
                    state.mediaData = api.getMediaData(controller)
                    state.playing = resolvePlaying(api, controller, state.mediaData)
                    safeApply(controller, state)
                }
                "bindMediaData" -> {
                    state.holder = api.getHolder(controller)
                    state.mediaData = chain.args.firstOrNull() ?: api.getMediaData(controller)
                    state.playing = resolvePlaying(api, controller, state.mediaData)
                    safeApply(controller, state)
                    if (state.fullAod) {
                        (state.holder as? View)?.context?.let {
                            ClassicAodFocusNotificationRecovery.requestAppRefresh(
                                it,
                                "media_data_bound",
                            )
                        }
                    }
                }
                "onFullAodStateChanged" -> {
                    state.fullAod = chain.args.firstOrNull() == true
                    state.holder = api.getHolder(controller)
                    state.mediaData = api.getMediaData(controller)
                    state.playing = resolvePlaying(api, controller, state.mediaData)
                    HookLogger.i(
                        TAG,
                        "全屏息屏状态: active=${state.fullAod}, playing=${state.playing}, " +
                            "modulePlaying=${LyriconDataBridge.currentPlaybackState}"
                    )
                    safeApply(controller, state)
                    if (state.fullAod) {
                        (state.holder as? View)?.context?.let {
                            ClassicAodFocusNotificationRecovery.requestAppRefresh(
                                it,
                                "full_aod_started",
                            )
                        }
                    }
                }
                "detach" -> {
                    states.remove(controller)
                    DisplayDiagnosticLogger.clear("AOD_LOCK")
                }
            }
            updatePositionPolling()
            return result
        }
    }

    private class HeaderVisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val header = chain.thisObject as? View ?: return chain.proceed()
            val newVisibility = (chain.args.firstOrNull() as? Int) ?: View.GONE
            val becomesVisible = newVisibility == View.VISIBLE
            val result = chain.proceed()
            runOnMain {
                var hiddenCount = 0
                synchronized(states) { states.values.toList() }.forEach { state ->
                    val overlay = state.overlay ?: return@forEach
                    if (BuildConfig.DEBUG) {
                        HookLogger.i(
                            TAG,
                            "AOD_HEADER_VIS header=0x" +
                                System.identityHashCode(header).toString(16) +
                                ", new=$newVisibility, shown=${overlay.root.isShown}, " +
                                "matched=${
                                    overlay.headerHeightController?.view === header
                                }, overlayHeader=0x${
                                    overlay.headerHeightController?.view
                                        ?.let { System.identityHashCode(it).toString(16) }
                                        ?: "null"
                                }"
                        )
                    }
                    val lyricsKept = becomesVisible &&
                        isMediaCardLyricsKept(overlay.root.context)
                    if (lyricsKept) {
                        // 媒体头恢复可见（如下拉通知中心）：延迟多次刷新等待布局稳定后显示歌词。
                        LOCK_SCREEN_LYRICS_INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
                            mainHandler.postDelayed({ refresh() }, delay)
                        }
                        return@forEach
                    }
                    if (becomesVisible && overlay.root.isShown) {
                            overlay.root.visibility = View.GONE
                        hiddenCount++
                    }
                }
                if (becomesVisible && hiddenCount > 0) {
                    HookLogger.i(
                        TAG,
                        "锁屏媒体头恢复可见，已立即隐藏 $hiddenCount 个 AOD 歌词覆盖层",
                    )
                }
            }
            return result
        }
    }

    private class AodPluginHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val aodView = chain.thisObject ?: return chain.proceed()
            if (methodName == "onDetachedFromWindow") {
                aodPluginStates.remove(aodView)?.let(::removeAodPluginOverlay)
                DisplayDiagnosticLogger.clear("AOD_CLASSIC")
                updatePositionPolling()
                return chain.proceed()
            }

            val result = chain.proceed()
            val state = aodPluginStates.getOrPut(aodView) { AodPluginState() }
            when (methodName) {
                "makeNormalPanel", "onAttachedToWindow" -> {
                    state.attached = (aodView as? View)?.isAttachedToWindow == true
                    state.playing = LyriconDataBridge.currentPlaybackState == true
                    safeApplyAodPlugin(aodView, state)
                    scheduleAodPluginInitialRefresh(aodView, state)
                    (aodView as? View)?.context?.let {
                        ClassicAodFocusNotificationRecovery.requestAppRefresh(
                            it,
                            "aod_view_attached",
                        )
                    }
                }
                "onAodContentLayoutChange", "onUpdatePositionTimer" -> {
                    state.overlay?.let(::positionAodPluginOverlay)
                    safeApplyAodPlugin(aodView, state)
                }
            }
            updatePositionPolling()
            return result
        }
    }

    private class DozeHostConstructorHook(
        private val refreshApi: DozeRefreshApi
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            chain.thisObject?.let(refreshApi::captureHost)
            return result
        }
    }

    @SuppressLint("UseKtx")
    private fun applyState(controller: Any, state: ControllerState) {
        val diagnosticKey = "AOD_LOCK/${System.identityHashCode(controller)}"
        val api = resolveApi(controller.javaClass.classLoader) ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_LOCK",
                "skipped",
                "hook_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        val holder = state.holder ?: api.getHolder(controller) ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_LOCK",
                "skipped",
                "holder_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        state.holder = holder
        state.playing = resolvePlaying(
            api,
            controller,
            state.mediaData ?: api.getMediaData(controller)
        )
        synchronizeLyricPosition(api, controller)
        val player = api.getPlayer(holder)
        val interactive = player.context.getSystemService(PowerManager::class.java).isInteractive
        val keyguardLocked = player.context
            .getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val lockScreenLyricsActive = AodMediaLyricPolicy.isLockScreenLyricsActive(
            interactive = interactive,
            keyguardLocked = keyguardLocked,
            playerShown = player.isShown,
            featureEnabled = isLockScreenLyricsEnabled(),
        )
        val notificationCenterActive = AodMediaLyricPolicy.isNotificationCenterLyricsActive(
            interactive = interactive,
            keyguardLocked = keyguardLocked,
            playerShown = player.isShown,
            featureEnabled = isNotificationCenterLyricsEnabled(),
        )
        state.aodActive = AodMediaLyricPolicy.isLockScreenAodActive(
            fullAod = state.fullAod,
            interactive = interactive,
            playerShown = player.isShown
        ) || lockScreenLyricsActive || notificationCenterActive
        state.lockScreenLyricsActive = lockScreenLyricsActive
        state.notificationCenterLyricsActive = notificationCenterActive
        // 各位置样式参数独立：息屏AOD / 亮屏锁屏歌词 / 通知中心分别读取各自偏好组
        val textStyle = when {
            state.fullAod -> lockScreenAodTextStyle()
            keyguardLocked -> lockScreenLyricsTextStyle()
            else -> notificationCenterTextStyle()
        }
        val lyricPackage = LyriconDataBridge.currentLyricPackageName
        val mediaPackage = api.packageName(state.mediaData ?: api.getMediaData(controller))
        val content = appendNextSongPreview(
            content = currentContent(textStyle),
            style = textStyle,
            context = player.context,
            packageName = mediaPackage,
        )
        val packageMatches = lyricPackage.isNullOrBlank() ||
            mediaPackage.isNullOrBlank() || lyricPackage == mediaPackage
        val hasLyric = content.main.isNotBlank() || content.next.isNotBlank()
        val enabled = isEnabled()
        val show = AodMediaLyricPolicy.shouldShow(
            enabled = enabled,
            fullAod = state.fullAod,
            playing = state.playing,
            hasLyric = hasLyric,
            packageMatches = packageMatches,
            pauseStyle = textStyle.pauseStyle,
            lockScreenLyrics = lockScreenLyricsActive,
            notificationCenter = notificationCenterActive,
        )
        val decisionReason = if (show) {
            "policy_passed"
        } else {
            when {
                !enabled && !lockScreenLyricsActive && !notificationCenterActive ->
                    "feature_disabled"
                !state.fullAod && !interactive -> "waiting_full_aod"
                interactive && !keyguardLocked && !state.fullAod && !notificationCenterActive ->
                    "keyguard_open"
                !state.aodActive && interactive -> "screen_interactive"
                !state.aodActive -> "player_hidden"
                !state.playing &&
                    textStyle.pauseStyle != RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS ->
                    "pause_policy"
                !hasLyric -> "no_lyrics"
                !packageMatches -> "package_mismatch"
                else -> "policy_rejected"
            }
        }
        AodEnvironmentDiagnostics.log(
            context = player.context,
            stage = "lockscreen_decision",
            modulePrefs = prefs,
            view = player,
            runtime = AodRuntimeDiagnosticState(
                surface = "lockscreen_media",
                fullAod = state.fullAod,
                playerShown = player.isShown,
                playing = state.playing,
                pauseStyle = textStyle.pauseStyle,
                pauseAllowed = state.playing ||
                    textStyle.pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
                hasContent = hasLyric,
                packageMatches = packageMatches,
                showDecision = show,
                decisionReason = decisionReason,
                overlayPresent = state.overlay != null,
                overlayShown = state.overlay?.root?.isShown,
            ),
            dedupeKey = diagnosticKey,
        )
        updatePositionPolling()

        if (!show) {
            val reason = decisionReason
            DisplayDiagnosticLogger.log(
                channel = "AOD_LOCK",
                result = "hidden",
                reason = reason,
                extra = "interactive=$interactive, playerShown=${player.isShown}, " +
                    "fullAod=${state.fullAod}, aodActive=${state.aodActive}, " +
                    "mediaPackage=${mediaPackage.orEmpty()}, overlay=${state.overlay != null}",
                dedupeKey = diagnosticKey,
            )
            restoreActions(state)
            state.overlay?.let { overlay ->
                overlay.root.visibility = View.GONE
                restorePlayerHeight(overlay, state.fullAod)
            }
            return
        }

        val actions = api.getActions(holder)
        // 原生三按钮（上一首/暂停/下一首）在歌词覆盖显示期间隐藏：交互改由歌词区
        // 手势承担（长按=播放/暂停，双击左半区=上一首，双击右半区=下一首）。
        // INVISIBLE 保持卡片几何不变；原始可见性记入 state，隐藏路径/detach 时恢复。
        if (state.actionVisibilities.isEmpty()) {
            actions.forEach { action ->
                runCatching {
                    state.actionVisibilities[action] = action.visibility
                    if (action.visibility != View.INVISIBLE) action.visibility = View.INVISIBLE
                }
            }
        }

        val overlay = state.overlay ?: createOverlay(api, holder, actions).also {
            state.overlay = it
        }
        if (overlay == null) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_LOCK",
                result = "skipped",
                reason = "overlay_unavailable",
                extra = "actions=${actions.size}",
                dedupeKey = diagnosticKey,
            )
            AodEnvironmentDiagnostics.log(
                context = player.context,
                stage = "lockscreen_overlay",
                modulePrefs = prefs,
                view = player,
                runtime = AodRuntimeDiagnosticState(
                    surface = "lockscreen_media",
                    fullAod = state.fullAod,
                    playerShown = player.isShown,
                    playing = state.playing,
                    pauseStyle = textStyle.pauseStyle,
                    hasContent = hasLyric,
                    packageMatches = packageMatches,
                    showDecision = false,
                    decisionReason = "overlay_unavailable",
                    overlayPresent = false,
                    overlayShown = false,
                ),
                dedupeKey = diagnosticKey,
            )
            restoreActions(state)
            return
        }
        val contentChanged = overlay.main.text.toString() != content.main ||
            overlay.translation.text.toString() != content.translation ||
            overlay.backing.text.toString() != content.backing ||
            overlay.backingTranslation.text.toString() != content.backingTranslation ||
            overlay.overlappingMain.text.toString() != content.overlappingMain ||
            overlay.overlappingTranslation.text.toString() != content.overlappingTranslation ||
            overlay.overlappingBacking.text.toString() != content.overlappingBacking ||
            overlay.overlappingBackingTranslation.text.toString() !=
                content.overlappingBackingTranslation ||
            overlay.next.text.toString() != content.next
        val styleChanged = overlay.appliedTextStyle != textStyle
        val alignmentChanged = overlay.appliedMainAlignment != content.mainAlignment ||
            overlay.appliedBackingAlignment != content.backingAlignment ||
            overlay.appliedOverlappingAlignment != content.overlappingAlignment ||
            overlay.appliedOverlappingBackingAlignment != content.overlappingBackingAlignment ||
            overlay.appliedNextAlignment != content.nextAlignment
        if (contentChanged || styleChanged || alignmentChanged) {
            overlay.drawWakeLock.acquire(DRAW_WAKE_LOCK_TIMEOUT_MS)
        }
        overlay.main.text = content.main
        setOptionalText(overlay.translation, content.translation)
        setOptionalText(overlay.backing, content.backing)
        setOptionalText(overlay.backingTranslation, content.backingTranslation)
        setOptionalText(overlay.overlappingMain, content.overlappingMain)
        setOptionalText(overlay.overlappingTranslation, content.overlappingTranslation)
        setOptionalText(overlay.overlappingBacking, content.overlappingBacking)
        setOptionalText(
            overlay.overlappingBackingTranslation,
            content.overlappingBackingTranslation
        )
        setOptionalText(overlay.next, content.next)
        // 当前句（主句/翻译/伴唱/重叠行）按总行数上限折行，单句最多占预留行数。
        val rowMaxLines = AodMediaLyricPolicy.lyricRowMaxLines(textStyle.lyricMaxLines)
        listOf(
            overlay.main,
            overlay.translation,
            overlay.backing,
            overlay.backingTranslation,
            overlay.overlappingMain,
            overlay.overlappingTranslation,
            overlay.overlappingBacking,
            overlay.overlappingBackingTranslation,
        ).forEach {
            it.setSingleLine(false)
            it.maxLines = rowMaxLines
            it.ellipsize = TextUtils.TruncateAt.END
            it.isSelected = false
        }
        // 后续歌词行也受总行数上限约束：超过预算的后续歌词在拼接阶段就被丢弃，
        // 这里再限制 maxLines，避免其中某条过长时继续换行撑爆预算。
        overlay.next.maxLines = AodMediaLyricPolicy
            .upcomingLineBudget(textStyle.lyricMaxLines)
            .coerceAtLeast(1)
        applyContentAlignment(overlay, content)
        applyLockScreenTextStyle(
            overlay = overlay,
            style = textStyle,
            mainTypefaceView = api.getTitleText(holder),
            translationTypefaceView = api.getArtistText(holder),
        )
        applyLyricRowOrder(overlay, textStyle.swapTranslation)
        updateLockScreenLineSpacing(overlay)
        overlay.main.setTextColor(api.getTitleText(holder).currentTextColor)
        overlay.backing.setTextColor(api.getTitleText(holder).currentTextColor)
        overlay.overlappingMain.setTextColor(api.getTitleText(holder).currentTextColor)
        overlay.overlappingBacking.setTextColor(api.getTitleText(holder).currentTextColor)
        val translationColor = api.getArtistText(holder).currentTextColor
        overlay.translation.setTextColor(translationColor)
        overlay.backingTranslation.setTextColor(translationColor)
        overlay.overlappingTranslation.setTextColor(translationColor)
        overlay.overlappingBackingTranslation.setTextColor(translationColor)
        overlay.next.setTextColor(
            if (textStyle.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING) {
                api.getTitleText(holder).currentTextColor
            } else {
                translationColor
            }
        )
        // 等待歌词匹配：用动态圆点占位（与摘要态一致），并启动循环高亮动画。
        if (content.waitingForLyrics) {
            overlay.waitingForLyrics = true
            val base = api.getTitleText(holder).currentTextColor
            overlay.main.text = buildWaitingDots(waitingDotsFrame, base, dimOf(base))
            ensureWaitingDotsTicker()
        } else {
            overlay.waitingForLyrics = false
        }
        // 紧凑模式排版基准文本：记录原始内容，后续按布局变化重复排版时
        // 不会把已合并的「歌词 翻译」串再次拼接。
        overlay.compactSourceMain = overlay.main.text.toString()
        overlay.compactSourceTranslation = overlay.translation.text.toString()
        // 全屏 AOD：展示自绘进度行（系统该模式无原生进度条）；
        // 紧凑模式由 applyCompactMode 隐藏，无时长数据时也不显示。
        val songDurationMs = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
        if (overlay.fullAodActive && songDurationMs != null) {
            overlay.progressRow.visibility = View.VISIBLE
            overlay.progressTimeLeft.setTextColor(translationColor)
            overlay.progressTimeRight.setTextColor(translationColor)
            overlay.progressTrack.fillColor = api.getTitleText(holder).currentTextColor
            overlay.progressTrack.trackColor = translationColor and 0x60FFFFFF
            updateProgressRow(overlay, LyriconDataBridge.currentPosition)
        } else {
            overlay.progressRow.visibility = View.GONE
        }
        // 字体颜色设置（莫奈取色/封面色/封面渐变色/自定义）非默认时覆盖系统跟随色。
        // 各位置独立：息屏AOD（fullAod）/锁屏歌词（亮屏+keyguard）/通知中心（亮屏未锁）分别取各自偏好组。
        val fontColorKeys = when {
            state.fullAod -> RootConstants.FONT_COLOR_KEYS_LOCK_SCREEN_AOD
            keyguardLocked -> RootConstants.FONT_COLOR_KEYS_LOCK_SCREEN_LYRICS
            else -> RootConstants.FONT_COLOR_KEYS_NOTIFICATION_CENTER
        }
        // 锁屏媒体卡片有封面视图：从 albumImage（真封面 ImageView）提取，
        // 提取失败再退回 albumView；共享给其他无封面视图的位置。
        val albumBitmap = OverlayFontColorApplier.bitmapFromAlbumView(api.getAlbumImage(holder))
            ?: OverlayFontColorApplier.bitmapFromAlbumView(api.getAlbumView(holder))
        if (albumBitmap != null) {
            CoverColorHelper.shareArtwork(albumBitmap)
        }
        OverlayFontColorApplier.apply(
            prefs = prefs,
            res = overlay.root.resources,
            keys = fontColorKeys,
            targets = listOf(
                overlay.main,
                overlay.backing,
                overlay.overlappingMain,
                overlay.overlappingBacking,
            ),
            secondaryTargets = listOf(
                overlay.translation,
                overlay.backingTranslation,
                overlay.overlappingTranslation,
                overlay.overlappingBackingTranslation,
                overlay.next,
            ),
            albumBitmap = albumBitmap ?: CoverColorHelper.currentArtwork(),
            mediaColorKey = CoverColorHelper.updateMediaSession(
                packageName = LyriconDataBridge.currentLyricPackageName.orEmpty(),
                title = api.getTitleText(holder).text?.toString().orEmpty(),
                artist = api.getArtistText(holder).text?.toString().orEmpty(),
                album = "",
                diagnosticSource = "media_card_overlay"
            ),
        )
        overlay.fullAodActive = state.fullAod
        // 亮屏场景（锁屏歌词/通知中心焦点通知）使用紧凑模式：不撑高卡片，
        // 在按钮与进度条之间的空白区域展示歌词与翻译。
        // 息屏 AOD / 自定义 AOD 保持原有全屏多行布局，不进入紧凑模式。
        val compactMode = interactive && !state.fullAod
        overlay.compactMode = compactMode
        applyCompactMode(overlay, compactMode, textStyle)
        if (overlay.root.visibility == View.GONE) {
            overlay.root.visibility = View.INVISIBLE
        }
        overlay.root.post {
            if (overlay.root.visibility == View.GONE) return@post
            if (overlay.root.visibility == View.INVISIBLE) {
                overlay.root.visibility = View.VISIBLE
                overlay.root.bringToFront()
                HookLogger.i(TAG, "锁屏 AOD 歌词已在原生控件隐藏完成后显示")
            }
            AodEnvironmentDiagnostics.log(
                context = player.context,
                stage = "lockscreen_overlay_visible",
                modulePrefs = prefs,
                view = overlay.root,
                runtime = AodRuntimeDiagnosticState(
                    surface = "lockscreen_media",
                    fullAod = state.fullAod,
                    playerShown = player.isShown,
                    playing = state.playing,
                    pauseStyle = textStyle.pauseStyle,
                    pauseAllowed = true,
                    hasContent = hasLyric,
                    packageMatches = packageMatches,
                    showDecision = true,
                    decisionReason = "overlay_visible",
                    overlayPresent = true,
                    overlayShown = overlay.root.isShown,
                ),
                dedupeKey = diagnosticKey,
            )
            if (compactMode) {
                // 紧凑模式：完全不改卡片几何（不加高/不下移/不 padding），
                // 歌词区覆盖显示在按钮与进度条之间的原生空隙内，自适应两行或单行合并。
                runCatching { restorePlayerHeight(overlay, false) }
                applyCompactOverlayLayout(overlay)
            } else {
                updateLockScreenCardHeight(
                    overlay,
                    forceRemeasure = contentChanged || styleChanged,
                )
            }
            DisplayDiagnosticLogger.log(
                channel = "AOD_LOCK",
                result = "shown",
                reason = "overlay_visible",
                extra = "interactive=$interactive, playerShown=${player.isShown}, " +
                    "fullAod=${state.fullAod}, mediaPackage=${mediaPackage.orEmpty()}, " +
                    "contentChanged=$contentChanged, styleChanged=$styleChanged",
                dedupeKey = diagnosticKey,
            )
        }
        if (contentChanged || styleChanged) {
            overlay.root.invalidate()
            (overlay.root.parent as? View)?.invalidate()
            requestAodFrameRefresh(controller.javaClass.classLoader)
        }
    }

    /**
     * 紧凑模式的延迟重排：卡片（唤醒到锁屏/通知中心展开）重新布局后，
     * 按钮行与进度条之间的空隙坐标会变化，需要在新一帧把歌词块摆回空隙内。
     * 用 post 延后到布局完成之后，并以 [LyricOverlay.compactLayoutScheduled] 去重，
     * 避免「排版 -> 触发布局 -> 再排版」自我循环。
     */
    private fun scheduleCompactOverlayLayout(overlay: LyricOverlay) {
        if (overlay.compactLayoutScheduled) return
        overlay.compactLayoutScheduled = true
        overlay.root.post {
            overlay.compactLayoutScheduled = false
            if (!overlay.compactMode || !overlay.root.isShown) return@post
            runCatching { applyCompactOverlayLayout(overlay) }
                .onFailure { HookLogger.w(TAG, "紧凑歌词重排失败: ${it.message}") }
        }
    }

    /** view 是否为 ancestor 的后代（跨父链判断，避免用到已脱离当前卡片的旧视图）。 */
    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var parent: Any? = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    /** 取 view 顶边在 player 坐标系中的 y；任一未挂载/未布局时返回 null。 */
    private fun viewTopInPlayer(overlay: LyricOverlay, view: View?): Int? {
        if (view == null || !view.isAttachedToWindow) return null
        if (!overlay.player.isAttachedToWindow) return null
        val viewLocation = IntArray(2)
        val playerLocation = IntArray(2)
        view.getLocationInWindow(viewLocation)
        overlay.player.getLocationInWindow(playerLocation)
        return viewLocation[1] - playerLocation[1]
    }

    /**
     * 紧凑模式布局：完全不改卡片几何（不加高/不下移进度条/不加 padding），
     * 歌词区覆盖显示在按钮行与进度条行之间的原生空隙内。
     * 主歌词与翻译始终分行展示，并各自按可用宽度最多折成两行。
     */
    private fun applyCompactOverlayLayout(overlay: LyricOverlay) {
        // 紧凑模式只服务于亮屏锁屏/通知中心焦点通知，息屏 AOD 不应进入此分支。
        if (overlay.fullAodActive) return

        val density = overlay.root.resources.displayMetrics.density
        val minGap = (COMPACT_LYRIC_TOP_GAP_DP * density).toInt()
        // 缓存的进度条实例可能已脱离当前 player（卡片重排/系统重新挂载），
        // 或创建覆盖层时该模式还没有进度条视图；这里重新在视图树里定位，
        // 并用窗口坐标换算成 player 坐标系，避免 seekBarTop 退化成卡片底部
        // 而把歌词块居中到进度条上。
        val seekBar = overlay.seekBar
            ?.takeIf { it.isAttachedToWindow && isDescendantOf(it, overlay.player) }
            ?: findSeekBarInTree(overlay.player)
        val seekBarTop = viewTopInPlayer(overlay, seekBar) ?: overlay.player.height
        if (seekBarTop <= 0) return
        // 按钮行在亮屏锁屏/通知中心中已被设为 INVISIBLE，仅保留几何占位；歌词允许覆盖该占位。
        val actionBottom = overlay.actions.maxOfOrNull { it.bottom }?.takeIf { it > 0 } ?: return

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            overlay.player.width.coerceAtLeast(1),
            View.MeasureSpec.EXACTLY
        )
        val mainText = overlay.compactSourceMain
        val translationText = overlay.compactSourceTranslation
        // 主歌词与翻译始终分行展示，不再合并成单行；超长时由 applyCompactMode 的
        // maxLines=2 + ellipsize=END 控制各自换行。
        if (overlay.main.text.toString() != mainText) overlay.main.text = mainText
        overlay.translation.visibility =
            if (translationText.isBlank()) View.GONE else View.VISIBLE

        // 息屏 AOD 会把根容器/歌词容器设成固定高度，进入紧凑模式前先恢复为内容高度，
        // 否则测量结果会沿用上一次 AOD 的固定高度。
        (overlay.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                overlay.root.layoutParams = params
            }
        }
        (overlay.lyricContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                overlay.lyricContainer.layoutParams = params
            }
        }

        overlay.root.measure(
            widthSpec,
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = overlay.root.measuredHeight
        if (contentHeight <= 0) return

        // 统一居中策略：歌词块在「可见歌曲信息下缘 ~ 进度条上缘」之间垂直居中。
        // 上边界只认可见的专辑图/标题/歌手下缘（按钮已 INVISIBLE，允许覆盖其几何占位）；
        // 歌词行同属一个纵向容器，折行/翻译只会把容器撑高，行与行不会重叠。
        val metadataBottom = maxOf(overlay.album.bottom, overlay.artist.bottom).coerceAtLeast(0)
        val referenceBottom = metadataBottom.takeIf { it > 0 } ?: actionBottom
        val bandTop = referenceBottom + minGap
        val bandBottom = (seekBarTop - minGap).coerceAtLeast(bandTop)
        val available = bandBottom - bandTop
        // 空间不足时不再把歌词顶到卡片顶部（会压住标题/专辑图），改为贴可见歌曲信息下缘排布，
        // 宁可让底部逼近进度条，也不遮挡歌曲信息。
        val top = if (contentHeight <= available) {
            bandTop + (available - contentHeight) / 2
        } else {
            bandTop
        }
        val topMargin = top - overlay.album.bottom
        (overlay.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.topMargin != topMargin ||
                params.height != ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                params.topMargin = topMargin
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                overlay.root.layoutParams = params
            }
        }
    }

    /** 在 player 视图树中定位进度条（HyperProgressSeekBar）。 */
    private fun findSeekBarInTree(root: ViewGroup): View? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var steps = 0
        while (queue.isNotEmpty() && steps < 64) {
            steps++
            val view = queue.removeFirst()
            if (view !== root && view.javaClass.simpleName.contains(SEEK_BAR_CLASS_HINT)) {
                return view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return null
    }

    /**
     * 紧凑模式切换：亮屏锁屏/通知中心场景下，
     * 主歌词与翻译各自独立展示，超长时自动换行（最多两行）；其余行隐藏。
     * 息屏 AOD 场景保持多行样式。
     */
    private fun applyCompactMode(
        overlay: LyricOverlay,
        compact: Boolean,
        style: AodTextStyleConfig,
    ) {
        val rowMaxLines = AodMediaLyricPolicy.lyricRowMaxLines(style.lyricMaxLines)
        fun config(view: TextView) {
            // 主/译分行展示，各自最多折两行；同时受总行数上限约束，
            // 长句自动换行后超出预算的部分以省略号截断，不会无限撑高卡片。
            view.setSingleLine(false)
            view.maxLines = rowMaxLines
            view.ellipsize = TextUtils.TruncateAt.END
            view.isSelected = false
        }
        config(overlay.main)
        config(overlay.translation)
        listOf(
            overlay.backing,
            overlay.backingTranslation,
            overlay.overlappingMain,
            overlay.overlappingTranslation,
            overlay.overlappingBacking,
            overlay.overlappingBackingTranslation,
        ).forEach { view ->
            if (compact) {
                view.visibility = View.GONE
            } else if (view.visibility == View.GONE && view.text.isNotBlank()) {
                view.visibility = View.VISIBLE
            }
        }
        // 后续歌词行（多行歌词）在紧凑模式同样放行，可见性只取决于有没有内容。
        if (overlay.next.text.isBlank()) {
            overlay.next.visibility = View.GONE
        } else if (overlay.next.visibility == View.GONE) {
            overlay.next.visibility = View.VISIBLE
        }
        // 进度行是 View 而非 TextView：紧凑模式一律隐藏，
        // 非紧凑（全屏 AOD）下由 applyState 按是否有时长数据决定可见性。
        overlay.progressRow.visibility = if (compact) View.GONE else overlay.progressRow.visibility
    }

    private fun refreshAodPluginStates() {
        synchronized(aodPluginStates) { aodPluginStates.entries.toList() }
            .forEach { (aodView, state) -> safeApplyAodPlugin(aodView, state) }
    }

    private fun safeApplyAodPlugin(aodView: Any, state: AodPluginState) {
        MediaCardDiagnosticLogger.log(
            stage = "aod_classic",
            event = "apply_begin",
            details = "aodView=${MediaCardDiagnosticLogger.identity(aodView)},state=${MediaCardDiagnosticLogger.identity(state)},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)},attached=${state.attached},playing=${state.playing}",
        )
        runCatching { applyAodPluginState(aodView, state) }
            .onSuccess {
                MediaCardDiagnosticLogger.log(
                    stage = "aod_classic",
                    event = "apply_complete",
                    details = "aodView=${MediaCardDiagnosticLogger.identity(aodView)},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)},attached=${state.attached},playing=${state.playing}",
                )
            }
            .onFailure {
                MediaCardDiagnosticLogger.log(
                    stage = "aod_classic",
                    event = "apply_failed",
                    reason = "exception",
                    details = "aodView=${MediaCardDiagnosticLogger.identity(aodView)},error=${MediaCardDiagnosticLogger.sanitize(it.message)}",
                )
                state.overlay?.root?.visibility = View.GONE
                HookLogger.e(TAG, "应用通知图标式息屏歌词失败", it)
            }
    }

    private fun applyAodPluginState(aodView: Any, state: AodPluginState) {
        val diagnosticKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}"
        val api = resolveAodPluginApi(aodView.javaClass.classLoader) ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_CLASSIC",
                "skipped",
                "hook_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        val view = aodView as? View ?: run {
            DisplayDiagnosticLogger.log(
                "AOD_CLASSIC",
                "skipped",
                "view_unavailable",
                dedupeKey = diagnosticKey,
            )
            return
        }
        state.attached = view.isAttachedToWindow
        state.playing = LyriconDataBridge.currentPlaybackState ?: state.playing
        synchronizeLyricPosition()
        val textStyle = classicAodTextStyle()
        val mediaPackage = LyriconDataBridge.currentLyricPackageName
            ?: LyriconDataBridge.activePackageName
        val content = appendNextSongPreview(
            content = currentContent(textStyle),
            style = textStyle,
            context = view.context,
            packageName = mediaPackage,
        )
        val songInfo = currentClassicAodEmbeddedSongInfo()
        val fullAodActive = synchronized(states) {
            states.values.any { controllerState ->
                controllerState.overlay?.root?.isShown == true &&
                    controllerState.aodActive
            }
        }
        val enabled = isEnabled()
        val viewShown = view.isShown
        val aodShown = api.isAodShown(aodView)
        val pauseAllowed = state.playing ||
            textStyle.pauseStyle == RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS
        // Overlapping/background-vocal rows are valid lyric content even when
        // the user disables “显示下一句歌词”. Do not let the single-line
        // layout policy hide a current line whose companion vocal is the only
        // additional rendered row.
        val hasContent = content.main.isNotBlank() ||
            content.next.isNotBlank() || songInfo.text.isNotBlank()
        val show = enabled &&
            state.attached &&
            viewShown &&
            aodShown &&
            pauseAllowed &&
            !fullAodActive &&
            hasContent
        val decisionReason = if (show) {
            "policy_passed"
        } else {
            when {
                !enabled -> "feature_disabled"
                !state.attached -> "view_detached"
                !viewShown -> "view_hidden"
                !aodShown -> "aod_panel_hidden"
                !pauseAllowed -> "pause_policy"
                fullAodActive -> "lockscreen_aod_active"
                !hasContent -> "no_lyrics_or_song_info"
                else -> "policy_rejected"
            }
        }
        AodEnvironmentDiagnostics.log(
            context = view.context,
            stage = "classic_decision",
            modulePrefs = prefs,
            view = view,
            runtime = AodRuntimeDiagnosticState(
                surface = "classic_aod_plugin",
                fullAod = fullAodActive,
                aodPanelShown = aodShown,
                playing = state.playing,
                pauseStyle = textStyle.pauseStyle,
                pauseAllowed = pauseAllowed,
                hasContent = hasContent,
                showDecision = show,
                decisionReason = decisionReason,
                overlayPresent = state.overlay != null,
                overlayShown = state.overlay?.root?.isShown,
            ),
            dedupeKey = diagnosticKey,
        )

        if (!show) {
            val reason = decisionReason
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "hidden",
                reason = reason,
                extra = "attached=${state.attached}, viewShown=$viewShown, " +
                    "aodShown=$aodShown, fullAodActive=$fullAodActive, " +
                    "overlay=${state.overlay != null}",
                dedupeKey = diagnosticKey,
            )
            state.overlay?.root?.visibility = View.GONE
            return
        }

        val overlay = state.overlay ?: createAodPluginOverlay(api, aodView)?.also {
            state.overlay = it
        } ?: return
        val contentChanged = overlay.main.text.toString() != content.main ||
            overlay.translation.text.toString() != content.translation ||
            overlay.backing.text.toString() != content.backing ||
            overlay.backingTranslation.text.toString() != content.backingTranslation ||
            overlay.overlappingMain.text.toString() != content.overlappingMain ||
            overlay.overlappingTranslation.text.toString() != content.overlappingTranslation ||
            overlay.overlappingBacking.text.toString() != content.overlappingBacking ||
            overlay.overlappingBackingTranslation.text.toString() !=
                content.overlappingBackingTranslation ||
            overlay.next.text.toString() != content.next ||
            overlay.songInfo.text.toString() != songInfo.text ||
            overlay.appliedSongInfoPackage != songInfo.sourcePackage ||
            overlay.appliedSongInfoTextSize != songInfo.textSize ||
            overlay.appliedSongInfoShowsIcon != songInfo.showIcon
        val styleChanged = overlay.appliedTextStyle != textStyle
        val alignmentChanged = overlay.appliedMainAlignment != content.mainAlignment ||
            overlay.appliedBackingAlignment != content.backingAlignment ||
            overlay.appliedOverlappingAlignment != content.overlappingAlignment ||
            overlay.appliedOverlappingBackingAlignment != content.overlappingBackingAlignment ||
            overlay.appliedNextAlignment != content.nextAlignment
        if (contentChanged || styleChanged || alignmentChanged) {
            overlay.drawWakeLock.acquire(DRAW_WAKE_LOCK_TIMEOUT_MS)
        }
        overlay.main.text = content.main
        setOptionalText(overlay.translation, content.translation)
        setOptionalText(overlay.backing, content.backing)
        setOptionalText(overlay.backingTranslation, content.backingTranslation)
        setOptionalText(overlay.overlappingMain, content.overlappingMain)
        setOptionalText(overlay.overlappingTranslation, content.overlappingTranslation)
        setOptionalText(overlay.overlappingBacking, content.overlappingBacking)
        setOptionalText(
            overlay.overlappingBackingTranslation,
            content.overlappingBackingTranslation
        )
        setOptionalText(overlay.next, content.next)
        // 当前句（主句/翻译/伴唱/重叠行）按总行数上限折行，单句最多占预留行数。
        val rowMaxLines = AodMediaLyricPolicy.lyricRowMaxLines(textStyle.lyricMaxLines)
        listOf(
            overlay.main,
            overlay.translation,
            overlay.backing,
            overlay.backingTranslation,
            overlay.overlappingMain,
            overlay.overlappingTranslation,
            overlay.overlappingBacking,
            overlay.overlappingBackingTranslation,
        ).forEach {
            it.setSingleLine(false)
            it.maxLines = rowMaxLines
            it.ellipsize = TextUtils.TruncateAt.END
            it.isSelected = false
        }
        // 后续歌词行也受总行数上限约束：超过预算的后续歌词在拼接阶段就被丢弃，
        // 这里再限制 maxLines，避免其中某条过长时继续换行撑爆预算。
        overlay.next.maxLines = AodMediaLyricPolicy
            .upcomingLineBudget(textStyle.lyricMaxLines)
            .coerceAtLeast(1)
        applyContentAlignment(overlay, content)
        applyClassicTextStyle(overlay, textStyle)
        applyLyricRowOrder(overlay, textStyle.swapTranslation)
        updateClassicEmbeddedSongInfo(overlay, songInfo)
        updateClassicLineSpacing(overlay)
        // 等待歌词匹配：用动态圆点占位（与摘要态一致），并启动循环高亮动画。
        if (content.waitingForLyrics) {
            overlay.waitingForLyrics = true
            val base = overlay.main.currentTextColor
            overlay.main.text = buildWaitingDots(waitingDotsFrame, base, dimOf(base))
            ensureWaitingDotsTicker()
        } else {
            overlay.waitingForLyrics = false
        }
        // 字体颜色设置（自定义AOD独立偏好组）非默认时覆盖系统跟随色
        OverlayFontColorApplier.apply(
            prefs = prefs,
            res = overlay.root.resources,
            keys = RootConstants.FONT_COLOR_KEYS_CLASSIC_AOD,
            targets = listOf(overlay.main, overlay.backing, overlay.overlappingMain, overlay.overlappingBacking),
            secondaryTargets = listOf(
                overlay.translation,
                overlay.backingTranslation,
                overlay.overlappingTranslation,
                overlay.overlappingBackingTranslation,
                overlay.next,
            ),
            albumBitmap = null,
            mediaColorKey = CoverColorHelper.currentMediaKey(),
        )
        overlay.root.visibility = View.VISIBLE
        overlay.root.bringToFront()
        positionAodPluginOverlay(overlay)
        AodEnvironmentDiagnostics.log(
            context = view.context,
            stage = "classic_overlay_visible",
            modulePrefs = prefs,
            view = overlay.root,
            runtime = AodRuntimeDiagnosticState(
                surface = "classic_aod_plugin",
                fullAod = fullAodActive,
                aodPanelShown = aodShown,
                playing = state.playing,
                pauseStyle = textStyle.pauseStyle,
                pauseAllowed = pauseAllowed,
                hasContent = hasContent,
                showDecision = true,
                decisionReason = "overlay_visible",
                overlayPresent = true,
                overlayShown = overlay.root.isShown,
            ),
            dedupeKey = diagnosticKey,
        )
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "shown",
            reason = "overlay_visible",
            extra = "attached=${state.attached}, viewShown=$viewShown, aodShown=$aodShown, " +
                "contentChanged=$contentChanged, styleChanged=$styleChanged, " +
                "songInfo=${songInfo.text.isNotBlank()}",
            dedupeKey = diagnosticKey,
        )
        if (contentChanged || styleChanged || alignmentChanged) {
            overlay.root.invalidate()
            overlay.parent.invalidate()
            requestAodFrameRefresh(aodView.javaClass.classLoader)
        }
    }

    @SuppressLint("UseKtx")
    private fun createAodPluginOverlay(
        api: AodPluginApi,
        aodView: Any
    ): AodPluginOverlay? {
        val diagnosticKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}/overlay"
        val aodRoot = aodView as? FrameLayout ?: run {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "skipped",
                reason = "root_not_frame_layout",
                extra = "rootClass=${aodView.javaClass.name}",
                dedupeKey = "$diagnosticKey/root",
            )
            return null
        }
        val anchor = api.getNotificationIcons(aodView) ?: run {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "skipped",
                reason = "notification_icons_null",
                extra = "rootClass=${aodRoot.javaClass.name}",
                dedupeKey = "$diagnosticKey/anchor_presence",
            )
            return null
        }
        if (!anchor.isAttachedToWindow) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "pending",
                reason = "anchor_detached",
                extra = "anchorClass=${anchor.javaClass.name}, " +
                    "width=${anchor.width}, height=${anchor.height}",
                dedupeKey = "$diagnosticKey/anchor_attachment",
            )
        }
        if (anchor.width <= 0 || anchor.height <= 0) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "pending",
                reason = "anchor_zero_size",
                extra = "attached=${anchor.isAttachedToWindow}, " +
                    "width=${anchor.width}, height=${anchor.height}",
                dedupeKey = "$diagnosticKey/anchor_size",
            )
        }
        val parentCandidate = api.getTableModeContainer(aodView)
        val movingContainer = parentCandidate as? FrameLayout
        if (movingContainer == null) {
            DisplayDiagnosticLogger.log(
                channel = "AOD_CLASSIC",
                result = "pending",
                reason = "parent_unavailable",
                extra = "candidateClass=${parentCandidate?.javaClass?.name ?: "null"}, " +
                    "usingRootFallback=true",
                dedupeKey = "$diagnosticKey/parent",
            )
        }
        val parent = movingContainer ?: aodRoot
        val context = aodRoot.context
        val main = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
        }
        val translation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val backing = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = main.typeface
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
        }
        val backingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingMain = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = main.typeface
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingBacking = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = main.typeface
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
        }
        val overlappingBackingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val next = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = translation.typeface
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val sourceIcon = ImageView(context).apply {
            visibility = View.GONE
        }
        val songInfo = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
        }
        val songInfoRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(
                sourceIcon,
                LinearLayout.LayoutParams(
                    (AOD_PLUGIN_SONG_INFO_ICON_DP * resources.displayMetrics.density).toInt(),
                    (AOD_PLUGIN_SONG_INFO_ICON_DP * resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (
                        AOD_PLUGIN_SONG_INFO_ICON_GAP_DP * resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                songInfo,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            tag = AOD_PLUGIN_OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val horizontalPadding = (8f * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                songInfoRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                main,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                translation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                backing,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                backingTranslation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingMain,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingTranslation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingBacking,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                overlappingBackingTranslation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
            addView(
                next,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (
                        AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP *
                            resources.displayMetrics.density
                    ).toInt()
                }
            )
        }
        parent.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        val drawWakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
            DRAW_WAKE_LOCK_LEVEL,
            "${context.packageName}:HyperLyricsAodDraw"
        ).apply {
            setReferenceCounted(false)
        }
        val overlay = AodPluginOverlay(
            root = root,
            songInfoRow = songInfoRow,
            sourceIcon = sourceIcon,
            songInfo = songInfo,
            main = main,
            translation = translation,
            backing = backing,
            backingTranslation = backingTranslation,
            overlappingMain = overlappingMain,
            overlappingTranslation = overlappingTranslation,
            overlappingBacking = overlappingBacking,
            overlappingBackingTranslation = overlappingBackingTranslation,
            next = next,
            parent = parent,
            aodRoot = aodRoot,
            anchor = anchor,
            drawWakeLock = drawWakeLock
        )
        main.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateClassicLineSpacing(overlay)
        }
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            positionAodPluginOverlay(overlay)
            true
        }
        overlay.preDrawListener = preDrawListener
        aodRoot.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        positionAodPluginOverlay(overlay)
        HookLogger.i(
            TAG,
            "通知图标式息屏歌词已挂载: parent=${parent.javaClass.name}, " +
                "anchor=${anchor.javaClass.name}"
        )
        return overlay
    }

    private fun updateClassicLineSpacing(overlay: AodPluginOverlay) {
        val mainParams = overlay.main.layoutParams as? LinearLayout.LayoutParams ?: return
        if (
            mainParams.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            mainParams.weight != 0f
        ) {
            mainParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            mainParams.weight = 0f
            overlay.main.layoutParams = mainParams
        }

        val compact = AodMediaLyricPolicy.shouldCompactClassicMain(overlay.main.lineCount)
        val gapDp = if (compact) {
            AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP
        } else {
            AOD_PLUGIN_MULTI_LINE_LYRIC_GAP_DP
        }
        val density = overlay.main.resources.displayMetrics.density
        val contentRows = orderedLyricViews(
            overlay,
            overlay.appliedTextStyle?.swapTranslation == true,
        )
        val visibleRows = contentRows.filter { it.visibility == View.VISIBLE }
        val firstRow = visibleRows.firstOrNull() ?: return
        val secondRow = visibleRows.getOrNull(1)
        val firstBackingRow = contentRows
            .filter { it === overlay.backing || it === overlay.backingTranslation }
            .firstOrNull { it.visibility == View.VISIBLE }
        val primaryVisibleCount = listOf(overlay.main, overlay.translation)
            .count { it.visibility == View.VISIBLE }
        val normalMargin = (AOD_PLUGIN_SINGLE_LINE_LYRIC_GAP_DP * density).toInt()
        val groupMargin = (8f * density).toInt()
        val firstMargin = (gapDp * density).toInt()
        contentRows.forEach { view ->
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            val targetMargin = when {
                view === firstRow -> 0
                view === secondRow && primaryVisibleCount > 0 -> firstMargin
                view === firstBackingRow && primaryVisibleCount > 1 -> groupMargin
                else -> normalMargin
            }
            if (params.topMargin != targetMargin) {
                params.topMargin = targetMargin
                view.layoutParams = params
            }
        }
    }

    private fun positionAodPluginOverlay(overlay: AodPluginOverlay) {
        if (!overlay.aodRoot.isAttachedToWindow || overlay.aodRoot.width <= 0) return
        val density = overlay.aodRoot.resources.displayMetrics.density
        val rootLocation = IntArray(2)
        val parentLocation = IntArray(2)
        val anchorLocation = IntArray(2)
        overlay.aodRoot.getLocationOnScreen(rootLocation)
        overlay.parent.getLocationOnScreen(parentLocation)
        overlay.anchor.getLocationOnScreen(anchorLocation)

        val sideMargin = (AOD_PLUGIN_SIDE_MARGIN_DP * density).toInt()
        val gap = (AOD_PLUGIN_GAP_DP * density).toInt()
        val bottomSafe = (AOD_PLUGIN_BOTTOM_SAFE_DP * density).toInt()
        val maxWidth = (AOD_PLUGIN_MAX_WIDTH_DP * density).toInt()
        val availableWidth = (overlay.aodRoot.width - sideMargin * 2).coerceAtLeast(1)
        val width = minOf(maxWidth, availableWidth)
        val anchorCenter = anchorLocation[0] + overlay.anchor.width / 2
        val rootLeft = rootLocation[0] + sideMargin
        val rootRight = rootLocation[0] + overlay.aodRoot.width - sideMargin
        val leftOnScreen = (anchorCenter - width / 2).coerceIn(
            rootLeft,
            (rootRight - width).coerceAtLeast(rootLeft)
        )
        val topOnScreen = anchorLocation[1] + overlay.anchor.height + gap
        val bottomLimit = rootLocation[1] + overlay.aodRoot.height - bottomSafe
        val availableHeight = (bottomLimit - topOnScreen).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        updateClassicSongInfoMaxWidth(overlay, width)
        overlay.root.measure(widthSpec, heightSpec)
        updateClassicLineSpacing(overlay)
        overlay.root.measure(widthSpec, heightSpec)
        val contentHeight = overlay.root.measuredHeight
        val height = AodMediaLyricPolicy.classicOverlayHeight(
            contentHeight = contentHeight,
            availableHeight = availableHeight
        )

        val params = overlay.root.layoutParams as FrameLayout.LayoutParams
        val leftMargin = leftOnScreen - parentLocation[0]
        // 自定义 AOD（时钟/日期浮层）保持原有定位：歌词紧贴锚点下缘显示，
        // 不做垂直居中——居中会把歌词推到屏幕中下部，与时钟样式差异过大。
        val topMargin = topOnScreen - parentLocation[1]
        if (
            params.width != width ||
            params.height != height ||
            params.leftMargin != leftMargin ||
            params.topMargin != topMargin
        ) {
            params.width = width
            params.height = height
            params.leftMargin = leftMargin
            params.topMargin = topMargin
            overlay.root.layoutParams = params
        }
        if (overlay.appliedHeight != height) {
            overlay.appliedHeight = height
            HookLogger.i(
                TAG,
                "自定义 AOD 歌词覆盖层已按内容实测高度调整: " +
                    "contentHeight=$contentHeight, availableHeight=$availableHeight, " +
                    "targetHeight=$height"
            )
        }
    }

    private fun createOverlay(
        api: NativeApi,
        holder: Any,
        actions: List<View>
    ): LyricOverlay? {
        val player = api.getPlayer(holder)
        if (actions.isEmpty()) return null
        val title = api.getTitleText(holder)
        val artist = api.getArtistText(holder)
        val album = api.getAlbumView(holder) ?: artist
        val context = player.context

        val main = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val translation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val backing = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val backingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val overlappingMain = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val overlappingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val overlappingBacking = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = title.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE.toFloat(),
            )
            setTextColor(title.currentTextColor)
        }
        val overlappingBackingTranslation = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val next = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        // 锁屏 AOD 专用的自绘进度行（紧凑模式下隐藏）：
        // 当前时间 ── 进度轨道 ── 总时长，颜色跟随原生标题/歌手文字。
        val progressDensity = context.resources.displayMetrics.density
        val progressTimeLeft = TextView(context).apply {
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val progressTrack = AodProgressTrackView(context).apply {
            fillColor = title.currentTextColor
            trackColor = artist.currentTextColor and 0x60FFFFFF
        }
        val progressTimeRight = TextView(context).apply {
            includeFontPadding = false
            typeface = artist.typeface
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE.toFloat(),
            )
            setTextColor(artist.currentTextColor)
        }
        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            visibility = View.GONE
            setPadding(
                (AOD_PROGRESS_H_PADDING_DP * progressDensity).toInt(),
                0,
                (AOD_PROGRESS_H_PADDING_DP * progressDensity).toInt(),
                0,
            )
            addView(
                progressTimeLeft,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                progressTrack,
                LinearLayout.LayoutParams(
                    0,
                    (AOD_PROGRESS_TRACK_HEIGHT_DP * progressDensity).toInt(),
                ).apply {
                    weight = 1f
                    marginStart = (AOD_PROGRESS_TRACK_GAP_DP * progressDensity).toInt()
                    marginEnd = (AOD_PROGRESS_TRACK_GAP_DP * progressDensity).toInt()
                },
            )
            addView(
                progressTimeRight,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // 歌词行单独放进 lyricContainer：全屏 AOD 时歌词容器在「歌曲信息下缘 ~ 进度条上缘」
        // 之间垂直居中，进度条独立贴卡片底部，避免多行歌词/翻译与进度条重叠。
        val lyricContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val density = resources.displayMetrics.density
            addView(main, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(translation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(backing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(backingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingMain, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingBacking, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(overlappingBackingTranslation, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
            addView(next, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
        }
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            tag = OVERLAY_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val density = resources.displayMetrics.density
            addView(lyricContainer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(progressRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
            })
        }

        val params = createConstraintLayoutParams(player, album) ?: return null
        val playerSize = captureViewSize(player)
        val backgroundView = api.getMediaBackground(holder) ?: player
        val backgroundSize = captureViewSize(backgroundView)
        val backgroundConstraints = BackgroundConstraints.create(backgroundView)
        val headerHeightController = MediaHeaderHeightController.create(player.parent as? View)
        val fullAodHeightId = context.resources.getIdentifier(
            "qs_media_session_height_expanded_fullAod",
            "dimen",
            context.packageName
        )
        if (fullAodHeightId != 0) {
            backgroundSize.baseHeight = context.resources.getDimensionPixelSize(fullAodHeightId)
        }
        player.addView(root, params)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val drawWakeLock = powerManager.newWakeLock(
            DRAW_WAKE_LOCK_LEVEL,
            "${context.packageName}:HyperLyricsAodDraw"
        ).apply {
            setReferenceCounted(false)
        }
        val overlay = LyricOverlay(
            root = root,
            lyricContainer = lyricContainer,
            main = main,
            translation = translation,
            backing = backing,
            backingTranslation = backingTranslation,
            overlappingMain = overlappingMain,
            overlappingTranslation = overlappingTranslation,
            overlappingBacking = overlappingBacking,
            overlappingBackingTranslation = overlappingBackingTranslation,
            next = next,
            progressRow = progressRow,
            progressTrack = progressTrack,
            progressTimeLeft = progressTimeLeft,
            progressTimeRight = progressTimeRight,
            artist = artist,
            album = album,
            seekBar = api.getSeekBar(holder)
                ?: player.let { findSeekBarInTree(it) },
            actions = actions,
            player = player,
            playerSize = playerSize,
            backgroundSize = backgroundSize,
            backgroundConstraints = backgroundConstraints,
            headerHeightController = headerHeightController,
            drawWakeLock = drawWakeLock
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateLockScreenCardHeight(overlay)
        }
        HookLogger.i(
            TAG,
            "锁屏 AOD 媒体卡片动态高度已挂载: player=${player.javaClass.name}, " +
                "playerBase=${playerSize.baseHeight}, " +
                "playerLayout=${playerSize.originalLayoutHeight}, " +
                "background=${backgroundSize.view.javaClass.name}, " +
                "backgroundBase=${backgroundSize.baseHeight}, " +
                "backgroundLayout=${backgroundSize.originalLayoutHeight}, " +
                "header=${headerHeightController?.view?.javaClass?.name.orEmpty()}, " +
                "headerBase=${headerHeightController?.originalHeight ?: 0}"
        )
        // 原生按钮已隐藏，歌词行接管播放控制手势。
        LyricGestureHelper.attach(overlay.main, overlay.translation)
        return overlay
    }

    /**
     * 锁屏 AOD 歌词区底部的自绘进度轨道：系统在该模式下不渲染原生进度条，
     * 由模块补一条跟随播放位置的圆角进度线（左侧已播/右侧未播）。
     */
    private class AodProgressTrackView(context: Context) : View(context) {
        var progress = 0f
            set(value) {
                val clamped = value.coerceIn(0f, 1f)
                if (field != clamped) {
                    field = clamped
                    invalidate()
                }
            }
        var fillColor = 0xFFFFFFFF.toInt()
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        var trackColor = 0x40FFFFFF
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            val height = height.toFloat()
            if (width <= 0f || height <= 0f) return
            val radius = height / 2f
            paint.color = trackColor
            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, paint)
            if (progress > 0f) {
                paint.color = fillColor
                canvas.drawRoundRect(0f, 0f, width * progress, height, radius, radius, paint)
            }
        }
    }

    private fun formatAodTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(
            java.util.Locale.US,
            "%d:%02d",
            totalSeconds / 60,
            totalSeconds % 60,
        )
    }

    /** 刷新单条锁屏 AOD 进度行的时间文本与轨道填充。 */
    private fun updateProgressRow(overlay: LyricOverlay, positionMs: Long) {
        val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L } ?: return
        val clamped = positionMs.coerceIn(0L, duration)
        overlay.progressTimeLeft.text = formatAodTime(clamped)
        overlay.progressTimeRight.text = formatAodTime(duration)
        overlay.progressTrack.progress = clamped.toFloat() / duration
    }

    /** 每个轮询节拍刷新所有可见的锁屏 AOD 进度行（轻量，不触发整卡重排）。 */
    private fun updateFullScreenAodProgressRows() {
        synchronized(states) { states.values.toList() }.forEach { state ->
            val overlay = state.overlay ?: return@forEach
            if (!state.fullAod || overlay.progressRow.visibility != View.VISIBLE) {
                return@forEach
            }
            runCatching { updateProgressRow(overlay, LyriconDataBridge.currentPosition) }
        }
    }

    private fun createConstraintLayoutParams(
        player: ViewGroup,
        metadataAnchor: View
    ): ViewGroup.LayoutParams? = runCatching {
        val loader = requireNotNull(player.javaClass.classLoader) {
            "ConstraintLayout class loader unavailable"
        }
        val paramsClass = loader.loadClass(
            "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
        )
        val params = paramsClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).newInstance(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ) as ViewGroup.LayoutParams
        paramsClass.getField("startToStart").setInt(params, 0)
        paramsClass.getField("endToEnd").setInt(params, 0)
        paramsClass.getField("topToBottom").setInt(params, metadataAnchor.id)
        (params as ViewGroup.MarginLayoutParams).topMargin = (
            LOCK_SCREEN_AOD_TOP_GAP_DP * player.resources.displayMetrics.density
            ).toInt()
        params
    }.onFailure {
        HookLogger.e(TAG, "创建息屏歌词布局参数失败", it)
    }.getOrNull()

    private fun restoreActions(state: ControllerState) {
        state.overlay?.actions?.forEach { view ->
            if (view.alpha != 1f) view.alpha = 1f
        }
        if (state.actionVisibilities.isEmpty()) return
        state.actionVisibilities.forEach { (view, visibility) -> view.visibility = visibility }
        state.actionVisibilities.clear()
    }

    private fun safeApply(controller: Any, state: ControllerState) {
        MediaCardDiagnosticLogger.log(
            stage = "aod_lockscreen_media",
            event = "apply_begin",
            details = "controller=${MediaCardDiagnosticLogger.identity(controller)},state=${MediaCardDiagnosticLogger.identity(state)},fullAod=${state.fullAod},aodActive=${state.aodActive},playing=${state.playing},holder=${MediaCardDiagnosticLogger.identity(state.holder)},mediaData=${MediaCardDiagnosticLogger.identity(state.mediaData)},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)}",
        )
        runCatching { applyState(controller, state) }
            .onSuccess {
                MediaCardDiagnosticLogger.log(
                    stage = "aod_lockscreen_media",
                    event = "apply_complete",
                    details = "controller=${MediaCardDiagnosticLogger.identity(controller)},fullAod=${state.fullAod},aodActive=${state.aodActive},playing=${state.playing},overlay=${MediaCardDiagnosticLogger.view(state.overlay?.root)}",
                )
            }
            .onFailure {
                MediaCardDiagnosticLogger.log(
                    stage = "aod_lockscreen_media",
                    event = "apply_failed",
                    reason = "exception",
                    details = "controller=${MediaCardDiagnosticLogger.identity(controller)},error=${MediaCardDiagnosticLogger.sanitize(it.message)}",
                )
                restoreActions(state)
                state.overlay?.let { overlay ->
                    overlay.root.visibility = View.GONE
                    restorePlayerHeight(overlay, state.fullAod)
                }
                HookLogger.e(TAG, "应用息屏歌词失败", it)
            }
    }

    private fun updateLockScreenCardHeight(
        overlay: LyricOverlay,
        forceRemeasure: Boolean = false
    ) {
        // 紧凑模式（亮屏锁屏/通知中心）不撑高卡片，避免与紧凑模式还原逻辑互相拉扯导致卡片闪烁；
        // 但卡片几何会随唤醒/展开变化（息屏 AOD 唤醒到锁屏后原生进度条才出现），
        // 必须按新几何重排歌词，否则歌词会停在旧空隙坐标上压住进度条。
        if (overlay.compactMode) {
            scheduleCompactOverlayLayout(overlay)
            return
        }
        if (!overlay.root.isShown) return
        if (updateLockScreenHorizontalMargins(overlay)) {
            overlay.root.post {
                updateLockScreenCardHeight(overlay, forceRemeasure = true)
            }
            return
        }
        // 全屏 AOD（息屏/锁屏 AOD）：歌词在「可见歌曲信息下缘 ~ 进度条上缘」之间居中，
        // 进度条贴卡片底部，由独立布局逻辑处理。
        if (overlay.fullAodActive) {
            updateFullAodCenteredLyricLayout(overlay)
            return
        }
        if (forceRemeasure) {
            val measuredWidth = overlay.root.width.takeIf { it > 0 }
                ?: overlay.root.measuredWidth
            if (measuredWidth > 0) {
                overlay.root.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        measuredWidth,
                        View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
        }
        if (overlay.root.measuredHeight <= 0) return
        // 非全屏 AOD 的兜底路径：同样忽略已 INVISIBLE 的按钮/进度条几何占位。
        val anchorBottom = AodMediaLyricPolicy.contentAnchorBottom(
            albumBottom = overlay.album.bottom,
            artistBottom = overlay.artist.bottom,
            actionBottom = overlay.actions.maxOfOrNull { it.bottom } ?: 0,
            seekBarBottom = overlay.seekBar?.bottom ?: 0,
            actionVisible = overlay.actions.any { it.visibility == View.VISIBLE },
            seekBarVisible = overlay.seekBar?.visibility == View.VISIBLE,
        )
        if (anchorBottom <= 0) return
        if (overlay.playerSize.baseHeight <= 0) {
            overlay.playerSize.baseHeight = overlay.player.height.takeIf { it > 0 }
                ?: overlay.player.measuredHeight
        }
        if (overlay.playerSize.baseHeight <= 0) return
        if (overlay.backgroundSize.baseHeight <= 0) {
            overlay.backgroundSize.baseHeight = overlay.backgroundSize.view.height.takeIf { it > 0 }
                ?: overlay.backgroundSize.view.measuredHeight
        }
        if (overlay.backgroundSize.baseHeight <= 0) return

        val density = overlay.root.resources.displayMetrics.density
        val nativeCardHeight = AodMediaLyricPolicy.lockScreenNativeCardHeight(
            fullAod = overlay.fullAodActive,
            fullAodBaseHeight = overlay.backgroundSize.baseHeight,
            playerBaseHeight = overlay.playerSize.baseHeight,
        )
        overlay.backgroundConstraints?.restore()

        // 与全屏 AOD 一致的居中策略：歌词块在「可见歌曲信息下缘 ~ 自绘进度条上缘」之间垂直居中，
        // 内容高于可用区间时向下撑高卡片，保证多行歌词/翻译不与进度条、歌曲信息重叠。
        val measuredWidth = (overlay.root.width.takeIf { it > 0 }
            ?: overlay.root.measuredWidth).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        overlay.lyricContainer.measure(widthSpec, heightSpec)
        val lyricContentHeight = overlay.lyricContainer.measuredHeight.coerceAtLeast(0)
        val progressVisible = overlay.progressRow.visibility == View.VISIBLE
        val progressRowHeight = if (progressVisible) {
            overlay.progressRow.measure(widthSpec, heightSpec)
            overlay.progressRow.measuredHeight.coerceAtLeast(0)
        } else {
            0
        }
        val progressRowTopMargin = if (progressVisible) {
            (overlay.progressRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: 0
        } else {
            0
        }
        val layout = AodMediaLyricPolicy.lockScreenCenteredLyricLayout(
            nativeCardHeight = nativeCardHeight,
            anchorBottom = anchorBottom,
            lyricContentHeight = lyricContentHeight,
            progressRowHeight = progressRowHeight,
            progressRowTopMargin = progressRowTopMargin,
            bottomGap = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt(),
            minTopGap = (COMPACT_LYRIC_TOP_GAP_DP * density).toInt(),
        )
        val params = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val targetTopMargin = layout.rootTop - overlay.album.bottom
        if (params.topMargin != targetTopMargin || params.height != layout.rootHeight) {
            params.topMargin = targetTopMargin
            params.height = layout.rootHeight
            overlay.root.layoutParams = params
        }
        (overlay.lyricContainer.layoutParams as? LinearLayout.LayoutParams)?.let { lyricParams ->
            if (lyricParams.height != layout.lyricContainerHeight) {
                lyricParams.height = layout.lyricContainerHeight
                overlay.lyricContainer.layoutParams = lyricParams
            }
        }
        val targetHeight = layout.targetCardHeight
        val backgroundTargetHeight = AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
            targetCardHeight = targetHeight,
        )

        if (overlay.appliedCardHeight != targetHeight) {
            animateLockScreenCardHeight(
                overlay = overlay,
                targetHeight = targetHeight,
                backgroundTargetHeight = backgroundTargetHeight,
            )
            overlay.appliedCardHeight = targetHeight
            HookLogger.i(
                TAG,
                "锁屏媒体卡片歌词居中布局: " +
                    "anchorBottom=$anchorBottom, lyricContentHeight=$lyricContentHeight, " +
                    "rootTop=${layout.rootTop}, rootHeight=${layout.rootHeight}, " +
                    "lyricContainerHeight=${layout.lyricContainerHeight}, " +
                    "progressRowHeight=$progressRowHeight, " +
                    "nativeCardHeight=$nativeCardHeight, targetHeight=$targetHeight"
            )
        }
        val appliedHeight = overlay.appliedCardHeight
        if (
            overlay.heightAnimator?.isRunning != true &&
            (
                AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                    if (overlay.backgroundConstraints?.isPinned == true) {
                        backgroundTargetHeight
                    } else {
                        appliedHeight
                    },
                    overlay.backgroundSize.view.layoutParams?.height,
                ) ||
                    AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                        appliedHeight,
                        overlay.player.layoutParams?.height,
                    )
            )
        ) {
            appliedHeight?.let { height ->
                animateLockScreenCardHeight(
                    overlay = overlay,
                    targetHeight = height,
                    backgroundTargetHeight = AodMediaLyricPolicy
                        .lockScreenBackgroundTargetHeight(height),
                )
            }
        }
        if (BuildConfig.DEBUG) {
            val background = overlay.backgroundSize.view
            val driftKey = buildString {
                append("applied=${overlay.appliedCardHeight}, target=$targetHeight")
                append(", bgTarget=$backgroundTargetHeight")
                append(", bg=${background.height}/${background.measuredHeight}/${background.layoutParams?.height}")
                append(", bgTop=${background.top}, bgBottom=${background.bottom}")
                append(", bgTransY=${background.translationY}")
                append(", ").append(overlay.backgroundConstraints?.snapshot().orEmpty())
                append(", player=${overlay.player.height}/${overlay.player.measuredHeight}/${overlay.player.layoutParams?.height}")
                append(", ").append(overlay.headerHeightController?.actualHeightSnapshot().orEmpty())
            }
            if (driftKey != overlay.lastHeightDriftKey) {
                overlay.lastHeightDriftKey = driftKey
                HookLogger.i(TAG, "AOD_HEIGHT_VERIFY $driftKey shown=${overlay.root.isShown}")
            }
        }
    }

    /**
     * 全屏 AOD（息屏/锁屏 AOD）歌词居中布局：
     *
     * - 上边界：可见歌曲信息下缘（标题/歌手），已 INVISIBLE 的按钮/进度条几何占位不参与，
     *   允许歌词覆盖这些占位。
     * - 下边界：自绘进度条上缘。
     * - 歌词容器在上、下边界之间垂直居中；进度条独立贴卡片底部（仅保留 [LOCK_SCREEN_AOD_LINE_GAP_DP]）。
     * - 多行歌词/翻译超过原生卡片可用高度时向下撑高卡片，保证行与行、歌词与进度条不重叠。
     */
    private fun updateFullAodCenteredLyricLayout(overlay: LyricOverlay) {
        if (overlay.playerSize.baseHeight <= 0) {
            overlay.playerSize.baseHeight = overlay.player.height.takeIf { it > 0 }
                ?: overlay.player.measuredHeight
        }
        if (overlay.playerSize.baseHeight <= 0) return
        if (overlay.backgroundSize.baseHeight <= 0) {
            overlay.backgroundSize.baseHeight = overlay.backgroundSize.view.height.takeIf { it > 0 }
                ?: overlay.backgroundSize.view.measuredHeight
        }
        if (overlay.backgroundSize.baseHeight <= 0) return

        // 上边界：可见歌曲信息下缘，忽略已 INVISIBLE 的按钮/进度条几何占位。
        val anchorBottom = AodMediaLyricPolicy.fullScreenAodContentAnchorBottom(
            artistBottom = overlay.artist.bottom,
            actionBottom = overlay.actions.maxOfOrNull { it.bottom } ?: 0,
            seekBarBottom = overlay.seekBar?.bottom ?: 0,
            actionVisible = overlay.actions.any { it.visibility == View.VISIBLE },
            seekBarVisible = overlay.seekBar?.visibility == View.VISIBLE,
        )
        if (anchorBottom <= 0) return

        val density = overlay.root.resources.displayMetrics.density
        val nativeCardHeight = AodMediaLyricPolicy.lockScreenNativeCardHeight(
            fullAod = true,
            fullAodBaseHeight = overlay.backgroundSize.baseHeight,
            playerBaseHeight = overlay.playerSize.baseHeight,
        )
        overlay.backgroundConstraints?.pinToParentTop(overlay.player)

        val rootParams = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val lyricParams = overlay.lyricContainer.layoutParams as? LinearLayout.LayoutParams
            ?: return

        // 直接以 UNSPECIFIED 高度测量歌词容器，拿到内容自然高度，
        // 不修改 layoutParams、不触发 requestLayout，避免与布局回调形成死循环。
        val measuredWidth = (overlay.root.width.takeIf { it > 0 }
            ?: overlay.root.measuredWidth).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        overlay.lyricContainer.measure(widthSpec, heightSpec)
        val lyricContentHeight = overlay.lyricContainer.measuredHeight.coerceAtLeast(0)
        val progressVisible = overlay.progressRow.visibility == View.VISIBLE
        val progressRowHeight = if (progressVisible) {
            overlay.progressRow.measure(widthSpec, heightSpec)
            overlay.progressRow.measuredHeight.coerceAtLeast(0)
        } else {
            0
        }
        val progressRowTopMargin = if (progressVisible) {
            (overlay.progressRow.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: 0
        } else {
            0
        }

        val layout = AodMediaLyricPolicy.lockScreenCenteredLyricLayout(
            nativeCardHeight = nativeCardHeight,
            anchorBottom = anchorBottom,
            lyricContentHeight = lyricContentHeight,
            progressRowHeight = progressRowHeight,
            progressRowTopMargin = progressRowTopMargin,
            bottomGap = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt(),
            minTopGap = (COMPACT_LYRIC_TOP_GAP_DP * density).toInt(),
        )

        val targetTopMargin = layout.rootTop - overlay.album.bottom
        if (rootParams.topMargin != targetTopMargin ||
            rootParams.height != layout.rootHeight
        ) {
            rootParams.topMargin = targetTopMargin
            rootParams.height = layout.rootHeight
            overlay.root.layoutParams = rootParams
        }
        if (lyricParams.height != layout.lyricContainerHeight) {
            lyricParams.height = layout.lyricContainerHeight
            overlay.lyricContainer.layoutParams = lyricParams
        }

        val targetHeight = layout.targetCardHeight
        val backgroundTargetHeight = AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
            targetCardHeight = targetHeight,
        )
        if (overlay.appliedCardHeight != targetHeight) {
            animateLockScreenCardHeight(
                overlay = overlay,
                targetHeight = targetHeight,
                backgroundTargetHeight = backgroundTargetHeight,
            )
            overlay.appliedCardHeight = targetHeight
            HookLogger.i(
                TAG,
                "锁屏 AOD 歌词居中布局: " +
                    "anchorBottom=$anchorBottom, lyricContentHeight=$lyricContentHeight, " +
                    "rootTop=${layout.rootTop}, rootHeight=${layout.rootHeight}, " +
                    "lyricContainerHeight=${layout.lyricContainerHeight}, " +
                    "progressRowHeight=$progressRowHeight, " +
                    "nativeCardHeight=$nativeCardHeight, targetHeight=$targetHeight"
            )
        }
        val appliedHeight = overlay.appliedCardHeight
        if (
            overlay.heightAnimator?.isRunning != true &&
            (
                AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                    if (overlay.backgroundConstraints?.isPinned == true) {
                        backgroundTargetHeight
                    } else {
                        appliedHeight
                    },
                    overlay.backgroundSize.view.layoutParams?.height,
                ) ||
                    AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                        appliedHeight,
                        overlay.player.layoutParams?.height,
                    )
            )
        ) {
            appliedHeight?.let { height ->
                animateLockScreenCardHeight(
                    overlay = overlay,
                    targetHeight = height,
                    backgroundTargetHeight = AodMediaLyricPolicy
                        .lockScreenBackgroundTargetHeight(height),
                )
            }
        }
        if (BuildConfig.DEBUG) {
            val background = overlay.backgroundSize.view
            val driftKey = buildString {
                append("applied=${overlay.appliedCardHeight}, target=$targetHeight")
                append(", bgTarget=$backgroundTargetHeight")
                append(", bg=${background.height}/${background.measuredHeight}/${background.layoutParams?.height}")
                append(", rootTop=${overlay.root.top}, rootH=${overlay.root.height}/${overlay.root.layoutParams?.height}")
                append(", lyricH=${overlay.lyricContainer.height}/${overlay.lyricContainer.layoutParams?.height}")
                append(", ").append(overlay.backgroundConstraints?.snapshot().orEmpty())
                append(", player=${overlay.player.height}/${overlay.player.measuredHeight}/${overlay.player.layoutParams?.height}")
            }
            if (driftKey != overlay.lastHeightDriftKey) {
                overlay.lastHeightDriftKey = driftKey
                HookLogger.i(TAG, "AOD_HEIGHT_VERIFY $driftKey shown=${overlay.root.isShown}")
            }
        }
    }

    private fun updateLockScreenHorizontalMargins(overlay: LyricOverlay): Boolean {
        val playerWidth = overlay.player.width.takeIf { it > 0 }
            ?: overlay.player.measuredWidth
        val backgroundWidth = overlay.backgroundSize.view.width.takeIf { it > 0 }
            ?: overlay.backgroundSize.view.measuredWidth
        val albumWidth = overlay.album.width.takeIf { it > 0 }
            ?: overlay.album.measuredWidth
        if (playerWidth <= 0 || backgroundWidth <= 0 || albumWidth <= 0) return false

        val playerLocation = IntArray(2)
        val backgroundLocation = IntArray(2)
        val albumLocation = IntArray(2)
        overlay.player.getLocationOnScreen(playerLocation)
        overlay.backgroundSize.view.getLocationOnScreen(backgroundLocation)
        overlay.album.getLocationOnScreen(albumLocation)

        val cardLeft = backgroundLocation[0] - playerLocation[0]
        val cardRight = cardLeft + backgroundWidth
        val albumLeft = albumLocation[0] - playerLocation[0]
        val margins = AodMediaLyricPolicy.lockScreenHorizontalMargins(
            playerWidth = playerWidth,
            cardLeft = cardLeft,
            cardRight = cardRight,
            albumLeft = albumLeft,
            extraInset = (
                LOCK_SCREEN_AOD_SIDE_MARGIN_EXTRA_DP *
                    overlay.root.resources.displayMetrics.density
                ).toInt(),
        )
        val params = overlay.root.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (
            params.leftMargin == margins.left &&
            params.rightMargin == margins.right &&
            params.marginStart == margins.left &&
            params.marginEnd == margins.right
        ) {
            return false
        }

        params.leftMargin = margins.left
        params.rightMargin = margins.right
        params.marginStart = margins.left
        params.marginEnd = margins.right
        overlay.root.layoutParams = params
        HookLogger.i(
            TAG,
            "锁屏 AOD 歌词左右边距已按封面位置调整: " +
                "cardLeft=$cardLeft, albumLeft=$albumLeft, " +
                "leftMargin=${margins.left}, rightMargin=${margins.right}",
        )
        return true
    }

    private fun restorePlayerHeight(overlay: LyricOverlay, fullAod: Boolean = false) {
        if (
            !AodMediaLyricPolicy.lockScreenHeightNeedsRestore(
                appliedHeight = overlay.appliedCardHeight,
                heightAnimationActive = overlay.heightAnimator != null,
            ) && overlay.backgroundConstraints?.isPinned != true
        ) {
            return
        }
        val animator = overlay.heightAnimator
        overlay.heightAnimator = null
        animator?.cancel()
        overlay.backgroundConstraints?.restore()
        restoreViewSize(overlay.playerSize)
        resizeViewToHeight(
            overlay.backgroundSize.view,
            if (fullAod) {
                overlay.backgroundSize.baseHeight
            } else {
                overlay.playerSize.baseHeight
            }
        )
        overlay.headerHeightController?.restoreHeight()
        overlay.appliedCardHeight = null
        overlay.backgroundSize.view.requestLayout()
        overlay.player.requestLayout()
        (overlay.player.parent as? View)?.requestLayout()
        if (BuildConfig.DEBUG) {
            HookLogger.i(
                TAG,
                "AOD_HEIGHT_RESTORE fullAod=$fullAod, " +
                    "bgH=${overlay.backgroundSize.view.height}, " +
                    "playerH=${overlay.player.height}, " +
                    overlay.headerHeightController?.actualHeightSnapshot().orEmpty()
            )
        }
    }

    private fun resizeViewToHeight(view: View, targetHeight: Int) {
        val params = view.layoutParams ?: return
        if (params.height != targetHeight) {
            params.height = targetHeight
            view.layoutParams = params
        }
    }

    private fun animateLockScreenCardHeight(
        overlay: LyricOverlay,
        targetHeight: Int,
        backgroundTargetHeight: Int = targetHeight,
    ) {
        val previousAnimator = overlay.heightAnimator
        overlay.heightAnimator = null
        previousAnimator?.cancel()
        val background = overlay.backgroundSize.view
        val player = overlay.player
        val headerController = overlay.headerHeightController

        val startBackgroundHeight = background.height.takeIf { it > 0 }
            ?: (background.layoutParams?.height)?.takeIf { it >= 0 }
            ?: overlay.backgroundSize.baseHeight
        val startPlayerHeight = player.height.takeIf { it > 0 }
            ?: (player.layoutParams?.height)?.takeIf { it >= 0 }
            ?: overlay.playerSize.baseHeight
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOCK_SCREEN_AOD_HEIGHT_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                resizeViewToHeight(
                    background,
                    lerp(startBackgroundHeight, backgroundTargetHeight, fraction),
                )
                resizeViewToHeight(
                    player,
                    lerp(startPlayerHeight, targetHeight, fraction),
                )
                background.requestLayout()
                player.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (overlay.heightAnimator !== animation) return
                    overlay.heightAnimator = null
                    resizeViewToHeight(background, backgroundTargetHeight)
                    resizeViewToHeight(player, targetHeight)
                    headerController?.applyFinalHeight(targetHeight)
                    background.requestLayout()
                    player.requestLayout()
                    (player.parent as? View)?.requestLayout()
                    HookLogger.i(
                        TAG,
                        "锁屏 AOD 媒体卡片高度动画完成: target=$targetHeight, " +
                            "backgroundTarget=$backgroundTargetHeight",
                    )
                }
            })
        }
        overlay.heightAnimator = animator
        animator.start()
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun restoreViewSize(size: ViewSizeSnapshot) {
        val params = size.view.layoutParams
        if (params.height != size.originalLayoutHeight) {
            params.height = size.originalLayoutHeight
            size.view.layoutParams = params
        }
        if (size.view.minimumHeight != size.originalMinimumHeight) {
            size.view.minimumHeight = size.originalMinimumHeight
        }
    }

    private fun captureViewSize(view: View): ViewSizeSnapshot {
        val layoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        return ViewSizeSnapshot(
            view = view,
            originalLayoutHeight = layoutHeight,
            originalMinimumHeight = view.minimumHeight,
            baseHeight = layoutHeight.takeIf { it >= 0 }
                ?: view.height.takeIf { it > 0 }
                ?: view.measuredHeight.takeIf { it > 0 }
                ?: layoutHeight.coerceAtLeast(0)
        )
    }

    private fun shouldPollPosition(state: ControllerState): Boolean {
        return (isEnabled() || state.lockScreenLyricsActive ||
            state.notificationCenterLyricsActive) && state.aodActive &&
            state.playing && LyriconDataBridge.currentSong != null
    }

    private fun hasActiveAodPluginState(): Boolean {
        return isEnabled() &&
            LyriconDataBridge.currentSong != null &&
            synchronized(aodPluginStates) {
                aodPluginStates.values.any { it.attached && it.playing }
            }
    }

    private fun shouldRefreshNoLyricPreview(position: Long): Boolean {
        if (currentActualLyrics().isNotEmpty()) return false
        val currentPrefs = prefs ?: return false
        // 任一位置的「下一首预览」开启且歌曲无歌词时，接近歌曲结尾刷新一次预览
        val previewEnabled = RootConstants.STYLE_KEY_PREFIXES.any { prefix ->
            currentPrefs.getBoolean(
                "${prefix}next_song_preview",
                RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
            )
        }
        if (!previewEnabled) return false
        val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
            ?: return false
        if (position < (duration - 5_000L).coerceAtLeast(0L) || position >= duration) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoLyricPreviewRefreshAt < NO_LYRIC_PREVIEW_REFRESH_INTERVAL_MS) {
            return false
        }
        lastNoLyricPreviewRefreshAt = now
        return true
    }

    private fun updatePositionPolling() {
        val hasTarget = synchronized(states) { states.values.any(::shouldPollPosition) } ||
            hasActiveAodPluginState()
        if (hasTarget) {
            schedulePositionPoll()
        } else {
        mainHandler.removeCallbacks(positionPollRunnable)
        positionPollScheduled = false
        DisplayDiagnosticLogger.clear("AOD_LOCK")
        DisplayDiagnosticLogger.clear("AOD_CLASSIC")
    }
    }

    private fun schedulePositionPoll() {
        if (positionPollScheduled) return
        positionPollScheduled = true
        mainHandler.postDelayed(positionPollRunnable, POSITION_POLL_INTERVAL_MS)
    }

    private fun scheduleAodPluginInitialRefresh(aodView: Any, state: AodPluginState) {
        state.initialRefreshGeneration++
        val generation = state.initialRefreshGeneration
        val viewReference = WeakReference(aodView)
        DisplayDiagnosticLogger.log(
            channel = "AOD_CLASSIC",
            result = "pending",
            reason = "initial_refresh_scheduled",
            extra = "attempts=${AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS.size}, generation=$generation",
            dedupeKey = "AOD_CLASSIC/${System.identityHashCode(aodView)}/initial",
        )
        AOD_PLUGIN_INITIAL_REFRESH_DELAYS_MS.forEach { delay ->
            mainHandler.postDelayed(
                {
                    val target = viewReference.get() ?: return@postDelayed
                    val currentState = synchronized(aodPluginStates) {
                        aodPluginStates[target]
                    }
                    if (currentState !== state || state.initialRefreshGeneration != generation) {
                        return@postDelayed
                    }
                    safeApplyAodPlugin(target, state)
                    updatePositionPolling()
                },
                delay
            )
        }
    }

    private fun synchronizeLyricPosition(
        preferredApi: NativeApi? = null,
        preferredController: Any? = null
    ) {
        val mediaPosition = if (preferredApi != null && preferredController != null) {
            preferredApi.currentPlaybackPosition(preferredController)
        } else {
            synchronized(states) { states.entries.toList() }
                .firstNotNullOfOrNull { (controller, state) ->
                    if (!state.playing) return@firstNotNullOfOrNull null
                    resolveApi(controller.javaClass.classLoader)
                        ?.currentPlaybackPosition(controller)
                }
        }
        val position = LyriconDataBridge.estimatedPosition() ?: mediaPosition ?: return
        LyriconDataBridge.updateEstimatedPosition(position)
    }

    private fun removeOverlay(state: ControllerState) {
        val overlay = state.overlay ?: return
        restorePlayerHeight(overlay, state.fullAod)
        (overlay.root.parent as? ViewGroup)?.removeView(overlay.root)
        state.overlay = null
    }

    private fun removeAodPluginOverlay(state: AodPluginState) {
        val overlay = state.overlay ?: return
        overlay.preDrawListener?.let { listener ->
            if (overlay.aodRoot.viewTreeObserver.isAlive) {
                overlay.aodRoot.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        (overlay.root.parent as? ViewGroup)?.removeView(overlay.root)
        state.overlay = null
    }

    private fun currentContent(style: AodTextStyleConfig): AodLyricContent {
        val currentLine = LyriconDataBridge.currentLyricLine
        val removeCjkLyricSpaces = currentLine != null && prefs?.getBoolean(
            RootConstants.KEY_HOOK_REMOVE_CJK_LYRIC_SPACES,
            RootConstants.DEFAULT_HOOK_REMOVE_CJK_LYRIC_SPACES,
        ) == true && currentLine.metadata?.getBoolean(
            SongPreprocessor.KEY_TITLE_LINE
        ) != true
        /** 只处理 AOD 展示文本，发音 roma 不进入此处理。 */
        fun displayText(text: String?): String? = if (removeCjkLyricSpaces) {
            CjkLyricWhitespacePolicy.transformText(text)
        } else {
            text
        }
        val suppressNoLyricPlaceholder =
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = LyriconDataBridge.isTextMode,
                hasActualLyrics = currentActualLyrics().isNotEmpty(),
            )
        // 只要桥里存在当前行（哪怕整首歌被折叠成一行、完整歌词列表为空），
        // 就不能把它当作「无歌词」抑制掉——否则无时间轴歌词会整片空白。
        val hasCurrentLine = currentLine != null
        val line = LyriconDataBridge.currentLyricLine.takeUnless {
            suppressNoLyricPlaceholder && !hasCurrentLine
        }
        val upcomingLines = LyriconDataBridge.currentUpcomingLyricLines.takeUnless {
            suppressNoLyricPlaceholder && !hasCurrentLine
        }.orEmpty()
        val nextLine = upcomingLines.firstOrNull()
            ?: LyriconDataBridge.currentNextLyricLine.takeUnless {
                suppressNoLyricPlaceholder && !hasCurrentLine
            }
        val metadata = line?.metadata
        val isOverlappingGroup = metadata?.getBoolean(
            LyricMetadataKeys.OVERLAPPING_LYRICS_GROUP
        ) == true
        val main = if (suppressNoLyricPlaceholder) {
            ""
        } else {
            line?.text?.trim().orEmpty().ifBlank {
                LyriconDataBridge.currentLyric?.trim().orEmpty()
            }
        }
        val mainAlignedRight = line?.isAlignedRight == true
        val backingAlignedRight = metadata?.getBoolean(
            LyricMetadataKeys.CONCURRENT_SECONDARY_ALIGNED_RIGHT,
            mainAlignedRight
        ) ?: mainAlignedRight
        val primaryBacking = if (isOverlappingGroup) {
            metadata.getString(LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING)
        } else {
            line?.secondary
        }
        val primaryBackingTranslation = if (isOverlappingGroup) {
            metadata.getString(
                LyricMetadataKeys.OVERLAPPING_PRIMARY_BACKING_TRANSLATION
            )
        } else {
            metadata?.getString(LyricMetadataKeys.BACKGROUND_VOCALS_TRANSLATION)
        }
        val overlappingMain = if (isOverlappingGroup) line.secondary else null
        val overlappingTranslation = if (isOverlappingGroup) {
            metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_TRANSLATION)
        } else {
            null
        }
        val overlappingBacking = if (isOverlappingGroup) {
            metadata.getString(LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING)
        } else {
            null
        }
        val overlappingBackingTranslation = if (isOverlappingGroup) {
            metadata.getString(
                LyricMetadataKeys.OVERLAPPING_SECONDARY_BACKING_TRANSLATION
            )
        } else {
            null
        }
        // 等待歌词匹配中（摘要态同款动态圆点）：有当前歌曲、正在播放、无实际歌词、
        // 且非报错占位、非纯文本模式时，主行用圆点占位，由 ticker 做循环高亮动画。
        val waitingForLyrics = !LyriconDataBridge.isTextMode &&
            LyriconDataBridge.currentPlaybackState == true &&
            LyriconDataBridge.currentSong != null &&
            !LyriconDataBridge.currentSong?.name.isNullOrBlank() &&
            currentActualLyrics().isEmpty() &&
            currentLine == null &&
            LyriconDataBridge.currentSong?.metadata
                ?.getString(LyricMetadataKeys.LYRIC_ERROR_MESSAGE).isNullOrBlank()
        val assembled = AodMediaLyricPolicy.assembleContent(
            main = displayText(main),
            translation = displayText(line?.translation),
            backing = displayText(primaryBacking),
            backingTranslation = displayText(primaryBackingTranslation),
            roma = line?.roma,
            overlappingMain = displayText(overlappingMain),
            overlappingTranslation = displayText(overlappingTranslation),
            overlappingBacking = displayText(overlappingBacking),
            overlappingBackingTranslation = displayText(overlappingBackingTranslation),
            next = buildUpcomingLyricText(upcomingLines, style.lyricMaxLines, removeCjkLyricSpaces),
            showNext = style.showNextLyric,
            mainAlignedRight = mainAlignedRight,
            backingAlignedRight = mainAlignedRight,
            overlappingAlignedRight = backingAlignedRight,
            overlappingBackingAlignedRight = backingAlignedRight,
            mainGroupVocals =
                line?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
            nextAlignedRight = nextLine?.isAlignedRight == true,
            nextGroupVocals =
                nextLine?.metadata?.getBoolean(LyricMetadataKeys.GROUP_VOCALS) == true,
            duetLyrics = style.duetLyrics,
            centerNonDuetSong = style.centerNonDuetSong &&
                LyriconDataBridge.currentSong?.lyrics.orEmpty().none { it.isAlignedRight },
            centerGroupVocals = style.centerGroupVocals,
            translationDisplayMode = style.translationDisplayMode,
            translationFallback = style.translationFallback,
        )
        return if (waitingForLyrics) {
            // 等待匹配阶段只展示动态圆点，不要把后续歌词拼接行也带进来，
            // 否则开启多行歌词时会出现圆点与多行歌词挤在一起。
            assembled.copy(main = WAITING_DOTS_PLACEHOLDER, next = "", waitingForLyrics = true)
        } else {
            assembled
        }
    }

    /**
     * 按总行数上限拼接后续歌词。
     *
     * 行数上限同时约束两件事：
     * - 条数：最多取 [AodMediaLyricPolicy.upcomingLineBudget] 条后续歌词；
     * - 行数：拼接后的整体再受 next 行 maxLines 限制，某条过长时截断而不是继续换行。
     */
    private fun buildUpcomingLyricText(
        upcoming: List<IRichLyricLine>,
        maxLines: Int,
        removeCjkSpaces: Boolean
    ): String {
        val budget = AodMediaLyricPolicy.upcomingLineBudget(maxLines)
        if (budget <= 0) return ""
        val transform: (String?) -> String? = if (removeCjkSpaces) {
            { CjkLyricWhitespacePolicy.transformText(it) }
        } else {
            { it }
        }
        return upcoming.asSequence()
            .mapNotNull { transform(it.text) }
            .filter { it.isNotBlank() }
            .take(budget)
            .joinToString("\n")
    }

    /** 等待歌词占位文案（动态圆点，颜色在 apply 时按当前文本色着色）。 */
    private const val WAITING_DOTS_PLACEHOLDER = "● ● ● ●"
    private const val WAITING_DOTS_INTERVAL_MS = 420L
    private const val WAITING_DOTS_COUNT = 4

    private fun dimOf(color: Int): Int = (color and 0x00FFFFFF) or 0x55000000

    private fun buildWaitingDots(frame: Int, active: Int, inactive: Int): SpannableString {
        val sb = SpannableString(WAITING_DOTS_PLACEHOLDER)
        val positions = (0 until WAITING_DOTS_COUNT).map { it * 2 }
        positions.forEachIndexed { index, pos ->
            sb.setSpan(
                ForegroundColorSpan(if (index == frame % WAITING_DOTS_COUNT) active else inactive),
                pos,
                pos + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    @Volatile
    private var waitingDotsFrame = 0
    private var waitingDotsRunnable: Runnable? = null

    /** 任一覆盖层处于等待态时启动圆点循环高亮动画，无等待态时自动停止。 */
    private fun ensureWaitingDotsTicker() {
        if (waitingDotsRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                waitingDotsFrame++
                var anyWaiting = false
                synchronized(states) {
                    for (state in states.values) {
                        val overlay = state.overlay ?: continue
                        if (overlay.waitingForLyrics && overlay.root.isShown) {
                            anyWaiting = true
                            val base = overlay.main.currentTextColor
                            overlay.main.text = buildWaitingDots(
                                waitingDotsFrame,
                                base,
                                dimOf(base),
                            )
                            overlay.root.invalidate()
                        }
                    }
                }
                synchronized(aodPluginStates) {
                    for (state in aodPluginStates.values) {
                        val overlay = state.overlay ?: continue
                        if (overlay.waitingForLyrics && overlay.root.isShown) {
                            anyWaiting = true
                            val base = overlay.main.currentTextColor
                            overlay.main.text = buildWaitingDots(
                                waitingDotsFrame,
                                base,
                                dimOf(base),
                            )
                            overlay.root.invalidate()
                        }
                    }
                }
                if (anyWaiting) {
                    mainHandler.postDelayed(this, WAITING_DOTS_INTERVAL_MS)
                } else {
                    waitingDotsRunnable = null
                }
            }
        }
        waitingDotsRunnable = runnable
        mainHandler.postDelayed(runnable, WAITING_DOTS_INTERVAL_MS)
    }

    private fun appendNextSongPreview(
        content: AodLyricContent,
        style: AodTextStyleConfig,
        context: Context,
        packageName: String?,
    ): AodLyricContent {
        if (!style.nextSongPreview || packageName.isNullOrBlank()) return content
        val actualLyrics = currentActualLyrics()
        val mediaInfo = MediaMetadataHelper.getMediaInfo(context, packageName, HookLogger)
        val duration = LyriconDataBridge.currentSong?.duration?.takeIf { it > 0L }
            ?: mediaInfo.duration
        val position = LyriconDataBridge.estimatedPosition()
            ?: LyriconDataBridge.currentPosition
        if (
            !AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = position,
                durationMs = duration,
                hasActualLyrics = actualLyrics.isNotEmpty(),
                lastLyricStartMs = actualLyrics.maxOfOrNull { it.begin } ?: -1L,
            )
        ) {
            return content
        }
        val nextSong = MediaMetadataHelper.getNextMediaInfo(
            context = context,
            packageName = packageName,
            current = mediaInfo,
        )
        val preview = AodMediaLyricPolicy.formatNextSongPreview(
            title = nextSong.title,
            artist = nextSong.artist,
        )
        return if (preview.isBlank()) {
            content
        } else {
            content.copy(
                next = preview,
                nextAlignment = AodMediaLyricPolicy.nextSongPreviewAlignment(
                    style.nextSongPreviewPosition
                ),
            )
        }
    }

    private fun currentActualLyrics() =
        LyriconDataBridge.currentSong?.lyrics.orEmpty().filterNot { line ->
            line.metadata?.getBoolean(SongPreprocessor.KEY_TITLE_LINE) == true
        }

    private fun lockScreenAodTextStyle(): AodTextStyleConfig =
        textStyleForPrefix("key_hook_lock_screen_aod_")

    /** 亮屏锁屏歌词：独立偏好组（与息屏AOD互不影响）。 */
    private fun lockScreenLyricsTextStyle(): AodTextStyleConfig =
        textStyleForPrefix("key_hook_lock_screen_lyrics_")

    /** 通知中心歌词：独立偏好组。 */
    private fun notificationCenterTextStyle(): AodTextStyleConfig =
        textStyleForPrefix("key_hook_notification_center_")

    private fun classicAodTextStyle(): AodTextStyleConfig =
        textStyleForPrefix("key_hook_classic_aod_")

    /** 按偏好 key 前缀构建文字样式（各歌词位置参数独立）。 */
    private fun textStyleForPrefix(prefix: String): AodTextStyleConfig = AodTextStyleConfig(
        mainTextSize = readAodTextSize(
            key = "${prefix}main_text_size",
            defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_MAIN_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_MAIN_TEXT_SIZE,
        ),
        backingTextSize = readAodTextSize(
            key = "${prefix}backing_text_size",
            defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_BACKING_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_BACKING_TEXT_SIZE,
        ),
        translationTextSize = readAodTextSize(
            key = "${prefix}translation_text_size",
            defaultValue = RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE,
            min = RootConstants.MIN_HOOK_AOD_TRANSLATION_TEXT_SIZE,
            max = RootConstants.MAX_HOOK_AOD_TRANSLATION_TEXT_SIZE,
        ),
        // 后续歌词（多行歌词）：位置内开关与全局「多行歌词」开关任一开启即显示，
        // 这样只开全局开关时息屏/锁屏 AOD 与自定义 AOD 也跟随生效。
        showNextLyric = (
            prefs?.getBoolean(
                "${prefix}show_next_lyric",
                RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
            ) ?: RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC
            ) || (
            prefs?.getBoolean(
                RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
                RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE,
            ) ?: RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE
            ),
        nextLyricStyle = readAodNextLyricStyle("${prefix}next_lyric_style"),
        // 歌词总行数上限：直接以行数为准，不再按高度/字号反推。
        lyricMaxLines = AodMediaLyricPolicy.sanitizeLyricAreaHeight(
            prefs?.all?.get(RootConstants.KEY_HOOK_LYRIC_MAX_LINES)
        ),
        duetLyrics = prefs?.getBoolean(
            "${prefix}duet_lyrics",
            RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS,
        centerNonDuetSong = prefs?.getBoolean(
            "${prefix}center_non_duet_song",
            RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_NON_DUET_SONG,
        centerGroupVocals = prefs?.getBoolean(
            "${prefix}center_group_vocals",
            RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS,
        pauseStyle = readAodPauseStyle("${prefix}pause_style"),
        translationDisplayMode = AodMediaLyricPolicy.readTranslationPronunciationMode(
            prefs = prefs,
            key = "${prefix}translation_display",
            defaultValue = RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_DISPLAY_MODE,
        ),
        translationFallback = prefs?.getBoolean(
            "${prefix}translation_fallback",
            RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_TRANSLATION_FALLBACK,
        swapTranslation = prefs?.getBoolean(
            "${prefix}swap_translation",
            RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_SWAP_TRANSLATION,
        nextSongPreview = prefs?.getBoolean(
            "${prefix}next_song_preview",
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW,
        nextSongPreviewPosition = readAodNextSongPreviewPosition(
            "${prefix}next_song_preview_position"
        ),
    )

    private fun readAodTextSize(
        key: String,
        defaultValue: Int,
        min: Int,
        max: Int,
    ): Int = AodMediaLyricPolicy.sanitizeTextSize(
        value = prefs?.getInt(key, defaultValue) ?: defaultValue,
        defaultValue = defaultValue,
        min = min,
        max = max,
    )

    private fun readAodNextLyricStyle(key: String): Int =
        AodMediaLyricPolicy.sanitizeNextLyricStyle(
            prefs?.getInt(
                key,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE,
            ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_LYRIC_STYLE
        )

    private fun readAodNextSongPreviewPosition(key: String): Int =
        AodMediaLyricPolicy.sanitizeNextSongPreviewPosition(
            prefs?.getInt(
                key,
                RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION,
            ) ?: RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION
        )

    private fun readAodPauseStyle(key: String): Int =
        (prefs?.getInt(
            key,
            RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE,
        ) ?: RootConstants.DEFAULT_HOOK_AOD_PAUSE_STYLE).coerceIn(
            RootConstants.AOD_PAUSE_STYLE_RESTORE,
            RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
        )

    private fun applyContentAlignment(
        overlay: LyricOverlay,
        content: AodLyricContent,
    ) {
        applyAlignment(
            views = listOf(overlay.main, overlay.translation),
            alignment = content.mainAlignment,
        )
        applyAlignment(
            views = listOf(overlay.backing, overlay.backingTranslation),
            alignment = content.backingAlignment,
        )
        applyAlignment(
            views = listOf(overlay.overlappingMain, overlay.overlappingTranslation),
            alignment = content.overlappingAlignment,
        )
        applyAlignment(
            views = listOf(
                overlay.overlappingBacking,
                overlay.overlappingBackingTranslation
            ),
            alignment = content.overlappingBackingAlignment,
        )
        applyAlignment(listOf(overlay.next), content.nextAlignment)
        overlay.appliedMainAlignment = content.mainAlignment
        overlay.appliedBackingAlignment = content.backingAlignment
        overlay.appliedOverlappingAlignment = content.overlappingAlignment
        overlay.appliedOverlappingBackingAlignment = content.overlappingBackingAlignment
        overlay.appliedNextAlignment = content.nextAlignment
    }

    private fun applyContentAlignment(
        overlay: AodPluginOverlay,
        content: AodLyricContent,
    ) {
        applyAlignment(
            views = listOf(overlay.main, overlay.translation),
            alignment = content.mainAlignment,
        )
        val songInfoGravity = AodMediaLyricPolicy.embeddedSongInfoGravity(
            prefs?.let(ClassicAodSongInfoConfig::embeddedPosition)
                ?: RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_POSITION
        )
        overlay.songInfoRow.gravity = songInfoGravity
        overlay.songInfo.gravity = songInfoGravity
        applyAlignment(
            views = listOf(overlay.backing, overlay.backingTranslation),
            alignment = content.backingAlignment,
        )
        applyAlignment(
            views = listOf(overlay.overlappingMain, overlay.overlappingTranslation),
            alignment = content.overlappingAlignment,
        )
        applyAlignment(
            views = listOf(
                overlay.overlappingBacking,
                overlay.overlappingBackingTranslation
            ),
            alignment = content.overlappingBackingAlignment,
        )
        applyAlignment(listOf(overlay.next), content.nextAlignment)
        overlay.appliedMainAlignment = content.mainAlignment
        overlay.appliedBackingAlignment = content.backingAlignment
        overlay.appliedOverlappingAlignment = content.overlappingAlignment
        overlay.appliedOverlappingBackingAlignment = content.overlappingBackingAlignment
        overlay.appliedNextAlignment = content.nextAlignment
    }

    private fun applyAlignment(
        views: List<TextView>,
        alignment: AodLyricAlignment,
    ) {
        val gravity = when (alignment) {
            AodLyricAlignment.LEFT -> Gravity.LEFT
            AodLyricAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            AodLyricAlignment.RIGHT -> Gravity.RIGHT
        }
        views.forEach { view ->
            if (view.gravity != gravity) {
                view.gravity = gravity
            }
        }
    }

    private fun applyLockScreenTextStyle(
        overlay: LyricOverlay,
        style: AodTextStyleConfig,
        mainTypefaceView: TextView,
        translationTypefaceView: TextView,
    ) {
        overlay.main.typeface = mainTypefaceView.typeface
        overlay.backing.typeface = mainTypefaceView.typeface
        overlay.overlappingMain.typeface = mainTypefaceView.typeface
        overlay.overlappingBacking.typeface = mainTypefaceView.typeface
        overlay.translation.typeface = translationTypefaceView.typeface
        overlay.backingTranslation.typeface = translationTypefaceView.typeface
        overlay.overlappingTranslation.typeface = translationTypefaceView.typeface
        overlay.overlappingBackingTranslation.typeface = translationTypefaceView.typeface
        overlay.main.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.mainTextSize.toFloat())
        overlay.backing.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.backingTextSize.toFloat())
        overlay.overlappingMain.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.mainTextSize.toFloat(),
        )
        overlay.overlappingBacking.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.backingTextSize.toFloat(),
        )
        overlay.translation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.backingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingBackingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        val nextUsesBackingStyle =
            style.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING
        overlay.next.typeface = if (nextUsesBackingStyle) {
            mainTypefaceView.typeface
        } else {
            translationTypefaceView.typeface
        }
        overlay.next.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (nextUsesBackingStyle) {
                style.backingTextSize.toFloat()
            } else {
                style.translationTextSize.toFloat()
            },
        )
        overlay.appliedTextStyle = style
    }

    private fun applyClassicTextStyle(
        overlay: AodPluginOverlay,
        style: AodTextStyleConfig,
    ) {
        overlay.songInfo.typeface = overlay.translation.typeface
        overlay.songInfo.setTextColor(0xCCFFFFFF.toInt())
        overlay.songInfo.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.main.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.mainTextSize.toFloat())
        overlay.backing.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.backingTextSize.toFloat())
        overlay.overlappingMain.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.mainTextSize.toFloat(),
        )
        overlay.overlappingBacking.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.backingTextSize.toFloat(),
        )
        overlay.translation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.backingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        overlay.overlappingBackingTranslation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            style.translationTextSize.toFloat(),
        )
        val nextUsesBackingStyle =
            style.nextLyricStyle == RootConstants.AOD_NEXT_LYRIC_STYLE_BACKING
        overlay.next.typeface = if (nextUsesBackingStyle) {
            overlay.main.typeface
        } else {
            overlay.translation.typeface
        }
        overlay.next.setTextColor(
            if (nextUsesBackingStyle) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        )
        overlay.next.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (nextUsesBackingStyle) {
                style.backingTextSize.toFloat()
            } else {
                style.translationTextSize.toFloat()
            },
        )
        val rowMaxLines = AodMediaLyricPolicy.lyricRowMaxLines(style.lyricMaxLines)
        listOf(
            overlay.main,
            overlay.backing,
            overlay.overlappingMain,
            overlay.overlappingBacking,
            overlay.translation,
            overlay.backingTranslation,
            overlay.overlappingTranslation,
            overlay.overlappingBackingTranslation,
        ).forEach { it.maxLines = rowMaxLines }
        overlay.next.maxLines = AodMediaLyricPolicy
            .upcomingLineBudget(style.lyricMaxLines)
            .coerceAtLeast(1)
        overlay.appliedTextStyle = style
    }

    private fun resolvePlaying(api: NativeApi, controller: Any, mediaData: Any?): Boolean {
        return LyriconDataBridge.currentPlaybackState
            ?: api.isControllerPlaying(controller)
            ?: api.isPlaying(mediaData)
    }

    private fun isEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_AOD_LYRICS,
        RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS
    ) ?: RootConstants.DEFAULT_HOOK_ENABLE_AOD_LYRICS

    private fun isLockScreenLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_LOCK_SCREEN_LYRICS_ENABLED,
        RootConstants.DEFAULT_HOOK_LOCK_SCREEN_LYRICS_ENABLED
    ) ?: RootConstants.DEFAULT_HOOK_LOCK_SCREEN_LYRICS_ENABLED

    private fun isNotificationCenterLyricsEnabled(): Boolean = prefs?.getBoolean(
        RootConstants.KEY_HOOK_NOTIFICATION_CENTER_LYRICS_ENABLED,
        RootConstants.DEFAULT_HOOK_NOTIFICATION_CENTER_LYRICS_ENABLED
    ) ?: RootConstants.DEFAULT_HOOK_NOTIFICATION_CENTER_LYRICS_ENABLED

    /**
     * 媒体头恢复可见时是否应保留歌词覆盖层：
     * 锁屏态看「锁屏歌词」开关，解锁态看「通知中心歌词」开关。
     */
    internal fun isMediaCardLyricsKept(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val keyguardLocked = keyguard?.isKeyguardLocked == true
        return if (keyguardLocked) {
            isLockScreenLyricsEnabled()
        } else {
            isNotificationCenterLyricsEnabled()
        }
    }

    private fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "息屏歌词接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private fun resolveAodPluginApi(classLoader: ClassLoader?): AodPluginApi? {
        classLoader ?: return null
        aodPluginApis[classLoader]?.let { return it }
        return runCatching { AodPluginApi.create(classLoader) }
            .onSuccess { aodPluginApis[classLoader] = it }
            .onFailure {
                HookLogger.w(TAG, "通知图标式息屏歌词接口不可用: reason=${it.message}")
            }
            .getOrNull()
    }

    private fun requestAodFrameRefresh(classLoader: ClassLoader?) {
        val refreshApi = classLoader?.let(dozeRefreshApis::get)
            ?: synchronized(dozeRefreshApis) { dozeRefreshApis.values.firstOrNull() }
        if (refreshApi == null) {
            MediaCardDiagnosticLogger.log(
                stage = "aod_refresh",
                event = "native_frame_refresh_skipped",
                reason = "api_unavailable",
                details = "classLoader=${MediaCardDiagnosticLogger.identity(classLoader)}",
            )
            HookLogger.w(TAG, "跳过 AOD 原生帧刷新: reason=api_unavailable")
            return
        }
        try {
            refreshApi.requestTick()
            MediaCardDiagnosticLogger.log(
                stage = "aod_refresh",
                event = "native_frame_refresh_requested",
                details = "api=${MediaCardDiagnosticLogger.identity(refreshApi)},classLoader=${MediaCardDiagnosticLogger.identity(classLoader)}",
            )
        } catch (error: Throwable) {
            MediaCardDiagnosticLogger.log(
                stage = "aod_refresh",
                event = "native_frame_refresh_failed",
                reason = "exception",
                details = "error=${MediaCardDiagnosticLogger.sanitize(error.message)}",
            )
            HookLogger.e(TAG, "请求 AOD 原生帧刷新失败", error)
            throw error
        }
    }

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private fun setOptionalText(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private data class ClassicAodEmbeddedSongInfo(
        val text: String,
        val sourcePackage: String?,
        val textSize: Int = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE,
        val showIcon: Boolean = RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_SHOW_ICON,
    )

    private fun currentClassicAodEmbeddedSongInfo(): ClassicAodEmbeddedSongInfo {
        val currentPrefs = prefs ?: return ClassicAodEmbeddedSongInfo("", null)
        if (
            ClassicAodSongInfoConfig.displayStyle(currentPrefs) !=
                RootConstants.AOD_SONG_INFO_DISPLAY_STYLE_TEXT_EMBEDDED
        ) {
            return ClassicAodEmbeddedSongInfo("", null)
        }
        val song = LyriconDataBridge.currentSong
        val text = ClassicAodSongInfoConfig.formatSongInfo(
            title = song?.name.orEmpty(),
            artist = song?.artist.orEmpty(),
            format = ClassicAodSongInfoConfig.format(currentPrefs)
        )
        return ClassicAodEmbeddedSongInfo(
            text = text,
            sourcePackage = LyriconDataBridge.currentLyricPackageName
                ?: LyriconDataBridge.activePackageName,
            textSize = ClassicAodSongInfoConfig.embeddedTextSize(currentPrefs),
            showIcon = ClassicAodSongInfoConfig.showsEmbeddedIcon(currentPrefs),
        )
    }

    private fun updateClassicEmbeddedSongInfo(
        overlay: AodPluginOverlay,
        songInfo: ClassicAodEmbeddedSongInfo,
    ) {
        if (songInfo.text.isBlank()) {
            overlay.songInfoRow.visibility = View.GONE
            overlay.songInfo.text = ""
            overlay.sourceIcon.setImageDrawable(null)
            overlay.appliedSongInfoPackage = null
            overlay.appliedSongInfoTextSize = songInfo.textSize
            overlay.appliedSongInfoShowsIcon = songInfo.showIcon
            return
        }

        overlay.songInfo.text = songInfo.text
        overlay.songInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, songInfo.textSize.toFloat())
        updateClassicSongInfoIconLayout(overlay, songInfo.textSize)
        if (overlay.appliedSongInfoPackage != songInfo.sourcePackage) {
            overlay.appliedSongInfoPackage = songInfo.sourcePackage
            val icon = songInfo.sourcePackage?.let { packageName ->
                runCatching {
                    overlay.root.context.packageManager.getApplicationIcon(packageName)
                }.getOrNull()
            }
            overlay.sourceIcon.setImageDrawable(icon)
        }
        overlay.sourceIcon.visibility = if (
            songInfo.showIcon && overlay.sourceIcon.drawable != null
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        overlay.appliedSongInfoTextSize = songInfo.textSize
        overlay.appliedSongInfoShowsIcon = songInfo.showIcon
        overlay.songInfoRow.visibility = View.VISIBLE
    }

    private fun updateClassicSongInfoIconLayout(
        overlay: AodPluginOverlay,
        textSize: Int,
    ) {
        val params = overlay.sourceIcon.layoutParams as? LinearLayout.LayoutParams ?: return
        val scale = textSize.toFloat() /
            RootConstants.DEFAULT_HOOK_CLASSIC_AOD_SONG_INFO_TEXT_SIZE
        val density = overlay.root.resources.displayMetrics.density
        val iconSize = (AOD_PLUGIN_SONG_INFO_ICON_DP * density * scale)
            .roundToInt()
            .coerceAtLeast(1)
        val iconGap = (AOD_PLUGIN_SONG_INFO_ICON_GAP_DP * density * scale)
            .roundToInt()
            .coerceAtLeast(0)
        if (
            params.width != iconSize ||
            params.height != iconSize ||
            params.marginEnd != iconGap
        ) {
            params.width = iconSize
            params.height = iconSize
            params.marginEnd = iconGap
            overlay.sourceIcon.layoutParams = params
        }
    }

    private fun updateClassicSongInfoMaxWidth(
        overlay: AodPluginOverlay,
        overlayWidth: Int,
    ) {
        if (overlay.songInfoRow.visibility != View.VISIBLE) return
        val rowWidth = overlayWidth -
            overlay.root.paddingLeft -
            overlay.root.paddingRight
        val iconWidth = if (overlay.sourceIcon.visibility == View.VISIBLE) {
            val iconParams = overlay.sourceIcon.layoutParams as? LinearLayout.LayoutParams
            (iconParams?.width ?: 0) + (iconParams?.marginEnd ?: 0)
        } else {
            0
        }
        val maxTextWidth = (rowWidth - iconWidth).coerceAtLeast(0)
        if (overlay.songInfo.maxWidth != maxTextWidth) {
            overlay.songInfo.maxWidth = maxTextWidth
        }
    }

    private fun updateLockScreenLineSpacing(overlay: LyricOverlay) {
        val density = overlay.root.resources.displayMetrics.density
        val normalMargin = (LOCK_SCREEN_AOD_LINE_GAP_DP * density).toInt()
        val groupMargin = (LOCK_SCREEN_AOD_GROUP_GAP_DP * density).toInt()
        val views = orderedLyricViews(
            overlay,
            overlay.appliedTextStyle?.swapTranslation == true,
        )
        val firstVisible = views.firstOrNull { it.visibility == View.VISIBLE }
        val firstBackingRow = views
            .filter { it === overlay.backing || it === overlay.backingTranslation }
            .firstOrNull { it.visibility == View.VISIBLE }
        val primaryVisibleCount = listOf(overlay.main, overlay.translation)
            .count { it.visibility == View.VISIBLE }
        views.forEach { view ->
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            val targetMargin = when {
                view === firstVisible -> 0
                view === firstBackingRow && primaryVisibleCount > 1 -> groupMargin
                else -> normalMargin
            }
            if (params.topMargin != targetMargin) {
                params.topMargin = targetMargin
                view.layoutParams = params
            }
        }
    }

    private fun applyLyricRowOrder(
        overlay: LyricOverlay,
        swapTranslation: Boolean,
    ) {
        reorderLyricViews(
            root = overlay.lyricContainer,
            orderedViews = orderedLyricViews(overlay, swapTranslation),
        )
    }

    private fun applyLyricRowOrder(
        overlay: AodPluginOverlay,
        swapTranslation: Boolean,
    ) {
        reorderLyricViews(
            root = overlay.root,
            orderedViews = orderedLyricViews(overlay, swapTranslation),
        )
    }

    private fun orderedLyricViews(
        overlay: LyricOverlay,
        swapTranslation: Boolean,
    ): List<TextView> = AodMediaLyricPolicy.orderedLyricRows(swapTranslation).map { row ->
        when (row) {
            AodLyricRow.MAIN -> overlay.main
            AodLyricRow.TRANSLATION -> overlay.translation
            AodLyricRow.BACKING -> overlay.backing
            AodLyricRow.BACKING_TRANSLATION -> overlay.backingTranslation
            AodLyricRow.OVERLAPPING_MAIN -> overlay.overlappingMain
            AodLyricRow.OVERLAPPING_TRANSLATION -> overlay.overlappingTranslation
            AodLyricRow.OVERLAPPING_BACKING -> overlay.overlappingBacking
            AodLyricRow.OVERLAPPING_BACKING_TRANSLATION ->
                overlay.overlappingBackingTranslation
            AodLyricRow.NEXT -> overlay.next
        }
    }

    private fun orderedLyricViews(
        overlay: AodPluginOverlay,
        swapTranslation: Boolean,
    ): List<TextView> = AodMediaLyricPolicy.orderedLyricRows(swapTranslation).map { row ->
        when (row) {
            AodLyricRow.MAIN -> overlay.main
            AodLyricRow.TRANSLATION -> overlay.translation
            AodLyricRow.BACKING -> overlay.backing
            AodLyricRow.BACKING_TRANSLATION -> overlay.backingTranslation
            AodLyricRow.OVERLAPPING_MAIN -> overlay.overlappingMain
            AodLyricRow.OVERLAPPING_TRANSLATION -> overlay.overlappingTranslation
            AodLyricRow.OVERLAPPING_BACKING -> overlay.overlappingBacking
            AodLyricRow.OVERLAPPING_BACKING_TRANSLATION ->
                overlay.overlappingBackingTranslation
            AodLyricRow.NEXT -> overlay.next
        }
    }

    private fun reorderLyricViews(
        root: LinearLayout,
        orderedViews: List<TextView>,
    ) {
        val currentOrder = (0 until root.childCount)
            .map(root::getChildAt)
            .filter { it in orderedViews }
        if (currentOrder == orderedViews) return
        val firstLyricIndex = orderedViews
            .map(root::indexOfChild)
            .filter { it >= 0 }
            .minOrNull()
            ?: return
        orderedViews.forEach(root::removeView)
        orderedViews.forEachIndexed { index, view ->
            root.addView(view, firstLyricIndex + index)
        }
    }

    private data class ViewSizeSnapshot(
        val view: View,
        val originalLayoutHeight: Int,
        val originalMinimumHeight: Int,
        var baseHeight: Int
    )

    private class BackgroundConstraints private constructor(
        private val view: View,
        private val originalTopMargin: Int,
        private val originalBottomMargin: Int,
        private val verticalBiasField: Field?,
        private val originalVerticalBias: Float?,
    ) {
        var isPinned: Boolean = false
            private set
        fun pinToParentTop(player: View): Boolean {
            if (isPinned) return true
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            val parent = view.parent as? View ?: return false
            if (parent !== player) return false
            verticalBiasField?.setFloat(params, 0f)
            params.topMargin = 0
            params.bottomMargin = 0
            view.layoutParams = params
            isPinned = true
            if (BuildConfig.DEBUG) {
                HookLogger.i(
                    TAG,
                    "full AOD mediaBg 已锚定父容器顶部: " +
                        "backgroundTop=${view.top}, backgroundBottom=${view.bottom}, " +
                        "backgroundTransY=${view.translationY}",
                )
            }
            return true
        }

        fun restore() {
            if (!isPinned) return
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            verticalBiasField?.let { field ->
                originalVerticalBias?.let { value -> field.setFloat(params, value) }
            }
            params.topMargin = originalTopMargin
            params.bottomMargin = originalBottomMargin
            view.layoutParams = params
            isPinned = false
        }

        fun snapshot(): String = runCatching {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                ?: return@runCatching "bgConstraintsUnavailable"
            "bgPinned=$isPinned, " +
                "bgTopMargin=${params.topMargin}, bgBottomMargin=${params.bottomMargin}, " +
                "bgVerticalBias=${verticalBiasField?.getFloat(params)}"
        }.getOrDefault("bgConstraintsUnavailable")

        companion object {
            fun create(view: View): BackgroundConstraints? {
                if (view.parent == null) return null
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return null
                val biasField = runCatching {
                    params.javaClass.getField("verticalBias").apply { isAccessible = true }
                }.getOrNull() ?: return null
                val bias = runCatching { biasField.getFloat(params) }.getOrNull() ?: return null
                return BackgroundConstraints(
                    view = view,
                    originalTopMargin = params.topMargin,
                    originalBottomMargin = params.bottomMargin,
                    verticalBiasField = biasField,
                    originalVerticalBias = bias,
                )
            }
        }
    }

    private class LyricOverlay(
        val root: LinearLayout,
        val lyricContainer: LinearLayout,
        val main: TextView,
        val translation: TextView,
        val backing: TextView,
        val backingTranslation: TextView,
        val overlappingMain: TextView,
        val overlappingTranslation: TextView,
        val overlappingBacking: TextView,
        val overlappingBackingTranslation: TextView,
        val next: TextView,
        val progressRow: View,
        val progressTrack: AodProgressTrackView,
        val progressTimeLeft: TextView,
        val progressTimeRight: TextView,
        val artist: View,
        val album: View,
        val seekBar: View?,
        val actions: List<View>,
        val player: ViewGroup,
        val playerSize: ViewSizeSnapshot,
        val backgroundSize: ViewSizeSnapshot,
        val backgroundConstraints: BackgroundConstraints?,
        val headerHeightController: MediaHeaderHeightController?,
        val drawWakeLock: PowerManager.WakeLock,
        var appliedCardHeight: Int? = null,
        var appliedTextStyle: AodTextStyleConfig? = null,
        var appliedMainAlignment: AodLyricAlignment? = null,
        var appliedBackingAlignment: AodLyricAlignment? = null,
        var appliedOverlappingAlignment: AodLyricAlignment? = null,
        var appliedOverlappingBackingAlignment: AodLyricAlignment? = null,
        var appliedNextAlignment: AodLyricAlignment? = null,
        var lastHeightDriftKey: String? = null,
        var fullAodActive: Boolean = false,
        var heightAnimator: ValueAnimator? = null,
        var compactMode: Boolean = false,
        var waitingForLyrics: Boolean = false,
        /** 紧凑模式排版的原始歌词文本：单行合并后可幂等重算，避免重复拼接。 */
        var compactSourceMain: String = "",
        var compactSourceTranslation: String = "",
        /** 紧凑模式重排去重标志，避免布局回调自我触发死循环。 */
        var compactLayoutScheduled: Boolean = false,
    )

    private class MediaHeaderHeightController private constructor(
        val view: View,
        private val lockScreenHeightField: Field,
        private val setAnimateHeightMethod: Method,
        private val setActualHeightMethod: Method,
        val originalHeight: Int,
        private val originalMinimumHeight: Int
    ) {
        fun currentHeight(): Int = runCatching {
            view.height.takeIf { it > 0 }
                ?: lockScreenHeightField.getInt(view)
        }.getOrDefault(0)

        fun applyFinalHeight(height: Int) {
            if (height <= 0) return
            lockScreenHeightField.setInt(view, height)
            setActualHeightMethod.invoke(view, height, false)
            if (view.minimumHeight != height) {
                view.minimumHeight = height
            }
            view.requestLayout()
            (view.parent as? View)?.requestLayout()
        }

        fun restoreHeight() {
            lockScreenHeightField.setInt(view, originalHeight)
            setAnimateHeightMethod.invoke(view, 0)
            setActualHeightMethod.invoke(view, originalHeight, false)
            if (view.minimumHeight != originalMinimumHeight) {
                view.minimumHeight = originalMinimumHeight
            }
            view.requestLayout()
            (view.parent as? View)?.requestLayout()
        }

        fun actualHeightSnapshot(): String = runCatching {
            "headerH=${view.height}, headerM=${view.measuredHeight}, " +
                "headerField=${lockScreenHeightField.getInt(view)}, " +
                "headerLP=${view.layoutParams?.height}, headerMin=${view.minimumHeight}, " +
                "headerTop=${view.top}, headerTransY=${view.translationY}"
        }.getOrDefault("headerUnavailable")

        companion object {
            fun create(view: View?): MediaHeaderHeightController? {
                if (view?.javaClass?.name != MEDIA_HEADER_VIEW_CLASS) return null
                return runCatching {
                    val viewClass = view.javaClass
                    val lockScreenHeightField = viewClass
                        .getDeclaredField("mediaLockScreenHeight")
                        .apply { isAccessible = true }
                    val setAnimateHeightMethod = viewClass
                        .getDeclaredMethod(
                            "setAnimateHeight",
                            Int::class.javaPrimitiveType
                        )
                        .apply { isAccessible = true }
                    val setActualHeightMethod = viewClass
                        .getMethod(
                            "setActualHeight",
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        )
                        .apply { isAccessible = true }
                    MediaHeaderHeightController(
                        view = view,
                        lockScreenHeightField = lockScreenHeightField,
                        setAnimateHeightMethod = setAnimateHeightMethod,
                        setActualHeightMethod = setActualHeightMethod,
                        originalHeight = lockScreenHeightField.getInt(view),
                        originalMinimumHeight = view.minimumHeight
                    )
                }.onFailure {
                    HookLogger.e(TAG, "初始化锁屏媒体通知外层动态高度接口失败", it)
                }.getOrNull()
            }
        }
    }

    private class AodPluginOverlay(
        val root: LinearLayout,
        val songInfoRow: LinearLayout,
        val sourceIcon: ImageView,
        val songInfo: TextView,
        val main: TextView,
        val translation: TextView,
        val backing: TextView,
        val backingTranslation: TextView,
        val overlappingMain: TextView,
        val overlappingTranslation: TextView,
        val overlappingBacking: TextView,
        val overlappingBackingTranslation: TextView,
        val next: TextView,
        val parent: FrameLayout,
        val aodRoot: FrameLayout,
        val anchor: View,
        val drawWakeLock: PowerManager.WakeLock,
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null,
        var appliedHeight: Int? = null,
        var appliedTextStyle: AodTextStyleConfig? = null,
        var appliedSongInfoPackage: String? = null,
        var appliedSongInfoTextSize: Int? = null,
        var appliedSongInfoShowsIcon: Boolean? = null,
        var appliedMainAlignment: AodLyricAlignment? = null,
        var appliedBackingAlignment: AodLyricAlignment? = null,
        var appliedOverlappingAlignment: AodLyricAlignment? = null,
        var appliedOverlappingBackingAlignment: AodLyricAlignment? = null,
        var appliedNextAlignment: AodLyricAlignment? = null,
        var waitingForLyrics: Boolean = false,
    )

    private data class AodPluginState(
        var attached: Boolean = false,
        var playing: Boolean = false,
        var overlay: AodPluginOverlay? = null,
        var initialRefreshGeneration: Int = 0
    )

    private data class ControllerState(
        var holder: Any? = null,
        var mediaData: Any? = null,
        var fullAod: Boolean = false,
        var aodActive: Boolean = false,
        var lockScreenLyricsActive: Boolean = false,
        var notificationCenterLyricsActive: Boolean = false,
        var playing: Boolean = false,
        var overlay: LyricOverlay? = null,
        val actionVisibilities: MutableMap<View, Int> = LinkedHashMap()
    )

    private class AodPluginApi private constructor(
        val hookMethods: List<Method>,
        private val tableModeContainerField: Field,
        private val notificationIconsField: Field,
        private val isAodShownMethod: Method
    ) {
        fun getTableModeContainer(aodView: Any): View? =
            tableModeContainerField.get(aodView) as? View

        fun getNotificationIcons(aodView: Any): View? =
            notificationIconsField.get(aodView) as? View

        fun isAodShown(aodView: Any): Boolean =
            isAodShownMethod.invoke(aodView) == true

        companion object {
            fun create(classLoader: ClassLoader): AodPluginApi {
                val aodViewClass = classLoader.loadClass(AOD_PLUGIN_VIEW_CLASS)
                val makeNormalPanel = aodViewClass.getDeclaredMethod("makeNormalPanel")
                    .apply { isAccessible = true }
                val onAttached = aodViewClass.getDeclaredMethod("onAttachedToWindow")
                    .apply { isAccessible = true }
                val onDetached = aodViewClass.getDeclaredMethod("onDetachedFromWindow")
                    .apply { isAccessible = true }
                val onPositionTimer = aodViewClass.getDeclaredMethod("onUpdatePositionTimer")
                    .apply { isAccessible = true }
                val onContentLayoutChange = aodViewClass.getDeclaredMethod(
                    "onAodContentLayoutChange",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }
                return AodPluginApi(
                    hookMethods = listOf(
                        makeNormalPanel,
                        onAttached,
                        onDetached,
                        onPositionTimer,
                        onContentLayoutChange
                    ),
                    tableModeContainerField = aodViewClass
                        .getDeclaredField("mTableModeContainer")
                        .apply { isAccessible = true },
                    notificationIconsField = aodViewClass
                        .getDeclaredField("mNotificationIcons")
                        .apply { isAccessible = true },
                    isAodShownMethod = aodViewClass.getDeclaredMethod("isAodShown")
                        .apply { isAccessible = true }
                )
            }
        }
    }

    private class NativeApi private constructor(
        val hookMethods: List<Method>,
        private val holderField: Field,
        private val mediaDataField: Field,
        private val playerField: Field,
        private val mediaBackgroundField: Field?,
        private val albumViewField: Field?,
        private val albumImageField: Field?,
        private val titleTextField: Field,
        private val artistTextField: Field,
        private val actionFields: List<Field>,
        private val seekBarField: Field?,
        private val mediaControllerField: Field,
        private val mediaDataIsPlayingField: Field,
        private val mediaDataPackageNameField: Field
    ) {
        fun getHolder(controller: Any): Any? = holderField.get(controller)
        fun getMediaData(controller: Any): Any? = mediaDataField.get(controller)
        fun getPlayer(holder: Any): ViewGroup = playerField.get(holder) as ViewGroup
        fun getSeekBar(holder: Any): View? =
            runCatching { seekBarField?.get(holder) as? View }.getOrNull()
        fun getMediaBackground(holder: Any): View? =
            runCatching { mediaBackgroundField?.get(holder) as? View }.getOrNull()
        fun getAlbumView(holder: Any): View? =
            runCatching { albumViewField?.get(holder) as? View }.getOrNull()
        fun getAlbumImage(holder: Any): View? =
            runCatching { albumImageField?.get(holder) as? View }.getOrNull()
        fun getTitleText(holder: Any): TextView = titleTextField.get(holder) as TextView
        fun getArtistText(holder: Any): TextView = artistTextField.get(holder) as TextView
        fun getActions(holder: Any): List<View> = actionFields.map { it.get(holder) as View }
        fun isPlaying(mediaData: Any?): Boolean =
            mediaData?.let { mediaDataIsPlayingField.get(it) == true } ?: false
        fun isControllerPlaying(controller: Any): Boolean? {
            val playbackState = (mediaControllerField.get(controller) as? MediaController)
                ?.playbackState ?: return null
            return playbackState.state == PlaybackState.STATE_PLAYING
        }
        fun packageName(mediaData: Any?): String? =
            mediaData?.let { mediaDataPackageNameField.get(it) as? String }
        fun currentPlaybackPosition(controller: Any): Long? {
            val playbackState = (mediaControllerField.get(controller) as? MediaController)
                ?.playbackState ?: return null
            var position = playbackState.position.coerceAtLeast(0L)
            if (
                playbackState.state == PlaybackState.STATE_PLAYING &&
                playbackState.lastPositionUpdateTime > 0L
            ) {
                val elapsed = (SystemClock.elapsedRealtime() - playbackState.lastPositionUpdateTime)
                    .coerceAtLeast(0L)
                position += (elapsed * playbackState.playbackSpeed).toLong()
            }
            return position.coerceAtLeast(0L)
        }

        companion object {
            fun create(classLoader: ClassLoader): NativeApi {
                val controllerClass = classLoader.loadClass(VIEW_CONTROLLER_CLASS)
                val holderClass = classLoader.loadClass(HOLDER_CLASS)
                val mediaDataClass = classLoader.loadClass(MEDIA_DATA_CLASS)
                val attach = controllerClass.getDeclaredMethod("attach", holderClass).accessible()
                val bind = controllerClass.getDeclaredMethod("bindMediaData", mediaDataClass).accessible()
                val detach = controllerClass.getDeclaredMethod("detach").accessible()
                val fullAod = controllerClass.getDeclaredMethod(
                    "onFullAodStateChanged",
                    Boolean::class.javaPrimitiveType
                ).accessible()
                return NativeApi(
                    hookMethods = listOf(attach, bind, detach, fullAod),
                    holderField = controllerClass.getDeclaredField("holder").accessible(),
                    mediaDataField = controllerClass.getDeclaredField("mediaData").accessible(),
                    playerField = holderClass.getDeclaredField("player").accessible(),
                    mediaBackgroundField = holderClass.declaredFields
                        .firstOrNull { it.name == "mediaBg" }
                        ?.accessible(),
                    albumViewField = holderClass.declaredFields
                        .firstOrNull { it.name == "albumView" }
                        ?.accessible(),
                    albumImageField = holderClass.declaredFields
                        .firstOrNull { it.name == "albumImage" }
                        ?.accessible(),
                    titleTextField = holderClass.getDeclaredField("titleText").accessible(),
                    artistTextField = holderClass.getDeclaredField("artistText").accessible(),
                    actionFields = (0..4).map { index ->
                        holderClass.getDeclaredField("action$index").accessible()
                    },
                    seekBarField = holderClass.declaredFields
                        .firstOrNull { it.name == "seekBar" }
                        ?.accessible(),
                    mediaControllerField = controllerClass.getDeclaredField(
                        "mediaController"
                    ).accessible(),
                    mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").accessible(),
                    mediaDataPackageNameField = mediaDataClass.getDeclaredField("packageName").accessible()
                )
            }

            private fun <T : java.lang.reflect.AccessibleObject> T.accessible(): T = apply {
                isAccessible = true
            }
        }
    }

    private class DozeRefreshApi private constructor(
        val hostConstructors: List<Constructor<*>>,
        private val tickRunnableFactory: DozeTickRunnableFactory
    ) {
        private var hostReference = WeakReference<Any>(null)
        private var didLogFirstTick = false

        fun captureHost(host: Any) {
            hostReference = WeakReference(host)
            HookLogger.i(TAG, "已捕获 DozeServiceHost，AOD 原生刷新可用")
        }

        fun requestTick() {
            val host = hostReference.get()
            if (host == null) {
                HookLogger.w(TAG, "跳过 AOD 原生帧刷新: reason=host_unavailable")
                return
            }
            runCatching {
                tickRunnableFactory.create(host).run()
            }
                .onSuccess {
                    if (!didLogFirstTick) {
                        didLogFirstTick = true
                        HookLogger.i(TAG, "已通过 SystemUI dozeTimeTick 提交 AOD 帧")
                    }
                }
                .onFailure { HookLogger.e(TAG, "SystemUI dozeTimeTick 执行失败", it) }
        }

        companion object {
            fun create(classLoader: ClassLoader): DozeRefreshApi {
                val hostClass = classLoader.loadClass(DOZE_SERVICE_HOST_CLASS)
                val tickRunnableClass = loadTickRunnableClass(classLoader)
                return DozeRefreshApi(
                    hostConstructors = hostClass.declaredConstructors
                        .onEach { it.isAccessible = true }
                        .toList(),
                    tickRunnableFactory = DozeTickRunnableFactory.resolve(
                        tickRunnableClass,
                        hostClass,
                    )
                )
            }

            /**
             * 原始 DEX 锁定 DozeUi$$ExternalSyntheticLambda0；部分移植包/新版本会把
             * 脱糖 lambda 的编号重排（Lambda1..Lambda9），按 Runnable 接口自动识别。
             */
            private fun loadTickRunnableClass(classLoader: ClassLoader): Class<*> {
                runCatching { return classLoader.loadClass(DOZE_TICK_RUNNABLE_CLASS) }
                for (index in 1..9) {
                    val candidate = runCatching {
                        classLoader.loadClass(
                            "com.android.systemui.doze.DozeUi\$\$ExternalSyntheticLambda$index",
                        )
                    }.getOrNull() ?: continue
                    if (Runnable::class.java.isAssignableFrom(candidate)) return candidate
                }
                // 都不存在时按原名加载，保持原始报错便于定位。
                return classLoader.loadClass(DOZE_TICK_RUNNABLE_CLASS)
            }
        }
    }
}
