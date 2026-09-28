package jp.nagu.continuousplayer

import java.text.Collator
import java.util.Locale

/** Sort each named season series chronologically without moving unrelated folders. */
internal object DlnaFolderSorter {
    private val seasonName = Regex("""^(.*?)\s*(\d{4})(?:(0[1-9]|1[0-2]))?年?\s*[*_]?([冬春夏秋])[*_]?(.*)$""")
    private data class Season(val series: Pair<String, String>, val year: Int, val order: Int)

    fun sort(folders: List<DlnaEntry>): List<DlnaEntry> = sort(folders) { it.title }

    fun <T> sort(folders: List<T>, title: (T) -> String): List<T> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val result = folders.sortedWith { a, b -> collator.compare(title(a), title(b)) }.toMutableList()
        val groups = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        result.forEachIndexed { index, folder ->
            parse(title(folder))?.let { groups.getOrPut(it.series) { mutableListOf() }.add(index) }
        }
        groups.values.forEach { positions ->
            val ordered = positions.map { result[it] }.sortedWith { a, b ->
                val first = requireNotNull(parse(title(a)))
                val second = requireNotNull(parse(title(b)))
                val year = first.year.compareTo(second.year)
                val season = first.order.compareTo(second.order)
                when {
                    year != 0 -> year
                    season != 0 -> season
                    else -> collator.compare(title(a), title(b))
                }
            }
            positions.forEachIndexed { index, position -> result[position] = ordered[index] }
        }
        return result
    }

    private fun parse(title: String): Season? {
        val match = seasonName.matchEntire(title.trim()) ?: return null
        val prefix = match.groupValues[1].trim().lowercase(Locale.ROOT)
        val suffix = match.groupValues[5].trim().lowercase(Locale.ROOT)
        return Season(prefix to suffix, match.groupValues[2].toInt(), "冬春夏秋".indexOf(match.groupValues[4]))
    }
}
