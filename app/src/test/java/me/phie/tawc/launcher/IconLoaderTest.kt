package me.phie.tawc.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [IconLoader.budgetBytes] heap-fraction vs. floor. */
class IconLoaderTest {

    private val sizePx = 144 // 48dp at density 3

    @Test
    fun tracksHeapWhenRoomy() {
        val heap = 256L * 1024 * 1024
        assertEquals((heap / 8).toInt(), IconLoader.budgetBytes(sizePx, heap))
    }

    @Test
    fun floorsOnSmallHeap() {
        val budget = IconLoader.budgetBytes(sizePx, 16L * 1024 * 1024)
        val perIcon = 4 * sizePx * sizePx * 2
        assertEquals(32 * perIcon, budget)
    }

    @Test
    fun floorScalesWithIconSize() {
        val small = IconLoader.budgetBytes(48, 8L * 1024 * 1024)
        val big = IconLoader.budgetBytes(192, 8L * 1024 * 1024)
        assertTrue("$small should be well under $big", small * 4 <= big)
    }

    @Test
    fun clampsAbsurdHeapToInt() {
        val budget = IconLoader.budgetBytes(sizePx, Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, budget)
    }
}
