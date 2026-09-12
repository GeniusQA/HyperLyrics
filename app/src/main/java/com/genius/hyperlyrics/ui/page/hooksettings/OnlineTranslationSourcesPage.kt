/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.ui.page.hooksettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.common.RootConstants
import com.genius.hyperlyrics.online.OnlineTranslationSourcePreferences
import com.genius.hyperlyrics.online.OnlineLyricTargeter
import com.genius.hyperlyrics.online.SourceMatchDiagnostic
import com.genius.hyperlyrics.online.model.ManualLyricMatchRequest
import com.genius.hyperlyrics.online.model.SongSearchResult
import com.genius.hyperlyrics.online.model.Source
import com.genius.hyperlyrics.service.LiveLyricService
import com.genius.hyperlyrics.ui.component.ProComponent
import com.genius.hyperlyrics.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.genius.hyperlyrics.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.genius.hyperlyrics.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun OnlineTranslationSourcesPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    val sourceOrder = remember {
        mutableStateListOf<Source>().apply {
            addAll(
                OnlineTranslationSourcePreferences.normalizeOrder(
                    prefs.getString(
                        com.genius.hyperlyrics.common.RootConstants
                            .KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                        com.genius.hyperlyrics.common.RootConstants
                            .DEFAULT_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                    )
                )
            )
        }
    }
    val configuredEnabledSources = remember {
        sourceOrder.filter { source ->
            OnlineTranslationSourcePreferences.isSourceEnabled(prefs, source)
        }
    }
    val initialEnabledSources = remember {
        OnlineTranslationSourcePreferences.resolveEnabledSources(sourceOrder) { source ->
            source in configuredEnabledSources
        }
    }
    val sourceEnabled = remember {
        mutableStateMapOf<Source, Boolean>().apply {
            sourceOrder.forEach { source ->
                this[source] = source in initialEnabledSources
            }
        }
    }
    var autoSelectBestSource by remember {
        mutableStateOf(
            OnlineTranslationSourcePreferences.isAutoSelectBestSourceEnabled(prefs)
        )
    }
    var saltPreferOnline by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
                RootConstants.DEFAULT_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
            )
        )
    }
    var pendingSwap by remember { mutableStateOf<SourceSwap?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }
    // 歌词来源链路弹窗：可视化展示当前歌曲歌词+翻译的完整获取链路。
    var showChainDialog by remember { mutableStateOf(false) }
    // 二次匹配：手动输入歌名/歌手/专辑，搜索候选后选中重新抓词。
    var showManualMatchDialog by remember { mutableStateOf(false) }
    var manualTitle by remember { mutableStateOf("") }
    var manualArtist by remember { mutableStateOf("") }
    var manualAlbum by remember { mutableStateOf("") }
    var manualSearching by remember { mutableStateOf(false) }
    var manualResults by remember { mutableStateOf<List<SongSearchResult>>(emptyList()) }
    var manualError by remember { mutableStateOf<String?>(null) }
    val swapProgress = remember { Animatable(0f) }
    val sourceRowHeightPx = with(LocalDensity.current) { SOURCE_ROW_HEIGHT.toPx() }
    val appEnabled = remember {
        mutableStateMapOf<String, Boolean>().apply {
            ENABLED_APPS.forEach { app ->
                this[app.packageName] = OnlineTranslationSourcePreferences.isAppEnabled(
                    prefs,
                    app.packageName,
                )
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // App 进程内通过 MediaSessionManager 读取当前媒体会话（依赖 LiveLyricService 通知监听权限）。
    var currentTrack by remember { mutableStateOf<Pair<String, String>?>(null) }
    var currentAlbum by remember { mutableStateOf("") }
    var currentDurationMs by remember { mutableStateOf(0L) }
    var currentPackage by remember { mutableStateOf<String?>(null) }
    var listenerEnabled by remember { mutableStateOf<Boolean?>(null) }
    val sourceDiagnostics = remember { mutableStateMapOf<Source, SourceMatchDiagnostic?>() }
    var diagnosing by remember { mutableStateOf(false) }
    // 当前播放 App 已在“启用 App”中开启时才展示在线源匹配信息；关闭则走原生歌词，隐藏匹配数据。
    val currentAppOnlineEnabled = currentPackage != null && appEnabled[currentPackage] == true

    fun isNotificationListenerEnabled(): Boolean =
        runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)

    fun queryCurrentTrack(): Boolean {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return false
        val componentName = ComponentName(context, LiveLyricService::class.java)
        val controllers = runCatching { manager.getActiveSessions(componentName) }
            .getOrDefault(emptyList())
        // 仅认“正在播放”的会话：暂停/残留会话不代表当前在放歌，不应展示为当前歌曲。
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        currentPackage = active?.packageName
        if (active == null) {
            listenerEnabled = isNotificationListenerEnabled()
            return false
        }
        val metadata = active?.metadata ?: run {
            listenerEnabled = isNotificationListenerEnabled()
            return false
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        if (title.isEmpty()) {
            listenerEnabled = isNotificationListenerEnabled()
            return false
        }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty().trim()
        currentTrack = title to artist
        currentAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim()
        currentDurationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        listenerEnabled = true
        return true
    }

    var lastDiagnosisAtMs by remember { mutableStateOf(0L) }

    fun runSourceDiagnosis(force: Boolean = false) {
        val (title, artist) = currentTrack ?: return
        if (!currentAppOnlineEnabled) return
        val order = sourceOrder.filter { sourceEnabled[it] == true }
        if (order.isEmpty()) return
        // 冷却节流：诊断会真实请求各在线源，高频重复会触发 LRCLIB 等源的限流（返回空），
        // 连累 Provider 抓词同 IP 被限。自动触发受 5 秒冷却，手动刷新按钮不受限。
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastDiagnosisAtMs < 5_000L) return
        lastDiagnosisAtMs = now
        diagnosing = true
        scope.launch {
            runCatching {
                val result = OnlineLyricTargeter.diagnoseSourceMatches(
                    context = context,
                    title = title,
                    artist = artist,
                    album = currentAlbum,
                    durationMs = currentDurationMs,
                    sourceOrder = order,
                )
                sourceDiagnostics.clear()
                result.forEach { sourceDiagnostics[it.source] = it }
            }
            diagnosing = false
        }
    }

    fun openManualMatchDialog() {
        manualTitle = currentTrack?.first ?: ""
        manualArtist = currentTrack?.second ?: ""
        manualAlbum = currentAlbum
        manualResults = emptyList()
        manualError = null
        showManualMatchDialog = true
    }

    fun submitManualMatchSearch() {
        if (manualTitle.isBlank() || manualArtist.isBlank()) {
            manualError = context.getString(R.string.manual_match_required_hint)
            return
        }
        val order = sourceOrder.filter { sourceEnabled[it] == true }.ifEmpty {
            sourceOrder.filter { it != Source.LRCLIB }
        }
        manualSearching = true
        manualError = null
        scope.launch {
            val results = runCatching {
                OnlineLyricTargeter.searchCandidates(
                    context = context,
                    title = manualTitle,
                    artist = manualArtist,
                    album = manualAlbum.takeIf { it.isNotBlank() },
                    sourceOrder = order,
                )
            }.getOrElse { error ->
                manualError = error.message ?: context.getString(R.string.manual_match_search_failed)
                emptyList()
            }
            manualResults = results
            manualSearching = false
            if (results.isEmpty() && manualError == null) {
                manualError = context.getString(R.string.manual_match_empty)
            }
        }
    }

    fun applyManualMatch(candidate: SongSearchResult) {
        val request = ManualLyricMatchRequest(
            currentTitle = currentTrack?.first ?: "",
            currentArtist = currentTrack?.second ?: "",
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album,
            source = candidate.source.name,
            sourceSongId = candidate.id,
            durationMs = candidate.duration,
            requestedAtMs = System.currentTimeMillis(),
        )
        saveConfig(
            RootConstants.KEY_HOOK_MANUAL_LYRIC_MATCH_REQUEST,
            ManualLyricMatchRequest.encode(request),
        )
        showManualMatchDialog = false
        Toast.makeText(
            context,
            context.getString(R.string.manual_match_submitted),
            Toast.LENGTH_SHORT,
        ).show()
    }

    LaunchedEffect(currentTrack) {
        if (currentTrack != null) {
            delay(800)
            runSourceDiagnosis()
        }
    }

    LaunchedEffect(currentAppOnlineEnabled) {
        // App 被禁用后清空残留匹配数据，页面只呈现原生歌词状态。
        if (!currentAppOnlineEnabled) sourceDiagnostics.clear()
    }

    DisposableEffect(Unit) {
        queryCurrentTrack()
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val componentName = ComponentName(context, LiveLyricService::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener {
            val previous = currentTrack
            if (queryCurrentTrack() && currentTrack != previous) {
                sourceDiagnostics.clear()
            }
        }
        runCatching {
            manager?.addOnActiveSessionsChangedListener(listener, componentName, Handler(Looper.getMainLooper()))
        }
        onDispose {
            runCatching { manager?.removeOnActiveSessionsChangedListener(listener) }
        }
    }

    var installedApps by remember { mutableStateOf<List<InstalledTranslationApp>?>(null) }
    LaunchedEffect(Unit) {
        if (configuredEnabledSources.isEmpty()) {
            initialEnabledSources.firstOrNull()?.let { source ->
                saveConfig(
                    OnlineTranslationSourcePreferences.sourcePreferenceKey(source),
                    true,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val installedPackageNames = packageManager
                .getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                .map { it.packageName }
                .toSet()
            ENABLED_APPS
                .filter { it.packageName in installedPackageNames }
                .mapNotNull { app ->
                    runCatching {
                        val info = packageManager.getApplicationInfo(app.packageName, 0)
                        InstalledTranslationApp(
                            app = app,
                            icon = info.loadIcon(packageManager),
                        )
                    }.getOrNull()
                }
        }
    }

    // 当前正在播放但不在固定启用列表里的播放器（通用歌词源场景），
    // 动态加入“启用 App”列表，让用户能开关在线翻译并查看匹配诊断。
    val dynamicCurrentApp = remember(currentPackage) {
        currentPackage?.takeIf { pkg ->
            pkg !in NATIVE_LYRIC_PACKAGES && ENABLED_APPS.none { it.packageName == pkg }
        }?.let { pkg ->
            runCatching {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                InstalledTranslationApp(
                    app = TranslationApp(
                        packageName = pkg,
                        displayName = info.loadLabel(pm).toString(),
                        summaryRes = R.string.summary_online_translation_app_lyrics_translation,
                    ),
                    icon = info.loadIcon(pm),
                )
            }.getOrNull()
        }
    }
    LaunchedEffect(currentPackage) {
        currentPackage?.let { pkg ->
            if (pkg !in appEnabled) {
                appEnabled[pkg] = OnlineTranslationSourcePreferences.isAppEnabled(prefs, pkg)
            }
        }
    }

    /** 请求相邻来源互换，动画期间拒绝新的排序操作。 */
    fun requestSourceMove(source: Source, direction: Int) {
        if (pendingSwap != null || swapProgress.value != 0f) return
        val sourceIndex = sourceOrder.indexOf(source)
        val targetIndex = sourceIndex + direction
        if (sourceIndex !in sourceOrder.indices || targetIndex !in sourceOrder.indices) return
        pendingSwap = SourceSwap(
            source = source,
            adjacentSource = sourceOrder[targetIndex],
            direction = direction,
        )
    }

    // 两个相邻行沿相反方向移动，抵达目标位置后再提交实际顺序。
    LaunchedEffect(pendingSwap) {
        val swap = pendingSwap
        if (swap == null) {
            swapProgress.snapTo(0f)
            return@LaunchedEffect
        }
        swapProgress.snapTo(0f)
        swapProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = SOURCE_SWAP_ANIMATION_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        )
        val sourceIndex = sourceOrder.indexOf(swap.source)
        val targetIndex = sourceOrder.indexOf(swap.adjacentSource)
        val orderChanged = targetIndex == sourceIndex + swap.direction
        Snapshot.withMutableSnapshot {
            if (orderChanged) {
                sourceOrder[sourceIndex] = swap.adjacentSource
                sourceOrder[targetIndex] = swap.source
            }
            pendingSwap = null
        }
        if (orderChanged) {
            saveConfig(
                com.genius.hyperlyrics.common.RootConstants
                    .KEY_HOOK_ONLINE_TRANSLATION_SOURCE_ORDER,
                OnlineTranslationSourcePreferences.serializeOrder(sourceOrder),
            )
        }
    }

    XposedLyricSettingPage(
        title = stringResource(R.string.title_online_translation_sources),
        actions = {
            IconButton(onClick = { showHelpDialog = true }) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.online_translation_help),
                )
            }
        },
    ) {
        item(key = "platform_sources_title") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.title_online_translation_platform_sources),
                    modifier = Modifier.weight(1f),
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                IconButton(onClick = {
                    queryCurrentTrack()
                    runSourceDiagnosis(force = true)
                }) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.online_translation_diagnose_refresh),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        item(key = "platform_sources_current_track") {
            val track = currentTrack
            val needsListenerAccess = track == null && listenerEnabled == false
            // 与 fetchBestLyric 同规则：按排序优先级取第一个评分达标且取到歌词的来源；
            // 四库全部未命中时回退展示通用源 LRCLIB（仅歌词）的实际命中。
            val matchedDiagnostic = sourceOrder
                .filter { sourceEnabled[it] == true }
                .firstNotNullOfOrNull { source ->
                    sourceDiagnostics[source]?.takeIf {
                        it.found && it.score >= OnlineLyricTargeter.PASS_SCORE
                    }
                }
                ?: sourceDiagnostics[Source.LRCLIB]?.takeIf {
                    it.found && it.score >= OnlineLyricTargeter.PASS_SCORE
                }
            // 近失兜底：未达标但标题精确+时长吻合的候选，实际抓词会被近失通道采用。
            val nearMissDiagnostic = sourceOrder
                .filter { sourceEnabled[it] == true }
                .firstNotNullOfOrNull { source ->
                    sourceDiagnostics[source]?.takeIf {
                        !it.found && it.nearMissEligible
                    }
                }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = when {
                        track != null -> stringResource(
                            R.string.online_translation_current_track_format,
                            track.first,
                            track.second,
                        ).let { base ->
                            // 展示为 歌名 - 歌手 - 专辑（专辑缺失时保持原格式）
                            if (currentAlbum.isNotBlank()) "$base - $currentAlbum" else base
                        }
                        needsListenerAccess -> stringResource(
                            R.string.online_translation_notification_access_needed,
                        )
                        else -> stringResource(R.string.online_translation_no_current_track)
                    },
                    modifier = Modifier.then(
                        when {
                            needsListenerAccess -> Modifier.clickable {
                                LiveLyricService.ensureListenerBound(context)
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                                    )
                                }
                            }
                            track != null -> Modifier.clickable {
                                // 复制完整歌曲信息（歌名/歌手/专辑），便于手动到各平台搜索核对。
                                val info = buildString {
                                    append(track.first)
                                    append(" - ")
                                    append(track.second)
                                    if (currentAlbum.isNotBlank()) {
                                        append(" - ")
                                        append(currentAlbum)
                                    }
                                }
                                runCatching {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("song", info))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_song_info_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            else -> Modifier
                        },
                    ),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = when {
                        track != null -> MiuixTheme.colorScheme.onBackground
                        needsListenerAccess -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                    },
                )
                if (track != null && currentAppOnlineEnabled) {
                    // 歌词来源：与 hook 侧管线决策一致——原生曲库 App 直读；
                    // 非原生 App 命中在线源时展示实际命中的在线来源，
                    // 未命中（或尚未诊断）时才展示“原生优先/兜底”策略。
                    var originRes = lyricOriginRes(currentPackage)
                    if (originRes != R.string.lyric_origin_native &&
                        (matchedDiagnostic != null || nearMissDiagnostic != null)
                    ) {
                        originRes = R.string.lyric_origin_online
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(originRes),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    // 匹配平台信息不再受“原生来源”限制：只要诊断有数据就展示，
                    // 便于确认当前曲目在四平台/通用源的实际匹配情况。
                    if (matchedDiagnostic != null || nearMissDiagnostic != null ||
                        diagnosing || sourceDiagnostics.isNotEmpty()
                    ) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                diagnosing -> stringResource(
                                    R.string.online_translation_matched_platform_diagnosing,
                                )
                                matchedDiagnostic != null -> stringResource(
                                    R.string.online_translation_matched_platform_format,
                                    matchedDiagnostic.source.displayName(),
                                    matchedDiagnostic.score,
                                    matchedDiagnostic.lineCount,
                                )
                                nearMissDiagnostic != null -> stringResource(
                                    R.string.online_translation_matched_platform_nearmiss,
                                    nearMissDiagnostic.source.displayName(),
                                    nearMissDiagnostic.score,
                                )
                                sourceDiagnostics.isEmpty() -> stringResource(
                                    R.string.online_translation_matched_platform_diagnosing,
                                )
                                else -> stringResource(
                                    R.string.online_translation_matched_platform_none,
                                )
                            },
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = if (matchedDiagnostic != null || nearMissDiagnostic != null) {
                                MiuixTheme.colorScheme.onBackground
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantActions
                            },
                            modifier = if (track != null && currentAppOnlineEnabled) {
                                Modifier.clickable { showChainDialog = true }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
        item(key = "platform_sources") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    val enabledSources = sourceEnabled
                        .filterValues { enabled -> enabled }
                        .keys
                    sourceOrder.forEachIndexed { index, source ->
                        key(source) {
                            val swapDirection = when (source) {
                                pendingSwap?.source -> pendingSwap?.direction ?: 0
                                pendingSwap?.adjacentSource -> -(pendingSwap?.direction ?: 0)
                                else -> 0
                            }
                            SourceOrderPreference(
                                source = source,
                                priority = index + 1,
                                checked = sourceEnabled[source] == true,
                                enabled = OnlineTranslationSourcePreferences.canToggleSource(
                                    source,
                                    enabledSources,
                                ),
                                sortingVisible = !autoSelectBestSource,
                                offsetY = swapDirection * sourceRowHeightPx * swapProgress.value,
                                isMovingForward = pendingSwap?.source == source,
                                canMoveUp = index > 0 &&
                                    pendingSwap == null && swapProgress.value == 0f,
                                canMoveDown = index < sourceOrder.lastIndex &&
                                    pendingSwap == null && swapProgress.value == 0f,
                                onCheckedChange = { checked ->
                                    val canApplyChange = checked ||
                                        OnlineTranslationSourcePreferences.canToggleSource(
                                            source,
                                            enabledSources,
                                        )
                                    if (canApplyChange) {
                                        sourceEnabled[source] = checked
                                        saveConfig(
                                            OnlineTranslationSourcePreferences
                                                .sourcePreferenceKey(source),
                                            checked,
                                        )
                                    }
                                },
                                onMoveUp = { requestSourceMove(source, -1) },
                                onMoveDown = { requestSourceMove(source, 1) },
                                diagnostic = if (currentAppOnlineEnabled) {
                                    sourceDiagnostics[source]
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
        item(key = "auto_select_best_source") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                SwitchPreference(
                    title = stringResource(
                        R.string.title_online_translation_auto_select_best_source
                    ),
                    summary = stringResource(
                        R.string.summary_online_translation_auto_select_best_source
                    ),
                    checked = autoSelectBestSource,
                    onCheckedChange = { checked ->
                        autoSelectBestSource = checked
                        if (checked) pendingSwap = null
                        saveConfig(
                            RootConstants.KEY_HOOK_ONLINE_TRANSLATION_AUTO_SELECT_BEST_SOURCE,
                            checked,
                        )
                    },
                )
            }
        }
        item(key = "manual_match_entry") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
            ) {
                ProComponent(
                    title = stringResource(R.string.title_manual_match),
                    summary = stringResource(R.string.summary_manual_match),
                    onClick = { openManualMatchDialog() },
                    endActions = {
                        Icon(
                            imageVector = MiuixIcons.ChevronForward,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                )
            }
        }
        enabledAppsSection(
            installedApps = installedApps.orEmpty() + listOfNotNull(dynamicCurrentApp),
            appEnabled = appEnabled,
            onCheckedChange = { packageName, checked ->
                appEnabled[packageName] = checked
                OnlineTranslationSourcePreferences.appPreferenceKey(packageName)?.let { key ->
                    saveConfig(key, checked)
                }
            },
        )
        val saltPackageName = OnlineTranslationSourcePreferences.SALT_PACKAGE
        val saltApplies = installedApps?.any { it.app.packageName == saltPackageName } == true &&
            appEnabled[saltPackageName] == true
        specialSettingsSection(
            saltApplies = saltApplies,
            preferOnline = saltPreferOnline,
            onPreferOnlineChange = { checked ->
                saltPreferOnline = checked
                saveConfig(
                    RootConstants.KEY_HOOK_ONLINE_TRANSLATION_SALT_PREFER_ONLINE,
                    checked,
                )
            },
        )
    }

    WindowDialog(
        title = stringResource(R.string.title_online_translation_help_dialog),
        show = showHelpDialog,
        onDismissRequest = { showHelpDialog = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.summary_online_translation_help_dialog),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            Spacer(modifier = Modifier.height(12.dp))
            HelpDialogTable()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.summary_online_translation_help_note),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }

    WindowDialog(
        title = stringResource(R.string.title_manual_match),
        show = showManualMatchDialog,
        onDismissRequest = { showManualMatchDialog = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TextField(
                value = manualTitle,
                onValueChange = { manualTitle = it },
                label = stringResource(R.string.manual_match_title),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = manualArtist,
                onValueChange = { manualArtist = it },
                label = stringResource(R.string.manual_match_artist),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = manualAlbum,
                onValueChange = { manualAlbum = it },
                label = stringResource(R.string.manual_match_album_optional),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.manual_match_search),
                    onClick = { if (!manualSearching) submitManualMatchSearch() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showManualMatchDialog = false },
                    modifier = Modifier.weight(1f),
                )
            }
            if (manualSearching) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.manual_match_searching),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            manualError?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            if (manualResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                manualResults.forEach { candidate ->
                    key(candidate.source.name, candidate.id) {
                        ManualMatchCandidateRow(
                            candidate = candidate,
                            onClick = { applyManualMatch(candidate) },
                        )
                    }
                }
            }
        }
    }

    WindowDialog(
        title = stringResource(R.string.title_lyric_source_chain),
        show = showChainDialog,
        onDismissRequest = { showChainDialog = false },
    ) {
        LyricSourceChainDialog(
            currentPackage = currentPackage,
            appDisplayName = installedApps?.firstOrNull { it.app.packageName == currentPackage }
                ?.app?.displayName ?: dynamicCurrentApp?.app?.displayName,
            currentTrack = currentTrack,
            currentAlbum = currentAlbum,
            lyricOriginRes = lyricOriginRes(currentPackage),
            enabledSources = sourceOrder.filter { sourceEnabled[it] == true },
            sourceDiagnostics = sourceDiagnostics,
        )
    }
}

