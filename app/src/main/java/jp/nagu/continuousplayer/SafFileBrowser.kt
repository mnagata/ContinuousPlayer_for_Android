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
    private val onAddUsb: (() -> Unit)? = null,
    private val onUsbFolder: (SavedUsbFolder) -> Unit = {},
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private val reader = SafDirectoryReader(activity)
    private val savedFolders = SavedDlnaFolders(activity)
    private val savedUsbFolders = SavedUsbFolders(activity)
    private val lastPlaybackFolder = LastPlaybackFolder(activity)
    private var dialog: DlnaSelectionDialog? = null
    private var folderMenu: DlnaSelectionDialog? = null
    private var job: Job? = null
    internal var selectedFolder: PlaybackFolder.Saf? = null
        private set

    internal fun openFolder(folder: PlaybackFolder.Saf, focusUri: Uri? = null) = browse(folder.path, focusUri)

    fun close() {
        folderMenu?.dismiss()
        folderMenu = null
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
    }

    /** Transfer the attached window without revealing the activity underneath. */
    internal fun takeDialog(): DlnaSelectionDialog? {
        folderMenu?.dismiss()
        folderMenu = null
        job?.cancel()
        job = null
        return dialog.also { dialog = null }
    }

    fun onConfigurationChanged() {
        dialog?.takeIf { it.isShowing }?.refreshForConfiguration()
        folderMenu?.takeIf { it.isShowing }?.refreshForConfiguration()
    }

    fun hasSavedDlnaFolders() = savedFolders.all().isNotEmpty()

    fun openSavedFolders() = showRoots()

    internal fun openSavedFolders(inheritedDialog: DlnaSelectionDialog?) {
        close()
        dialog = inheritedDialog
        showRoots()
    }

    fun open(treeUri: Uri? = null) {
        if (treeUri != null) {
            load(retry = { open(treeUri) }, onUp = { showRoots() }) {
                val root = withContext(Dispatchers.IO) { reader.root(treeUri) }
                showDirectory(listOf(root))
            }
        } else {
            val trees = grantedTrees()
            if (hasSavedDlnaFolders() || savedUsbFolders.all().isNotEmpty()) showRoots()
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
        val hasRoots = grantedTrees().isNotEmpty() || hasSavedDlnaFolders() || savedUsbFolders.all().isNotEmpty()
        val sources = buildList<Pair<DlnaRow, () -> Unit>> {
            add(DlnaRow(activity.getString(R.string.saved_folder_local), R.drawable.ic_folder_open,
                detail = activity.getString(R.string.add_folder_local_detail)) to onAddFolder)
            if (isTv && onAddUsb != null) {
                add(DlnaRow(activity.getString(R.string.usb_choose_drive), R.drawable.ic_folder_open,
                    detail = activity.getString(R.string.add_folder_usb_detail)) to onAddUsb)
            }
            add(DlnaRow(activity.getString(R.string.dlna_select), R.drawable.ic_dlna_server,
                detail = activity.getString(R.string.add_folder_dlna_detail)) to {
                // Attach the DLNA loading window before releasing this one.
                onAddDlna()
                close()
            })
        }
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.saf_add_folder),
            label = R.string.saved_folder_dialog_label,
            hint = R.string.add_folder_source_hint,
            rows = sources.map { it.first },
            upLabel = R.string.saved_folder_list,
            onUp = if (hasRoots) ({ showRoots() }) else null,
            onClose = { close() },
            onSelected = { index ->
                // Keep this window underneath the SAF/USB picker so cancelling returns here.
                sources[index].second()
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
            val usb = DlnaFolderSorter.sort(savedUsbFolders.all()) { it.title }
            showDialog(DlnaDialogContent(
                title = activity.getString(R.string.saf_choose_folder),
                label = null,
                hint = R.string.saved_folder_hint,
                rows = roots.map { DlnaRow(it.second, R.drawable.ic_folder_open,
                    detail = activity.getString(R.string.saved_folder_local)) } + dlna.map {
                    DlnaRow(it.title, R.drawable.ic_dlna_server,
                        detail = "${activity.getString(R.string.dlna_dialog_label)} · ${it.path.joinToString(" / ") { entry -> entry.title }}")
                } + usb.map {
                    DlnaRow(it.title, R.drawable.ic_folder_open,
                        detail = "${activity.getString(R.string.saved_folder_usb)} · ${it.directoryPath}")
                },
                message = activity.getString(R.string.saf_no_folder),
                initialSelection = lastPlaybackFolder.initialSelection(roots.map { it.first }, dlna, focusUri, usb),
                refreshLabel = R.string.saf_add_folder,
                onRefresh = { addFolder() },
                onClose = { close() },
                onSelected = { index ->
                    if (index < roots.size) open(roots[index].first)
                    else if (index < roots.size + dlna.size) {
                        onDlnaFolder(dlna[index - roots.size])
                        close()
                    } else {
                        onUsbFolder(usb[index - roots.size - dlna.size])
                        close()
                    }
                },
                onLongSelected = { index ->
                    val root = roots.getOrNull(index)
                    val folder = dlna.getOrNull(index - roots.size)
                    val usbFolder = usb.getOrNull(index - roots.size - dlna.size)
                    folderMenu?.dismiss()
                    folderMenu = DlnaSelectionDialog(activity, DlnaDialogContent(
                        title = root?.second ?: folder?.title ?: usbFolder?.title.orEmpty(),
                        label = R.string.saved_folder_dialog_label,
                        message = activity.getString(R.string.folder_unregister_message),
                        registerLabel = R.string.folder_unregister,
                        onRegister = {
                            folderMenu?.dismiss()
                            folderMenu = null
                            try {
                                if (root != null) releaseFolderPermission(root.first)
                                else if (folder != null) savedFolders.remove(folder.serverId, folder.folderId)
                                else if (usbFolder != null) savedUsbFolders.remove(usbFolder)
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
        val mediaSelection = if (focusUri != null) media.indexOfFirst { it.uri == focusUri.toString() } else -1
        val rows = folders.map { DlnaRow(it.title, R.drawable.ic_folder_open,
            description = "${it.title}。${activity.getString(R.string.dlna_folder_detail)}") } + media.map {
            val audio = it.mimeType?.startsWith("audio/") == true ||
                it.displayName.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in
                setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
            val action = activity.getString(if (audio) R.string.dlna_audio_detail else R.string.dlna_video_detail)
            DlnaRow(it.selectionTitle, if (audio) R.drawable.ic_dlna_audio else R.drawable.ic_play,
                description = "${it.selectionTitle}。$action")
        }
        showDialog(DlnaDialogContent(
            title = path.last().title,
            label = null,
            fileSelection = true,
            rows = rows,
            message = activity.getString(R.string.usb_empty),
            initialSelection = if (mediaSelection >= 0) folders.size + mediaSelection
                else lastPlaybackFolder.initialDirectorySelection(folders, focusUri),
            upLabel = if (path.size == 1) R.string.saf_folder_list else R.string.usb_parent,
            onUp = { parent(path) },
            onClose = { close() },
            onSelected = { index ->
                if (index < folders.size) browse(path + folders[index])
                else {
                    selectedFolder = PlaybackFolder.Saf(path.toList())
                    onSelected(media, index - folders.size)
                    dialog?.closeAfterPlaybackFrame { close() }
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
        if (dialog?.keepListWhileLoading(onClose = { close() }) != true) showDialog(DlnaDialogContent(
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
