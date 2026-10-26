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
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
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
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
          }

    private lateinit var viewModel: PlayerViewModel
    private lateinit var lastPlaybackFolder: LastPlaybackFolder

    private var playerController: PlayerController? = null
    private var bitPerfectAudio: BitPerfectAudioManager? = null
    private lateinit var audioManager: AudioManager
    private var gestureHandler: GestureHandler? = null
    private var multiTouchSequence = false
    private var pointerControls: PointerControls? = null
    private var pointerFocusGesture = false
    private var playbackWindowFocused = false
    private var lastWindowFocusGain = Long.MIN_VALUE

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
    private var pendingPlaybackError: PendingPlaybackError? = null
    private data class PendingPlaybackError(
        val controller: PlayerController,
        val error: androidx.media3.common.PlaybackException,
        val mediaIndex: Int,
        val mediaItem: androidx.media3.common.MediaItem?
    )
    private val playbackModalDialogs = mutableSetOf<AlertDialog>()
    private var userPaused = false
    private var audioOnlyMedia = false
    private lateinit var accessibilityManager: AccessibilityManager
    private val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
        updatePauseOverlay()
    }
    private var isInBackground = false
    private var pendingUsbFolder: PlaybackFolder.Usb? = null
    private var playbackSelection: RetainedPlayback? = null
    private var playbackInformation: RetainedPlayback? = null
    private var informationDialog: AlertDialog? = null
    private var informationJob: Job? = null
    private var homeMenu: PopupMenu? = null
    private val playbackKeyPresses = mutableMapOf<Pair<Int, Int>, Long>()

    private data class RetainedPlayback(
        val controller: PlayerController,
        val videos: List<VideoItem>,
        val folder: PlaybackFolder?,
        val mediaIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val focusedView: View?,
        val wasCompleted: Boolean = false,
        var interrupted: Boolean = false,
        var restoring: Boolean = false
    )

    private val storageSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Environment.isExternalStorageManager()) {
            openUsbBrowser()
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
        lastPlaybackFolder = LastPlaybackFolder(this)
        usbBrowser = UsbFileBrowser(this,
            onFolderSelected = { safBrowser.openSavedFolders(usbBrowser.takeDialog()) },
            onCancelled = ::cancelPlaybackSelection) { videos, index ->
            if (::safBrowser.isInitialized) safBrowser.close()
            viewModel.playbackFolder = usbBrowser.selectedFolder
            viewModel.videos = videos
            startPlayback(videos, index)
        }
        dlnaBrowser = DlnaBrowser(this, onCancelled = ::cancelPlaybackSelection) { videos, index ->
            viewModel.playbackFolder = dlnaBrowser.selectedFolder
            viewModel.videos = videos
            startPlayback(videos, index)
        }
        safBrowser = SafFileBrowser(this,
            onAddFolder = { launchTreePicker() },
            onAddDlna = { dlnaBrowser.open() },
            onDlnaFolder = { folder ->
                dlnaBrowser.openSaved(folder, safBrowser.takeDialog()) {
                    safBrowser.openSavedFolders(dlnaBrowser.takeDialog())
                }
            },
            onAddUsb = { launchUsbPicker() },
            onUsbFolder = { launchUsbPicker(it.folder) },
            onCancelled = ::cancelPlaybackSelection) { videos, index ->
            viewModel.playbackFolder = safBrowser.selectedFolder
            viewModel.videos = videos
            startPlayback(videos, index)
        }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        folderSelectContainer = findViewById(R.id.folder_select_container)
        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.player_view)
        pauseOverlay = findViewById(R.id.pause_overlay)
        val controlPaddingLeft = pauseOverlay.paddingLeft
        val controlPaddingRight = pauseOverlay.paddingRight
        val controlPaddingBottom = pauseOverlay.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(pauseOverlay) { view, insets ->
            // Keep controls clear of notches and transient system bars without moving the video.
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(controlPaddingLeft + safe.left, safe.top,
                controlPaddingRight + safe.right, controlPaddingBottom)
            insets
        }
        overlayFilename = findViewById(R.id.overlay_filename)
        portraitVideoInfo = findViewById(R.id.portrait_video_info)

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            updateExternalPlaybackCommands()
        }
        selectFolderButton = findViewById(R.id.btn_select_folder)
        overlayPlayPauseButton = findViewById(R.id.btn_overlay_play_pause)
        accessibilityManager = getSystemService(AccessibilityManager::class.java)
        accessibilityManager.addTouchExplorationStateChangeListener(touchExplorationListener)

        selectFolderButton.setOnClickListener {
            launchPicker()
              }

        findViewById<Button>(R.id.btn_home_more).setOnClickListener { showHomeMenu() }

        findViewById<ImageButton>(R.id.btn_overlay_home).setOnClickListener { returnHome() }

        overlayPlayPauseButton.setOnClickListener {
            gestureHandler?.cancel()
            togglePlayPause()
              }

        findViewById<ImageButton>(R.id.btn_overlay_previous).setOnClickListener {
            gestureHandler?.cancel()
            playerController?.previousVideo()
            updatePauseOverlay()
        }
        findViewById<ImageButton>(R.id.btn_overlay_rewind).setOnClickListener {
            gestureHandler?.cancel()
            playerController?.seekBackward()
            updatePauseOverlay()
        }
        findViewById<ImageButton>(R.id.btn_overlay_forward).setOnClickListener {
            gestureHandler?.cancel()
            playerController?.seekForward()
            updatePauseOverlay()
        }
        findViewById<ImageButton>(R.id.btn_overlay_next).setOnClickListener {
            gestureHandler?.cancel()
            playerController?.nextVideo()
            updatePauseOverlay()
        }

        findViewById<ImageButton>(R.id.btn_overlay_info).setOnClickListener {
            gestureHandler?.cancel()
            showInfoDialog()
              }

        findViewById<ImageButton>(R.id.btn_overlay_select_folder).setOnClickListener {
            gestureHandler?.cancel()
            openCurrentPlaybackSelection()
              }

        if (isTelevision) {
            findViewById<TextView>(R.id.tv_controls_hint).visibility = View.VISIBLE
            selectFolderButton.requestFocus()
              }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (homeMenu != null) {
                    homeMenu?.dismiss()
                } else if (playbackInformation != null) {
                    closeInformation()
                } else if (viewModel.isPlayerScreen) {
                    openCurrentPlaybackSelection()
                     } else {
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                     }
                 }
             })

        if (savedInstanceState != null && viewModel.isPlayerScreen) {
            startPlayback(viewModel.videos)
             } else {
            showFolderSelect()
             }
          }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isPointerInput()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Input timestamps precede the focus callback when the same click activates a window.
                pointerFocusGesture = !playbackWindowFocused || !window.decorView.hasWindowFocus() ||
                    event.downTime <= lastWindowFocusGain
            }
            if (pointerFocusGesture) {
                gestureHandler?.cancel()
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerFocusGesture = false
                }
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouchSequence = false
        if (event.pointerCount > 1) multiTouchSequence = true
        if (multiTouchSequence || event.actionMasked == MotionEvent.ACTION_CANCEL) gestureHandler?.cancel()
        // Observe the full stream before ViewGroup splits pointers between controls and media.
        try {
            return super.dispatchTouchEvent(event)
        } finally {
            // Reveal after hit testing, so newly shown buttons cannot steal a background DOWN.
            if (event.isPointerInput()) pointerControls?.onPointerEvent(event)
            else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pointerControls?.reset()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouchSequence = false
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isPointerInput()) {
            pointerControls?.onPointerEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                event.actionButton != MotionEvent.BUTTON_PRIMARY) gestureHandler?.cancel()
            // The browser's own window retains normal wheel scrolling.
            if (event.actionMasked == MotionEvent.ACTION_SCROLL && playerController != null) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.deviceId to event.keyCode
        if (playbackKeyPresses[key] == event.downTime) {
            // Focus can move to a button after pausing. Its key-up must not click it too.
            if (event.action == KeyEvent.ACTION_UP) playbackKeyPresses.remove(key)
            return true
        }
        if (playerController != null && isPlaybackInputBlocked() && PlaybackInput.isMediaTransportKey(event.keyCode)) return true
        if (!::viewModel.isInitialized || !viewModel.isPlayerScreen || isPlaybackInputBlocked() ||
            currentFocus?.onCheckIsTextEditor() == true ||
            !window.decorView.hasWindowFocus()
        ) return super.dispatchKeyEvent(event)

        val controller = playerController ?: return super.dispatchKeyEvent(event)
        val player = controller.player
        val componentHasFocus = currentFocus?.let { focus ->
            focus.isShown && focus !== playerView && !isDescendantOf(focus, playerView)
        } == true
        val noModifiers = event.hasNoModifiers()
        val action: (() -> Unit)? = when {
            noModifiers && event.keyCode in listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_O) ->
                ::openCurrentPlaybackSelection
            noModifiers && event.keyCode in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_INFO) ->
                ::showInfoDialog
            noModifiers && event.keyCode == KeyEvent.KEYCODE_H -> ::returnHome
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> ::togglePlayPause
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> ({ player.play(); updatePauseOverlay() })
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ->
                ({ userPaused = true; player.pause(); updatePauseOverlay() })
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controller::previousVideo
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> controller::nextVideo
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND -> controller::seekBackward
            noModifiers && event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> controller::seekForward
            !componentHasFocus && noModifiers && event.keyCode in listOf(KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A) -> ::togglePlayPause
            !componentHasFocus && noModifiers && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ->
                controller::previousVideo
            !componentHasFocus && noModifiers && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ->
                controller::nextVideo
            !componentHasFocus && ((noModifiers && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) ||
                (event.hasModifiers(KeyEvent.META_SHIFT_ON) && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) ->
                controller::seekBackward
            !componentHasFocus && ((noModifiers && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ||
                (event.hasModifiers(KeyEvent.META_SHIFT_ON) && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) ->
                controller::seekForward
            else -> null
        }
        if (action == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Keep the whole gesture even when its action changes the screen or focus.
            playbackKeyPresses[key] = event.downTime
            pointerControls?.reset()
            gestureHandler?.cancel()
            action()
        }
        return true
    }

    private fun returnHome() {
        closeSelectionBrowsers()
        stopPlayback()
        showFolderSelect()
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

    private fun openCurrentPlaybackSelection() {
        val focusMedia = playerController?.player?.currentMediaItemIndex
            ?.let { viewModel.videos.getOrNull(it) }
        openPlaybackSelection(focusMedia)
    }

    private fun openPlaybackSelection(focusMedia: VideoItem?) {
        if (playbackSelection != null || playbackInformation != null) return
        val controller = playerController
        if (controller != null) {
            val player = controller.player
            playbackSelection = retainPlayback(controller)
            player.pause()
            updatePauseOverlay()
            exitImmersiveMode()
        } else showFolderSelect()
        launchPlaybackFolder(focusMedia)
    }

    private fun closeSelectionBrowsers() {
        if (::usbBrowser.isInitialized) usbBrowser.close()
        if (::dlnaBrowser.isInitialized) dlnaBrowser.close()
        if (::safBrowser.isInitialized) safBrowser.close()
    }

    private fun cancelPlaybackSelection() {
        val selection = playbackSelection ?: return
        if (selection.restoring) return
        closeSelectionBrowsers()
        restoreRetainedPlayback(selection)
    }

    private fun retainPlayback(controller: PlayerController): RetainedPlayback {
        val player = controller.player
        return RetainedPlayback(controller, viewModel.videos, viewModel.playbackFolder,
            player.currentMediaItemIndex, player.currentPosition, player.playWhenReady, currentFocus,
            wasCompleted = controller.isPlaybackCompleted)
    }

    private fun ownsRetainedPlayback(state: RetainedPlayback) =
        playbackSelection === state || playbackInformation === state

    private fun restoreRetainedPlayback(selection: RetainedPlayback) {
        if (!ownsRetainedPlayback(selection) || selection.restoring) return
        selection.restoring = true
        lifecycleScope.launch {
            // A retained decoder may still have cached data after storage access is lost.
            val accessible = withContext(Dispatchers.IO) {
                val media = selection.videos.getOrNull(selection.mediaIndex)
                try {
                    val uri = Uri.parse(media?.uri ?: return@withContext false)
                    when (uri.scheme) {
                        "file" -> File(requireNotNull(uri.path)).inputStream().use { }
                        "content" -> contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                            ?: return@withContext false
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            // Selecting another file, going home, or destroying the activity supersedes this restore.
            if (!ownsRetainedPlayback(selection) || playerController !== selection.controller ||
                isFinishing || isDestroyed) return@launch
            val wasInformation = playbackInformation === selection
            if (wasInformation) playbackInformation = null else playbackSelection = null
            viewModel.videos = selection.videos
            viewModel.playbackFolder = selection.folder
            val player = selection.controller.player
            // An error is already stopped at its failing position; restoring the old
            // snapshot must not clear that error or rewind it before the user decides.
            if (pendingPlaybackError == null && player.playerError == null) {
                selection.controller.restorePlaybackPosition(selection.mediaIndex, selection.positionMs, selection.wasCompleted)
            }
            val requiresManualResume = selection.interrupted || isInBackground || !accessible ||
                player.playerError != null
            val resume = selection.playWhenReady && !requiresManualResume
            userPaused = !resume
            showPlayer()
            if (resume) {
                player.play()
                if (isTelevision) playerView.requestFocus()
            } else player.pause()
            updatePauseOverlay()
            if (wasInformation && !selection.playWhenReady && !requiresManualResume) {
                selection.focusedView?.takeIf { it.isShown }?.requestFocus()
            }
            if (!accessible && !isInBackground && pendingPlaybackError == null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.playback_failed)
                    .setMessage(R.string.playback_source_unavailable)
                    .setPositiveButton(android.R.string.ok, null)
                    .showPlaybackModal()
            }
            presentPendingPlaybackError()
        }
    }

    private fun launchPlaybackFolder(focusMedia: VideoItem? = null) {
        when (val folder = viewModel.playbackFolder) {
            is PlaybackFolder.Saf -> safBrowser.openFolder(folder, focusMedia?.uri?.let(Uri::parse))
            is PlaybackFolder.Dlna -> dlnaBrowser.openSaved(folder.folder, focusMediaId = focusMedia?.dlnaObjectId) {
                safBrowser.openSavedFolders(dlnaBrowser.takeDialog())
            }
            is PlaybackFolder.Usb -> usbBrowser.openSaved(folder, null, focusUri = focusMedia?.uri) {
                safBrowser.openSavedFolders(usbBrowser.takeDialog())
            }
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
            AlertDialog.Builder(this)
                .setTitle(R.string.saved_folder_local)
                .setMessage(R.string.saf_picker_unavailable)
                .setNegativeButton(R.string.file_browser_cancel, null)
                .setPositiveButton(R.string.usb_choose_drive) { _, _ -> launchUsbPicker() }
                .showPlaybackModal()
        }
    }

    private fun openUsbBrowser() {
        val folder = pendingUsbFolder
        pendingUsbFolder = null
        if (folder == null) usbBrowser.open()
        else usbBrowser.openSaved(folder, safBrowser.takeDialog()) {
            safBrowser.openSavedFolders(usbBrowser.takeDialog())
        }
    }

    private fun launchUsbPicker(folder: PlaybackFolder.Usb? = null) {
        pendingUsbFolder = folder
        if (Environment.isExternalStorageManager()) {
            openUsbBrowser()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.usb_permission_title)
            .setMessage(R.string.usb_permission_message)
            .setNegativeButton(R.string.file_browser_cancel, null)
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
                        .setPositiveButton(android.R.string.ok, null).showPlaybackModal()
                }
            }.showPlaybackModal()
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
        lastPlaybackFolder.save(viewModel.playbackFolder)
        stopPlayback()
        showPlayer()
        lateinit var controller: PlayerController
        controller = PlayerController(
            this,
            onPlaybackFailure = { error ->
                if (playerController === controller) showPlaybackError(error)
            },
            onPlaybackInterrupted = {
                playbackSelection?.interrupted = true
                playbackInformation?.interrupted = true
                userPaused = true
                updatePauseOverlay()
            },
            isPlaybackAllowed = { playbackSelection == null && playbackInformation == null },
            isExternalInputAllowed = { !isPlaybackInputBlocked() }
        )
        playerController = controller
        updateExternalPlaybackCommands()
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
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                updatePauseOverlay()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updatePauseOverlay()
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
                   ) {
                pendingPlaybackError = null
                playbackErrorDialog?.dismiss()
                playbackErrorDialog = null
                audioOnlyMedia = false
                Log.d(TAG, "onMediaItemTransition: mediaItem=$mediaItem, reason=$reason")
                updatePortraitVideoInfo()
                updatePauseOverlay()
                   }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Empty tracks during buffering do not reclassify the current item.
                if (!tracks.isEmpty) audioOnlyMedia = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO) &&
                    !tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                updatePortraitVideoInfo()
                updatePauseOverlay()
                   }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: state=$playbackState")
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    updatePortraitVideoInfo()
                       }
                updatePauseOverlay()
                   }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                updatePauseOverlay()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playbackSelection != null || playbackInformation != null) {
                    // Temporary windows retain the original playback intent independently.
                    if (playWhenReady) controller.player.pause()
                    updatePauseOverlay()
                    return
                }
                if (playWhenReady) {
                    userPaused = false
                    // Audio controls remain visible, but TV transport keys return to playback.
                    if (isTelevision && !accessibilityManager.isTouchExplorationEnabled &&
                        currentFocus?.let { isDescendantOf(it, pauseOverlay) } == true) playerView.requestFocus()
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
        playerView.isFocusable = true
        if (isTelevision) playerView.requestFocus()
        Log.d(TAG, "startPlayback: calling updatePortraitVideoInfo")
        updatePortraitVideoInfo()

        val touchOverlay = findViewById<View>(R.id.touch_overlay)
        val handler = GestureHandler(
            touchOverlay,
            controller,
            onPlayPauseToggled = { updatePauseOverlay() }
        )
        gestureHandler = handler
        pointerControls = PointerControls(
            touchOverlay, pauseOverlay,
            listOf(R.id.btn_overlay_home, R.id.btn_overlay_select_folder, R.id.btn_overlay_info,
                R.id.btn_overlay_previous, R.id.btn_overlay_rewind, R.id.btn_overlay_play_pause,
                R.id.btn_overlay_forward, R.id.btn_overlay_next).map { findViewById(it) },
            canReveal = { !isInBackground && playerController != null && !isPlaybackInputBlocked() },
            canAutoHide = {
                controller.player.playWhenReady && !mustShowPlaybackControls() &&
                    controller.player.currentTracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            },
            onChanged = { updatePauseOverlay() }
        )
        touchOverlay.setOnTouchListener { _, event ->
            if (isPlaybackInputBlocked() || multiTouchSequence) gestureHandler?.cancel()
            else gestureHandler?.onTouchEvent(event)
            true
               }

        enterImmersiveMode()
          }

    private fun stopPlayback() {
        pendingPlaybackError = null
        pointerControls?.release()
        pointerControls = null
        gestureHandler?.cancel()
        gestureHandler = null
        playbackSelection = null
        playbackInformation = null
        informationJob?.cancel()
        informationJob = null
        informationDialog?.setOnDismissListener(null)
        informationDialog?.dismiss()
        informationDialog = null
        userPaused = false
        audioOnlyMedia = false
        playbackModalDialogs.toList().forEach { it.dismiss() }
        playbackErrorDialog = null
        playerView.keepScreenOn = false
        pauseOverlay.visibility = View.GONE
        bitPerfectAudio?.release()
        bitPerfectAudio = null
        playerView.player = null
        playerController?.release()
        playerController = null
        viewModel.isPlayerScreen = false
          }

    private fun showPlaybackError(error: androidx.media3.common.PlaybackException) {
        val controller = playerController ?: return
        playbackSelection?.interrupted = true
        playbackInformation?.interrupted = true
        userPaused = true
        controller.player.pause()
        pendingPlaybackError = PendingPlaybackError(controller, error,
            controller.player.currentMediaItemIndex, controller.player.currentMediaItem)
        updatePauseOverlay()
        presentPendingPlaybackError()
    }

    private fun presentPendingPlaybackError() {
        val pending = pendingPlaybackError ?: return
        val controller = playerController
        if (controller !== pending.controller || controller.player.currentMediaItemIndex != pending.mediaIndex ||
            controller.player.currentMediaItem != pending.mediaItem) {
            pendingPlaybackError = null
            return
        }
        if (isFinishing || isDestroyed || isInBackground || !window.decorView.hasWindowFocus() ||
            playbackSelection != null || playbackInformation != null || playbackModalDialogs.isNotEmpty()) return
        pendingPlaybackError = null
        val player = controller.player
        val error = pending.error
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
            .setNeutralButton(R.string.file_browser_cancel, null)
            .showPlaybackModal()
    }

    private fun AlertDialog.Builder.showPlaybackModal(): AlertDialog {
        val dialog = create()
        playbackModalDialogs.add(dialog)
        dialog.setOnKeyListener { _, key, _ -> PlaybackInput.isMediaTransportKey(key) }
        dialog.setOnDismissListener {
            playbackModalDialogs.remove(dialog)
            if (playbackErrorDialog === dialog) playbackErrorDialog = null
            updateExternalPlaybackCommands()
            presentPendingPlaybackError()
        }
        // Block before attaching the window; focus loss arrives on a later UI dispatch.
        updateExternalPlaybackCommands()
        dialog.show()
        return dialog
    }

    private fun showInfoDialog() {
        if (isFinishing || isDestroyed || playbackInformation != null || playbackSelection != null) return
        val controller = playerController ?: return
        val player = controller.player
        val index = player.currentMediaItemIndex
        val video = viewModel.videos.getOrNull(index) ?: return
        val videoUri = Uri.parse(video.uri)
        val state = retainPlayback(controller)
        playbackInformation = state
        player.pause()
        updatePauseOverlay()
        exitImmersiveMode()

        val content = LayoutInflater.from(this).inflate(R.layout.dialog_playback_info, null)
        content.findViewById<TextView>(R.id.info_filename).text = video.displayName
        val audioText = content.findViewById<TextView>(R.id.info_audio).apply { visibility = View.GONE }
        val dialog = AlertDialog.Builder(this).create()
        informationDialog = dialog
        dialog.setView(content, 0, 0, 0, 0)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnKeyListener { _, key, event ->
            if (PlaybackInput.isMediaTransportKey(key)) true
            else if (dialog.currentFocus?.onCheckIsTextEditor() == true) false
            else if (key in listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_ESCAPE) && event.hasNoModifiers()) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeInformation()
                true
            } else false
        }
        dialog.setOnDismissListener {
            if (informationDialog === dialog) {
                informationDialog = null
                informationJob?.cancel()
                informationJob = null
                restoreRetainedPlayback(state)
            }
        }
        content.findViewById<Button>(R.id.info_close).setOnClickListener { closeInformation() }
        dialog.show()
        if (isTelevision) content.findViewById<View>(R.id.info_close).doOnLayout {
            if (informationDialog === dialog && dialog.isShowing) it.requestFocus()
        }

        val audio = bitPerfectAudio
        informationJob = lifecycleScope.launch {
            val audioInfo = withContext(Dispatchers.IO) {
                audio?.getAudioOutputInfo(videoUri) ?: ""
            }
            if (playbackInformation !== state || state.restoring || informationDialog !== dialog ||
                isFinishing || isDestroyed) return@launch
            audioText.apply {
                text = audioInfo
                visibility = if (audioInfo.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun closeInformation() {
        informationDialog?.dismiss()
    }

    private fun isPlaybackInputBlocked(): Boolean =
        playbackSelection != null || playbackInformation != null || playbackModalDialogs.isNotEmpty() ||
            (!isInBackground && (!window.decorView.hasWindowFocus() || currentFocus?.onCheckIsTextEditor() == true))

    private fun updateExternalPlaybackCommands() {
        playerController?.setExternalCommandsBlocked(isPlaybackInputBlocked())
    }

    private fun mustShowPlaybackControls(): Boolean {
        val player = playerController?.player ?: return false
        return userPaused || audioOnlyMedia || playerController?.isPlaybackCompleted == true ||
            player.playerError != null || accessibilityManager.isTouchExplorationEnabled
    }

    private fun updatePauseOverlay() {
        updateExternalPlaybackCommands()
        pointerControls?.refresh()
        val wasVisible = pauseOverlay.visibility == View.VISIBLE
        val show = (mustShowPlaybackControls() || pointerControls?.visible == true) && !isInBackground && playerController != null &&
            playbackSelection == null && playbackInformation == null
        pauseOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !wasVisible) ViewCompat.requestApplyInsets(pauseOverlay)
        updateTransportButtons()
        val player = playerController?.player
        val ended = playerController?.isPlaybackCompleted == true
        val failed = player?.playerError != null
        val playing = player?.playWhenReady == true && !ended && !failed
        val playPauseLabel = getString(if (playing) R.string.pause_playback else R.string.resume_playback)
        overlayPlayPauseButton.apply {
            setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = playPauseLabel
            tooltipText = playPauseLabel
        }
        findViewById<TextView>(R.id.playback_status).apply {
            val message = when {
                failed -> getString(R.string.playback_failed)
                ended -> getString(R.string.playback_completed)
                else -> ""
            }
            if (text.toString() != message) text = message
            visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        }
        if (show) {
            val index = playerController?.player?.currentMediaItemIndex ?: 0
            val video = viewModel.videos.getOrNull(index)
            overlayFilename.text = video?.displayName ?: ""
            if ((userPaused || ended || failed) && isTelevision && currentFocus?.let { isDescendantOf(it, pauseOverlay) } != true) {
                overlayPlayPauseButton.requestFocus()
            }
               }
        if (!show && wasVisible && (isTelevision || !playerView.isInTouchMode)) playerView.requestFocus()
          }

    private fun updateTransportButtons() {
        val player = playerController?.player
        val focused = currentFocus?.takeIf { isDescendantOf(it, pauseOverlay) }
        findViewById<View>(R.id.btn_overlay_previous).isEnabled = player != null &&
            player.hasPreviousMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        findViewById<View>(R.id.btn_overlay_next).isEnabled = player != null &&
            player.hasNextMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val canSeek = player != null && player.isCurrentMediaItemSeekable &&
            player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        findViewById<View>(R.id.btn_overlay_rewind).isEnabled = canSeek && player?.isCommandAvailable(Player.COMMAND_SEEK_BACK) == true
        findViewById<View>(R.id.btn_overlay_forward).isEnabled = canSeek && player?.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) == true
        if (pauseOverlay.isShown && focused != null && !focused.isEnabled) {
            overlayPlayPauseButton.requestFocus()
        }
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
                        val videoFormat = playerController?.videoFormat
                        val audioFormat = playerController?.audioFormat
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
        pointerControls?.reset()
        gestureHandler?.cancel()
        super.onConfigurationChanged(newConfig)
        if (::dlnaBrowser.isInitialized) dlnaBrowser.onConfigurationChanged()
        if (::safBrowser.isInitialized) safBrowser.onConfigurationChanged()
        if (::usbBrowser.isInitialized) usbBrowser.onConfigurationChanged()
        updatePortraitVideoInfo()
          }

    private fun showPlayer() {
        viewModel.isPlayerScreen = true
        homeMenu?.dismiss()
        folderSelectContainer.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        enterImmersiveMode()
          }

    private fun showHomeMenu() {
        if (viewModel.isPlayerScreen || isFinishing || isDestroyed) return
        homeMenu?.dismiss()
        val anchor = findViewById<Button>(R.id.btn_home_more)
        val menu = PopupMenu(this, anchor)
        homeMenu = menu
        menu.inflate(R.menu.home_more)
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_exit_app && !viewModel.isPlayerScreen) {
                finishAndRemoveTask()
                true
            } else false
        }
        menu.setOnDismissListener {
            if (homeMenu === menu) homeMenu = null
            if (!viewModel.isPlayerScreen && !isFinishing && !isDestroyed) anchor.requestFocus()
        }
        menu.show()
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
        playbackWindowFocused = hasFocus
        if (hasFocus) lastWindowFocusGain = SystemClock.uptimeMillis()
        if (!hasFocus) {
            gestureHandler?.cancel()
            pointerControls?.reset()
        }
        super.onWindowFocusChanged(hasFocus)
        updateExternalPlaybackCommands()
        if (hasFocus && ::viewModel.isInitialized && viewModel.isPlayerScreen && playbackSelection == null && playbackInformation == null) {
            enterImmersiveMode()
               }
        if (hasFocus) presentPendingPlaybackError()
          }

    override fun onStop() {
        pointerControls?.reset()
        gestureHandler?.cancel()
        homeMenu?.dismiss()
        lastPlaybackFolder.save(viewModel.playbackFolder)
        super.onStop()
        isInBackground = true
        playbackSelection?.interrupted = true
        playbackInformation?.interrupted = true
        // A stopped activity always returns paused, with controls for explicit resumption.
        if (playerController != null) userPaused = true
        playerController?.pauseForBackground()
        updatePauseOverlay()
          }

    override fun onStart() {
        super.onStart()
        isInBackground = false
        updatePauseOverlay()
        presentPendingPlaybackError()
          }

    override fun onDestroy() {
        if (::accessibilityManager.isInitialized) {
            accessibilityManager.removeTouchExplorationStateChangeListener(touchExplorationListener)
        }
        super.onDestroy()
        if (::usbBrowser.isInitialized) usbBrowser.close()
        if (::dlnaBrowser.isInitialized) dlnaBrowser.close()
        if (::safBrowser.isInitialized) safBrowser.close()
        stopPlayback()
          }
}
