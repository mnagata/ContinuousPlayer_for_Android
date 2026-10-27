package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MediaPresentationTest {
    private fun media(name:String,mime:String?=null,id:String=name)=VideoItem("content://fixture/$id",name,1,0,mime,dlnaObjectId=id)
    @Test fun knownSuffixesAreHiddenWithoutChangingOriginalName() {
        for(ext in listOf("mp4","M4V","mp3","flac","m4a","aac","wav","ogg","opus","mkv","avi","mov","webm","wmv","mpeg","ts","m2ts","aiff","wma")) {
            val item=media("Dr.STONE 第1.5話.$ext")
            assertEquals("Dr.STONE 第1.5話",item.selectionTitle)
            assertEquals("Dr.STONE 第1.5話.$ext",item.displayName)
            assertEquals(ext.uppercase(Locale.ROOT),item.formatLabel)
        }
    }
    @Test fun missingAndUnknownSuffixesPreserveTheTitle() {
        for(name in listOf("Dr.STONE","No suffix","Episode.mp4.backup","mp4","")) {
            val item=media(name)
            assertEquals(name,item.selectionTitle);assertNull(item.formatLabel)
        }
    }
    @Test fun extensionWinsOverBroadOrConflictingMime() {
        assertEquals("M4A",media("Episode.m4a","audio/mp4").formatLabel)
        assertEquals("MP4",media("Episode.mp4","application/octet-stream").formatLabel)
        assertEquals("FLAC",media("Episode.flac","audio/mpeg").formatLabel)
    }
    @Test fun declaredMimeSuppliesFormatForExtensionlessDlnaTitles() {
        for((mime,expected) in listOf("video/mp4" to "MP4","audio/mp4" to "M4A","audio/mpeg" to "MP3","audio/flac" to "FLAC","video/x-matroska" to "MKV","video/quicktime" to "MOV")) {
            assertEquals(expected,media("Title",mime).formatLabel)
        }
    }
    @Test fun mimeParametersAndCaseAreNormalized() {
        assertEquals("FLAC",media("Title"," AUDIO/FLAC ; codecs=flac").formatLabel)
    }
    @Test fun unknownMimeDoesNotPretendToKnowAFormat() {
        assertNull(media("Title","application/octet-stream").formatLabel)
        assertNull(media("Title","audio/vendor-private").formatLabel)
    }
    @Test fun collisionDetectionUsesVisibleNamesAndKeepsIdentityAndOrder() {
        val items=listOf(media("Episode.mp4",id="video"),media("Episode.flac",id="audio"),media("Unique.mp4",id="unique"))
        val original=items.toList()
        assertEquals(setOf("Episode"),ambiguousMediaTitles(items))
        assertEquals(original,items)
        assertEquals(listOf("video","audio","unique"),items.map { it.dlnaObjectId })
    }
    @Test fun foldersAreIncludedWhenDetectingVisibleNameCollisions() {
        assertEquals(setOf("Episode"),ambiguousMediaTitles(listOf(media("Episode.mp4")),listOf("Episode")))
    }
    @Test fun dotsInsideNamesAndCaseDoNotCreateUnrelatedCollisions() {
        val items=listOf(media("Dr.STONE.mp4"),media("Dr.flac"),media("episode.mp3"),media("Episode.wav"))
        assertTrue(ambiguousMediaTitles(items).isEmpty())
    }
    @Test fun formatLabelsDoNotDependOnDeviceLocale() {
        val original=Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("AIFF",media("Episode.aiff").formatLabel) }
        finally { Locale.setDefault(original) }
    }
}