@Composable
private fun ManualMatchCandidateRow(
    candidate: SongSearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = candidate.title,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
        )
        val detail = listOfNotNull(
            candidate.artist.takeIf { it.isNotBlank() },
            candidate.album.takeIf { it.isNotBlank() },
            candidate.source.displayName(),
            formatDuration(candidate.duration).takeIf { candidate.duration > 0L },
        ).joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@Composable
private fun LyricSourceChainDialog(
    currentPackage: String?,
    appDisplayName: String?,
    currentTrack: Pair<String, String>?,
    currentAlbum: String,
    lyricOriginRes: Int,
    enabledSources: List<Source>,
    sourceDiagnostics: Map<Source, SourceMatchDiagnostic?>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (currentTrack == null) {
            Text(
                text = stringResource(R.string.chain_dialog_empty),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            return@Column
        }
        val trackLabel = buildString {
            append(currentTrack.first)
            append(" - ")
            append(currentTrack.second)
            if (currentAlbum.isNotBlank()) append(" - $currentAlbum")
        }
        ChainStepRow(
            label = stringResource(R.string.chain_step_player),
            value = appDisplayName ?: currentPackage ?: "",
        )
        ChainStepRow(
            label = stringResource(R.string.chain_step_strategy),
            value = trackLabel,
        )
        // 基础歌词：原生曲库直读 或 通用歌词源 LRCLIB 殿后
        val lrclib = sourceDiagnostics[Source.LRCLIB]
        val baseValue = if (lyricOriginRes == R.string.lyric_origin_native) {
            stringResource(R.string.chain_base_native)
        } else if (lrclib != null && lrclib.found) {
            stringResource(R.string.chain_base_lrclib_lines, lrclib.lineCount)
        } else {
            stringResource(R.string.chain_base_lrclib_none)
        }
        ChainStepRow(
            label = stringResource(R.string.chain_step_base),
            value = baseValue,
            highlight = lyricOriginRes != R.string.lyric_origin_native,
        )
        // 翻译平台逐源匹配链路
        val translationSources = enabledSources.filter { it != Source.LRCLIB }
        if (translationSources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chain_step_translate),
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            translationSources.forEach { source ->
                val diag = sourceDiagnostics[source]
                val (statusText, statusColor) = when {
                    diag == null ->
                        stringResource(R.string.chain_status_no_data) to
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                    diag.errorMessage != null || !diag.searched ->
                        stringResource(R.string.chain_status_search_fail) to
                            MiuixTheme.colorScheme.error
                    diag.found ->
                        stringResource(R.string.chain_status_hit) to
                            MiuixTheme.colorScheme.primary
                    diag.nearMissEligible ->
                        stringResource(R.string.chain_status_nearmiss) to
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                    diag.score >= OnlineLyricTargeter.PASS_SCORE ->
                        stringResource(R.string.chain_status_pass_no_lyric) to
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                    else ->
                        stringResource(R.string.chain_status_fail) to
                            MiuixTheme.colorScheme.error
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = source.displayName(),
                        modifier = Modifier.weight(1f),
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = statusText,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = statusColor,
                    )
                }
            }
        }
        // 最终命中平台
        val matched = enabledSources.firstNotNullOfOrNull { source ->
            sourceDiagnostics[source]?.takeIf {
                it.found && it.score >= OnlineLyricTargeter.PASS_SCORE
            }
        }
        if (matched != null) {
            Spacer(modifier = Modifier.height(12.dp))
            ChainStepRow(
                label = stringResource(R.string.chain_step_final),
                value = stringResource(
                    R.string.chain_final_format,
                    matched.source.displayName(),
                    matched.score,
                    matched.lineCount,
                ),
                emphasize = true,
            )
        }
    }
}

