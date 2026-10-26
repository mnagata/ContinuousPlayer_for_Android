package jp.nagu.continuousplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the actual home button with real persisted SAF grants and saved bookmarks. */
@RunWith(AndroidJUnit4::class)
class SavedFolderEntryTest {
    @Test fun phoneEmptyListRequiresExplicitAdd() = checkEntry()
    @Test fun tvEmptyListRequiresExplicitAdd() = checkEntry(tv = true)
    @Test fun phoneSingleSafRequiresExplicitOpen() = checkEntry(saf = 1)
    @Test fun tvSingleSafRequiresExplicitOpen() = checkEntry(saf = 1, tv = true)
    @Test fun phoneMultipleSafOpenSameSavedList() = checkEntry(saf = 2)
    @Test fun tvMultipleSafOpenSameSavedList() = checkEntry(saf = 2, tv = true)
    @Test fun phoneSingleDlnaRemainsInSavedList() = checkEntry(dlna = true)
    @Test fun tvSingleDlnaRemainsInSavedList() = checkEntry(dlna = true, tv = true)
    @Test fun phoneSingleUsbRemainsInSavedList() = checkEntry(usb = true)
    @Test fun tvSingleUsbRemainsInSavedList() = checkEntry(usb = true, tv = true)
    @Test fun phoneMixedListSelectsLastPlayedWithoutOpeningIt() = checkEntry(saf = 1, dlna = true, usb = true)
    @Test fun tvMixedListSelectsLastPlayedWithoutOpeningIt() = checkEntry(saf = 1, dlna = true, usb = true, tv = true)
    @Test fun emptyListAddFitsAtLargeFont() = checkEntry(fontScale = 2f)
    @Test fun singleSafListFitsAtLargeFont() = checkEntry(saf = 1, fontScale = 2f)

