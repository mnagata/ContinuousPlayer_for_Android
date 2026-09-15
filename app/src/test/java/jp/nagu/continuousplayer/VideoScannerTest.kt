package jp.nagu.continuousplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScannerTest {
    @Test
    fun excludesAppleDoubleSidecarsForAllSupportedExtensions() {
        for (extension in listOf("mp4", "m4v", "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")) {
            assertFalse(VideoScanner.isSupportedFile("._作品 OP.$extension"))
            assertFalse(VideoScanner.isSupportedFile("._作品 ED.${extension.uppercase()}"))
            assertTrue(VideoScanner.isSupportedFile("作品 OP.$extension"))
            assertTrue(VideoScanner.isSupportedFile("作品 ED.${extension.uppercase()}"))
        }
    }

    @Test
    fun preservesOrdinaryHiddenFilesAndNonPrefixUnderscores() {
        for (name in listOf(".hidden.mp4", "_作品 OP.mp4", "作品._OP.mp4")) {
            assertTrue(name, VideoScanner.isSupportedFile(name))
        }
    }

    @Test
    fun excludesUnsupportedAndMissingExtensions() {
        for (name in listOf("", "作品 OP", "mp4", ".DS_Store", "作品.txt", "作品.mp4.tmp")) {
            assertFalse(name, VideoScanner.isSupportedFile(name))
        }
    }
}
