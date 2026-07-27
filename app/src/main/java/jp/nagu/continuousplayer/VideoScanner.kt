package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import android.util.Log
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
        Log.d(TAG, "scanTree START: treeUri=$treeUri, documentUri=$documentUri")

        // documentUri is the parent directory URI — use it directly as scan root
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: run {
                Log.e(TAG, "scanTree: fromTreeUri returned null for $treeUri")
                return emptyList()
            }

        // Find the scan root by traversing the tree from documentUri path
        val scanRoot = try {
            val treeDocId = treeUri.toString()
                .substringAfter("tree/")
                .substringBefore('?')
                .let { Uri.decode(it) }
            Log.d(TAG, "scanTree: treeDocId=$treeDocId")

            val docId = documentUri.toString()
                .substringAfter("document/")
                .substringBefore('?')
                .let { Uri.decode(it) }
            Log.d(TAG, "scanTree: docId=$docId")

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
            Log.e(TAG, "scanTree: scanRoot is null, falling back to tree root")
            return emptyList()
        }
        Log.d(TAG, "scanTree: scanRoot=${scanRoot.name}, exists=${scanRoot.exists()}")

        val allFiles = scanRoot.listFiles()
        Log.d(TAG, "scanTree: listFiles() returned ${allFiles.size} items")

        for (doc in allFiles) {
            val name = doc.name
            val isFile = doc.isFile
            val isDir = doc.isDirectory
            Log.d(TAG, "scanTree: item: name=$name, isFile=$isFile, isDir=$isDir, uri=${doc.uri}")
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
                Log.d(TAG, "scanTree: video file found: name=$name, uri=${doc.uri}")
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
        Log.d(TAG, "scanTree: total ${filtered.size} video files found")

	return filtered.sortedWith(
		java.util.Comparator<VideoItem> { a, b ->
		val nameA = a.displayName.lowercase(Locale.ROOT)
		val nameB = b.displayName.lowercase(Locale.ROOT)
		// Extract base name: remove op/op2/ed/ed2 suffix
		val baseA = nameA.replace(Regex("""\s+(op|op\d|ed|ed\d)\s*\.\w+$"""), "").trim()
		val baseB = nameB.replace(Regex("""\s+(op|op\d|ed|ed\d)\s*\.\w+$"""), "").trim()
		// Group by base name first
		val cmp = baseA.compareTo(baseB)
		if (cmp != 0) return@Comparator cmp
		// Same base: extract variant number (op/ed=0, op2/ed2=1, etc.)
		val numA = extractVariantNum(nameA)
		val numB = extractVariantNum(nameB)
		val numCmp = numA.compareTo(numB)
		if (numCmp != 0) return@Comparator numCmp
		// Same variant: op before ed
		val catA = if (nameA.contains("op")) 0 else 1
		val catB = if (nameB.contains("op")) 0 else 1
		catA.compareTo(catB)
	},
	)
	}

	private fun extractVariantNum(name: String): Int {
		val match = Regex("""(?:op|ed)(\d*)""").find(name) ?: return 0
		return if (match.groupValues[1].isEmpty()) 0 else match.groupValues[1].toInt()
	}
}
