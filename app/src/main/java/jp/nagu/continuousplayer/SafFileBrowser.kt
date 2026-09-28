package jp.nagu.continuousplayer

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF supplies access; the shared DLNA dialog supplies file selection and navigation. */
class SafFileBrowser(
    private val activity: AppCompatActivity,
    private val onAddFolder: () -> Unit,
    private val onAddDlna: () -> Unit,
    private val onDlnaFolder: (SavedDlnaFolder) -> Unit,
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private val reader = SafDirectoryReader(activity)
    private val savedFolders = SavedDlnaFolders(activity)
    private var dialog: DlnaSelectionDialog? = null
    private var folderMenu: DlnaSelectionDialog? = null
    private var job: Job? = null
    internal var selectedFolder: PlaybackFolder.Saf? = null
        private set

    internal fun openFolder(folder: PlaybackFolder.Saf) = browse(folder.path)

    fun close() {
        folderMenu?.dismiss()
        folderMenu = null
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
    }

    fun onConfigurationChanged() {
        dialog?.takeIf { it.isShowing }?.refreshForConfiguration()
        folderMenu?.takeIf { it.isShowing }?.refreshForConfiguration()
    }

    fun hasSavedDlnaFolders() = savedFolders.all().isNotEmpty()

    fun openSavedFolders() = showRoots()

    fun open(treeUri: Uri? = null) {
        if (treeUri != null) {
            load(retry = { open(treeUri) }, onUp = { showRoots() }) {
                val root = withContext(Dispatchers.IO) { reader.root(treeUri) }
                showDirectory(listOf(root))
            }
        } else {
            val trees = grantedTrees()
            if (hasSavedDlnaFolders()) showRoots()
            else if (trees.isEmpty()) addFolder()
            else if (trees.size == 1) open(trees.single())
            else showRoots()
        }
    }

    private fun grantedTrees() = activity.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }.map { it.uri }

    private fun addFolder() {
        // Replace the contents of the attached window; dismissing first reveals the home screen.
        job?.cancel()
        job = null
        val isTv = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val hasRoots = grantedTrees().isNotEmpty() || hasSavedDlnaFolders()
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.saf_add_folder),
            label = R.string.saved_folder_dialog_label,
            hint = R.string.add_folder_source_hint,
            rows = listOf(
                DlnaRow(activity.getString(if (isTv) R.string.usb_choose_drive else R.string.saved_folder_local),
                    R.drawable.ic_folder_open,
                    detail = activity.getString(if (isTv) R.string.add_folder_usb_detail else R.string.add_folder_local_detail)),
                DlnaRow(activity.getString(R.string.dlna_select), R.drawable.ic_dlna_server,
                    detail = activity.getString(R.string.add_folder_dlna_detail))),
            upLabel = R.string.saved_folder_list,
            onUp = if (hasRoots) ({ showRoots() }) else null,
            onClose = { close() },
            onSelected = { index ->
                if (index == 1) {
                    // Attach the DLNA loading window before releasing this one.
                    onAddDlna()
                    close()
                } else {
                    // Keep the window underneath the system picker, including when it is cancelled.
                    onAddFolder()
                }
            }))
    }

    private fun showRoots(focusUri: Uri? = null) {
        load(retry = { showRoots(focusUri) }) {
            val roots = withContext(Dispatchers.IO) {
                // Keep disconnected/revoked roots selectable so their error offers retry/add.
                val folders = grantedTrees().map { tree ->
                    val title = try { reader.root(tree).title }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { activity.getString(R.string.saf_unavailable_folder) }
                    tree to title
                }
                DlnaFolderSorter.sort(folders) { it.second }
            }
            val dlna = savedFolders.all().sortedBy { it.serverName + it.title }
            showDialog(DlnaDialogContent(
                title = activity.getString(R.string.saf_choose_folder),
                label = R.string.saved_folder_dialog_label,
                hint = R.string.saved_folder_hint,
                rows = roots.map { DlnaRow(it.second, R.drawable.ic_folder_open,
                    detail = activity.getString(R.string.saved_folder_local)) } + dlna.map {
                    DlnaRow(it.title, R.drawable.ic_dlna_server,
                        detail = "${activity.getString(R.string.dlna_dialog_label)} · ${it.path.joinToString(" / ") { entry -> entry.title }}")
                },
                message = activity.getString(R.string.saf_no_folder),
                initialSelection = roots.indexOfFirst { it.first == focusUri }.coerceAtLeast(0),
                refreshLabel = R.string.saf_add_folder,
                onRefresh = { addFolder() },
                onClose = { close() },
                onSelected = { index ->
                    if (index < roots.size) open(roots[index].first)
                    else {
                        close()
                        onDlnaFolder(dlna[index - roots.size])
                    }
                },
                onLongSelected = { index ->
                    val root = roots.getOrNull(index)
                    val folder = dlna.getOrNull(index - roots.size)
                    folderMenu?.dismiss()
                    folderMenu = DlnaSelectionDialog(activity, DlnaDialogContent(
                        title = root?.second ?: folder?.title.orEmpty(),
                        label = R.string.saved_folder_dialog_label,
                        message = activity.getString(R.string.folder_unregister_message),
                        registerLabel = R.string.folder_unregister,
                        onRegister = {
                            folderMenu?.dismiss()
                            folderMenu = null
                            try {
                                if (root != null) releaseFolderPermission(root.first)
                                else if (folder != null) savedFolders.remove(folder.serverId, folder.folderId)
                                android.widget.Toast.makeText(activity, R.string.folder_unregistered,
                                    android.widget.Toast.LENGTH_SHORT).show()
                            } catch (error: SecurityException) {
                                Log.w("SafFileBrowser", "Cannot release folder permission", error)
                                android.widget.Toast.makeText(activity, R.string.folder_unregister_failed,
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                            showRoots()
                        },
                        onClose = {
                            folderMenu?.dismiss()
                            folderMenu = null
                        })).also { it.show() }
                }))
        }
    }

    private fun releaseFolderPermission(tree: Uri) {
        val permission = activity.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
            ?: return
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) activity.contentResolver.releasePersistableUriPermission(tree, flags)
    }

    private fun browse(path: List<SafFolder>, focusUri: Uri? = null) {
        load(retry = { browse(path, focusUri) }, onUp = { parent(path) }) {
            showDirectory(path, focusUri)
        }
    }

    private suspend fun showDirectory(path: List<SafFolder>, focusUri: Uri? = null) {
        val (folders, media) = withContext(Dispatchers.IO) { reader.read(path.last()) }
        val rows = folders.map { DlnaRow(it.title, R.drawable.ic_folder_open,
            description = "${it.title}。${activity.getString(R.string.dlna_folder_detail)}") } + media.map {
            val audio = it.mimeType?.startsWith("audio/") == true ||
                it.displayName.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in
                setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
            val action = activity.getString(if (audio) R.string.dlna_audio_detail else R.string.dlna_video_detail)
            DlnaRow(it.displayName, if (audio) R.drawable.ic_dlna_audio else R.drawable.ic_play,
                description = "${it.displayName}。$action")
        }
        showDialog(DlnaDialogContent(
            title = path.last().title,
            path = path.joinToString("  /  ") { it.title },
            summary = activity.getString(R.string.dlna_folder_count, folders.size, media.size),
            rows = rows,
            message = activity.getString(R.string.usb_empty),
            initialSelection = folders.indexOfFirst { it.uri == focusUri }.coerceAtLeast(0),
            upLabel = if (path.size == 1) R.string.saf_folder_list else R.string.usb_parent,
            onUp = { parent(path) },
            onRefresh = { browse(path) },
            onClose = { close() },
            onSelected = { index ->
                if (index < folders.size) browse(path + folders[index])
                else {
                    selectedFolder = PlaybackFolder.Saf(path.toList())
                    close()
                    onSelected(media, index - folders.size)
                }
            }))
    }

    private fun parent(path: List<SafFolder>) {
        if (path.size == 1) {
            val root = path.first().uri
            showRoots(DocumentsContract.buildTreeDocumentUri(root.authority!!,
                DocumentsContract.getTreeDocumentId(root)))
        } else browse(path.dropLast(1), path.last().uri)
    }

    private fun showDialog(content: DlnaDialogContent) {
        val next = content.copy(
            label = if (content.label == R.string.dlna_dialog_label) R.string.saf_dialog_label else content.label,
            hint = content.hint ?: R.string.dlna_touch_hint)
        val current = dialog
        if (current?.isShowing == true) current.updateContent(next)
        else dialog = DlnaSelectionDialog(activity, next).also { it.show() }
    }

    private fun load(retry: () -> Unit, onUp: (() -> Unit)? = null, action: suspend () -> Unit) {
        job?.cancel()
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.saf_dialog_label),
            message = activity.getString(R.string.saf_loading), loading = true,
            onUp = onUp, onClose = { close() }))
        job = activity.lifecycleScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("SafFileBrowser", "SAF browsing failed", error)
                showDialog(DlnaDialogContent(
                    title = activity.getString(R.string.saf_read_error),
                    message = activity.getString(R.string.saf_error_help) + "\n\n${error.message.orEmpty()}",
                    upLabel = R.string.saf_folder_list, onUp = { showRoots() },
                    refreshLabel = R.string.playback_retry, onRefresh = retry,
                    onClose = { close() }))
            }
        }
    }
}
