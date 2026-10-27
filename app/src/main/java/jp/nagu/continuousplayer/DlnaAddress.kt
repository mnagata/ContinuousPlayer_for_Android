package jp.nagu.continuousplayer

import java.net.URI
import java.util.Locale

/** A bare host uses directed discovery; an HTTP(S) URL identifies a device description. */
internal sealed interface DlnaAddress {
    data class Host(val name: String) : DlnaAddress
    data class Description(val url: String) : DlnaAddress

    companion object {
        fun parse(input: String): DlnaAddress {
            val value = input.trim()
            require(value.isNotEmpty() && value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() })
            val hasScheme = value.contains("://")
            val looksLikeUrl = hasScheme || value.contains('/') || Regex("^[^:]+:[0-9]+$").matches(value)
            if (looksLikeUrl) {
                val uri = URI(if (hasScheme) value else "http://$value")
                require(uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank())
                require(uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535))
                return Description(uri.toASCIIString())
            }
            val uri = URI("http://" + if (value.contains(':') && !value.startsWith('[')) "[$value]" else value)
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty())
            return Host(uri.host.removePrefix("[").removeSuffix("]"))
        }
    }
}
