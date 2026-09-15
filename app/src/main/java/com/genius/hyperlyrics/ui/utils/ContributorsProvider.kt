package com.genius.hyperlyrics.ui.utils

import com.genius.hyperlyrics.R

data class ContributorItem(
    val name: String,
    val summary: String,
    val githubUrl: String,
    val avatarRes: Int? = null
)

object ContributorsProvider {
    val maintainer = ContributorItem(
        "QuanTum2088",
        "@QuanTum2088",
        "https://github.com/QuanTum2088",
        R.drawable.contributor_quantum2088
    )
    val developmentContributors = listOf<ContributorItem>(

    )
    val originalHyperLyricContributors = listOf<ContributorItem>(
        ContributorItem("lidesheng", "@limczhh", "https://github.com/limczhh", R.drawable.contributor_limczhh),
        ContributorItem("zszf", "@zszf114514", "https://github.com/zszf114514", R.drawable.contributor_zszf114514)
    )
}
