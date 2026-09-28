package jp.nagu.continuousplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaFolderSorterTest {
    @Test
    fun sortsRequestedExampleAcrossYearsAndSeasons() {
        val expected = listOf("アニメOPED 2024秋", "アニメOPED 2025冬", "アニメOPED 2025春",
            "アニメOPED 2025夏", "アニメOPED 2025秋", "アニメOPED 2026冬")
        assertEquals(expected, sort(expected.reversed()))
    }

    @Test
    fun sortsSavedSafFolderTitlesWhileKeepingTheirUris() {
        val expected = listOf("アニメOPED 2024*秋*", "アニメOPED 2025冬", "アニメOPED 2025春",
            "アニメOPED 2025夏", "アニメOPED 2025秋", "アニメOPED 2026冬")
            .mapIndexed { index, title -> "content://documents/tree/root-$index" to title }
        val registrationOrder = listOf(expected[4], expected[2], expected[5], expected[0], expected[3], expected[1])

        assertEquals(expected, DlnaFolderSorter.sort(registrationOrder) { it.second })
    }

    @Test
    fun acceptsYearMarkerMonthAndEmphasisAroundSeason() {
        assertEquals(listOf("アニメOPED 2024*秋*", "アニメOPED 202501年冬", "アニメOPED 2025年春",
            "アニメOPED 2025夏", "アニメOPED 2025年秋"), sort(listOf(
            "アニメOPED 2025年秋", "アニメOPED 2025夏", "アニメOPED 2024*秋*",
            "アニメOPED 2025年春", "アニメOPED 202501年冬")))
    }

    @Test
    fun keepsDifferentSeriesAndOrdinaryFoldersInNameOrder() {
        assertEquals(listOf("00 お気に入り", "A 2025冬", "A 2025春", "A 2025秋",
            "B 2025冬", "B 2025夏", "zz その他"), sort(listOf(
            "B 2025夏", "zz その他", "A 2025秋", "A 2025春", "00 お気に入り", "A 2025冬", "B 2025冬")))
    }

    @Test
    fun plainSeasonFoldersAlsoFollowWinterSpringSummerAutumn() {
        assertEquals(listOf("2025冬", "2025春", "2025夏", "2025秋"),
            sort(listOf("2025夏", "2025春", "2025秋", "2025冬")))
        assertEquals(emptyList<String>(), sort(emptyList()))
    }

    private fun sort(titles: List<String>) = DlnaFolderSorter.sort(
        titles.mapIndexed { index, title -> DlnaEntry(index.toString(), title) }).map { it.title }
}
