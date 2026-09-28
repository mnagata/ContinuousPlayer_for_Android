package jp.nagu.continuousplayer

import java.io.File

/** The folder that supplied the playlist, independent of subsequent browsing. */
internal sealed interface PlaybackFolder {
    data class Saf(val path: List<SafFolder>) : PlaybackFolder
    data class Dlna(val folder: SavedDlnaFolder) : PlaybackFolder
    data class Usb(val root: File, val directory: File) : PlaybackFolder
}
