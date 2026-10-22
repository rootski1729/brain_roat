package com.brainrot.core

/** Platform-neutral snapshot of one on-screen node (bounds in screen pixels). */
data class NodeSnap(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

data class RailResult(val isReel: Boolean, val tokens: Set<Int>?)

/**
 * Recognises a full-screen short-video player by its layout when the app exposes no stable view
 * ids (Facebook): a column of action buttons (like, comment, share, …) hugging the right edge,
 * next to a text-less video surface that spans the screen. The reel's identity is its author
 * button plus its longest description, reduced to [ReelFingerprints] tokens.
 */
object RailDetector {
    /** Action labels, lower-case, matched as whole words. English plus common Indian languages. */
    private val ACTION_WORDS = setOf(
        "like", "likes", "comment", "comments", "share", "shares", "remix", "send", "save", "reactions", "react",
        "पसंद", "टिप्पणी", "कमेंट", "शेयर", "भेजें",
    )
    private val NOT_AUTHOR = setOf("back", "search", "share", "more", "sponsored", "follow", "like", "comment", "send")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun detect(nodes: List<NodeSnap>, screenWidth: Int, screenHeight: Int): RailResult {
        if (screenWidth <= 0 || screenHeight <= 0) return RailResult(false, null)
        val railMinX = screenWidth * 3 / 4
        val actions = HashSet<String>()
        for (n in nodes) {
            if (n.centerX < railMinX) continue
            labelWords(n).firstOrNull { it in ACTION_WORDS }?.let(actions::add)
        }
        val hasRail = actions.size >= 2
        val hasSurface = nodes.any {
            it.width >= screenWidth * 95 / 100 && it.height >= screenHeight / 2 &&
                it.text.isNullOrBlank() && !it.description.isNullOrBlank()
        }
        if (!hasRail || !hasSurface) return RailResult(false, null)

        val author = nodes.firstOrNull {
            val t = it.text?.trim().orEmpty()
            t.length > 2 && t == it.description?.trim() && (it.className?.endsWith("Button") == true) &&
                t.lowercase() !in NOT_AUTHOR
        }?.text
        val caption = nodes
            .filter { it.text.isNullOrBlank() && it.width >= screenWidth * 9 / 10 }
            .mapNotNull { it.description?.trim() }
            .filter { it.length > 20 }
            .maxByOrNull { it.length }
        val tokens = ReelFingerprints.tokenize(listOfNotNull(author, caption).joinToString(" "))
        return RailResult(true, tokens.takeIf { it.size >= ReelFingerprints.MIN_TOKENS })
    }

    private fun labelWords(n: NodeSnap): List<String> =
        listOfNotNull(n.description, n.text).flatMap { it.lowercase().split(SPLIT) }.filter { it.isNotEmpty() }
}
