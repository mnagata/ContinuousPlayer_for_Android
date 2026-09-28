package jp.nagu.continuousplayer

import android.widget.ImageButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackFolderTest {
    @Test fun pauseFolderButtonReopensPlaylistDirectoryAfterActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "playback-folder-fixture").apply { mkdirs() }
        val directory = File(root, "Current OP ED folder").apply { mkdirs() }
        File(directory, "Current OP.mp4").writeBytes(byteArrayOf(1))
        val preferences = context.getSharedPreferences("usb_browser", android.content.Context.MODE_PRIVATE)
        val originalRoot = preferences.getString("root", null)
        val originalDirectory = preferences.getString("directory", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    ViewModelProvider(it)[PlayerViewModel::class.java].playbackFolder =
                        PlaybackFolder.Usb(root, directory)
                }
                scenario.recreate()
                scenario.onActivity {
                    it.findViewById<ImageButton>(R.id.btn_overlay_select_folder).performClick()
                }
                BrowserTestUi.waitForText("Current OP ED folder")
                BrowserTestUi.waitForText("Current OP.mp4")
            }
        } finally {
            preferences.edit().putString("root", originalRoot)
                .putString("directory", originalDirectory).commit()
            root.deleteRecursively()
        }
    }
}
