package jp.nagu.continuousplayer

import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/** Read-only USB registration browser using the shared file-selection window. */
class UsbFileBrowser(
    private val activity: AppCompatActivity,
    private val onFolderSelected: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private var dialog: DlnaSelectionDialog? = null
    private var job: Job? = null
    private var onRegistered: ((SavedUsbFolder) -> Unit)? = null
    private var returnFromRegistration: (() -> Unit)? = null
    internal var selectedFolder: PlaybackFolder.Usb? = null
        private set
    private val playbackBrowser = UsbPlaybackBrowser(activity, onCancelled) { folder, videos, index ->
        selectedFolder = folder
        onSelected(videos, index)
    }

    internal fun openFolder(folder: PlaybackFolder.Usb, forRegistration: Boolean = false) {
        if (forRegistration) {
            close()
            browse(folder.root, folder.directory)
        } else openSaved(folder, null, onReturn = onFolderSelected)
    }

    internal fun openSaved(folder: PlaybackFolder.Usb, inheritedDialog: DlnaSelectionDialog?, focusUri: String? = null,
        onReturn: () -> Unit) {
        close()
        playbackBrowser.open(folder, inheritedDialog, focusUri, onReturn)
    }

    internal fun takeDialog(): DlnaSelectionDialog? {
        job?.cancel()
        job = null
        return dialog?.also { dialog = null } ?: playbackBrowser.takeDialog()
    }

    fun onConfigurationChanged() {
        dialog?.takeIf { it.isShowing }?.refreshForConfiguration()
        playbackBrowser.onConfigurationChanged()
    }

    private val scanner = VideoScanner()
    private val preferences = activity.getSharedPreferences("usb_browser", android.content.Context.MODE_PRIVATE)

    fun close() {
        playbackBrowser.close()
        job?.cancel()
        job = null
        dialog?.dismiss()
        dialog = null
        onRegistered = null
        returnFromRegistration = null
    }

    private fun cancel() {
        val onReturn = returnFromRegistration
        if (onReturn != null) {
            onReturn()
            close()
        } else {
            close()
            onCancelled()
        }
    }

    internal fun openForRegistration(inheritedDialog: DlnaSelectionDialog?,
        onRegistered: (SavedUsbFolder) -> Unit, onReturn: () -> Unit) {
        close()
        dialog = inheritedDialog
        this.onRegistered = onRegistered
        returnFromRegistration = onReturn
        openStorage(restoreDirectory = true)
    }

    fun open() {
        close()
        openStorage(restoreDirectory = true)
    }

    private fun openStorage(restoreDirectory: Boolean, focusRoot: File? = null) {
        load(activity.getString(R.string.usb_choose_drive),
            retry = { openStorage(false, focusRoot) }) {
            val roots = withContext(Dispatchers.IO) {
                activity.getSystemService(StorageManager::class.java).storageVolumes
                    .filter { it.isRemovable && it.state in
                        listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) }
                    .mapNotNull { volume ->
                        volume.directory?.let { volume.getDescription(activity) to it }
                    }
            }
            val previous = if (restoreDirectory) withContext(Dispatchers.IO) {
                val rootPath = preferences.getString("root", null)
                val directoryPath = preferences.getString("directory", null)
                runCatching {
                    val root = roots.firstOrNull { it.second.canonicalPath == rootPath }?.second
                    val directory = directoryPath?.let { File(it).canonicalFile }
                    if (root != null && directory != null &&
                        directory.toPath().startsWith(root.canonicalFile.toPath()) &&
                        directory.isDirectory && directory.listFiles() != null
                    ) root to directory else null
                }.getOrNull()
            } else null
            if (previous != null) {
                browse(previous.first, previous.second)
                return@load
            }
            show(DlnaDialogContent(
                title = activity.getString(R.string.usb_choose_drive),
                label = null,
                fileSelection = true,
                rows = roots.map { (name, root) ->
                    DlnaRow(name, R.drawable.ic_folder_open, detail = root.name)
                },
                message = activity.getString(R.string.usb_no_drive),
                initialSelection = roots.indexOfFirst { it.second == focusRoot }.coerceAtLeast(0),
                onRefresh = { openStorage(false) },
                onClose = { cancel() },
                onSelected = { index -> roots[index].second.let { browse(it, it) } }
            ))
        }
    }

    private fun browse(root: File, directory: File, focusChild: File? = null) {
        load(directory.name.ifEmpty { directory.path },
            retry = { browse(root, directory, focusChild) }, onUp = { parent(root, directory) }) {
            val (directories, videos) = withContext(Dispatchers.IO) {
                check(directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val files = directory.listFiles() ?: throw IOException("Unreadable or removed USB drive")
                val directories = files.filter {
                    it.isDirectory && it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
                }.sortedBy { it.name.lowercase(Locale.ROOT) }
                val videos = scanner.scanDirectory(directory)
                // Persist only locations whose contents were successfully read.
                preferences.edit().putString("root", root.canonicalPath)
                    .putString("directory", directory.canonicalPath).apply()
                directories to videos
            }
            show(DlnaDialogContent(
                title = directory.name.ifEmpty { directory.path },
                label = null,
                fileSelection = true,
                rows = directories.map { DlnaRow(it.name, R.drawable.ic_folder_open,
                    description = "${it.name}。${activity.getString(R.string.dlna_folder_detail)}") } + videos.map {
                    val audio = it.displayName.substringAfterLast('.').lowercase(Locale.ROOT) in
                        setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
                    DlnaRow(it.selectionTitle, if (audio) R.drawable.ic_dlna_audio else R.drawable.ic_play,
                        description = "${it.selectionTitle}。${activity.getString(if (audio) R.string.dlna_audio_detail else R.string.dlna_video_detail)}")
                },
                message = activity.getString(R.string.usb_empty),
                initialSelection = directories.indexOf(focusChild).coerceAtLeast(0),
                onUp = { parent(root, directory) },
                onRefresh = { browse(root, directory) },
                registerLabel = R.string.usb_use_folder,
                onRegister = {
                    SavedUsbFolders(activity).save(root, directory)
                    android.widget.Toast.makeText(activity, R.string.folder_registered,
                        android.widget.Toast.LENGTH_SHORT).show()
                    val registered = SavedUsbFolder(root.canonicalPath, directory.canonicalPath)
                    onRegistered?.invoke(registered) ?: onFolderSelected()
                    close()
                },
                onClose = { cancel() },
                onSelected = { index ->
                    if (index < directories.size) browse(root, directories[index])
                    else play(root, directory, videos, index - directories.size)
                }
            ))
        }
    }

    private fun play(root: File, directory: File, videos: List<VideoItem>, index: Int) {
        load(directory.name, retry = { play(root, directory, videos, index) },
            onUp = { parent(root, directory) }) {
            withContext(Dispatchers.IO) {
                // canRead() alone does not prove that the USB file can be opened.
                RandomAccessFile(File(requireNotNull(Uri.parse(videos[index].uri).path)), "r").use { file ->
                    if (file.length() == 0L) throw IOException("Empty media file")
                    file.readByte()
                    file.seek(file.length() - 1)
                    file.readByte()
                }
            }
            selectedFolder = PlaybackFolder.Usb(root, directory)
            onSelected(videos, index)
            dialog?.closeAfterPlaybackFrame { close() }
        }
    }

    private fun parent(root: File, directory: File) {
        if (directory.canonicalFile == root.canonicalFile) openStorage(false, focusRoot = root)
        else browse(root, directory.parentFile ?: root, focusChild = directory)
    }

    private fun show(content: DlnaDialogContent) {
        val current = dialog
        if (current?.isShowing == true) current.updateContent(content)
        else dialog = DlnaSelectionDialog(activity, content).also { it.show() }
    }

    private fun load(title: String, retry: () -> Unit, onUp: (() -> Unit)? = null, action: suspend () -> Unit) {
        job?.cancel()
        if (dialog?.keepListWhileLoading(onClose = { cancel() }) != true) {
            show(DlnaDialogContent(title = title, label = null, fileSelection = true,
                loading = true, message = activity.getString(R.string.usb_loading),
                onUp = onUp, onClose = { cancel() }))
        }
        job = activity.lifecycleScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("UsbFileBrowser", "USB selection failed", error)
                show(DlnaDialogContent(title = title, label = null, fileSelection = true,
                    message = "${activity.getString(R.string.usb_read_error)}\n\n${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    onUp = onUp, onRefresh = retry, onClose = { cancel() }))
            }
        }
    }
}
