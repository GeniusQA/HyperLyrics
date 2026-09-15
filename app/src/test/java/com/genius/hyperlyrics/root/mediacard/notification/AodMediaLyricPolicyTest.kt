package com.genius.hyperlyrics.root.mediacard.notification

import android.view.Gravity
import com.genius.hyperlyrics.common.RootConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AodMediaLyricPolicyTest {

    @Test
    fun `shows next song preview from the final lyric start`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 54_999L,
                durationMs = 60_000L,
                hasActualLyrics = true,
                lastLyricStartMs = 55_000L,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 55_000L,
                durationMs = 60_000L,
                hasActualLyrics = true,
                lastLyricStartMs = 55_000L,
            )
        )
    }

    @Test
    fun `shows next song preview only in final five seconds without lyrics`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 54_999L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 55_000L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = true,
                positionMs = 60_000L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
    }

    @Test
    fun `does not show next song preview when disabled`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShowNextSongPreview(
                enabled = false,
                positionMs = 59_000L,
                durationMs = 60_000L,
                hasActualLyrics = false,
                lastLyricStartMs = -1L,
            )
        )
    }

    @Test
    fun `formats next song title and artist`() {
        assertEquals(
            "下一首：Song-Artist",
            AodMediaLyricPolicy.formatNextSongPreview("Song", "Artist")
        )
        assertEquals(
            "下一首：Song",
            AodMediaLyricPolicy.formatNextSongPreview("Song", "")
        )
        assertEquals("", AodMediaLyricPolicy.formatNextSongPreview("", ""))
    }

    @Test
    fun `aligns next song preview using its independent position`() {
        assertEquals(
            AodLyricAlignment.LEFT,
            AodMediaLyricPolicy.nextSongPreviewAlignment(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_LEFT
            )
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.nextSongPreviewAlignment(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_CENTER
            )
        )
        assertEquals(
            AodLyricAlignment.RIGHT,
            AodMediaLyricPolicy.nextSongPreviewAlignment(
                RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_RIGHT
            )
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.nextSongPreviewAlignment(Int.MAX_VALUE)
        )
        assertEquals(
            RootConstants.AOD_NEXT_SONG_PREVIEW_POSITION_CENTER,
            RootConstants.DEFAULT_HOOK_AOD_NEXT_SONG_PREVIEW_POSITION
        )
    }

    @Test
    fun `suppresses title placeholder only for non text mode songs without lyrics`() {
        assertTrue(
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = false,
                hasActualLyrics = false,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = true,
                hasActualLyrics = false,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldSuppressNoLyricPlaceholder(
                isTextMode = false,
                hasActualLyrics = true,
            )
        )
    }

    @Test
    fun `uses configured independent gravity for embedded song information`() {
        assertEquals(
            Gravity.LEFT or Gravity.CENTER_VERTICAL,
            AodMediaLyricPolicy.embeddedSongInfoGravity(
                RootConstants.AOD_SONG_INFO_POSITION_LEFT
            )
        )
        assertEquals(
            Gravity.CENTER,
            AodMediaLyricPolicy.embeddedSongInfoGravity(
                RootConstants.AOD_SONG_INFO_POSITION_CENTER
            )
        )
        assertEquals(
            Gravity.RIGHT or Gravity.CENTER_VERTICAL,
            AodMediaLyricPolicy.embeddedSongInfoGravity(
                RootConstants.AOD_SONG_INFO_POSITION_RIGHT
            )
        )
    }

    @Test
    fun `shows lyrics only while enabled playing and in full aod`() {
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = true,
                hasLyric = true,
                packageMatches = true
            )
        )
    }

    @Test
    fun `keeps lyrics visible while paused when configured`() {
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = false,
                hasLyric = true,
                packageMatches = true,
                pauseStyle = RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = false,
                hasLyric = true,
                packageMatches = true,
                pauseStyle = RootConstants.AOD_PAUSE_STYLE_RESTORE,
            )
        )
    }

    @Test
    fun `restores controls when screen is on or playback is paused`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = false,
                playing = true,
                hasLyric = true,
                packageMatches = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = false,
                hasLyric = true,
                packageMatches = true
            )
        )
    }

    @Test
    fun `keeps native controls without lyrics or for another player`() {
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = true,
                hasLyric = false,
                packageMatches = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = true,
                playing = true,
                hasLyric = true,
                packageMatches = false
            )
        )
    }

    @Test
    fun `compacts classic spacing only for a single main lyric line`() {
        assertTrue(AodMediaLyricPolicy.shouldCompactClassicMain(lineCount = 1))
        assertFalse(AodMediaLyricPolicy.shouldCompactClassicMain(lineCount = 2))
        assertFalse(AodMediaLyricPolicy.shouldCompactClassicMain(lineCount = 3))
    }

    @Test
    fun `grows the whole card to the measured lyric bottom without a fixed cap`() {
        assertEquals(
            185,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 185,
                lyricBottom = 140,
                bottomPadding = 12
            )
        )
        assertEquals(
            232,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 185,
                lyricBottom = 220,
                bottomPadding = 12
            )
        )
        assertEquals(
            352,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 185,
                lyricBottom = 340,
                bottomPadding = 12
            )
        )
    }

    @Test
    fun `uses the lower album edge as the real metadata boundary`() {
        assertEquals(
            65,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45
            )
        )
    }

    @Test
    fun `activates notification center lyrics only while awake unlocked and shown`() {
        assertTrue(
            AodMediaLyricPolicy.isNotificationCenterLyricsActive(
                interactive = true,
                keyguardLocked = false,
                playerShown = true,
                featureEnabled = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isNotificationCenterLyricsActive(
                interactive = true,
                keyguardLocked = true,
                playerShown = true,
                featureEnabled = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isNotificationCenterLyricsActive(
                interactive = true,
                keyguardLocked = false,
                playerShown = true,
                featureEnabled = false,
            )
        )
    }

    @Test
    fun `shows lyrics in notification center independently of aod switch`() {
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = false,
                fullAod = false,
                playing = true,
                hasLyric = true,
                packageMatches = true,
                notificationCenter = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = false,
                fullAod = false,
                playing = true,
                hasLyric = true,
                packageMatches = true,
                notificationCenter = false,
            )
        )
    }

    @Test
    fun `anchors lock screen lyrics below the playback actions`() {
        assertEquals(
            150,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45,
                actionBottom = 150,
            )
        )
        assertEquals(
            65,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45,
                actionBottom = 0,
            )
        )
        assertEquals(
            65,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45,
                actionBottom = -3,
            )
        )
    }

    @Test
    fun `uses the cover inset as both lock screen lyric side margins`() {
        assertEquals(
            AodHorizontalMargins(left = 24, right = 24),
            AodMediaLyricPolicy.lockScreenHorizontalMargins(
                playerWidth = 400,
                cardLeft = 0,
                cardRight = 400,
                albumLeft = 24,
            )
        )
    }

    @Test
    fun `keeps equal in-card margins when the media background is offset`() {
        assertEquals(
            AodHorizontalMargins(left = 32, right = 28),
            AodMediaLyricPolicy.lockScreenHorizontalMargins(
                playerWidth = 420,
                cardLeft = 8,
                cardRight = 416,
                albumLeft = 32,
            )
        )
    }

    @Test
    fun `adds the configured extra inset to both lock screen lyric margins`() {
        assertEquals(
            AodHorizontalMargins(left = 27, right = 27),
            AodMediaLyricPolicy.lockScreenHorizontalMargins(
                playerWidth = 400,
                cardLeft = 0,
                cardRight = 400,
                albumLeft = 24,
                extraInset = 3,
            )
        )
    }

    @Test
    fun `anchors lock screen lyric top below the media content`() {
        val lyricTop = AodMediaLyricPolicy.lockScreenLyricTop(
            anchorBottom = 65,
            topGap = 17
        )

        assertEquals(82, lyricTop)
        assertEquals(
            138,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 138,
                lyricBottom = lyricTop + 18,
                bottomPadding = 21
            )
        )
    }

    @Test
    fun `ignores invisible action and seek bar geometry when anchoring`() {
        assertEquals(
            65,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45,
                actionBottom = 150,
                seekBarBottom = 200,
                actionVisible = false,
                seekBarVisible = false,
            )
        )
        assertEquals(
            200,
            AodMediaLyricPolicy.contentAnchorBottom(
                albumBottom = 65,
                artistBottom = 45,
                actionBottom = 150,
                seekBarBottom = 200,
                actionVisible = true,
                seekBarVisible = true,
            )
        )
    }

    @Test
    fun `centers full aod lyrics between song info and progress top`() {
        val layout = AodMediaLyricPolicy.lockScreenCenteredLyricLayout(
            nativeCardHeight = 500,
            anchorBottom = 100,
            lyricContentHeight = 80,
            progressRowHeight = 20,
            progressRowTopMargin = 4,
            bottomGap = 4,
            minTopGap = 4,
        )

        assertEquals(500, layout.targetCardHeight)
        assertEquals(104, layout.rootTop)
        assertEquals(392, layout.rootHeight)
        assertEquals(368, layout.lyricContainerHeight)
        // 歌词区中心 = (歌曲信息下缘 + 进度条上缘) / 2
        val progressTop = layout.rootTop + layout.lyricContainerHeight + 4
        assertEquals(100 + (progressTop - 100) / 2, layout.rootTop + layout.lyricContainerHeight / 2)
    }

    @Test
    fun `grows full aod card when multiline lyrics exceed the native band`() {
        val layout = AodMediaLyricPolicy.lockScreenCenteredLyricLayout(
            nativeCardHeight = 500,
            anchorBottom = 100,
            lyricContentHeight = 400,
            progressRowHeight = 20,
            progressRowTopMargin = 4,
            bottomGap = 4,
            minTopGap = 4,
        )

        assertEquals(532, layout.targetCardHeight)
        assertEquals(104, layout.rootTop)
        assertEquals(424, layout.rootHeight)
        assertEquals(400, layout.lyricContainerHeight)
        assertTrue(layout.targetCardHeight > 500)
    }

    @Test
    fun `grows the card below fixed-top tall lyrics`() {
        val lyricTop = AodMediaLyricPolicy.lockScreenLyricTop(
            anchorBottom = 65,
            topGap = 17
        )

        assertEquals(82, lyricTop)
        assertEquals(
            227,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 138,
                lyricBottom = lyricTop + 124,
                bottomPadding = 21
            )
        )
    }

    @Test
    fun `does not cap classic aod lyrics at the old fixed height`() {
        assertEquals(
            246,
            AodMediaLyricPolicy.classicOverlayHeight(
                contentHeight = 246,
                availableHeight = 420
            )
        )
    }

    @Test
    fun `caps classic aod lyrics only at the physical bottom boundary`() {
        assertEquals(
            240,
            AodMediaLyricPolicy.classicOverlayHeight(
                contentHeight = 300,
                availableHeight = 240
            )
        )
    }

    @Test
    fun `starts lock screen lyrics during the non interactive aod transition`() {
        assertTrue(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = true,
                interactive = true,
                playerShown = true
            )
        )
        assertTrue(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = false,
                interactive = false,
                playerShown = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = false,
                interactive = true,
                playerShown = true
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenAodActive(
                fullAod = false,
                interactive = false,
                playerShown = false
            )
        )
    }

    @Test
    fun `activates lock screen lyrics only while awake locked and shown`() {
        assertTrue(
            AodMediaLyricPolicy.isLockScreenLyricsActive(
                interactive = true,
                keyguardLocked = true,
                playerShown = true,
                featureEnabled = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenLyricsActive(
                interactive = false,
                keyguardLocked = true,
                playerShown = true,
                featureEnabled = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenLyricsActive(
                interactive = true,
                keyguardLocked = false,
                playerShown = true,
                featureEnabled = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenLyricsActive(
                interactive = true,
                keyguardLocked = true,
                playerShown = false,
                featureEnabled = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.isLockScreenLyricsActive(
                interactive = true,
                keyguardLocked = true,
                playerShown = true,
                featureEnabled = false,
            )
        )
    }

    @Test
    fun `shows lyrics during awake lock screen when lock screen lyrics enabled`() {
        val base = mapOf(
            "enabled" to true,
            "fullAod" to false,
            "playing" to true,
            "hasLyric" to true,
            "packageMatches" to true,
        )
        fun show(lockScreenLyrics: Boolean) = AodMediaLyricPolicy.shouldShow(
            enabled = base["enabled"] as Boolean,
            fullAod = base["fullAod"] as Boolean,
            playing = base["playing"] as Boolean,
            hasLyric = base["hasLyric"] as Boolean,
            packageMatches = base["packageMatches"] as Boolean,
            lockScreenLyrics = lockScreenLyrics,
        )
        assertTrue(show(lockScreenLyrics = true))
        assertFalse(show(lockScreenLyrics = false))
        // 锁屏歌词开启时独立于息屏歌词总开关生效
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = false,
                fullAod = false,
                playing = true,
                hasLyric = true,
                packageMatches = true,
                lockScreenLyrics = true,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = false,
                fullAod = false,
                playing = true,
                hasLyric = true,
                packageMatches = true,
                lockScreenLyrics = false,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = false,
                playing = false,
                hasLyric = true,
                packageMatches = true,
                lockScreenLyrics = true,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.shouldShow(
                enabled = true,
                fullAod = false,
                playing = false,
                hasLyric = true,
                packageMatches = true,
                pauseStyle = RootConstants.AOD_PAUSE_STYLE_KEEP_LYRICS,
                lockScreenLyrics = true,
            )
        )
    }

    @Test
    fun `keeps main translation backing vocal and its translation in display order`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = "主句翻译",
            backing = "Backing vocal",
            backingTranslation = "伴唱翻译",
            roma = "Main lyric pronunciation",
            translationDisplay = true,
        )

        assertEquals("Main lyric", content.main)
        assertEquals("主句翻译", content.translation)
        assertEquals("Backing vocal", content.backing)
        assertEquals("伴唱翻译", content.backingTranslation)
    }

    @Test
    fun `hides every translation layer while preserving original lyric rows`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "First",
            translation = "第一句翻译",
            backing = "First backing",
            backingTranslation = "第一句伴唱翻译",
            roma = null,
            overlappingMain = "Second",
            overlappingTranslation = "第二句翻译",
            overlappingBacking = "Second backing",
            overlappingBackingTranslation = "第二句伴唱翻译",
            next = "Next",
            showNext = true,
            translationDisplay = false,
        )

        assertEquals("First", content.main)
        assertEquals("", content.translation)
        assertEquals("First backing", content.backing)
        assertEquals("", content.backingTranslation)
        assertEquals("Second", content.overlappingMain)
        assertEquals("", content.overlappingTranslation)
        assertEquals("Second backing", content.overlappingBacking)
        assertEquals("", content.overlappingBackingTranslation)
        assertEquals("Next", content.next)
    }

    @Test
    fun `swaps each original lyric and translation row without moving next lyric`() {
        assertEquals(
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
            ),
            AodMediaLyricPolicy.orderedLyricRows(swapTranslation = false),
        )
        assertEquals(
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
            ),
            AodMediaLyricPolicy.orderedLyricRows(swapTranslation = true),
        )
    }

    @Test
    fun `uses pronunciation only as the original single secondary row fallback`() {
        val withoutBacking = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = "Main lyric pronunciation",
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            translationFallback = true,
        )
        val withBacking = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = null,
            backing = "Backing vocal",
            backingTranslation = null,
            roma = "Main lyric pronunciation",
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            translationFallback = true,
        )

        assertEquals("Main lyric pronunciation", withoutBacking.translation)
        assertEquals("", withBacking.translation)
        assertEquals("Backing vocal", withBacking.backing)
    }

    @Test
    fun `does not show a detached backing vocal translation`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Main lyric",
            translation = "主句翻译",
            backing = null,
            backingTranslation = "伴唱翻译",
            roma = null,
            translationDisplay = true,
        )

        assertEquals("", content.backing)
        assertEquals("", content.backingTranslation)
    }

    @Test
    fun `uses the overlapping lyric second line direction for duet aod display`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Left lyric",
            translation = null,
            backing = "Right lyric",
            backingTranslation = null,
            roma = null,
            mainAlignedRight = false,
            backingAlignedRight = true,
            duetLyrics = true,
        )

        assertEquals(AodLyricAlignment.LEFT, content.mainAlignment)
        assertEquals(AodLyricAlignment.RIGHT, content.backingAlignment)
    }

    @Test
    fun `keeps both overlapping lyric content hierarchies for aod`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "First",
            translation = "第一句翻译",
            backing = "First backing",
            backingTranslation = "第一句伴唱翻译",
            roma = null,
            overlappingMain = "Second",
            overlappingTranslation = "第二句翻译",
            overlappingBacking = "Second backing",
            overlappingBackingTranslation = "第二句伴唱翻译",
            translationDisplay = true,
        )

        assertEquals("First", content.main)
        assertEquals("第一句翻译", content.translation)
        assertEquals("First backing", content.backing)
        assertEquals("第一句伴唱翻译", content.backingTranslation)
        assertEquals("Second", content.overlappingMain)
        assertEquals("第二句翻译", content.overlappingTranslation)
        assertEquals("Second backing", content.overlappingBacking)
        assertEquals("第二句伴唱翻译", content.overlappingBackingTranslation)
    }

    @Test
    fun `keeps the existing lock screen and classic text sizes as defaults`() {
        assertEquals(18, RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_MAIN_TEXT_SIZE)
        assertEquals(17, RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_BACKING_TEXT_SIZE)
        assertEquals(15, RootConstants.DEFAULT_HOOK_LOCK_SCREEN_AOD_TRANSLATION_TEXT_SIZE)
        assertEquals(26, RootConstants.DEFAULT_HOOK_CLASSIC_AOD_MAIN_TEXT_SIZE)
        assertEquals(23, RootConstants.DEFAULT_HOOK_CLASSIC_AOD_BACKING_TEXT_SIZE)
        assertEquals(21, RootConstants.DEFAULT_HOOK_CLASSIC_AOD_TRANSLATION_TEXT_SIZE)
    }

    @Test
    fun `uses valid text sizes and falls back from values outside their range`() {
        assertEquals(
            30,
            AodMediaLyricPolicy.sanitizeTextSize(
                value = 30,
                defaultValue = 18,
                min = 12,
                max = 40,
            )
        )
        assertEquals(
            18,
            AodMediaLyricPolicy.sanitizeTextSize(
                value = 41,
                defaultValue = 18,
                min = 12,
                max = 40,
            )
        )
    }

    @Test
    fun `keeps next lyric hidden by default`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Next",
            showNext = RootConstants.DEFAULT_HOOK_AOD_SHOW_NEXT_LYRIC,
        )

        assertEquals("", content.next)
    }

    @Test
    fun `keeps all lyrics centered while duet lyrics are disabled`() {
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = false,
                alignedRight = true,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
    }

    @Test
    fun `aligns duet lyrics left and right when enabled`() {
        assertEquals(
            AodLyricAlignment.LEFT,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = false,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
        assertEquals(
            AodLyricAlignment.RIGHT,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = true,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
    }

    @Test
    fun `centers every lyric layer for a non-duet song when enabled`() {
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                centerNonDuetSong = true,
                alignedRight = false,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                centerNonDuetSong = true,
                alignedRight = true,
                groupVocals = false,
                centerGroupVocals = false,
            ),
        )
    }

    @Test
    fun `centers group vocals only when the dependent option is enabled`() {
        assertEquals(
            AodLyricAlignment.LEFT,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = false,
                groupVocals = true,
                centerGroupVocals = false,
            ),
        )
        assertEquals(
            AodLyricAlignment.CENTER,
            AodMediaLyricPolicy.lyricAlignment(
                duetLyrics = true,
                alignedRight = false,
                groupVocals = true,
                centerGroupVocals = true,
            ),
        )
    }

    @Test
    fun `keeps current and next lyric alignment independent`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Left singer",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Right singer",
            showNext = true,
            mainAlignedRight = false,
            nextAlignedRight = true,
            duetLyrics = true,
        )

        assertEquals(AodLyricAlignment.LEFT, content.mainAlignment)
        assertEquals(AodLyricAlignment.RIGHT, content.nextAlignment)
    }

    @Test
    fun `keeps duet and group vocal centering disabled by default`() {
        assertFalse(RootConstants.DEFAULT_HOOK_AOD_DUET_LYRICS)
        assertFalse(RootConstants.DEFAULT_HOOK_AOD_CENTER_GROUP_VOCALS)
    }

    @Test
    fun `shows a distinct next lyric only when enabled`() {
        val next = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Next",
            showNext = true,
        )
        val duplicate = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Current",
            showNext = true,
        )

        assertEquals("Next", next.next)
        assertEquals("", duplicate.next)
    }

    @Test
    fun `hides next lyric while main translation is displayed`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = "Translation",
            backing = null,
            backingTranslation = null,
            roma = null,
            next = "Next",
            showNext = true,
            translationDisplay = true,
        )

        assertEquals("Translation", content.translation)
        assertEquals("", content.next)
    }

    @Test
    fun `hides next lyric while pronunciation fallback is displayed`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = "Pronunciation",
            next = "Next",
            showNext = true,
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            translationFallback = true,
        )

        assertEquals("Pronunciation", content.translation)
        assertEquals("", content.next)
    }

    @Test
    fun `hides next lyric while backing vocal translation is displayed`() {
        val content = AodMediaLyricPolicy.assembleContent(
            main = "Current",
            translation = null,
            backing = "Backing",
            backingTranslation = "Backing translation",
            roma = null,
            next = "Next",
            showNext = true,
            translationDisplay = true,
        )

        assertEquals("Backing translation", content.backingTranslation)
        assertEquals("", content.next)
    }

    @Test
    fun `grows lock screen card for measured content including next lyric`() {
        assertEquals(
            278,
            AodMediaLyricPolicy.requiredCardHeight(
                nativeCardHeight = 138,
                lyricBottom = 257,
                bottomPadding = 21,
            )
        )
    }

    @Test
    fun `shrinks lock screen card when multiline lyrics return to one line`() {
        val multilineHeight = AodMediaLyricPolicy.lockScreenTargetCardHeight(
            nativeCardHeight = 429,
            lyricBottom = 607,
            bottomPadding = 21,
        )
        val singleLineHeight = AodMediaLyricPolicy.lockScreenTargetCardHeight(
            nativeCardHeight = 429,
            lyricBottom = 453,
            bottomPadding = 21,
        )

        assertEquals(628, multilineHeight)
        assertEquals(474, singleLineHeight)
        assertTrue(singleLineHeight < multilineHeight)
    }

    @Test
    fun `keeps full aod media background top fixed while card grows downward`() {
        assertEquals(
            698,
            AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
                targetCardHeight = 698,
            )
        )
    }

    @Test
    fun `clamps invalid full aod background height`() {
        assertEquals(
            0,
            AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
                targetCardHeight = -1,
            )
        )
    }

    @Test
    fun `full aod media background still shrinks with the current lyric`() {
        val multilineCardHeight = AodMediaLyricPolicy.lockScreenTargetCardHeight(
            nativeCardHeight = 429,
            lyricBottom = 607,
            bottomPadding = 21,
        )
        val singleLineCardHeight = AodMediaLyricPolicy.lockScreenTargetCardHeight(
            nativeCardHeight = 429,
            lyricBottom = 453,
            bottomPadding = 21,
        )

        assertEquals(
            628,
            AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
                targetCardHeight = multilineCardHeight,
            )
        )
        assertEquals(
            474,
            AodMediaLyricPolicy.lockScreenBackgroundTargetHeight(
                targetCardHeight = singleLineCardHeight,
            )
        )
    }

    @Test
    fun `selects mode aware native height for the lock screen card`() {
        assertEquals(
            429,
            AodMediaLyricPolicy.lockScreenNativeCardHeight(
                fullAod = true,
                fullAodBaseHeight = 429,
                playerBaseHeight = 579,
            )
        )
        assertEquals(
            579,
            AodMediaLyricPolicy.lockScreenNativeCardHeight(
                fullAod = false,
                fullAodBaseHeight = 429,
                playerBaseHeight = 579,
            )
        )
    }

    @Test
    fun `detects lock screen height drift only for a positive applied height`() {
        assertTrue(
            AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                appliedHeight = 532,
                currentLayoutHeight = 429,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                appliedHeight = 532,
                currentLayoutHeight = 532,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                appliedHeight = null,
                currentLayoutHeight = 429,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                appliedHeight = 0,
                currentLayoutHeight = 429,
            )
        )
        assertFalse(
            AodMediaLyricPolicy.lockScreenHeightNeedsReassert(
                appliedHeight = 532,
                currentLayoutHeight = null,
            )
        )
    }

    @Test
    fun `restores native lock screen height only after this overlay changed it`() {
        assertFalse(
            AodMediaLyricPolicy.lockScreenHeightNeedsRestore(
                appliedHeight = null,
                heightAnimationActive = false,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.lockScreenHeightNeedsRestore(
                appliedHeight = 532,
                heightAnimationActive = false,
            )
        )
        assertTrue(
            AodMediaLyricPolicy.lockScreenHeightNeedsRestore(
                appliedHeight = null,
                heightAnimationActive = true,
            )
        )
    }

    @Test
    fun `lock screen AOD never takes ownership of notification stack translation y`() {
        val relativeSourcePath =
            "src/main/java/com/juren233/hyperlyrics/root/mediacard/notification/" +
                "NotificationMediaAodLyricHooker.kt"
        val sourceFile = listOf(File("app/$relativeSourcePath"), File(relativeSourcePath))
            .first(File::isFile)
        val source = sourceFile.readText()

        assertFalse(
            "MiuiMediaHeaderView.translationY belongs to the notification stack positioner",
            source.contains("headerHeightController?.view?.translationY") ||
                source.contains("pinnedHeaderTop"),
        )
    }

    @Test
    fun `assembleContent respects translation and pronunciation modes and fallback`() {
        val lineBoth = AodMediaLyricPolicy.assembleContent(
            main = "Main",
            translation = "Trans",
            backing = null,
            backingTranslation = null,
            roma = "Roma",
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            translationFallback = false,
        )
        assertEquals("Trans", lineBoth.translation)

        val lineNoTransNoFallback = AodMediaLyricPolicy.assembleContent(
            main = "Main",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = "Roma",
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            translationFallback = false,
        )
        assertEquals("", lineNoTransNoFallback.translation)

        val lineNoTransWithFallback = AodMediaLyricPolicy.assembleContent(
            main = "Main",
            translation = null,
            backing = null,
            backingTranslation = null,
            roma = "Roma",
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_TRANSLATION,
            translationFallback = true,
        )
        assertEquals("Roma", lineNoTransWithFallback.translation)

        val linePronunciationNoFallback = AodMediaLyricPolicy.assembleContent(
            main = "Main",
            translation = "Trans",
            backing = null,
            backingTranslation = null,
            roma = null,
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            translationFallback = false,
        )
        assertEquals("", linePronunciationNoFallback.translation)

        val linePronunciationWithFallback = AodMediaLyricPolicy.assembleContent(
            main = "Main",
            translation = "Trans",
            backing = null,
            backingTranslation = null,
            roma = null,
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_PRONUNCIATION,
            translationFallback = true,
        )
        assertEquals("Trans", linePronunciationWithFallback.translation)

        val lineOff = AodMediaLyricPolicy.assembleContent(
            main = "Main",
            translation = "Trans",
            backing = null,
            backingTranslation = null,
            roma = "Roma",
            translationDisplayMode = RootConstants.TRANSLATION_PRONUNCIATION_DISPLAY_OFF,
            translationFallback = true,
        )
        assertEquals("", lineOff.translation)
    }

    @Test
    fun `derives upcoming budget from total line count`() {
        assertEquals(0, AodMediaLyricPolicy.upcomingLineBudget(2))
        assertEquals(1, AodMediaLyricPolicy.upcomingLineBudget(3))
        assertEquals(3, AodMediaLyricPolicy.upcomingLineBudget(5))

        assertEquals(2, AodMediaLyricPolicy.lyricRowMaxLines(2))
        assertEquals(2, AodMediaLyricPolicy.lyricRowMaxLines(3))
        assertEquals(2, AodMediaLyricPolicy.lyricRowMaxLines(5))
    }

    @Test
    fun `sanitizes lyric area height into allowed range`() {
        assertEquals(
            RootConstants.DEFAULT_HOOK_LYRIC_AREA_HEIGHT,
            AodMediaLyricPolicy.sanitizeLyricAreaHeight(null)
        )
        assertEquals(120, AodMediaLyricPolicy.sanitizeLyricAreaHeight(120))
        assertEquals(300, AodMediaLyricPolicy.sanitizeLyricAreaHeight(300))
        assertEquals(500, AodMediaLyricPolicy.sanitizeLyricAreaHeight(500))
        assertEquals(120, AodMediaLyricPolicy.sanitizeLyricAreaHeight(50))
        assertEquals(500, AodMediaLyricPolicy.sanitizeLyricAreaHeight(999))
    }

    @Test
    fun `derives max lines from area height and line height`() {
        // 300dp / (20sp * 1.2 倍率) ≈ 12 行
        val maxLines = AodMediaLyricPolicy.lyricAreaMaxLines(
            300,
            (20f * AodMediaLyricPolicy.LYRIC_LINE_HEIGHT_MULTIPLIER).toInt()
        )
        assertTrue(maxLines in 10..14)
        // 可用空间过小或字号过大时，至少保留主句预留行数
        assertEquals(2, AodMediaLyricPolicy.lyricAreaMaxLines(10, 100))
    }
}
