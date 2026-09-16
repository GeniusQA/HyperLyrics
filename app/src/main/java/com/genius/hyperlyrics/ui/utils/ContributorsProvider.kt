package com.genius.hyperlyrics.ui.utils

data class ContributorItem(
    val name: String,
    val summary: String,
    val githubUrl: String,
    val avatarRes: Int?
)

object ContributorsProvider {
    val maintainer = ContributorItem("GeniusQA", "@GeniusQA", "https://github.com/GeniusQA", com.genius.hyperlyrics.R.drawable.contributor_quantum2088)
    val developmentContributors = listOf<ContributorItem>(

    )
    // 历史贡献者：本项目赖以构建的开源项目，与 README「致谢与许可证」保持一致。
    // 头像取自各项目 GitHub 组织/所有者的公开头像（libxposed 组织页面已不可访问，改用 GitHub 默认头像）。
    val historicalContributors = listOf<ContributorItem>(
        ContributorItem("miuix-kmp", "HyperOS 风格 Compose 组件库", "https://github.com/compose-miuix-ui/miuix", com.genius.hyperlyrics.R.drawable.contributor_miuix),
        ContributorItem("lyricon", "歌词订阅、数据模型和部分歌词动画基础", "https://github.com/tomakino/lyricon", com.genius.hyperlyrics.R.drawable.contributor_lyricon),
        ContributorItem("SuperLyric", "第三方歌词广播与跨应用歌词数据接口", "https://github.com/HChenX/SuperLyric", com.genius.hyperlyrics.R.drawable.contributor_superlyric),
        ContributorItem("LyricInfo", "歌词数据源与 LyricInfo 格式解析基础", "https://github.com/limczhh/LyricInfo", com.genius.hyperlyrics.R.drawable.contributor_lyricinfo),
        ContributorItem("libxposed", "本项目使用的 Xposed / LSPosed Hook 框架 API", "https://github.com/libxposed/api", com.genius.hyperlyrics.R.drawable.contributor_github),
    )
}
