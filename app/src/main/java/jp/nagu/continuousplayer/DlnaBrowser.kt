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
    private val onCancelled: () -> Unit = {},
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private val savedFolders = SavedDlnaFolders(activity)
    private var returnToSavedFolders: (() -> Unit)? = null
    private var navigationRoot: SavedDlnaFolder? = null
    private var registering = false
    private var onRegistered: ((SavedDlnaFolder) -> Unit)? = null
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
        navigationRoot = null
        registering = false
        onRegistered = null
    }

    private fun cancel() {
        if (registering && returnToSavedFolders != null) returnToSaved()
        else {
            close()
            onCancelled()
        }
    }

    internal fun takeDialog(): DlnaSelectionDialog? {
        job?.cancel()
        job = null
        returnToSavedFolders = null
        navigationRoot = null
        registering = false
        onRegistered = null
        return dialog.also { dialog = null }
    }

    fun onConfigurationChanged() {
        val current = dialog ?: return
        if (current.isShowing) current.refreshForConfiguration()
    }

    fun open() {
        close()
        registering = true
        refreshServers()
    }

    internal fun openForRegistration(inheritedDialog: DlnaSelectionDialog?,
        onRegistered: (SavedDlnaFolder) -> Unit, onReturn: () -> Unit) {
        close()
        dialog = inheritedDialog
        registering = true
        this.onRegistered = onRegistered
        returnToSavedFolders = onReturn
        refreshServers()
    }

    private fun refreshServers() {
        load(R.string.dlna_searching, retry = { refreshServers() }) {
            servers = discoverServers()
            showServers()
        }
    }

    fun openSaved(folder: SavedDlnaFolder, focusMediaId: String? = null, onReturn: (SavedDlnaFolder) -> Unit) {
        registering = false
        onRegistered = null
        // A playback directory may lie below a saved root; direct saved selections match themselves.
        val root = savedFolders.all().filter { candidate ->
            candidate.serverId == folder.serverId && candidate.path.size <= folder.path.size &&
                candidate.path.map { it.id } == folder.path.take(candidate.path.size).map { it.id }
        }.maxByOrNull { it.path.size } ?: folder
        navigationRoot = root
        returnToSavedFolders = { onReturn(root) }
        load(R.string.dlna_searching, retry = { openSaved(folder, focusMediaId, onReturn) }) {
            servers = discoverServers()
            val server = servers.firstOrNull { it.id == folder.serverId }
                ?: throw java.io.IOException(activity.getString(R.string.dlna_saved_server_missing, folder.serverName))
            showDirectory(server, folder.path.mapIndexed { index, entry ->
                DlnaEntry(entry.id, if (index == 0) server.name else entry.title)
            }, focusMediaId)
        }
    }

    internal fun openSaved(folder: SavedDlnaFolder, inheritedDialog: DlnaSelectionDialog?, onReturn: (SavedDlnaFolder) -> Unit) {
        close()
        dialog = inheritedDialog
        openSaved(folder, onReturn = onReturn)
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
        onReturn()
        close()
    }

    private fun showServers(focusId: String? = null) {
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.dlna_choose_server),
            summary = activity.getString(R.string.dlna_server_count, servers.size),
            rows = servers.map { DlnaRow(it.name, R.drawable.ic_dlna_server,
                detail = activity.getString(R.string.dlna_server_detail, java.net.URI(it.controlUrl).host)) },
            message = activity.getString(R.string.dlna_no_server),
            initialSelection = servers.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
            upLabel = R.string.saved_folder_list,
            onUp = if (returnToSavedFolders != null) ({ returnToSaved() }) else null,
            onRefresh = { refreshServers() },
            onClose = { cancel() },
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

    private fun atNavigationRoot(path: List<DlnaEntry>) = !registering && navigationRoot?.let {
        path.map { entry -> entry.id } == it.path.map { entry -> entry.id }
    } == true

    private suspend fun showDirectory(server: DlnaServer, path: List<DlnaEntry>, focusId: String? = null) {
        navigationRoot?.takeUnless { registering }?.let { root ->
            check(server.id == root.serverId && path.take(root.path.size).map { it.id } == root.path.map { it.id })
        }
        val entries = withContext(Dispatchers.IO) { client.browse(server, path.last().id) }
        val folders = DlnaFolderSorter.sort(entries.filter { it.media == null })
        val videos = scanner.sortMedia(entries.mapNotNull { entry ->
            entry.media?.copy(dlnaObjectId = entry.id)
        })
        val mediaSelection = if (focusId != null) videos.indexOfFirst { it.dlnaObjectId == focusId } else -1
        val rows = folders.map { DlnaRow(it.title, R.drawable.ic_folder_open,
            description = "${it.title}。${activity.getString(R.string.dlna_folder_detail)}") } + mediaBrowserRows(activity, videos, folders.map { it.title })
        val content = DlnaDialogContent(
            title = path.last().title,
            label = null,
            fileSelection = true,
            rows = rows,
            message = activity.getString(R.string.dlna_empty),
            initialSelection = if (mediaSelection >= 0) folders.size + mediaSelection
                else folders.indexOfFirst { it.id == focusId }.coerceAtLeast(0),
            upLabel = if (atNavigationRoot(path)) R.string.saved_folder_list
                else if (path.size > 1) R.string.usb_parent else R.string.dlna_server_list,
            onUp = { parent(server, path) },
            onRefresh = { browse(server, path) },
            registerLabel = R.string.folder_register,
            onRegister = if (registering) ({ registerFolder(server, path) }) else null,
            onClose = { cancel() },
            onSelected = { index ->
                when {
                    index < folders.size -> browse(server, path + folders[index])
                    else -> {
                        selectedFolder = PlaybackFolder.Dlna(SavedDlnaFolder(server.id, server.name,
                            path.map { SavedDlnaPath(it.id, it.title) }))
                        onSelected(videos, index - folders.size)
                        dialog?.closeAfterPlaybackFrame { close() }
                    }
                }
            })
        showDialog(content)
    }

    private fun registerFolder(server: DlnaServer, path: List<DlnaEntry>) {
        savedFolders.save(server, path)
        val registered = SavedDlnaFolder(server.id, server.name, path.map { SavedDlnaPath(it.id, it.title) })
        android.widget.Toast.makeText(activity, R.string.folder_registered, android.widget.Toast.LENGTH_SHORT).show()
        onRegistered?.invoke(registered)
        close()
    }

    private fun parent(server: DlnaServer, path: List<DlnaEntry>) {
        if (atNavigationRoot(path)) returnToSaved()
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
        if (dialog?.keepListWhileLoading(onClose = { cancel() }, showLoadingIndicator = true) != true) showDialog(DlnaDialogContent(
            title = activity.getString(R.string.dlna_dialog_label),
            message = activity.getString(message),
            loading = true,
            onClose = { cancel() }))
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
                    onClose = { cancel() }))
            }
        }
    }
}
