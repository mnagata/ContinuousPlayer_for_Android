package jp.nagu.continuousplayer

import android.content.Context
import com.google.gson.Gson
import java.io.File

data class SavedUsbFolder(val rootPath: String, val directoryPath: String) {
    val title: String get() = File(directoryPath).name.ifEmpty { directoryPath }
    internal val folder: PlaybackFolder.Usb get() = PlaybackFolder.Usb(File(rootPath), File(directoryPath))
}

/** Keep disconnected folders registered so they can be reopened or removed later. */
internal class SavedUsbFolders(context: Context) {
    private val preferences = context.getSharedPreferences("saved_usb_folders", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun all(): List<SavedUsbFolder> = runCatching {
        gson.fromJson(preferences.getString("folders", "[]"), Array<SavedUsbFolder>::class.java)
            .orEmpty().filter { folder ->
                runCatching {
                    val root = File(folder.rootPath)
                    val directory = File(folder.directoryPath)
                    root.isAbsolute && directory.isAbsolute &&
                        directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
                }.getOrDefault(false)
            }.distinctBy { it.rootPath to it.directoryPath }
    }.getOrDefault(emptyList())

    fun save(root: File, directory: File) {
        val canonicalRoot = root.canonicalFile
        val canonicalDirectory = directory.canonicalFile
        require(canonicalDirectory.toPath().startsWith(canonicalRoot.toPath()))
        val folder = SavedUsbFolder(canonicalRoot.path, canonicalDirectory.path)
        val next = all().filterNot { it == folder } + folder
        preferences.edit().putString("folders", gson.toJson(next)).apply()
    }

    fun remove(folder: SavedUsbFolder) {
        preferences.edit().putString("folders", gson.toJson(all().filterNot { it == folder })).apply()
    }
}
