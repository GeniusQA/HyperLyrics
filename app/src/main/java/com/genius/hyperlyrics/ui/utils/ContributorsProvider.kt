package com.genius.hyperlyrics.ui.utils

data class ContributorItem(
    val name: String,
    val summary: String,
    val githubUrl: String,
    val avatarRes: Int
)

object ContributorsProvider {
    val maintainer = ContributorItem("QuanTum2088", "@QuanTum2088", "https://github.com/QuanTum2088", android.R.drawable.sym_def_app_icon)
    val developmentContributors = listOf<ContributorItem>(

    )
    val originalHyperLyricContributors = listOf<ContributorItem>(
        ContributorItem("lidesheng", "@limczhh", "https://github.com/limczhh", com.genius.hyperlyrics.R.drawable.contributor_limczhh),
        ContributorItem("zszf", "@zszf114514", "https://github.com/zszf114514", com.genius.hyperlyrics.R.drawable.contributor_zszf114514)
    )
}
