package jp.nagu.continuousplayer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ContinuousPlayerのメインActivity。
 *
 * ## 画面構成
 * - **フォルダ選択画面**: 初期画面。ボタン押下でピッカーを起動する
 * - **プレーヤー画面**: 動画をフルスクリーンで連続再生する
 *
 * ## ファイル選択フロー
 * 1. SAFツリー権限がなければ [OpenDocumentTree] でフォルダ権限を取得
 * 2. [OpenDocument] でファイルを選択
 * 3. 選択ファイルがツリー権限の範囲外なら再度ツリーピッカーを表示
 * 4. ツリー配下をスキャンしてプレイリストを構築し、選択ファイルから再生開始
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var viewModel: PlayerViewModel
    private lateinit var scanner: VideoScanner

    private var playerController: PlayerController? = null
    private var bitPerfectAudio: BitPerfectAudioManager? = null
    private var gestureDetector: GestureDetector? = null

    private lateinit var folderSelectContainer: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var pauseOverlay: LinearLayout
    private lateinit var overlayFilename: TextView

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

        folderSelectContainer = findViewById(R.id.folder_select_container)
        playerContainer = findViewById(R.id.player_container)
        playerView = findViewById(R.id.player_view)
        pauseOverlay = findViewById(R.id.pause_overlay)
        overlayFilename = findViewById(R.id.overlay_filename)

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
            filePicker.launch(arrayOf("video/*"))
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
        filePicker.launch(arrayOf("video/*"))
    }

    private fun onFileSelected(fileUri: Uri) {
        Log.d(TAG, "onFileSelected: fileUri=$fileUri")

        val selectedDocId = try {
            DocumentsContract.getDocumentId(fileUri)
        } catch (_: Exception) { null }

        val matchedTree = findMatchingTreePermission(selectedDocId)
        if (matchedTree != null) {
            Log.d(TAG, "Found matching tree permission: $matchedTree")
            scanAndPlayFrom(matchedTree, selectedDocId)
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

    private fun scanAndPlayFrom(treeUri: Uri, selectedDocId: String?) {
        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) {
                scanner.scanTree(treeUri)
            }

            Log.d(TAG, "scanAndPlayFrom: ${videos.size} videos")
            if (videos.isEmpty()) {
                Toast.makeText(this@MainActivity, "No video files found", Toast.LENGTH_SHORT).show()
                return@launch
            }

            viewModel.videos = videos
            val startIndex = findStartIndex(videos, selectedDocId)
            showPlayer()
            startPlayback(videos, startIndex)
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

        controller.setPlaylist(videos, startIndex)
        playerView.keepScreenOn = true

        val touchOverlay = findViewById<View>(R.id.touch_overlay)
        val handler = GestureHandler(touchOverlay, controller) {
            updatePauseOverlay()
        }
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
        val playing = playerController?.player?.isPlaying == true
        if (!playing) {
            val index = playerController?.player?.currentMediaItemIndex ?: 0
            val video = viewModel.videos.getOrNull(index)
            overlayFilename.text = video?.displayName ?: ""
        }
        pauseOverlay.visibility = if (playing) View.GONE else View.VISIBLE
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
