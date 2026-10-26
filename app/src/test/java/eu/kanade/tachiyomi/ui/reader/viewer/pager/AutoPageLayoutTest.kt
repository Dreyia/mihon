package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoPageLayoutTest {

    @Test
    fun `phone portrait shows single pages`() {
        assertFalse(AutoPageLayout.useDoublePages(widthDp = 411, heightDp = 891))
    }

    @Test
    fun `any landscape window shows double pages`() {
        assertTrue(AutoPageLayout.useDoublePages(widthDp = 891, heightDp = 411))
    }

    @Test
    fun `unfolded foldable inner screen shows double pages even in portrait`() {
        assertTrue(AutoPageLayout.useDoublePages(widthDp = 817, heightDp = 902))
    }

    @Test
    fun `foldable cover screen shows single pages`() {
        assertFalse(AutoPageLayout.useDoublePages(widthDp = 380, heightDp = 872))
    }

    @Test
    fun `tablet portrait stays single`() {
        assertFalse(AutoPageLayout.useDoublePages(widthDp = 800, heightDp = 1280))
    }

    @Test
    fun `unknown size falls back to single`() {
        assertFalse(AutoPageLayout.useDoublePages(widthDp = 0, heightDp = 0))
    }
}
