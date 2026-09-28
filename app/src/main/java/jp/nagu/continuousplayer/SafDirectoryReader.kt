package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

internal data class SafFolder(val uri: Uri, val title: String)
internal data class SafDirectory(val folders: List<SafFolder>, val media: List<VideoItem>)

/** Document IDs are opaque. Keep tree-scoped URIs and navigate using returned child IDs. */
internal class SafDirectoryReader(private val context: Context) {
    private val scanner = VideoScanner()
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    fun root(treeUri: Uri): SafFolder {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw IOException("Folder is unavailable")
            }
            SafFolder(uri, cursor.getString(1) ?: context.getString(R.string.saf_unnamed_folder))
        } ?: throw IOException("Cannot read folder")
    }

    fun read(folder: SafFolder): SafDirectory {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folder.uri, DocumentsContract.getDocumentId(folder.uri))
        val folders = mutableListOf<SafFolder>()
        val media = mutableListOf<VideoItem>()
        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Cannot read folder contents")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                val uri = DocumentsContract.buildDocumentUriUsingTree(folder.uri, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += SafFolder(uri, name)
                } else if (VideoScanner.isSupportedFile(name)) {
                    media += VideoItem(uri.toString(), name,
                        if (it.isNull(3)) -1 else it.getLong(3),
                        if (it.isNull(4)) 0 else it.getLong(4), mime)
                }
            }
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                // A cloud provider may return a partial listing; do not build a partial playlist.
                throw IOException(context.getString(R.string.saf_provider_loading))
            }
            it.extras.getString(DocumentsContract.EXTRA_ERROR)?.let { error -> throw IOException(error) }
        }
        return SafDirectory(DlnaFolderSorter.sort(folders) { it.title }, scanner.sortMedia(media))
    }
}