    private fun checkEntry(saf: Int = 0, dlna: Boolean = false, usb: Boolean = false,
        tv: Boolean = false, fontScale: Float? = null) = withFixture(saf, dlna, usb) { scenario, context ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var configuration: Configuration
        scenario.onActivity { configuration = Configuration(it.resources.configuration) }
        try {
            scenario.onActivity {
                val next = Configuration(configuration).apply {
                    if (tv) uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
                    if (fontScale != null) this.fontScale = fontScale
                }
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(next, it.resources.displayMetrics)
            }
            val expected = saf + (if (dlna) 1 else 0) + (if (usb) 1 else 0)
            val grants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
            // Close/reopen must keep the same entry even with exactly one SAF grant.
            repeat(2) {
                scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).performClick() }
                awaitSavedList(context, expected)
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    val window = browserWindow()!!
                    assertEquals(activity.getString(R.string.saf_choose_folder), window.findViewById<TextView>(R.id.dlna_title).text.toString())
                    assertEquals(1, WindowInspector.getGlobalWindowViews().count { it.findViewById<View>(R.id.dlna_title) != null })
                    val list = window.findViewById<ListView>(R.id.dlna_list)
                    assertEquals(expected, list.count)
                    val add = window.findViewById<TextView>(R.id.dlna_refresh)
                    assertTrue(add.isShown && add.isEnabled)
                    val visibleBounds = Rect()
                    assertTrue(add.getGlobalVisibleRect(visibleBounds))
                    assertEquals("The add button must remain fully visible", add.height, visibleBounds.height())
                    assertEquals(add.width, visibleBounds.width())
                    assertEquals(activity.getString(R.string.saf_add_folder), add.text.toString())
                    assertEquals(activity.getString(R.string.saf_add_folder), add.contentDescription.toString())
                    assertTrue(add.layout.height <= add.height - add.compoundPaddingTop - add.compoundPaddingBottom)
                    assertTrue((0 until add.lineCount).all { add.layout.getEllipsisCount(it) == 0 })
                    assertFalse(ViewModelProvider(activity)[PlayerViewModel::class.java].isPlayerScreen)
                    assertNull(MainActivity::class.java.getDeclaredField("playerController").apply { isAccessible = true }.get(activity))
                    if (expected == 0) {
                        assertEquals(R.id.dlna_refresh, window.findFocus()!!.id)
                        assertTrue(window.findViewById<View>(R.id.dlna_message_container).isShown)
                        assertEquals(activity.getString(R.string.saf_no_folder), window.findViewById<TextView>(R.id.dlna_message).text.toString())
                    } else {
                        assertTrue(list.isEnabled)
                        assertEquals(if (usb) expected - 1 else 0, list.selectedItemPosition)
                        val titles = (0 until list.count).map { (list.getItemAtPosition(it) as DlnaRow).title }
                        if (saf > 0) assertTrue(titles.contains("Season 00"))
                        if (saf > 1) assertTrue(titles.contains("Season 01"))
                        if (dlna) assertTrue(titles.contains("D17 offline NAS folder"))
                        if (usb) assertTrue(titles.contains("D17 offline USB folder"))
                    }
                }
                assertEquals(grants, context.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
                scenario.onActivity { browserWindow()!!.findViewById<View>(R.id.dlna_close).performClick() }
                BrowserTestUi.waitUntil { browserWindow() == null }
            }
            scenario.onActivity { it.findViewById<View>(R.id.btn_select_folder).performClick() }
            awaitSavedList(context, expected)
            if (expected == 0) {
                screenshot(if (fontScale != null) "empty-font200" else if (tv) "empty-tv" else "empty-phone")
                if (tv) {
                    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        val now = SystemClock.uptimeMillis()
                        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_DPAD_CENTER, 0), true))
                    }
                } else BrowserTestUi.tap(R.string.saf_add_folder)
                BrowserTestUi.waitUntil { browserWindow()?.findViewById<TextView>(R.id.dlna_title)?.text.toString() == context.getString(R.string.saf_add_folder) }
                BrowserTestUi.waitForText(R.string.saved_folder_local)
                BrowserTestUi.waitForText(R.string.dlna_select)
                if (tv) BrowserTestUi.waitForText(R.string.usb_choose_drive)
            } else if (saf == 1 && !dlna && !usb) {
                screenshot(if (fontScale != null) "single-saf-font200" else if (tv) "single-saf-tv" else "single-saf-phone")
                scenario.onActivity {
                    val list = browserWindow()!!.findViewById<ListView>(R.id.dlna_list)
                    list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                }
                BrowserTestUi.waitUntil {
                    browserWindow()?.findViewById<TextView>(R.id.dlna_title)?.text.toString() == "Season 00" &&
                        browserWindow()!!.findViewById<ListView>(R.id.dlna_list).count == 40
                }
            } else if (saf > 0 && dlna && usb && !tv) screenshot("mixed-phone")
        } finally {
            scenario.onActivity {
                @Suppress("DEPRECATION")
                it.resources.updateConfiguration(configuration, it.resources.displayMetrics)
            }
        }
    }

    private fun browserWindow() = WindowInspector.getGlobalWindowViews().singleOrNull { it.findViewById<View>(R.id.dlna_title) != null }
    private fun awaitSavedList(context: Context, count: Int) = BrowserTestUi.waitUntil {
        val window = browserWindow()
        window?.hasWindowFocus() == true &&
            window.findViewById<TextView>(R.id.dlna_title).text.toString() == context.getString(R.string.saf_choose_folder) &&
            window.findViewById<ListView>(R.id.dlna_list).count == count && window.findViewById<View>(R.id.dlna_refresh).isEnabled
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync();SystemClock.sleep(600)
        File(instrumentation.targetContext.filesDir, "d17-$name.png").outputStream().use {
            assertTrue(instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun withFixture(safCount: Int, dlna: Boolean, usb: Boolean,
        action: (ActivityScenario<MainActivity>, Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val authority = ScrollFixtureProvider.tree.authority
        val previousGrants = resolver.persistedUriPermissions.toList()
        // Only fixture-owned grants may be changed; use a dedicated emulator for this matrix.
        assumeTrue("Non-fixture SAF grants must be preserved", previousGrants.all { it.uri.authority == authority })
        val preferences = listOf("saved_dlna_folders", "saved_usb_folders", "last_playback_folder")
            .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val originals = preferences.map { it.all.toMap() }
        fun clearFixtureGrants() {
            resolver.persistedUriPermissions.filter { it.uri.authority == authority }.forEach {
                val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                resolver.releasePersistableUriPermission(it.uri, flags)
            }
        }
        fun grant(trees: List<Uri>, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
            if (trees.isEmpty()) return
            context.startActivity(Intent().setClassName("jp.nagu.continuousplayer.test", ScrollFixtureGrantActivity::class.java.name)
                .putExtra("target", context.packageName).putExtra("trees", trees.map { it.toString() }.toTypedArray())
                .putExtra("accessFlags", flags).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            BrowserTestUi.waitUntil { runCatching { trees.forEach { resolver.takePersistableUriPermission(it, flags) } }.isSuccess }
        }
        var touchMode = true
        try {
            clearFixtureGrants()
            preferences.forEach { it.edit().clear().commit() }
            grant((0 until safCount).map { DocumentsContract.buildTreeDocumentUri(authority!!, "season-%02d".format(it)) })
            if (dlna) SavedDlnaFolders(context).save(DlnaServer("uuid:d17-offline", "D17 NAS", "http://192.0.2.1/control", "ContentDirectory:1"),
                listOf(DlnaEntry("0", "D17 offline NAS folder")))
            if (usb) {
                val root = File(context.filesDir, "d17-disconnected-storage")
                val directory = File(root, "D17 offline USB folder")
                SavedUsbFolders(context).save(root, directory)
                LastPlaybackFolder(context).save(PlaybackFolder.Usb(root, directory))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { touchMode = it.findViewById<View>(R.id.btn_select_folder).isInTouchMode }
                instrumentation.setInTouchMode(false)
                BrowserTestUi.waitUntil { WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() } }
                action(scenario, context)
            }
        } finally {
            instrumentation.setInTouchMode(touchMode)
            clearFixtureGrants()
            previousGrants.forEach {
                grant(listOf(it.uri), (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
            }
            preferences.zip(originals).forEach { (prefs, values) ->
                val editor = prefs.edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                } }
                editor.commit()
            }
        }
    }
}
