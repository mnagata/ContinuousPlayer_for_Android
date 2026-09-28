package jp.nagu.continuousplayer

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Server and ContentDirectory browser shared by touch and D-pad screens. */
class DlnaBrowser(
    private val activity: AppCompatActivity,
    private val client: DlnaClient = DlnaClient(),
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private val savedFolders = SavedDlnaFolders(activity)
    private var returnToSavedFolders: (() -> Unit)? = null
    private val scanner = VideoScanner()
    private var servers = emptyList<DlnaServer>()
    private var dialog: DlnaSelectionDialog? = null
    private var job: Job? = null
    internal var selectedFolder: PlaybackFolder.Dlna? = null
        private set

    fun close() {
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
        returnToSavedFolders = null
        directoryContent = null
    }

    fun onConfigurationChanged() {
        val current = dialog ?: return
        if (current.isShowing) current.refreshForConfiguration()
    }

    fun open() {
        returnToSavedFolders = null
        load(R.string.dlna_searching, retry = { open() }) {
            servers = discoverServers()
            showServers()
        }
    }

    fun openSaved(folder: SavedDlnaFolder, onReturn: () -> Unit) {
        returnToSavedFolders = onReturn
        load(R.string.dlna_searching, retry = { openSaved(folder, onReturn) }) {
            servers = discoverServers()
            val server = servers.firstOrNull { it.id == folder.serverId }
                ?: throw java.io.IOException(activity.getString(R.string.dlna_saved_server_missing, folder.serverName))
            showDirectory(server, folder.path.mapIndexed { index, entry ->
                DlnaEntry(entry.id, if (index == 0) server.name else entry.title)
            })
        }
    }

    private suspend fun discoverServers(): List<DlnaServer> = withContext(Dispatchers.IO) {
        val wifi = if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI))
            activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager else null
        val lock = wifi?.createMulticastLock("ContinuousPlayer:DLNA")
        try {
            lock?.acquire()
            client.discover()
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private fun returnToSaved() {
        val onReturn = returnToSavedFolders ?: return
        close()
        onReturn()
    }

    private fun showServers(focusId: String? = null) {
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.dlna_choose_server),
            summary = activity.getString(R.string.dlna_server_count, servers.size),
            rows = servers.map { DlnaRow(it.name, R.drawable.ic_dlna_server,
                detail = activity.getString(R.string.dlna_server_detail, java.net.URI(it.controlUrl).host)) },
            message = activity.getString(R.string.dlna_no_server),
            initialSelection = servers.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
            onRefresh = { open() },
            onClose = { close() },
            onSelected = { index ->
                val server = servers[index]
                browse(server, listOf(DlnaEntry("0", server.name)))
            }))
    }

    private fun browse(server: DlnaServer, path: List<DlnaEntry>, focusId: String? = null) {
        load(R.string.dlna_loading, retry = { browse(server, path, focusId) }) {
            showDirectory(server, path, focusId)
        }
    }

    private var directoryContent: DlnaDialogContent? = null

    private suspend fun showDirectory(server: DlnaServer, path: List<DlnaEntry>, focusId: String? = null) {
        val entries = withContext(Dispatchers.IO) { client.browse(server, path.last().id) }
        val folders = DlnaFolderSorter.sort(entries.filter { it.media == null })
        val videos = scanner.sortMedia(entries.mapNotNull { it.media })
        val rows = folders.map { DlnaRow(it.title, R.drawable.ic_folder_open,
            description = "${it.title}。${activity.getString(R.string.dlna_folder_detail)}") } + videos.map { media ->
            val audio = media.mimeType?.startsWith("audio/") == true ||
                media.displayName.substringAfterLast('.').lowercase() in
                setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
            val action = activity.getString(if (audio) R.string.dlna_audio_detail else R.string.dlna_video_detail)
            DlnaRow(media.displayName, if (audio) R.drawable.ic_dlna_audio else R.drawable.ic_play,
                description = "${media.displayName}。$action")
        }
        val content = DlnaDialogContent(
            title = path.last().title,
            path = path.joinToString("  /  ") { it.title },
            summary = activity.getString(R.string.dlna_folder_count, folders.size, videos.size),
            rows = rows,
            message = activity.getString(R.string.dlna_empty),
            initialSelection = folders.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
            upLabel = if (path.size > 1) R.string.usb_parent else if (returnToSavedFolders != null)
                R.string.saved_folder_list else R.string.dlna_server_list,
            onUp = { parent(server, path) },
            onRefresh = { browse(server, path) },
            registerLabel = if (savedFolders.contains(server.id, path.last().id))
                R.string.folder_unregister else R.string.folder_register,
            onRegister = { toggleSavedFolder(server, path) },
            onClose = { close() },
            onSelected = { index ->
                when {
                    index < folders.size -> browse(server, path + folders[index])
                    else -> {
                        selectedFolder = PlaybackFolder.Dlna(SavedDlnaFolder(server.id, server.name,
                            path.map { SavedDlnaPath(it.id, it.title) }))
                        close()
                        onSelected(videos, index - folders.size)
                    }
                }
            })
        directoryContent = content
        showDialog(content)
    }

    private fun toggleSavedFolder(server: DlnaServer, path: List<DlnaEntry>) {
        val removing = savedFolders.contains(server.id, path.last().id)
        if (removing) savedFolders.remove(server.id, path.last().id)
        else savedFolders.save(server, path)
        android.widget.Toast.makeText(activity,
            if (removing) R.string.folder_unregistered else R.string.folder_registered,
            android.widget.Toast.LENGTH_SHORT).show()
        directoryContent?.copy(registerLabel = if (removing) R.string.folder_register else R.string.folder_unregister)
            ?.let { directoryContent = it; showDialog(it) }
    }

    private fun parent(server: DlnaServer, path: List<DlnaEntry>) {
        if (path.size == 1 && returnToSavedFolders != null) returnToSaved()
        else if (path.size == 1) showServers(server.id)
        else browse(server, path.dropLast(1), path.last().id)
    }

    private fun showDialog(content: DlnaDialogContent) {
        val current = dialog
        if (current?.isShowing == true) {
            current.updateContent(content)
        } else {
            dialog = DlnaSelectionDialog(activity, content).also { it.show() }
        }
    }

    private fun load(message: Int, retry: () -> Unit, action: suspend () -> Unit) {
        // Cancel only the old request. Keep the browser window attached while changing content.
        job?.cancel()
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.dlna_dialog_label),
            message = activity.getString(message),
            loading = true,
            onClose = { close() }))
        job = activity.lifecycleScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("DlnaBrowser", "DLNA browsing failed", error)
                showDialog(DlnaDialogContent(
                    title = activity.getString(R.string.dlna_error),
                    message = activity.getString(R.string.dlna_error_help) + "\n\n${error.message.orEmpty()}",
                    upLabel = R.string.saved_folder_list,
                    onUp = if (returnToSavedFolders != null) ({ returnToSaved() }) else null,
                    refreshLabel = R.string.playback_retry,
                    onRefresh = retry,
                    onClose = { close() }))
            }
        }
    }
}
