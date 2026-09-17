package com.genius.hyperlyrics.ui.page.main

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.genius.hyperlyrics.BuildConfig
import com.genius.hyperlyrics.R
import com.genius.hyperlyrics.ui.utils.pageScrollModifiers
import com.genius.hyperlyrics.utils.LatestRelease
import com.genius.hyperlyrics.utils.UpdateCheckResult
import com.genius.hyperlyrics.utils.UpdateData
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

data class AboutHeroVisualState(
    val backgroundAlpha: Float = 1f,
    val logoAlpha: Float = 1f,
    val logoScale: Float = 1f,
    val scrollOffsetPx: Float = 0f,
)

/** 构建具有全屏动态背景的关于页，并保留原有导航入口。 */
@Composable
fun AboutPage(
    outerPadding: PaddingValues,
    aboutAppVersion: String?,
    availableUpdateVersion: String?,
    aboutDeviceName: String,
    aboutDeviceModel: String,
    aboutOsVersion: String,
    aboutAndroidVersion: String,
    onHelpClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onContributorsClick: () -> Unit,
    onHeroStateChanged: (AboutHeroVisualState) -> Unit,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateCheckInFlight by remember { mutableStateOf(false) }
    var updateDialogRelease by remember { mutableStateOf<LatestRelease?>(null) }
    val density = LocalDensity.current
    val backgroundFadeDistance = with(density) { 352.dp.toPx() }
    val logoFadeStart = with(density) { 70.dp.toPx() }
    val logoFadeDistance = with(density) { 40.dp.toPx() }
    val currentScrollOffset by remember(lazyListState, backgroundFadeDistance) {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0) {
                backgroundFadeDistance
            } else {
                lazyListState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }
    val backgroundAlpha by remember(currentScrollOffset, backgroundFadeDistance) {
        derivedStateOf {
            (1f - currentScrollOffset / backgroundFadeDistance).coerceIn(0f, 1f)
        }
    }
    val logoFadeProgress by remember(currentScrollOffset, logoFadeStart, logoFadeDistance) {
        derivedStateOf {
            ((currentScrollOffset - logoFadeStart) / logoFadeDistance).coerceIn(0f, 1f)
        }
    }
    val versionFadeProgress by remember(currentScrollOffset, logoFadeStart) {
        derivedStateOf { (currentScrollOffset / logoFadeStart).coerceIn(0f, 1f) }
    }
    val showTopBarTitle = logoFadeProgress >= 1f
    val pageTitle = stringResource(R.string.about)

    SideEffect {
        onHeroStateChanged(
            AboutHeroVisualState(
                backgroundAlpha = backgroundAlpha,
                logoAlpha = 1f - logoFadeProgress,
                logoScale = 1f - logoFadeProgress * 0.1f,
                scrollOffsetPx = currentScrollOffset,
            ),
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = if (showTopBarTitle) pageTitle else "",
                largeTitle = "",
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) {
        // HyperCeiler 的品牌区从页面顶部开始，顶栏以覆盖方式叠在内容之上。
        val contentPadding = PaddingValues(
            bottom = outerPadding.calculateBottomPadding(),
        )

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.pageScrollModifiers(
                enableScrollEndHaptic = true,
                showTopAppBar = true,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
            ),
            contentPadding = contentPadding,
        ) {
            aboutPageSections(
                aboutAppVersion = aboutAppVersion,
                availableUpdateVersion = availableUpdateVersion,
                aboutDeviceName = aboutDeviceName,
                aboutDeviceModel = aboutDeviceModel,
                aboutOsVersion = aboutOsVersion,
                aboutAndroidVersion = aboutAndroidVersion,
                headerVersionAlpha = 1f - versionFadeProgress,
                headerVersionScale = 1f -
                    (currentScrollOffset / backgroundFadeDistance).coerceIn(0f, 1f) * 0.1f,
                cardSurfaceRestoreProgress = 1f - backgroundAlpha,
                onHelpClick = onHelpClick,
                onLicensesClick = onLicensesClick,
                onChangelogClick = onChangelogClick,
                onCheckUpdateClick = {
                    if (!updateCheckInFlight) {
                        updateCheckInFlight = true
                        scope.launch {
                            val result = UpdateData.checkForUpdate(
                                currentVersionName = BuildConfig.VERSION_NAME,
                                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            )
                            updateCheckInFlight = false
                            when (result) {
                                is UpdateCheckResult.Available ->
                                    updateDialogRelease = result.latest
                                UpdateCheckResult.UpToDate -> Toast.makeText(
                                    context,
                                    R.string.update_check_up_to_date,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                UpdateCheckResult.Failed -> Toast.makeText(
                                    context,
                                    R.string.update_check_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
                onContributorsClick = onContributorsClick,
            )
        }
    }

    UpdateAvailableDialog(
        release = updateDialogRelease,
        onDismiss = { updateDialogRelease = null },
    )
}

/** 「发现新版本」弹窗：展示当前/最新版本与 Release 说明，更新按钮跳转 Releases 页面。 */
@Composable
private fun UpdateAvailableDialog(
    release: LatestRelease?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    WindowDialog(
        title = stringResource(id = R.string.update_dialog_title),
        show = release != null,
        onDismissRequest = onDismiss,
    ) {
        if (release != null) {
            Text(
                text = stringResource(
                    id = R.string.update_dialog_current_version,
                    "v${BuildConfig.VERSION_NAME}",
                ),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(
                    id = R.string.update_dialog_latest_version,
                    "v${release.update.versionName}",
                ),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (release.releaseNotes.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                ) {
                    Text(
                        text = release.releaseNotes,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSecondaryVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    text = stringResource(id = R.string.cancel),
                    onClick = onDismiss,
                )
                TextButton(
                    text = stringResource(id = R.string.update_dialog_action),
                    onClick = {
                        onDismiss()
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, UpdateData.RELEASES_PAGE_URL.toUri()),
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
