package jp.nagu.continuousplayer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.GestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
          }

    private lateinit var viewModel: PlayerViewModel
    private lateinit var scanner: VideoScanner

    private var playerController: PlayerController? = null
    private var bitPerfectAudio: BitPerfectAudioManager? = null
    private lateinit var audioManager: AudioManager
    private var gestureDetector: GestureDetector? = null

    private lateinit var folderSelectContainer: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var pauseOverlay: LinearLayout
    private lateinit var overlayFilename: TextView
    private lateinit var portraitVideoInfo: TextView

    private val treePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
         ) { uri -> uri?.let { onTreeSelected(it) } }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
         ) { uri -> uri?.let { onFileSelected(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        scanner = VideoScanner(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        folderSelectContainer = findViewById(R.id.folder_select_container)
        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.player_view)
        pauseOverlay = findViewById(R.id.pause_overlay)
        overlayFilename = findViewById(R.id.overlay_filename)
        portraitVideoInfo = findViewById(R.id.portrait_video_info)

        findViewById<Button>(R.id.btn_select_folder).setOnClickListener {
            launchPicker()
              }

        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            finishAndRemoveTask()
              }

        findViewById<ImageButton>(R.id.btn_overlay_info).setOnClickListener {
            showInfoDialog()
              }

        findViewById<ImageButton>(R.id.btn_overlay_select_folder).setOnClickListener {
            stopPlayback()
            showFolderSelect()
            launchPicker()
              }

        findViewById<ImageButton>(R.id.btn_overlay_exit).setOnClickListener {
            stopPlayback()
            finishAndRemoveTask()
              }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.isPlayerScreen) {
                    stopPlayback()
                    showFolderSelect()
                     } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                     }
                 }
             })

        if (savedInstanceState == null) {
            launchPicker()
              } else if (viewModel.isPlayerScreen) {
            showPlayer()
            startPlayback(viewModel.videos)
             }
          }

    private fun hasTreePermission(): Boolean {
        return contentResolver.persistedUriPermissions.any { it.isReadPermission }
          }

    private fun launchPicker() {
        if (hasTreePermission()) {
            filePicker.launch(arrayOf("video/*", "audio/*"))
              } else {
            treePicker.launch(null)
              }
          }

    private fun onTreeSelected(treeUri: Uri) {
        Log.d(TAG, "onTreeSelected: treeUri=$treeUri")
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                  )
              } catch (_: SecurityException) {
            Log.d(TAG, "takePersistableUriPermission failed")
              }

               // Tree permission acquired — now open file picker
        filePicker.launch(arrayOf("video/*", "audio/*"))
          }

    private fun onFileSelected(fileUri: Uri) {
        Log.d(TAG, "onFileSelected: fileUri=$fileUri")

        val selectedDocId = try {
            DocumentsContract.getDocumentId(fileUri)
               } catch (_: Exception) { null }

        val matchedTree = findMatchingTreePermission(selectedDocId)
        if (matchedTree != null) {
               // Extract parent directory docId of the selected file
            val parentDocId = try {
                val docId = selectedDocId ?: ""
                docId.substringBeforeLast('/')
                      } catch (_: Exception) { null }

            val parentUri = parentDocId?.let { parentId ->
                DocumentsContract.buildDocumentUri(matchedTree.authority!!, parentId)
                      }

            Log.d(TAG, "onFileSelected: matchedTree=$matchedTree, parentUri=$parentUri, parentDocId=$parentDocId, selectedDocId=$selectedDocId")
            scanAndPlayFrom(matchedTree, parentUri, selectedDocId)
               } else {
            Log.d(TAG, "No matching tree, launching tree picker for permission")
            treePicker.launch(null)
               }
          }

    private fun findMatchingTreePermission(docId: String?): Uri? {
        if (docId == null) return null
        return contentResolver.persistedUriPermissions
                   .filter { it.isReadPermission }
                   .map { it.uri }
                   .firstOrNull { treeUri ->
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                    docId.startsWith("$treeDocId/") || docId == treeDocId
                       } catch (_: Exception) {
                    false
                       }
                   }
          }

    private fun scanAndPlayFrom(treeUri: Uri, parentDirUri: Uri?, selectedDocId: String?) {
        lifecycleScope.launch {
            val scanUri = parentDirUri ?: treeUri
            Log.d(TAG, "scanAndPlayFrom: scanUri=$scanUri")
            val videos = withContext(Dispatchers.IO) {
                scanner.scanTree(treeUri, scanUri)
                   }

            Log.d(TAG, "scanAndPlayFrom: ${videos.size} videos")
            if (videos.isEmpty()) {
                Toast.makeText(this@MainActivity, "No video files found", Toast.LENGTH_SHORT).show()
                return@launch
                   }

            viewModel.videos = videos
            val startIndex = findStartIndex(videos, selectedDocId)
            startPlayback(videos, startIndex)
            showPlayer()
               }
          }

    private fun findStartIndex(videos: List<VideoItem>, selectedDocId: String?): Int {
        if (selectedDocId == null) return 0
        val index = videos.indexOfFirst { video ->
            try {
                val videoDocId = DocumentsContract.getDocumentId(Uri.parse(video.uri))
                videoDocId == selectedDocId
                   } catch (_: Exception) {
                false
                   }
               }
        if (index >= 0) return index
        val selectedName = selectedDocId.substringAfterLast('/')
        return videos.indexOfFirst {
            it.displayName.equals(selectedName, ignoreCase = true)
               }.coerceAtLeast(0)
          }

    @SuppressLint("ClickableViewAccessibility")
    private fun startPlayback(videos: List<VideoItem>, startIndex: Int = 0) {
        stopPlayback()
        val controller = PlayerController(this)
        playerController = controller
        playerView.player = controller.player

        val bitPerfect = BitPerfectAudioManager(this)
        bitPerfectAudio = bitPerfect
        val bitPerfectTarget = videos.getOrNull(startIndex) ?: videos.firstOrNull()
        if (bitPerfectTarget != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    bitPerfect.configure(Uri.parse(bitPerfectTarget.uri))
                       }
                   }
               }

        controller.player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
                   ) {
                Log.d(TAG, "onMediaItemTransition: mediaItem=$mediaItem, reason=$reason")
                updatePortraitVideoInfo()
                   }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updatePortraitVideoInfo()
                   }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: state=$playbackState")
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    updatePortraitVideoInfo()
                       }
                   }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
                pauseOverlay.visibility = if (isPlaying) View.GONE else View.VISIBLE
                if (!isPlaying) {
                    updatePauseOverlay()
                }
             }
               })

        Log.d(TAG, "startPlayback: calling setPlaylist, videos=${videos.size}")
        controller.setPlaylist(videos, startIndex)
        playerView.keepScreenOn = true
        Log.d(TAG, "startPlayback: calling updatePortraitVideoInfo")
        updatePortraitVideoInfo()

        val touchOverlay = findViewById<View>(R.id.touch_overlay)
        val handler = GestureHandler(
            touchOverlay,
            controller,
            onPlayPauseToggled = { updatePauseOverlay() }
        )
        gestureDetector = GestureDetector(this, handler)
        touchOverlay.setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)
            true
               }

        enterImmersiveMode()
          }

    private fun stopPlayback() {
        playerView.keepScreenOn = false
        pauseOverlay.visibility = View.GONE
        bitPerfectAudio?.release()
        bitPerfectAudio = null
        playerView.player = null
        playerController?.release()
        playerController = null
        gestureDetector = null
        viewModel.isPlayerScreen = false
          }

    private fun showInfoDialog() {
        val player = playerController?.player ?: return
        val index = player.currentMediaItemIndex
        val video = viewModel.videos.getOrNull(index) ?: return
        val videoUri = Uri.parse(video.uri)

        lifecycleScope.launch {
            val audioInfo = withContext(Dispatchers.IO) {
                bitPerfectAudio?.getAudioOutputInfo(videoUri) ?: ""
                   }
            val message = buildString {
                appendLine(video.displayName)
                if (audioInfo.isNotEmpty()) {
                    appendLine()
                    append(audioInfo)
                       }
                   }
            AlertDialog.Builder(this@MainActivity)
                   .setTitle(R.string.info)
                   .setMessage(message)
                   .setPositiveButton(android.R.string.ok, null)
                   .show()
               }
           }

    private fun updatePauseOverlay() {
        Log.d(TAG, "updatePauseOverlay: player=${playerController?.player != null}, playing=${playerController?.player?.isPlaying}")
        val playing = playerController?.player?.isPlaying == true
        if (!playing) {
            val index = playerController?.player?.currentMediaItemIndex ?: 0
            val video = viewModel.videos.getOrNull(index)
            overlayFilename.text = video?.displayName ?: ""
               }
        pauseOverlay.visibility = if (playing) View.GONE else View.VISIBLE
          }

