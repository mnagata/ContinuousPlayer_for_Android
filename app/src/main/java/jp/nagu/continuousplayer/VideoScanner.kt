package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.Collator
import java.util.Locale
import java.io.File

class VideoScanner(private val context: Context) {

    companion object {
        // macOS AppleDouble sidecars retain the media extension but contain metadata.
        fun isSupportedFile(name: String): Boolean =
            !name.startsWith("._") &&
                name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
                setOf("mp4", "m4v", "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
    }

    fun scanDirectory(directory: File): List<VideoItem> {
        val files = directory.listFiles()
            ?: throw java.io.IOException("Cannot read directory: ${directory.path}")
        return sortLikeSafThenReorderOpEd(files.filter {
            it.isFile && isSupportedFile(it.name)
        }.map {
            VideoItem(Uri.fromFile(it).toString(), it.name, it.length(), it.lastModified())
        })
    }

    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    fun scanTree(treeUri: Uri, documentUri: Uri): List<VideoItem> {

        // documentUri is the parent directory URI — use it directly as scan root
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                return emptyList()
            }

        // Find the scan root by traversing the tree from documentUri path
        val scanRoot = try {
            val treeDocId = treeUri.toString()
                .substringAfter("tree/")
                .substringBefore('?')
                .let { Uri.decode(it) }

            val docId = documentUri.toString()
                .substringAfter("document/")
                .substringBefore('?')
                .let { Uri.decode(it) }

            if (docId.startsWith(treeDocId)) {
                val relativePath = docId.substring(treeDocId.length)
                    .trimStart('/')
                if (relativePath.isNotEmpty()) {
                    var current: DocumentFile? = tree
                    for (seg in relativePath.split('/')) {
                        if (current == null) break
                        val child = current.listFiles().find {
                            it.isDirectory && it.name == seg
                        }
                        current = child
                    }
                    current
                } else {
                    tree
                }
            } else {
                tree
            }
        } catch (_: Exception) {
            tree
        }

        if (scanRoot == null) {
            return emptyList()
        }

        val allFiles = scanRoot.listFiles()

        val filtered = allFiles.filter { doc ->
            val name = doc.name ?: return@filter false
            doc.isFile && isSupportedFile(name)
        }.map { doc ->
            VideoItem(
                uri = doc.uri.toString(),
                displayName = doc.name ?: "unknown",
                size = doc.length(),
                lastModified = doc.lastModified()
            )
        }

        return sortLikeSafThenReorderOpEd(filtered)
	}

    private data class OpEdInfo(
        val baseKey: String,
        val number: Int,
        val category: Int
    )

    private val opEdRegex = Regex("""^(.+?)\s+(OP|ED)(\d*)$""", RegexOption.IGNORE_CASE)

    private fun sortLikeSafThenReorderOpEd(
        items: List<VideoItem>
    ): List<VideoItem> {
        // 1. SAFの標準DocumentsUI相当の名前昇順
        val result = items.sortedWith { a, b ->
            collator.compare(a.displayName, b.displayName)
        }.toMutableList()

        // 2. 同じベース名のOP/EDファイル位置を収集
        val groups = mutableMapOf<String, MutableList<Int>>()

        result.forEachIndexed { index, item ->
            val info = parseOpEd(item.displayName) ?: return@forEachIndexed
            groups.getOrPut(info.baseKey) { mutableListOf() }.add(index)
        }

        // 3. OP/EDファイルだけ入れ替える
        groups.values.forEach { posotions ->
            if (posotions.size < 2) return@forEach

            val reordered = posotions
                .map { result[it] }
                .sortedWith { a, b ->
                    val infoA = requireNotNull(parseOpEd(a.displayName))
                    val infoB = requireNotNull(parseOpEd(b.displayName))

                    // OP -> ED -> OP2 -> ED2 -> OP3 -> ED3
                    val numberComparison = infoA.number.compareTo(infoB.number)
                    if (numberComparison != 0) {
                        numberComparison
                    } else {
                        val categoryComparison = infoA.category.compareTo(infoB.category)

                        if (categoryComparison != 0) {
                            categoryComparison
                        } else {
                            collator.compare(a.displayName, b.displayName)
                        }
                    }
                }
            posotions.forEachIndexed { positionIndex, resultIndex ->
                result[resultIndex] = reordered[positionIndex]
            }
        }
        return result
    }

    private fun parseOpEd(fileName: String): OpEdInfo? {
        // 最後の拡張子だけを除去
        val stem = fileName.substringBeforeLast('.', fileName).trim()
        val match = opEdRegex.matchEntire(stem) ?: return null

        val baseKey = match.groupValues[1]
            .trim()
            .lowercase(Locale.ROOT)

        val category = when (match.groupValues[2].uppercase(Locale.ROOT)) {
            "OP" -> 0
            "ED" -> 1
            else -> return null
        }

        // OP/EDは1, OP2/ED2は2
        val number = match.groupValues[3].toIntOrNull() ?: 1

        return OpEdInfo(
            baseKey = baseKey,
            number = number,
            category = category
        )
    }
}
