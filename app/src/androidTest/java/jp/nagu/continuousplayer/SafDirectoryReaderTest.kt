package jp.nagu.continuousplayer

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SafDirectoryReaderTest {
    private val tree = DocumentsContract.buildTreeDocumentUri("saf.fixture", "opaque-root")
    private val columns = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")

    @Test
    fun opaqueIdsKeepTreeAccessAndSortTheDisplayedPlaylist() {
        val requests = mutableListOf<Uri>()
        val reader = reader { uri ->
            requests += uri
            when {
                !uri.path.orEmpty().endsWith("/children") -> cursor(arrayOf<Any?>("opaque-root", "コレクション", dir, null, null))
                DocumentsContract.getDocumentId(uri) == "opaque-root" -> cursor(
                    arrayOf<Any?>("f-autumn", "2026 秋", dir, null, null),
                    arrayOf<Any?>("ed-2", "作品 ED2.mp4", "video/mp4", 20L, 1L),
                    arrayOf<Any?>("f-winter", "2026 冬", dir, null, null),
                    arrayOf<Any?>("ed-1", "作品 ED.mp4", "video/mp4", 10L, 1L),
                    arrayOf<Any?>("f-spring", "2026 春", dir, null, null),
                    arrayOf<Any?>("op-2", "作品 OP2.mp4", "video/mp4", 20L, 1L),
                    arrayOf<Any?>("op-1", "作品 OP.mp4", "video/mp4", null, null),
                    arrayOf<Any?>("sidecar", "._作品 OP.mp4", "video/mp4", 10L, 1L),
                    arrayOf<Any?>("text", "notes.txt", "text/plain", 10L, 1L))
                else -> cursor(arrayOf<Any?>("song", "曲.flac", "audio/flac", 100L, 2L))
            }
        }
        val root = reader.root(tree)
        val listing = reader.read(root)
        assertEquals("コレクション", root.title)
        assertEquals(listOf("2026 冬", "2026 春", "2026 秋"), listing.folders.map { it.title })
        assertEquals(listOf("作品 OP.mp4", "作品 ED.mp4", "作品 OP2.mp4", "作品 ED2.mp4"),
            listing.media.map { it.displayName })
        assertEquals(-1L, listing.media.first().size)
        assertEquals(0L, listing.media.first().lastModified)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "op-1").toString(), listing.media.first().uri)
        val child = reader.read(listing.folders.first())
        assertEquals("曲.flac", child.media.single().displayName)
        assertEquals("audio/flac", child.media.single().mimeType)
        assertTrue(requests.all { DocumentsContract.getTreeDocumentId(it) == "opaque-root" })
        assertEquals("f-winter", DocumentsContract.getDocumentId(requests.last()))
    }

    @Test
    fun emptyDirectoryRemainsEmpty() {
        val listing = reader { cursor() }.read(SafFolder(
            DocumentsContract.buildDocumentUriUsingTree(tree, "empty"), "空フォルダー"))
        assertTrue(listing.folders.isEmpty())
        assertTrue(listing.media.isEmpty())
    }

    @Test
    fun partialCloudListingAndProviderErrorsAreNotUsedAsPlaylists() {
        for (extras in listOf(
            Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) },
            Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Offline") }
        )) {
            val reader = reader { cursor(arrayOf<Any?>("op", "作品 OP.mp4", "video/mp4", 1L, 0L)).apply {
                setExtras(extras)
            } }
            assertThrows(IOException::class.java) {
                reader.read(SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree, "folder"), "Folder"))
            }
        }
    }

    @Test
    fun missingOrRevokedAccessSurfacesAnError() {
        val root = SafFolder(DocumentsContract.buildDocumentUriUsingTree(tree, "opaque-root"), "Folder")
        assertThrows(IOException::class.java) { reader { null }.read(root) }
        assertThrows(IOException::class.java) { reader { cursor() }.root(tree) }
        assertThrows(SecurityException::class.java) { reader { throw SecurityException("Revoked") }.read(root) }
    }

    private fun cursor(vararg rows: Array<out Any?>) = MatrixCursor(columns).apply {
        rows.forEach { addRow(it) }
    }

    private fun reader(query: (Uri) -> Cursor?): SafDirectoryReader {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                               selectionArgs: Array<out String>?, sortOrder: String?) = query(uri)
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?,
                                selectionArgs: Array<out String>?) = error("Read only")
        }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getContentResolver() = resolver
        }
        return SafDirectoryReader(context)
    }

    private val dir get() = DocumentsContract.Document.MIME_TYPE_DIR
}
