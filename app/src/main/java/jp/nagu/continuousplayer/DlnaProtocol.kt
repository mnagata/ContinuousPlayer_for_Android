package jp.nagu.continuousplayer

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaServer(
    val id: String,
    val name: String,
    val controlUrl: String,
    val serviceType: String
)

data class DlnaEntry(val id: String, val title: String, val media: VideoItem? = null)
data class DlnaPage(val entries: List<DlnaEntry>, val returned: Int, val total: Int)

/** UPnP XML and SSDP parsing, independent of Android for protocol tests. */
internal object DlnaProtocol {
    private const val DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:"

    fun location(response: String): String? {
        if (!response.lineSequence().first().trim().matches(Regex("HTTP/1\\.[01] 200.*"))) return null
        return response.lineSequence().drop(1).firstNotNullOfOrNull { line ->
            if (line.substringBefore(':').trim().equals("location", true)) {
                line.substringAfter(':').trim().takeIf { isHttp(it) }
            } else null
        }
    }

    fun server(xml: String, location: String): DlnaServer {
        val root = parse(xml)
        val base = root.childText("URLBase").ifBlank { location }
        val devices = root.getElementsByTagNameNS("*", "device")
        for (index in 0 until devices.length) {
            val device = devices.item(index) as Element
            val services = device.children("serviceList").flatMap { it.children("service") }
            val service = services.firstOrNull { it.childText("serviceType").startsWith(DIRECTORY) }
                ?: continue
            val control = service.childText("controlURL")
            if (control.isBlank()) continue
            return DlnaServer(
                device.childText("UDN").ifBlank { location },
                device.childText("friendlyName").ifBlank { URI(location).host },
                resolve(base, control), service.childText("serviceType")
            )
        }
        throw IOException("ContentDirectoryサービスが見つかりません")
    }

    fun browseRequest(server: DlnaServer, objectId: String, start: Int, count: Int): String =
        """<?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body><u:Browse xmlns:u="${escape(server.serviceType)}">
            <ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>
            <Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>
          </u:Browse></s:Body>
        </s:Envelope>""".trimIndent()

    fun page(xml: String, base: String): DlnaPage {
        val root = parse(xml)
        val faults = root.getElementsByTagNameNS("*", "Fault")
        if (faults.length > 0) {
            val fault = faults.item(0) as Element
            throw IOException("UPnP: ${fault.text("errorCode")} ${fault.text("errorDescription")}".trim())
        }
        val response = root.getElementsByTagNameNS("*", "BrowseResponse").item(0) as? Element
            ?: throw IOException("BrowseResponseがありません")
        val returned = response.childText("NumberReturned").toIntOrNull()
            ?.takeIf { it >= 0 } ?: throw IOException("NumberReturnedが不正です")
        val total = response.childText("TotalMatches").toIntOrNull()
            ?.takeIf { it >= 0 } ?: throw IOException("TotalMatchesが不正です")
        val result = response.childText("Result")
        val entries = if (result.isBlank()) emptyList() else {
            val didl = parse(result)
            didl.children().mapNotNull { element ->
                val id = element.getAttribute("id")
                val title = element.childText("title").ifBlank { id }
                when (element.localName) {
                    "container" -> DlnaEntry(id, title)
                    "item" -> media(element, title, base)?.let { DlnaEntry(id, title, it) }
                    else -> null
                }
            }
        }
        return DlnaPage(entries, returned, total)
    }

    private fun media(item: Element, title: String, base: String): VideoItem? {
        if (title.startsWith("._")) return null
        val resources = item.children("res").mapNotNull { res ->
            val protocol = res.getAttribute("protocolInfo").split(':', limit = 4)
            if (protocol.size != 4 || !protocol[0].equals("http-get", true)) return@mapNotNull null
            val mime = protocol[2].lowercase().substringBefore(';')
            val url = runCatching { resolve(base, res.textContent.trim()) }.getOrNull()
                ?: return@mapNotNull null
            val isMedia = mime.startsWith("video/") || mime.startsWith("audio/") ||
                ((mime == "application/octet-stream" || mime == "*") &&
                    (VideoScanner.isSupportedFile(title) || VideoScanner.isSupportedFile(URI(url).path.orEmpty())))
            if (!isMedia) return@mapNotNull null
            Triple(res, mime, url)
        }.sortedBy { if (it.first.getAttribute("protocolInfo").contains("DLNA.ORG_CI=1")) 1 else 0 }
        val (res, mime, url) = resources.firstOrNull() ?: return null
        return VideoItem(url, title, res.getAttribute("size").toLongOrNull() ?: -1L, 0L,
            mime.takeIf { it.startsWith("audio/") || it.startsWith("video/") })
    }

    private fun parse(xml: String): Element {
        // No network-provided DTDs or entities. Android and JVM parsers expose different features.
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            throw IOException("DTD / 外部エンティティは使用できません")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(xml))).documentElement
    }

    private fun Element.children(name: String? = null): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { name == null || it.localName == name }

    private fun Element.childText(name: String) = children(name).firstOrNull()?.textContent.orEmpty().trim()
    private fun Element.text(name: String) = getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty()
    private fun resolve(base: String, value: String): String {
        val resolved = URI(base).resolve(value).toString()
        if (!isHttp(resolved)) throw IOException("HTTP以外のURLは使用できません")
        return resolved
    }
    private fun isHttp(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null
    }.getOrDefault(false)
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
