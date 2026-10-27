package jp.nagu.continuousplayer

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import android.widget.PopupMenu
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
    private val onCancelled: () -> Unit = {},
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private val reader = SafDirectoryReader(activity)
    private val savedFolders = SavedDlnaFolders(activity)
    private val savedUsbFolders = SavedUsbFolders(activity)
    private val lastPlaybackFolder = LastPlaybackFolder(activity)
    private var dialog: DlnaSelectionDialog? = null
    private var folderMenu: DlnaSelectionDialog? = null
    private var folderActions: PopupMenu? = null
    private var job: Job? = null
    internal var selectedFolder: PlaybackFolder.Saf? = null
        private set

    internal fun openFolder(folder: PlaybackFolder.Saf, focusUri: Uri? = null) = browse(folder.path, focusUri)

    fun close() {
        folderActions?.dismiss()
        folderActions = null
        folderMenu?.dismiss()
        folderMenu = null
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
    }

    private fun cancel() {
        close()
        onCancelled()
    }

    /** Transfer the attached window without revealing the activity underneath. */
    internal fun takeDialog(): DlnaSelectionDialog? {
        folderActions?.dismiss()
        folderActions = null
        folderMenu?.dismiss()
        folderMenu = null
        job?.cancel()
        job = null
        return dialog.also { dialog = null }
    }

    fun onConfigurationChanged() {
        folderActions?.dismiss()
        folderActions = null
        dialog?.takeIf { it.isShowing }?.refreshForConfiguration()
        folderMenu?.takeIf { it.isShowing }?.refreshForConfiguration()
    }

    fun hasSavedDlnaFolders() = savedFolders.all().isNotEmpty()

    fun openSavedFolders() = showRoots()

    internal fun openSavedFolders(inheritedDialog: DlnaSelectionDialog?, focusDlna: SavedDlnaFolder? = null,
        focusUsb: SavedUsbFolder? = null) {
        close()
        dialog = inheritedDialog
        showRoots(focusDlna = focusDlna, focusUsb = focusUsb)
    }

    internal fun folderRegistered(treeUri: Uri) = showRoots(focusUri = treeUri)

    fun open(treeUri: Uri? = null) {
        if (treeUri != null) {
            load(retry = { open(treeUri) }, onUp = { showRoots(focusUri = treeUri) }, focusTree = treeUri) {
                val root = withContext(Dispatchers.IO) { reader.root(treeUri) }
                showDirectory(listOf(root))
            }
        } else {
            // Home always opens the saved list; opening a folder or adding one is explicit.
            showRoots()
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
        val sources = buildList<Pair<DlnaRow, () -> Unit>> {
            add(DlnaRow(activity.getString(R.string.saved_folder_local), R.drawable.ic_folder_open,
                detail = activity.getString(R.string.add_folder_local_detail)) to onAddFolder)
            if (isTv && onAddUsb != null) {
                add(DlnaRow(activity.getString(R.string.usb_choose_drive), R.drawable.ic_folder_open,
                    detail = activity.getString(R.string.add_folder_usb_detail)) to onAddUsb)
            }
            add(DlnaRow(activity.getString(R.string.dlna_select), R.drawable.ic_dlna_server,
                detail = activity.getString(R.string.add_folder_dlna_detail)) to {
                // The receiver takes ownership of the attached window.
                onAddDlna()
            })
        }
        showDialog(DlnaDialogContent(
            title = activity.getString(R.string.saf_add_folder),
            label = R.string.saved_folder_dialog_label,
            hint = R.string.add_folder_source_hint,
            rows = sources.map { it.first },
            upLabel = R.string.saved_folder_list,
            onUp = { showRoots() },
            onClose = { showRoots() },
            onSelected = { index ->
                // Keep this window underneath the SAF/USB picker so cancelling returns here.
                sources[index].second()
            }))
    }

    private fun showRoots(focusUri: Uri? = null, focusDlna: SavedDlnaFolder? = null,
        focusUsb: SavedUsbFolder? = null, focusIndex: Int? = null) {
        load(retry = { showRoots(focusUri, focusDlna, focusUsb, focusIndex) }) {
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
            val explicitSelection = when {
                focusDlna != null -> dlna.indexOfFirst {
                    it.serverId == focusDlna.serverId && it.folderId == focusDlna.folderId
                }.takeIf { it >= 0 }?.let { roots.size + it }
                focusUsb != null -> usb.indexOf(focusUsb).takeIf { it >= 0 }?.let { roots.size + dlna.size + it }
                else -> null
            }
            val titles = roots.map { it.second } + dlna.map { it.title } + usb.map { it.title }
            fun removeAt(index: Int) {
                when {
                    index < roots.size -> releaseFolderPermission(roots[index].first)
                    index < roots.size + dlna.size -> dlna[index - roots.size].let { savedFolders.remove(it.serverId, it.folderId) }
                    else -> savedUsbFolders.remove(usb[index - roots.size - dlna.size])
                }
            }
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
                initialSelection = focusIndex?.coerceIn(0, (titles.size - 1).coerceAtLeast(0)) ?: explicitSelection ?:
                    lastPlaybackFolder.initialSelection(roots.map { it.first }, dlna, focusUri, usb),
                refreshLabel = R.string.saf_add_folder,
                onRefresh = { addFolder() },
                onClose = { cancel() },
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
                onMoreSelected = { index -> showFolderActions(index, titles[index]) { removeAt(index) } },
                onLongSelected = { index -> confirmUnregister(index, titles[index]) { removeAt(index) } }))
        }
    }

    private fun showFolderActions(index: Int, title: String, remove: () -> Unit) {
        val owner = dialog ?: return
        val list = owner.findViewById<android.widget.ListView>(R.id.dlna_list) ?: return
        val row = list.getChildAt(index - list.firstVisiblePosition) ?: return
        val anchor = row.findViewById<android.view.View>(R.id.saved_folder_more) ?: return
        folderActions?.dismiss()
        val actions = PopupMenu(activity, anchor)
        folderActions = actions
        actions.menu.add(R.string.folder_unregister).setOnMenuItemClickListener {
            if (folderActions === actions && dialog === owner && owner.isShowing) confirmUnregister(index, title, remove)
            true
        }
        actions.setOnDismissListener {
            if (folderActions === actions) folderActions = null
            if (dialog === owner && owner.isShowing && folderMenu == null) owner.focusRow(index)
        }
        actions.show()
    }

    private fun confirmUnregister(index: Int, title: String, remove: () -> Unit) {
        val owner = dialog ?: return
        folderMenu?.dismiss()
        lateinit var confirmation: DlnaSelectionDialog
        confirmation = DlnaSelectionDialog(activity, DlnaDialogContent(
            title = title,
            label = R.string.saved_folder_dialog_label,
            message = activity.getString(R.string.folder_unregister_message),
            registerLabel = R.string.folder_unregister,
            initialActionFocus = R.id.dlna_close,
            onRegister = {
                if (folderMenu === confirmation && dialog === owner && owner.isShowing) {
                    folderMenu = null
                    confirmation.dismiss()
                    try {
                        remove()
                        android.widget.Toast.makeText(activity, R.string.folder_unregistered, android.widget.Toast.LENGTH_SHORT).show()
                        // The next row keeps this index; clamping selects the previous row at the end.
                        showRoots(focusIndex = index)
                    } catch (error: SecurityException) {
                        Log.w("SafFileBrowser", "Cannot release folder permission", error)
                        android.widget.Toast.makeText(activity, R.string.folder_unregister_failed, android.widget.Toast.LENGTH_LONG).show()
                        owner.focusRow(index)
                    }
                }
            },
            onClose = {
                if (folderMenu === confirmation) {
                    folderMenu = null
                    confirmation.dismiss()
                    if (dialog === owner && owner.isShowing) owner.focusRow(index)
                }
            }))
        folderMenu = confirmation
        confirmation.show()
    }

    private fun releaseFolderPermission(tree: Uri) {
        val permission = activity.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
            ?: return
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) activity.contentResolver.releasePersistableUriPermission(tree, flags)
    }

    private fun browse(path: List<SafFolder>, focusUri: Uri? = null) {
        val tree = path.firstOrNull()?.uri?.let { DocumentsContract.buildTreeDocumentUri(
            requireNotNull(it.authority), DocumentsContract.getTreeDocumentId(it)) }
        load(retry = { browse(path, focusUri) }, onUp = { parent(path) }, focusTree = tree) {
            showDirectory(path, focusUri)
        }
    }

    private suspend fun showDirectory(path: List<SafFolder>, focusUri: Uri? = null) {
        val (folders, media) = withContext(Dispatchers.IO) { reader.read(path.last()) }
        val mediaSelection = if (focusUri != null) media.indexOfFirst { it.uri == focusUri.toString() } else -1
        val rows = folders.map { DlnaRow(it.title, R.drawable.ic_folder_open,
            description = "${it.title}。${activity.getString(R.string.dlna_folder_detail)}") } + mediaBrowserRows(activity, media, folders.map { it.title })
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
            onClose = { cancel() },
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

    private fun load(retry: () -> Unit, onUp: (() -> Unit)? = null, focusTree: Uri? = null, action: suspend () -> Unit) {
        job?.cancel()
        if (dialog?.keepListWhileLoading(onClose = { cancel() }) != true) showDialog(DlnaDialogContent(
            title = activity.getString(R.string.saf_dialog_label),
            message = activity.getString(R.string.saf_loading), loading = true,
            onUp = onUp, onClose = { cancel() }))
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
                    upLabel = R.string.saf_folder_list, onUp = { showRoots(focusUri = focusTree) },
                    refreshLabel = R.string.playback_retry, onRefresh = retry,
                    onClose = { cancel() }))
            }
        }
    }
}
