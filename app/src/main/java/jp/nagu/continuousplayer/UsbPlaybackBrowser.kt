package jp.nagu.continuousplayer

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Registered USB folders use the same file-selection window as SAF and DLNA. */
internal class UsbPlaybackBrowser(
    private val activity: AppCompatActivity,
    private val onCancelled: () -> Unit = {},
    private val onSelected: (PlaybackFolder.Usb, List<VideoItem>, Int) -> Unit
) {
    private var dialog: DlnaSelectionDialog? = null
    private var job: Job? = null
    private var navigationRoot: File? = null
    private var onReturn: (() -> Unit)? = null
    private val scanner = VideoScanner()

    fun close() {
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
        navigationRoot = null
        onReturn = null
    }

    private fun cancel() {
        close()
        onCancelled()
    }

    fun takeDialog(): DlnaSelectionDialog? {
        job?.cancel()
        job = null
        return dialog.also { dialog = null }
    }

    fun onConfigurationChanged() {
        dialog?.takeIf { it.isShowing }?.refreshForConfiguration()
    }

    fun open(folder: PlaybackFolder.Usb, inheritedDialog: DlnaSelectionDialog?, focusUri: String?, onReturn: (SavedUsbFolder) -> Unit) {
        close()
        dialog = inheritedDialog
        // Reopening the playback folder preserves navigation within its registered ancestor.
        val boundary = SavedUsbFolders(activity).all().filter {
            it.rootPath == folder.root.canonicalPath &&
                folder.directory.canonicalFile.toPath().startsWith(File(it.directoryPath).toPath())
        }.maxByOrNull { it.directoryPath.length }?.let { File(it.directoryPath) } ?: folder.directory
        navigationRoot = boundary
        val returnedFolder = SavedUsbFolder(folder.root.canonicalPath, boundary.canonicalPath)
        this.onReturn = { onReturn(returnedFolder) }
        browse(folder.root, folder.directory, focusUri = focusUri)
    }

    private fun parent(root: File, directory: File) {
        if (directory.canonicalFile == navigationRoot?.canonicalFile) {
            onReturn?.invoke()
            close()
        } else browse(root, directory.parentFile ?: root, directory)
    }

    private fun browse(root: File, directory: File, focusChild: File? = null, focusUri: String? = null) {
        load(directory, retry = { browse(root, directory, focusChild, focusUri) }, onUp = { parent(root, directory) }) {
            val (folders, media) = withContext(Dispatchers.IO) {
                val boundary = requireNotNull(navigationRoot).canonicalFile.toPath()
                check(boundary.startsWith(root.canonicalFile.toPath()))
                check(directory.canonicalFile.toPath().startsWith(boundary))
                val files = directory.listFiles() ?: throw IOException("Unreadable or removed USB drive")
                val children = files.filter { it.isDirectory && it.canonicalFile.toPath().startsWith(boundary) }
                activity.getSharedPreferences("usb_browser", android.content.Context.MODE_PRIVATE).edit()
                    .putString("root", root.canonicalPath).putString("directory", directory.canonicalPath).apply()
                DlnaFolderSorter.sort(children) { it.name } to scanner.scanDirectory(directory)
            }
            val mediaSelection = if (focusUri != null) media.indexOfFirst { it.uri == focusUri } else -1
            show(DlnaDialogContent(
                title = directory.name.ifEmpty { directory.path },
                label = null,
                fileSelection = true,
                rows = folders.map { DlnaRow(it.name, R.drawable.ic_folder_open,
                    description = "${it.name}。${activity.getString(R.string.dlna_folder_detail)}") } + mediaBrowserRows(activity, media, folders.map { it.name }),
                message = activity.getString(R.string.usb_empty),
                initialSelection = if (mediaSelection >= 0) folders.size + mediaSelection
                    else folders.indexOf(focusChild).coerceAtLeast(0),
                upLabel = if (directory.canonicalFile == navigationRoot?.canonicalFile)
                    R.string.saved_folder_list else R.string.usb_parent,
                onUp = { parent(root, directory) },
                onRefresh = { browse(root, directory, focusUri = focusUri) },
                onClose = { cancel() },
                onSelected = { index ->
                    if (index < folders.size) browse(root, folders[index])
                    else play(root, directory, media, index - folders.size)
                }))
        }
    }

    private fun play(root: File, directory: File, media: List<VideoItem>, index: Int) {
        load(directory, retry = { play(root, directory, media, index) }, onUp = { parent(root, directory) }) {
            withContext(Dispatchers.IO) {
                RandomAccessFile(File(requireNotNull(Uri.parse(media[index].uri).path)), "r").use { file ->
                    if (file.length() == 0L) throw IOException("Empty media file")
                    file.readByte()
                    file.seek(file.length() - 1)
                    file.readByte()
                }
            }
            onSelected(PlaybackFolder.Usb(root, directory), media, index)
            dialog?.closeAfterPlaybackFrame { close() }
        }
    }

    private fun show(content: DlnaDialogContent) {
        val current = dialog
        if (current?.isShowing == true) current.updateContent(content)
        else dialog = DlnaSelectionDialog(activity, content).also { it.show() }
    }

    private fun load(directory: File, retry: () -> Unit, onUp: () -> Unit, action: suspend () -> Unit) {
        job?.cancel()
        if (dialog?.keepListWhileLoading(onClose = { cancel() }) != true) {
            show(DlnaDialogContent(title = directory.name, label = null,
                loading = true, message = activity.getString(R.string.usb_loading),
                onUp = onUp, onClose = { cancel() }))
        }
        job = activity.lifecycleScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.e("UsbPlaybackBrowser", "USB browsing failed", error)
                show(DlnaDialogContent(title = directory.name, label = null,
                    message = "${activity.getString(R.string.usb_read_error)}\n\n${error.message.orEmpty()}",
                    refreshLabel = R.string.playback_retry, onRefresh = retry,
                    upLabel = R.string.saved_folder_list,
                    onUp = { onReturn?.invoke(); close() }, onClose = { cancel() }))
            }
        }
    }
}
