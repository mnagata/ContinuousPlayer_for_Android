package jp.nagu.continuousplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Persist identities rather than row numbers, since saved folders can be reordered. */
internal class LastPlaybackFolder(context: Context) {
    private val preferences = context.getSharedPreferences("last_playback_folder", Context.MODE_PRIVATE)

    fun save(folder: PlaybackFolder?) {
        // Opening and closing the app without playing must preserve the previous selection.
        if (folder == null) return
        val editor = preferences.edit().clear()
        when (folder) {
            is PlaybackFolder.Saf -> folder.path.firstOrNull()?.uri?.let { uri ->
                editor.putString("saf_tree", DocumentsContract.buildTreeDocumentUri(
                    requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri)).toString())
                editor.putStringSet("saf_path", folder.path.map { it.uri.toString() }.toSet())
            }
            is PlaybackFolder.Dlna -> editor
                .putString("dlna_server", folder.folder.serverId)
                .putStringSet("dlna_path", folder.folder.path.map { it.id }.toSet())
            is PlaybackFolder.Usb -> Unit // USB folders are not part of the saved list.
        }
        editor.apply()
    }

    fun initialSelection(roots: List<Uri>, dlna: List<SavedDlnaFolder>, focusUri: Uri? = null): Int {
        // Returning from a directory explicitly selects that directory's root.
        roots.indexOf(focusUri).takeIf { it >= 0 }?.let { return it }
        val tree = preferences.getString("saf_tree", null)
        roots.indexOfFirst { it.toString() == tree }.takeIf { it >= 0 }?.let { return it }

        val server = preferences.getString("dlna_server", null)
        val path = preferences.getStringSet("dlna_path", emptySet()).orEmpty()
        // A child may not be registered itself: select its nearest saved ancestor.
        val index = dlna.indices.filter { dlna[it].serverId == server && dlna[it].folderId in path }
            .maxByOrNull { dlna[it].path.size }
        return index?.let { roots.size + it } ?: 0
    }

    fun initialDirectorySelection(folders: List<SafFolder>, focusUri: Uri? = null): Int {
        folders.indexOfFirst { it.uri == focusUri }.takeIf { it >= 0 }?.let { return it }
        val path = preferences.getStringSet("saf_path", emptySet()).orEmpty()
        return folders.indexOfFirst { it.uri.toString() in path }.coerceAtLeast(0)
    }
}
