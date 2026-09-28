package jp.nagu.continuousplayer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Environment
import android.provider.Settings
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
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
    private lateinit var selectFolderButton: Button
    private lateinit var overlayPlayPauseButton: ImageButton
    private lateinit var usbBrowser: UsbFileBrowser
    private lateinit var dlnaBrowser: DlnaBrowser
    private lateinit var safBrowser: SafFileBrowser
    private var playbackErrorDialog: AlertDialog? = null
    private var userPaused = false
    private var isInBackground = false

    private val storageSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) {
            usbBrowser.open()
        } else {
            Toast.makeText(this, R.string.usb_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val isTelevision: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private val treePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
         ) { uri -> uri?.let { onTreeSelected(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system starting window uses the manifest theme; content uses the normal theme.
        setTheme(R.style.Theme_ContinuousPlayer)
        super.onCreate(savedInstanceState)
        // Android TV uses its system launch transition instead of custom splash animations.
        if (!isTelevision) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.animate()
                    .alpha(0f)
                    .setDuration(180L)
                    .withEndAction { splash.remove() }
                    .start()
            }
        }
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        usbBrowser = UsbFileBrowser(this) { videos, index ->
            if (::safBrowser.isInitialized) safBrowser.close()
            viewModel.playbackFolder = usbBrowser.selectedFolder
            viewModel.videos = videos
            startPlayback(videos, index)
            showPlayer()
        }
        dlnaBrowser = DlnaBrowser(this) { videos, index ->
            viewModel.playbackFolder = dlnaBrowser.selectedFolder
            viewModel.videos = videos
            startPlayback(videos, index)
            showPlayer()
        }
        safBrowser = SafFileBrowser(this,
            onAddFolder = { if (isTelevision) launchUsbPicker() else launchTreePicker() },
            onAddDlna = { dlnaBrowser.open() },
            onDlnaFolder = { folder ->
                dlnaBrowser.openSaved(folder) { safBrowser.openSavedFolders() }
            }) { videos, index ->
            viewModel.playbackFolder = safBrowser.selectedFolder
            viewModel.videos = videos
            startPlayback(videos, index)
            showPlayer()
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        folderSelectContainer = findViewById(R.id.folder_select_container)
        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.player_view)
        pauseOverlay = findViewById(R.id.pause_overlay)
        overlayFilename = findViewById(R.id.overlay_filename)
        portraitVideoInfo = findViewById(R.id.portrait_video_info)

        selectFolderButton = findViewById(R.id.btn_select_folder)
        overlayPlayPauseButton = findViewById(R.id.btn_overlay_play_pause)

        selectFolderButton.setOnClickListener {
            launchPicker()
              }

        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            finishAndRemoveTask()
              }

        overlayPlayPauseButton.setOnClickListener {
            togglePlayPause()
              }

        findViewById<ImageButton>(R.id.btn_overlay_info).setOnClickListener {
            showInfoDialog()
              }

        findViewById<ImageButton>(R.id.btn_overlay_select_folder).setOnClickListener {
            stopPlayback()
            showFolderSelect()
            launchPlaybackFolder()
              }

        findViewById<ImageButton>(R.id.btn_overlay_exit).setOnClickListener {
            stopPlayback()
            finishAndRemoveTask()
              }

        if (isTelevision) {
            findViewById<TextView>(R.id.tv_controls_hint).visibility = View.VISIBLE
            selectFolderButton.requestFocus()
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

        if (savedInstanceState != null && viewModel.isPlayerScreen) {
            showPlayer()
            startPlayback(viewModel.videos)
             } else {
            showFolderSelect()
             }
          }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::viewModel.isInitialized || !viewModel.isPlayerScreen ||
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0
        ) {
            return super.dispatchKeyEvent(event)
        }

        val player = playerController?.player ?: return super.dispatchKeyEvent(event)
        val pauseMenuHasFocus = !player.isPlaying && pauseOverlay.visibility == View.VISIBLE &&
            currentFocus?.let { focus -> isDescendantOf(focus, pauseOverlay) } == true

        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player.play()
                updatePauseOverlay()
                true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                userPaused = true
                player.pause()
                updatePauseOverlay()
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (pauseMenuHasFocus) {
                    super.dispatchKeyEvent(event)
                } else {
                    togglePlayPause()
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (pauseMenuHasFocus) super.dispatchKeyEvent(event)
                else {
                    playerController?.seekBackward()
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (pauseMenuHasFocus) super.dispatchKeyEvent(event)
                else {
                    playerController?.seekForward()
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playerController?.previousVideo()
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playerController?.nextVideo()
                true
            }

            KeyEvent.KEYCODE_INFO -> {
                showInfoDialog()
                true
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun togglePlayPause() {
        playerController?.togglePlayPause()
        updatePauseOverlay()
    }

    private fun launchPlaybackFolder() {
        when (val folder = viewModel.playbackFolder) {
            is PlaybackFolder.Saf -> safBrowser.openFolder(folder)
            is PlaybackFolder.Dlna -> dlnaBrowser.openSaved(folder.folder) { safBrowser.openSavedFolders() }
            is PlaybackFolder.Usb -> usbBrowser.openFolder(folder)
            null -> launchPicker()
        }
    }

    private fun launchPicker() {
        safBrowser.open()
    }

    private fun launchTreePicker() {
        try {
            treePicker.launch(null)
        } catch (_: ActivityNotFoundException) {
            launchUsbPicker()
        }
    }

    private fun launchUsbPicker() {
        if (Environment.isExternalStorageManager()) {
            usbBrowser.open()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.usb_permission_title)
            .setMessage(R.string.usb_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.usb_open_settings) { _, _ ->
                val intents = listOf(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
                var launched = false
                for (intent in intents) {
                    try {
                        storageSettings.launch(intent)
                        launched = true
                        break
                    } catch (_: ActivityNotFoundException) {
                        // Some TV settings apps only provide the global settings page.
                    } catch (_: SecurityException) {
                        // Try the other documented settings entry point.
                    }
                }
                if (!launched) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.usb_settings_unavailable)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }.show()
    }

    private fun onTreeSelected(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            Log.w(TAG, "Folder access could not be persisted", error)
            Toast.makeText(this, R.string.saf_permission_not_saved, Toast.LENGTH_LONG).show()
        }
        safBrowser.open(treeUri)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startPlayback(videos: List<VideoItem>, startIndex: Int = 0) {
        stopPlayback()
        val controller = PlayerController(
            this,
            onPlaybackFailure = if (isTelevision || videos.any { it.uri.startsWith("http") })
                ({ error -> showPlaybackError(error) }) else null
        )
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
                updatePauseOverlay()
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    userPaused = false
                } else if (!isInBackground && controller.player.playerError == null &&
                    reason == androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                ) {
                    userPaused = true
                }
                updatePauseOverlay()
            }
               })

        Log.d(TAG, "startPlayback: calling setPlaylist, videos=${videos.size}")
        controller.setPlaylist(videos, startIndex)
        playerView.keepScreenOn = true
        playerView.isFocusable = isTelevision
        if (isTelevision) playerView.requestFocus()
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
        userPaused = false
        playbackErrorDialog?.dismiss()
        playbackErrorDialog = null
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

    private fun showPlaybackError(error: androidx.media3.common.PlaybackException) {
        if (isFinishing || isDestroyed) return
        val player = playerController?.player ?: return
        userPaused = false
        player.pause()
        updatePauseOverlay()
        val filename = viewModel.videos.getOrNull(player.currentMediaItemIndex)?.displayName.orEmpty()
        val details = buildString {
            appendLine(filename)
            appendLine()
            appendLine("${error.errorCodeName} (${error.errorCode})")
            var cause: Throwable? = error
            repeat(5) {
                val current = cause ?: return@repeat
                appendLine("${current.javaClass.simpleName}: ${current.message.orEmpty()}")
                cause = current.cause
            }
        }.trim()
        playbackErrorDialog?.dismiss()
        playbackErrorDialog = AlertDialog.Builder(this)
            .setTitle(R.string.playback_failed)
            .setMessage(details)
            .setPositiveButton(R.string.playback_retry) { _, _ ->
                player.prepare()
                player.play()
            }
            .setNegativeButton(R.string.playback_choose_another) { _, _ ->
                stopPlayback()
                showFolderSelect()
                launchPlaybackFolder()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
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
            if (isFinishing || isDestroyed) return@launch
            val content = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.dialog_playback_info, null)
            content.findViewById<TextView>(R.id.info_filename).text = video.displayName
            content.findViewById<TextView>(R.id.info_audio).apply {
                text = audioInfo
                visibility = if (audioInfo.isEmpty()) View.GONE else View.VISIBLE
            }
            val dialog = AlertDialog.Builder(this@MainActivity).create()
            dialog.setView(content, 0, 0, 0, 0)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            content.findViewById<Button>(R.id.info_close).setOnClickListener { dialog.dismiss() }
            dialog.show()
               }
           }

    private fun updatePauseOverlay() {
        val wasVisible = pauseOverlay.visibility == View.VISIBLE
        val show = userPaused && !isInBackground && playerController != null
        pauseOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val index = playerController?.player?.currentMediaItemIndex ?: 0
            val video = viewModel.videos.getOrNull(index)
            overlayFilename.text = video?.displayName ?: ""
            if (isTelevision && currentFocus?.let { isDescendantOf(it, pauseOverlay) } != true) {
                overlayPlayPauseButton.requestFocus()
            }
               }
        if (!show && wasVisible && isTelevision) playerView.requestFocus()
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
            bytes < 0 -> getString(R.string.dlna_unknown_size)
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
               }
          }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::dlnaBrowser.isInitialized) dlnaBrowser.onConfigurationChanged()
        if (::safBrowser.isInitialized) safBrowser.onConfigurationChanged()
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
        if (isTelevision) selectFolderButton.requestFocus()
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
        isInBackground = true
        playerController?.player?.pause()
        updatePauseOverlay()
          }

    override fun onStart() {
        super.onStart()
        isInBackground = false
        if (viewModel.isPlayerScreen) {
            playerController?.player?.play()
               }
        updatePauseOverlay()
          }

    override fun onDestroy() {
        super.onDestroy()
        if (::usbBrowser.isInitialized) usbBrowser.close()
        if (::dlnaBrowser.isInitialized) dlnaBrowser.close()
        if (::safBrowser.isInitialized) safBrowser.close()
        stopPlayback()
          }
}