@Composable
private fun ChainStepRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (emphasize) {
                    Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp)
            .then(if (emphasize) Modifier.padding(horizontal = 12.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(84.dp),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = if (emphasize) {
                MiuixTheme.textStyles.body1.fontSize
            } else {
                MiuixTheme.textStyles.body2.fontSize
            },
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight || emphasize) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onBackground
            },
        )
    }
}

@Composable
private fun HelpDialogTable() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.title_online_translation_help_table_app),
                modifier = Modifier.weight(1.2f),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.title_online_translation_help_table_content),
                modifier = Modifier.weight(2f),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        HelpDialogTableRow(
            "Apple Music",
            stringResource(R.string.summary_online_translation_app_lyrics_translation),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        HelpDialogTableRow(
            "椒盐音乐",
            stringResource(R.string.summary_online_translation_app_lyrics_translation),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        HelpDialogTableRow(
            "YouTube Music",
            stringResource(R.string.summary_online_translation_app_lyrics_translation),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        HelpDialogTableRow(
            "汽水音乐",
            stringResource(R.string.summary_online_translation_app_translation),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        HelpDialogTableRow(
            "Spotify",
            stringResource(R.string.summary_online_translation_app_lyrics_translation),
        )
    }
}

@Composable
private fun HelpDialogTableRow(app: String, content: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = app,
            modifier = Modifier.weight(1.2f),
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = content,
            modifier = Modifier.weight(2f),
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

private fun sourceDiagnosticSummary(diagnostic: SourceMatchDiagnostic): String {
    return when {
        !diagnostic.searched || diagnostic.errorMessage != null -> "搜索失败"
        diagnostic.score < 0 -> "未搜到该歌曲"
        else -> buildString {
            append("匹配分 ${diagnostic.score}")
            if (!diagnostic.found) {
                // 达标但取词失败 ≠ 未达标：区分两种失败原因
                if (diagnostic.score >= OnlineLyricTargeter.PASS_SCORE) {
                    append("（达标但取词失败）")
                } else {
                    append("（未达标）")
                    if (diagnostic.nearMissEligible) append(" · 近失候选")
                }
            }
            if (diagnostic.lineCount > 0) append(" · ${diagnostic.lineCount}行")
            if (diagnostic.durationMs > 0) append(" · ${formatDuration(diagnostic.durationMs)}")
            if (diagnostic.found && diagnostic.source == Source.LRCLIB) append(" · 仅歌词无翻译")
        }
    }
}

private fun LazyListScope.specialSettingsSection(
    saltApplies: Boolean,
    preferOnline: Boolean,
    onPreferOnlineChange: (Boolean) -> Unit,
) {
    item(key = "special_settings") {
        AnimatedVisibility(
            visible = saltApplies,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column {
                SmallTitle(
                    text = stringResource(
                        R.string.title_online_translation_salt_special_settings
                    )
                )
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        SwitchPreference(
                            title = stringResource(
                                R.string.title_online_translation_salt_prefer_online
                            ),
                            summary = stringResource(
                                R.string.summary_online_translation_salt_prefer_online
                            ),
                            checked = preferOnline,
                            onCheckedChange = onPreferOnlineChange,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.enabledAppsSection(
    installedApps: List<InstalledTranslationApp>?,
    appEnabled: Map<String, Boolean>,
    onCheckedChange: (String, Boolean) -> Unit,
) {
    item(key = "enabled_apps_title") {
        SmallTitle(text = stringResource(R.string.title_online_translation_enabled_apps))
    }
    item(key = "enabled_apps") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            val apps = installedApps
            when {
                apps == null -> Unit
                apps.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.title_online_translation_no_enabled_apps),
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                else -> Column {
                    apps.forEach { installedApp ->
                        AppSwitchPreference(
                            app = installedApp.app,
                            icon = installedApp.icon,
                            checked = appEnabled[installedApp.app.packageName] == true,
                            onCheckedChange = { checked ->
                                onCheckedChange(installedApp.app.packageName, checked)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 来源行右侧通过上下箭头调整优先级，开关区域保持独立可点击。 */
@Composable
private fun SourceOrderPreference(
    source: Source,
    priority: Int,
    checked: Boolean,
    enabled: Boolean,
    sortingVisible: Boolean,
    offsetY: Float,
    isMovingForward: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    diagnostic: SourceMatchDiagnostic? = null,
    onCheckedChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val sourceControlsSlideDistancePx = with(LocalDensity.current) {
        SOURCE_CONTROLS_SLIDE_DISTANCE.toPx()
    }
    val sortingProgress by animateFloatAsState(
        targetValue = if (sortingVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = SOURCE_CONTROLS_ANIMATION_DURATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "sourceSortingControls",
    )
    val prioritySlotWidth = (SOURCE_PRIORITY_BADGE_SIZE + SOURCE_PRIORITY_TO_NAME_GAP) * sortingProgress
    val moveButtonsSlotWidth = SOURCE_MOVE_BUTTONS_WIDTH * sortingProgress
    val rowEndPadding = SOURCE_ROW_HORIZONTAL_PADDING -
        (SOURCE_ROW_HORIZONTAL_PADDING - SOURCE_ROW_END_PADDING) * sortingProgress
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SOURCE_ROW_HEIGHT)
            .zIndex(if (isMovingForward) 1f else 0f)
            .graphicsLayer {
                translationY = offsetY
            }
            .padding(
                start = SOURCE_ROW_HORIZONTAL_PADDING,
                end = rowEndPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keep the slot and badge measured throughout the animation. Only the
        // slot width and the badge's visual offset change, so the title never
        // jumps when the controls reach their final state.
        Box(
            modifier = Modifier
                .width(prioritySlotWidth)
                .requiredHeight(SOURCE_PRIORITY_BADGE_SIZE)
                .graphicsLayer { clip = true },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .requiredWidth(SOURCE_PRIORITY_BADGE_SIZE)
                    .requiredHeight(SOURCE_PRIORITY_BADGE_SIZE)
                    .graphicsLayer {
                        alpha = sortingProgress
                        translationX = -sourceControlsSlideDistancePx *
                            (1f - sortingProgress)
                    },
            ) {
                SourcePriorityBadge(priority)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.displayName(),
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            diagnostic?.let { diag ->
                Text(
                    text = sourceDiagnosticSummary(diag),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = if (diag.searched && diag.score >= 0) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                )
            }
        }
        // The switch always has the same measured height and remains on the
        // row's center line even while the move-button slot is entering/leaving.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Box(
            modifier = Modifier
                .width(moveButtonsSlotWidth)
                .requiredHeight(SOURCE_MOVE_BUTTON_SIZE)
                .graphicsLayer { clip = true },
        ) {
            Row(
                modifier = Modifier
                    .requiredWidth(SOURCE_MOVE_BUTTONS_WIDTH)
                    .requiredHeight(SOURCE_MOVE_BUTTON_SIZE)
                    .graphicsLayer {
                        alpha = sortingProgress
                        translationX = sourceControlsSlideDistancePx *
                            (1f - sortingProgress)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(SOURCE_SWITCH_TO_MOVE_BUTTON_GAP))
                SourceMoveButton(
                    moveUp = true,
                    enabled = sortingVisible && canMoveUp,
                    onClick = onMoveUp,
                )
                SourceMoveButton(
                    moveUp = false,
                    enabled = sortingVisible && canMoveDown,
                    onClick = onMoveDown,
                    modifier = Modifier.offset(x = SOURCE_DOWN_BUTTON_END_OFFSET),
                )
            }
        }
    }
}

/** 单个排序按钮复用 Chevron 图标，通过旋转表达上移或下移。 */
@Composable
private fun SourceMoveButton(
    moveUp: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(SOURCE_MOVE_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = stringResource(
                if (moveUp) R.string.action_move_source_up else R.string.action_move_source_down,
            ),
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
            modifier = Modifier
                .size(20.dp)
                .rotate(if (moveUp) -90f else 90f),
        )
    }
}

/** 左侧圆形序号使用成对的主题表面色，深色模式会自动反转明暗关系。 */
@Composable
private fun SourcePriorityBadge(priority: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(SOURCE_PRIORITY_BADGE_SIZE)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = priority.toString(),
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppSwitchPreference(
    app: TranslationApp,
    icon: Drawable?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = app.displayName,
        summary = stringResource(app.summaryRes),
        checked = checked,
        onCheckedChange = onCheckedChange,
        startAction = {
            Row {
                AppIcon(icon)
                Spacer(modifier = Modifier.width(APP_ICON_TO_NAME_GAP_ADJUSTMENT))
            }
        },
    )
}

@Composable
private fun AppIcon(icon: Drawable?) {
    Box(modifier = Modifier.size(40.dp)) {
        if (icon != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(icon)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            )
        }
    }
}

/** 候选时长展示格式：分:秒（如 4:19）。 */
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun Source.displayName(): String = when (this) {
    Source.NE -> stringResource(R.string.source_netease_music)
    Source.QM -> stringResource(R.string.source_qq_music)
    Source.KUWO -> stringResource(R.string.source_kuwo_music)
    Source.KUGOU -> stringResource(R.string.source_kugou_music)
    Source.LB -> "LunaBeat TTML"
    Source.LRCLIB -> "LRCLIB"
}

private data class TranslationApp(
    val packageName: String,
    val displayName: String,
    val summaryRes: Int,
)

private data class InstalledTranslationApp(val app: TranslationApp, val icon: Drawable?)

private data class SourceSwap(
    val source: Source,
    val adjacentSource: Source,
    val direction: Int,
)

/** 自带曲库歌词+翻译、由 provider 直读的播放器包名（含其在用变体）。 */
private val NATIVE_LYRIC_PACKAGES = setOf(
    "com.netease.cloudmusic",
    "com.tencent.qqmusic",
    "com.tencent.qqmusicpad",
    "cn.kuwo.player",
    "com.kugou.android",
    "com.kugou.android.lite",
    "com.luna.music",
    "cn.wenyu.bodian",
    "com.miui.player",
    "cmccwm.mobilemusic",
    "com.xuncorp.qinalt.music",
)

/** 按播放器类型推断当前歌词/翻译的实际来源（与 hook 侧管线决策一致）。 */
private fun lyricOriginRes(packageName: String?): Int = when (packageName) {
    OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE -> R.string.lyric_origin_spotify
    OnlineTranslationSourcePreferences.YOUTUBE_MUSIC_PACKAGE -> R.string.lyric_origin_ytm
    OnlineTranslationSourcePreferences.SALT_PACKAGE -> R.string.lyric_origin_salt
    OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE,
    OnlineTranslationSourcePreferences.QISHUI_PACKAGE,
    -> R.string.lyric_origin_native
    in NATIVE_LYRIC_PACKAGES -> R.string.lyric_origin_native
    else -> R.string.lyric_origin_online
}

private val ENABLED_APPS = listOf(
    TranslationApp(
        OnlineTranslationSourcePreferences.APPLE_MUSIC_PACKAGE,
        "Apple Music",
        R.string.summary_online_translation_app_lyrics_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.QISHUI_PACKAGE,
        "汽水音乐",
        R.string.summary_online_translation_app_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.SPOTIFY_PACKAGE,
        "Spotify",
        R.string.summary_online_translation_app_lyrics_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.SALT_PACKAGE,
        "椒盐音乐",
        R.string.summary_online_translation_app_lyrics_translation,
    ),
    TranslationApp(
        OnlineTranslationSourcePreferences.YOUTUBE_MUSIC_PACKAGE,
        "YouTube Music",
        R.string.summary_online_translation_app_lyrics_translation,
    ),
)

private val SOURCE_ROW_HEIGHT = 64.dp
private val SOURCE_ROW_HORIZONTAL_PADDING = 16.dp
private val SOURCE_ROW_END_PADDING = 12.dp
private val SOURCE_PRIORITY_BADGE_SIZE = 30.dp
private val SOURCE_PRIORITY_TO_NAME_GAP = 16.dp
private val SOURCE_MOVE_BUTTON_SIZE = 44.dp
private val SOURCE_SWITCH_TO_MOVE_BUTTON_GAP = 12.dp
private val SOURCE_DOWN_BUTTON_END_OFFSET = 4.dp
private val SOURCE_MOVE_BUTTONS_WIDTH = SOURCE_SWITCH_TO_MOVE_BUTTON_GAP +
    SOURCE_MOVE_BUTTON_SIZE * 2 + SOURCE_DOWN_BUTTON_END_OFFSET
private val APP_ICON_TO_NAME_GAP_ADJUSTMENT = 8.dp
private const val SOURCE_SWAP_ANIMATION_DURATION_MS = 220
private const val SOURCE_CONTROLS_ANIMATION_DURATION_MS = 320
private val SOURCE_CONTROLS_SLIDE_DISTANCE = 8.dp
