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
    private val scanner = VideoScanner(activity)

    fun close() {
        job?.cancel()
        dialog?.dismiss()
        dialog = null
    }

    fun open() {
        load {
            val roots = withContext(Dispatchers.IO) {
                activity.getSystemService(StorageManager::class.java).storageVolumes
                    .filter { it.isRemovable && it.state in
                        listOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) }
                    .mapNotNull { volume ->
                        volume.directory?.let { volume.getDescription(activity) to it }
                    }
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle(R.string.usb_choose_drive)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.usb_refresh) { _, _ -> open() }
            if (roots.isEmpty()) {
                builder.setMessage(R.string.usb_no_drive)
            } else {
                builder.setItems(roots.map { "${it.first}\n${it.second.name}" }.toTypedArray()) { _, index ->
                    val root = roots[index].second
                    browse(root, root)
                }
            }
            dialog = builder.show()
        }
    }

    private fun browse(root: File, directory: File) {
        load {
            val entries = withContext(Dispatchers.IO) {
                check(directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val files = directory.listFiles()
                    ?: throw java.io.IOException("Unreadable or removed USB drive")
                val directories = files.filter {
                    it.isDirectory && it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
                }.sortedBy { it.name.lowercase() }
                directories to scanner.scanDirectory(directory)
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
                                onSelected(videos, videoIndex)
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnCancelListener { parent(root, directory) }
                .show()
            if (directories.isEmpty() && videos.isEmpty()) {
                android.widget.Toast.makeText(activity, R.string.usb_empty,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parent(root: File, directory: File) {
        if (directory == root) open()
        else browse(root, directory.parentFile ?: root)
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
                    .setPositiveButton(R.string.usb_refresh) { _, _ -> open() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } finally {
                loading.dismiss()
                if (dialog === loading) dialog = null
            }
        }
    }
}
