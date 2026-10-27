package jp.nagu.continuousplayer

import android.content.Context
import com.google.gson.Gson

data class SavedDlnaPath(val id: String, val title: String)
data class SavedDlnaFolder(val serverId: String, val serverName: String, val path: List<SavedDlnaPath>) {
    val folderId: String get() = path.last().id
    val title: String get() = path.last().title
}

/** Store identities/paths and explicitly connected device-description addresses. */
internal class SavedDlnaFolders(context: Context) {
    private val preferences = context.getSharedPreferences("saved_dlna_folders", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun all(): List<SavedDlnaFolder> = runCatching {
        gson.fromJson(preferences.getString("folders", "[]"), Array<SavedDlnaFolder>::class.java)
            .orEmpty().filter { folder ->
                runCatching {
                    folder.serverId.isNotBlank() && folder.serverName.isNotBlank() && folder.path.isNotEmpty() &&
                        folder.path.all { it.id.isNotBlank() && it.title.isNotBlank() }
                }.getOrDefault(false)
            }.distinctBy { it.serverId to it.folderId }
    }.getOrDefault(emptyList())

    fun addressFor(serverId: String): String? = preferences.getString("address:$serverId", null)
        ?.takeIf { runCatching { DlnaAddress.parse(it) is DlnaAddress.Description }.getOrDefault(false) }

    fun lastAddress(): String = preferences.getString("last_address", "").orEmpty()

    fun rememberAddress(server: DlnaServer) {
        val address = requireNotNull(server.descriptionUrl)
        require(DlnaAddress.parse(address) is DlnaAddress.Description)
        preferences.edit().putString("address:${server.id}", address).putString("last_address", address).apply()
    }

    fun contains(serverId: String, folderId: String) = all().any {
        it.serverId == serverId && it.folderId == folderId
    }

    fun save(server: DlnaServer, path: List<DlnaEntry>) {
        require(path.isNotEmpty() && path.all { it.media == null })
        val folder = SavedDlnaFolder(server.id, server.name, path.map { SavedDlnaPath(it.id, it.title) })
        val next = all().filterNot { it.serverId == server.id && it.folderId == folder.folderId } + folder
        preferences.edit().putString("folders", gson.toJson(next)).apply()
    }

    fun remove(serverId: String, folderId: String) {
        val next = all().filterNot { it.serverId == serverId && it.folderId == folderId }
        preferences.edit().putString("folders", gson.toJson(next)).apply()
    }
}
