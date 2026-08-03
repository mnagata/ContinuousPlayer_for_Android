package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.Collator
import java.util.Locale

class VideoScanner(private val context: Context) {

    companion object {
        private const val TAG = "VideoScanner"
    }

    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    fun scanTree(treeUri: Uri, documentUri: Uri): List<VideoItem> {
        //Log.d(TAG, "scanTree START: treeUri=$treeUri, documentUri=$documentUri")

        // documentUri is the parent directory URI — use it directly as scan root
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                //Log.e(TAG, "scanTree: fromTreeUri returned null for $treeUri")
                return emptyList()
            }

        // Find the scan root by traversing the tree from documentUri path
        val scanRoot = try {
            val treeDocId = treeUri.toString()
                .substringAfter("tree/")
                .substringBefore('?')
                .let { Uri.decode(it) }
            //Log.d(TAG, "scanTree: treeDocId=$treeDocId")

            val docId = documentUri.toString()
                .substringAfter("document/")
                .substringBefore('?')
                .let { Uri.decode(it) }
            //Log.d(TAG, "scanTree: docId=$docId")

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
            //Log.e(TAG, "scanTree: scanRoot is null, falling back to tree root")
            return emptyList()
        }
        //Log.d(TAG, "scanTree: scanRoot=${scanRoot.name}, exists=${scanRoot.exists()}")

        val allFiles = scanRoot.listFiles()
        //Log.d(TAG, "scanTree: listFiles() returned ${allFiles.size} items")

        for (doc in allFiles) {
            val name = doc.name
            val isFile = doc.isFile
            val isDir = doc.isDirectory
            //Log.d(TAG, "scanTree: item: name=$name, isFile=$isFile, isDir=$isDir, uri=${doc.uri}")
        }

        val filtered = allFiles.filter { doc ->
            val name = doc.name ?: return@filter false
            val isVideoFile = name.endsWith(".mp4", ignoreCase = true) ||
                name.endsWith(".m4v", ignoreCase = true) ||
                name.endsWith(".mp3", ignoreCase = true) ||
                name.endsWith(".flac", ignoreCase = true) ||
                name.endsWith(".m4a", ignoreCase = true) ||
                name.endsWith(".aac", ignoreCase = true) ||
                name.endsWith(".wav", ignoreCase = true) ||
                name.endsWith(".ogg", ignoreCase = true) ||
                name.endsWith(".opus", ignoreCase = true)
            if (isVideoFile) {
                //Log.d(TAG, "scanTree: video file found: name=$name, uri=${doc.uri}")
            }
            return@filter isVideoFile
        }.map { doc ->
            VideoItem(
                uri = doc.uri.toString(),
                displayName = doc.name ?: "unknown",
                size = doc.length(),
                lastModified = doc.lastModified()
            )
        }
        //Log.d(TAG, "scanTree: total ${filtered.size} video files found")

        return sortLikeSafThenReorderOpEd(filtered)
	}

	private fun extractVariantNum(name: String): Int {
		val match = Regex("""(?:op|ed)(\d*)""").find(name) ?: return 0
		return if (match.groupValues[1].isEmpty()) 0 else match.groupValues[1].toInt()
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
