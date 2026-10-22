package com.brainrot.core

/**
 * Where each app keeps its Shorts/Reels player. Apps rename view ids between releases, so old ids
 * stay listed next to new ones and matching is by id suffix (`:id/<name>`), which also covers
 * patched forks that keep the resource names under another package.
 *
 * Ids were cross-checked against open-source detectors (DetoxDroid, Scrolless, curbox, Mindful) in
 * Oct 2026; none of their code is copied here. Facebook Reels has no stable ids and is not listed.
 * To add or refresh an app: open its reels player, run `adb shell uiautomator dump`, and look for
 * the full-screen pager.
 */
data class ShortVideoApp(
    val packageNames: Set<String>,
    val label: String,
    /** Any of these on screen and roughly full-screen means the reels player is visible. */
    val surfaceIds: List<String>,
    /** Vertical pagers whose scroll events mean "next reel". Usually the same as [surfaceIds]. */
    val pagerIds: List<String> = surfaceIds,
    /** The feed opens straight into the player (TikTok), so the app itself is the surface. */
    val wholeAppIsSurface: Boolean = false,
)

object ShortVideoApps {
    val YOUTUBE = ShortVideoApp(
        packageNames = setOf("com.google.android.youtube", "app.revanced.android.youtube"),
        label = "YouTube Shorts",
        surfaceIds = listOf("reel_recycler", "reel_player_page_container", "reel_player_underlay"),
        pagerIds = listOf("reel_recycler"),
    )
    val INSTAGRAM = ShortVideoApp(
        packageNames = setOf("com.instagram.android"),
        label = "Instagram Reels",
        surfaceIds = listOf("clips_viewer_view_pager", "clips_video_container"),
        pagerIds = listOf("clips_viewer_view_pager"),
    )
    val TIKTOK = ShortVideoApp(
        packageNames = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme"),
        label = "TikTok",
        surfaceIds = listOf("view_pager_layout_wrapper", "player_view"),
        pagerIds = listOf("view_pager_layout_wrapper"),
        wholeAppIsSurface = true,
    )
    val SNAPCHAT = ShortVideoApp(
        packageNames = setOf("com.snapchat.android"),
        label = "Snapchat Spotlight",
        surfaceIds = listOf("spotlight_container"),
    )

    val all: List<ShortVideoApp> = listOf(YOUTUBE, INSTAGRAM, TIKTOK, SNAPCHAT)

    private val byPackage: Map<String, ShortVideoApp> = all.flatMap { app -> app.packageNames.map { it to app } }.toMap()

    fun find(packageName: String?): ShortVideoApp? = packageName?.let(byPackage::get)

    /** "com.google.android.youtube:id/reel_recycler" matches "reel_recycler". */
    fun matchesId(viewId: String?, names: List<String>): Boolean {
        if (viewId.isNullOrEmpty()) return false
        val name = viewId.substringAfter(":id/", viewId)
        return name in names
    }
}
