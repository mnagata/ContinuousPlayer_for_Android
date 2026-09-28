package jp.nagu.continuousplayer

import android.app.AlertDialog
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

/** Read-only, D-pad navigable browser for mounted removable storage. */
class UsbFileBrowser(
    private val activity: AppCompatActivity,
    private val onSelected: (List<VideoItem>, Int) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var job: Job? = null
    internal var selectedFolder: PlaybackFolder.Usb? = null
        private set

    internal fun openFolder(folder: PlaybackFolder.Usb) = browse(folder.root, folder.directory)
    private val scanner = VideoScanner()
    private val preferences = activity.getSharedPreferences("usb_browser", android.content.Context.MODE_PRIVATE)

    fun close() {
        job?.cancel()
        dialog?.dismiss()
        dialog = null
    }

    fun open() = openStorage(restoreDirectory = true)

    private fun openStorage(restoreDirectory: Boolean, focusRoot: File? = null) {
        load {
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
                showDirectory(previous.first, previous.second)
                return@load
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle(R.string.usb_choose_drive)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.usb_refresh) { _, _ -> openStorage(false) }
            if (roots.isEmpty()) {
                builder.setMessage(R.string.usb_no_drive)
            } else {
                builder.setItems(roots.map { "${it.first}\n${it.second.name}" }.toTypedArray()) { _, index ->
                    val root = roots[index].second
                    browse(root, root)
                }
            }
            dialog = builder.show()
            if (focusRoot != null && roots.isNotEmpty()) {
                focusRow(roots.indexOfFirst { it.second == focusRoot }.coerceAtLeast(0))
            }
        }
    }

    private fun browse(root: File, directory: File, focusChild: File? = null) {
        load {
            showDirectory(root, directory, focusChild)
        }
    }

    private suspend fun showDirectory(root: File, directory: File, focusChild: File? = null) {
            val entries = withContext(Dispatchers.IO) {
                check(directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val files = directory.listFiles()
                    ?: throw java.io.IOException("Unreadable or removed USB drive")
                val directories = files.filter {
                    it.isDirectory && it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
                }.sortedBy { it.name.lowercase() }
                directories to scanner.scanDirectory(directory)
            }
            // Save only directories successfully read. Closing or playing preserves this location.
            withContext(Dispatchers.IO) {
                preferences.edit()
                    .putString("root", root.canonicalPath)
                    .putString("directory", directory.canonicalPath)
                    .apply()
            }
            val (directories, videos) = entries
            val labels = listOf(activity.getString(R.string.usb_parent)) +
                directories.map { "▸ ${it.name}" } + videos.map { it.displayName }
            dialog = AlertDialog.Builder(activity)
                .setTitle(directory.name.ifEmpty { directory.path })
                .setItems(labels.toTypedArray()) { _, index ->
                    when {
                        index == 0 -> parent(root, directory)
                        index <= directories.size -> browse(root, directories[index - 1])
                        else -> {
                            val videoIndex = index - directories.size - 1
                            load {
                                withContext(Dispatchers.IO) {
                                    // canRead() alone does not prove that the USB file can be opened.
                                    java.io.RandomAccessFile(
                                        File(android.net.Uri.parse(videos[videoIndex].uri).path!!), "r"
                                    ).use { file ->
                                        if (file.length() == 0L) throw java.io.IOException("Empty media file")
                                        file.readByte()
                                        file.seek(file.length() - 1)
                                        file.readByte()
                                    }
                                }
                                selectedFolder = PlaybackFolder.Usb(root, directory)
                                onSelected(videos, videoIndex)
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnCancelListener { parent(root, directory) }
                .show()
            if (focusChild != null) {
                // Row zero is "parent"; a removed child falls back to that row.
                focusRow(directories.indexOfFirst { it == focusChild } + 1)
            }
            if (directories.isEmpty() && videos.isEmpty()) {
                android.widget.Toast.makeText(activity, R.string.usb_empty,
                    android.widget.Toast.LENGTH_LONG).show()
            }
    }

    private fun parent(root: File, directory: File) {
        if (directory == root) openStorage(restoreDirectory = false, focusRoot = root)
        else browse(root, directory.parentFile ?: root, focusChild = directory)
    }

    private fun focusRow(position: Int) {
        val target = dialog ?: return
        val list = target.listView ?: return
        // Wait until the new list is laid out and the loading dialog has been dismissed.
        list.post {
            if (dialog === target && target.isShowing && position in 0 until list.count) {
                list.requestFocus()
                list.setSelection(position)
            }
        }
    }

    private fun load(action: suspend () -> Unit) {
        close()
        job = activity.lifecycleScope.launch {
            val loading = AlertDialog.Builder(activity)
                .setMessage(R.string.usb_loading)
                .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
                .setOnCancelListener { job?.cancel() }
                .show()
            dialog = loading
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("UsbFileBrowser", "USB selection failed", error)
                dialog?.dismiss()
                dialog = AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.usb_read_error) +
                        "\n\n${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    .setPositiveButton(R.string.usb_refresh) { _, _ -> openStorage(false) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } finally {
                loading.dismiss()
                if (dialog === loading) dialog = null
            }
        }
    }
}
