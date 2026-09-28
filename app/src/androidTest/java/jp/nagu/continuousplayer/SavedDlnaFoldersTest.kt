package jp.nagu.continuousplayer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedDlnaFoldersTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = base.getSharedPreferences("saved_dlna_folders_test", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) = preferences
    }
    private val server = DlnaServer("uuid:fixture", "NAS", "http://192.0.2.1/control", "ContentDirectory:1")
    private val path = listOf(DlnaEntry("0", "NAS"), DlnaEntry("opaque-folder", "OP / ED"))

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun savesFolderIdentityAndFullNavigationPathAcrossInstances() {
        SavedDlnaFolders(context).save(server, path)
        val saved = SavedDlnaFolders(context).all().single()
        assertEquals(server.id, saved.serverId)
        assertEquals("OP / ED", saved.title)
        assertEquals(path.map { it.id }, saved.path.map { it.id })
        assertFalse(preferences.getString("folders", "")!!.contains(server.controlUrl))
    }

    @Test fun reregistrationUpdatesNamesWithoutDuplicatingTheFolder() {
        val store = SavedDlnaFolders(context)
        store.save(server, path)
        store.save(server.copy(name = "Renamed NAS", controlUrl = "http://192.0.2.2/control"),
            path.dropLast(1) + path.last().copy(title = "Renamed folder"))
        assertEquals(1, store.all().size)
        assertEquals("Renamed NAS", store.all().single().serverName)
        assertEquals("Renamed folder", store.all().single().title)
    }

    @Test fun folderIdsAreScopedToEachServerAndCanBeRemovedOffline() {
        val store = SavedDlnaFolders(context)
        store.save(server, path)
        store.save(server.copy(id = "uuid:other"), path)
        store.remove(server.id, path.last().id)
        assertFalse(store.contains(server.id, path.last().id))
        assertTrue(store.contains("uuid:other", path.last().id))
    }

    @Test fun corruptOrIncompleteBackupDataDoesNotProduceBrokenBookmarks() {
        for (json in listOf("not JSON", "null", "[null]", "[{\"serverId\":\"uuid:fixture\"}]")) {
            preferences.edit().putString("folders", json).commit()
            assertTrue(SavedDlnaFolders(context).all().isEmpty())
        }
    }
}