@OptIn(UnstableApi::class)
private fun updatePortraitVideoInfo() {
        try {
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            Log.d(TAG, "updatePortraitVideoInfo: isPortrait=$isPortrait, isPlayerScreen=${viewModel.isPlayerScreen}, player=${playerController?.player != null}, videos=${viewModel.videos.size}")
            if (!isPortrait || !viewModel.isPlayerScreen) {
                portraitVideoInfo.visibility = View.GONE
                return
                   }
            val player = playerController?.player
            val index = player?.currentMediaItemIndex ?: 0
            val video = viewModel.videos.getOrNull(index)
            if (video == null) {
                portraitVideoInfo.visibility = View.GONE
                return
                   }

            val nameWithoutExt = video.displayName.substringBeforeLast('.')
            val info = buildString {
                appendLine(nameWithoutExt)
                append(formatFileSize(video.size))

                if (player != null) {
                    try {
                        val videoFormat = player.videoFormat
                        val audioFormat = player.audioFormat
                        if (videoFormat != null) {
                            val codec = videoFormat.codecs ?: videoFormat.sampleMimeType ?: ""
                            val resolution = "${videoFormat.width}x${videoFormat.height}"
                            val fps = videoFormat.frameRate
                            append("\n$codec  $resolution")
                            if (fps > 0) append("    %.1ffps".format(fps))
                               }
                        if (audioFormat != null) {
                            val audioCodec = audioFormat.codecs ?: audioFormat.sampleMimeType ?: ""
                            val sampleRate = audioFormat.sampleRate
                            val channels = audioFormat.channelCount
                            append("\n$audioCodec   ${sampleRate}Hz   ${channels}ch")
                               }
                           } catch (e: Exception) {
                        Log.w(TAG, "Failed to get player format info", e)
                           }
                       }

                val outputSummary = bitPerfectAudio?.getOutputSummary()
                if (!outputSummary.isNullOrEmpty()) {
                    append("\n$outputSummary")
                       }
                   }
            portraitVideoInfo.text = info
            portraitVideoInfo.visibility = View.VISIBLE
               } catch (e: Exception) {
            Log.w(TAG, "updatePortraitVideoInfo failed", e)
               }
          }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
               }
          }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePortraitVideoInfo()
          }

    private fun showPlayer() {
        viewModel.isPlayerScreen = true
        folderSelectContainer.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        enterImmersiveMode()
          }

    private fun showFolderSelect() {
        viewModel.isPlayerScreen = false
        playerContainer.visibility = View.GONE
        folderSelectContainer.visibility = View.VISIBLE
        exitImmersiveMode()
          }

    private fun enterImmersiveMode() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
          }

    private fun exitImmersiveMode() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
          }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && viewModel.isPlayerScreen) {
            enterImmersiveMode()
               }
          }

    override fun onStop() {
        super.onStop()
        playerController?.player?.pause()
          }

    override fun onStart() {
        super.onStart()
        if (viewModel.isPlayerScreen) {
            playerController?.player?.play()
               }
          }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
          }
}
