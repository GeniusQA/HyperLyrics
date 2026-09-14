package com.genius.hyperlyrics.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
) {
    val displayVersion: String = "v$versionName-$versionCode"
}

@Serializable
private data class GitHubLatestRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
)

object UpdateData {
    /**
     * 更新检查开关：包名独立（com.genius.hyperlyrics）后不再跟随上游仓库
     * 的版本发布线——上游的 v7.5.1-151021 与本分支 1.0.0 属于不同发布线，
     * 比较没有意义。自有仓库 GeniusQA/HyperLyrics 的 releases/latest 已
     * 配置，但默认保持关闭，直到新发布线有正式 Release 后再开启。
     */
    private const val UPDATE_CHECK_ENABLED = false
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/GeniusQA/HyperLyrics/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    private val _availableUpdate = MutableStateFlow<AvailableUpdate?>(null)
    val availableUpdate = _availableUpdate.asStateFlow()

    suspend fun refresh(
        currentVersionName: String,
        currentVersionCode: Long,
    ) {
        if (!UPDATE_CHECK_ENABLED) {
            _availableUpdate.value = null
            return
        }
        runCatching {
            fetchLatestRelease()?.takeIf { latest ->
                isUpdateAvailable(
                    latestVersionName = latest.versionName,
                    latestVersionCode = latest.versionCode,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                )
            }
        }.onSuccess { latest ->
            _availableUpdate.value = latest
        }
    }

    private suspend fun fetchLatestRelease(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "HyperLyrics-Android")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("GitHub latest release request failed: HTTP $responseCode")
            }

            val release = connection.inputStream.bufferedReader().use { reader ->
                json.decodeFromString<GitHubLatestRelease>(reader.readText())
            }
            val versionName = parseReleaseVersionName(release.tagName) ?: return@withContext null
            val versionCode = extractReleaseVersionCode(
                assetNames = release.assets.map(GitHubReleaseAsset::name),
                versionName = versionName,
            ) ?: return@withContext null

            AvailableUpdate(versionName = versionName, versionCode = versionCode)
        } finally {
            connection.disconnect()
        }
    }

    internal fun isUpdateAvailable(
        latestVersionName: String,
        latestVersionCode: Long,
        currentVersionName: String,
        currentVersionCode: Long,
    ): Boolean {
        if (parseVersionParts(latestVersionName) == null) return false
        if (parseVersionParts(currentVersionName) == null) return false
        return latestVersionCode > currentVersionCode
    }

    internal fun extractReleaseVersionCode(
        assetNames: List<String>,
        versionName: String,
    ): Long? {
        val versionCodeRegex = Regex(
            """v${Regex.escape(versionName)}-(\d+)\.apk$""",
            RegexOption.IGNORE_CASE,
        )
        return assetNames
            .sortedBy { assetName -> if (assetName.contains("release", ignoreCase = true)) 0 else 1 }
            .firstNotNullOfOrNull { assetName ->
                versionCodeRegex.find(assetName.trim())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            }
    }

    private fun parseReleaseVersionName(tagName: String): String? {
        val match = Regex("^v(\\d+\\.\\d+\\.\\d+)$").matchEntire(tagName.trim())
            ?: return null
        return match.groupValues[1]
    }

    private fun parseVersionParts(versionName: String): List<Int>? {
        val normalized = versionName.trim()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
        if (!normalized.matches(Regex("\\d+\\.\\d+\\.\\d+"))) return null
        return normalized.split('.').mapNotNull(String::toIntOrNull)
    }
}
