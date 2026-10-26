package eu.kanade.tachiyomi.ui.reader.viewer.pager

/**
 * Decides whether the "Automatic" page layout should show double pages for the current window.
 *
 * Landscape alone is not enough on foldables: an unfolded inner screen (for example 2248x2480 px,
 * roughly 820x900 dp) reports portrait even though it is wide enough for a two-page spread. A window
 * that is at least [MIN_WIDTH_DP] wide and close to square counts as a spread-sized screen too.
 * Tablets in portrait (around 0.6 aspect ratio) stay on single pages.
 *
 * ASSUMPTION — to verify on device: the 600 dp / 0.8 aspect thresholds were chosen by reasoning
 * from published screen sizes, not measured on hardware.
 */
internal object AutoPageLayout {

    /** Material "medium" window width class starts here. */
    const val MIN_WIDTH_DP = 600

    /** width / height at or above this counts as near-square. */
    const val MIN_ASPECT = 0.8f

    fun useDoublePages(widthDp: Int, heightDp: Int): Boolean {
        if (widthDp <= 0 || heightDp <= 0) return false
        if (widthDp > heightDp) return true
        return widthDp >= MIN_WIDTH_DP && widthDp >= heightDp * MIN_ASPECT
    }
}
