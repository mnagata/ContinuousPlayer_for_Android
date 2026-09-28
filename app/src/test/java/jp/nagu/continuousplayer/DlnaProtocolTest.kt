package jp.nagu.continuousplayer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DlnaProtocolTest {
    @Test
    fun discoveryAcceptsCaseInsensitiveLocationAndRejectsNonHttp() {
        assertEquals("http://192.168.1.10:50001/desc.xml", DlnaProtocol.location(
            "HTTP/1.1 200 OK\r\nlOcAtIoN: http://192.168.1.10:50001/desc.xml\r\n\r\n"))
        assertNull(DlnaProtocol.location("HTTP/1.1 404 Not Found\r\nLocation: http://nas/desc.xml"))
        assertNull(DlnaProtocol.location("HTTP/1.1 200 OK\r\nLocation: file:///etc/passwd"))
        assertNull(DlnaProtocol.location(""))
    }

    @Test
    fun descriptionUsesUrlBaseAndTheDeviceOwningContentDirectory() {
        val description = """<root xmlns="urn:schemas-upnp-org:device-1-0">
            <URLBase>http://192.168.1.10:50001/media/</URLBase>
            <device><friendlyName>Router</friendlyName><UDN>uuid:router</UDN>
              <deviceList><device><friendlyName>DiskStation</friendlyName><UDN>uuid:nas</UDN>
                <serviceList><service>
                  <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                  <controlURL>control</controlURL>
                </service></serviceList>
              </device></deviceList>
            </device></root>"""
        val server = DlnaProtocol.server(description, "http://192.168.1.10:50001/desc.xml")
        assertEquals("DiskStation", server.name)
        assertEquals("uuid:nas", server.id)
        assertEquals("http://192.168.1.10:50001/media/control", server.controlUrl)
        assertEquals("http://nas/control", DlnaProtocol.server(description.replace(
            "<URLBase>http://192.168.1.10:50001/media/</URLBase>", "").replace(
            "<controlURL>control</controlURL>", "<controlURL>/control</controlURL>"), "http://nas/desc.xml").controlUrl)
    }

    @Test
    fun browseParsesEscapedDidlAndChoosesOriginalHttpResourceWithoutExtension() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <container id="folder"><dc:title>動画 &amp; 音声</dc:title></container>
          <item id="op"><dc:title>作品 OP</dc:title>
            <res protocolInfo="http-get:*:video/mp2t:DLNA.ORG_CI=1">/transcoded/1</res>
            <res protocolInfo="rtsp-rtp-udp:*:video/mp4:*">rtsp://nas/1</res>
            <res size="12345" protocolInfo="http-get:*:video/mp4:DLNA.ORG_CI=0">/original/1?x=1&amp;y=2</res>
          </item>
          <item id="audio"><dc:title>作品 ED</dc:title><res protocolInfo="http-get:*:audio/flac:*">http://nas/audio/2</res></item>
          <item id="photo"><dc:title>写真</dc:title><res protocolInfo="http-get:*:image/jpeg:*">/photo/1</res></item>
          <item id="sidecar"><dc:title>._作品 OP.mp4</dc:title><res protocolInfo="http-get:*:video/mp4:*">/bad</res></item>
        </DIDL-Lite>"""
        val page = DlnaProtocol.page(response(didl, 5, 5), "http://nas/control")
        assertEquals(5, page.returned) // Includes filtered items for correct paging offsets.
        assertEquals(listOf("folder", "op", "audio"), page.entries.map { it.id })
        assertEquals("動画 & 音声", page.entries[0].title)
        val video = requireNotNull(page.entries[1].media)
        assertEquals("http://nas/original/1?x=1&y=2", video.uri)
        assertEquals("video/mp4", video.mimeType)
        assertEquals(12345L, video.size)
        assertEquals(-1L, page.entries[2].media!!.size)
    }

    @Test
    fun octetStreamRequiresSupportedExtensionAndNonHttpResourcesAreIgnored() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <item id="1"><dc:title>作品 OP.mp4</dc:title><res protocolInfo="http-get:*:application/octet-stream:*">/file/1</res></item>
          <item id="2"><dc:title>Unknown</dc:title><res protocolInfo="http-get:*:application/octet-stream:*">/file/2</res></item>
          <item id="3"><dc:title>作品 ED.mp4</dc:title><res protocolInfo="http-get:*:video/mp4:*">file:///private/movie.mp4</res></item>
        </DIDL-Lite>"""
        val page = DlnaProtocol.page(response(didl, 3, 3), "http://nas/control")
        assertEquals(listOf("1"), page.entries.map { it.id })
        assertNull(page.entries.single().media!!.mimeType)
    }

    @Test
    fun requestEscapesObjectIdsAndUsesBrowseDirectChildren() {
        val request = DlnaProtocol.browseRequest(server("http://nas/control"), "a<&\"'", 200, 200)
        assertTrue(request.contains("<ObjectID>a&lt;&amp;&quot;&apos;</ObjectID>"))
        assertTrue(request.contains("<BrowseFlag>BrowseDirectChildren</BrowseFlag>"))
        assertTrue(request.contains("<StartingIndex>200</StartingIndex>"))
    }

    @Test
    fun rejectsExternalEntitiesAndReportsUpnpFaults() {
        assertThrows(IOException::class.java) { DlnaProtocol.server(
            "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>", "http://nas/desc") }
        val error = assertThrows(IOException::class.java) { DlnaProtocol.page(
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail>
              <UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>No Such Object</errorDescription></UPnPError>
            </detail></s:Fault></s:Body></s:Envelope>""", "http://nas/control") }
        assertTrue(error.message!!.contains("701 No Such Object"))
        assertThrows(IOException::class.java) { DlnaProtocol.page("<root/>", "http://nas/control") }
    }

    @Test
    fun clientFetchesEveryPageUsingNumberReturnedRatherThanFilteredEntryCount() = runBlocking {
        ServerSocket(0).use { listener ->
            listener.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val requests = executor.submit<List<String>> {
                    (0..1).map { index ->
                        listener.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            val count = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            val chars = CharArray(count)
                            var offset = 0
                            while (offset < count) {
                                val read = reader.read(chars, offset, count - offset)
                                check(read > 0)
                                offset += read
                            }
                            val didl = if (index == 0) """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
                              <container id="folder"/><item id="photo"><res protocolInfo="http-get:*:image/jpeg:*">/photo</res></item>
                            </DIDL-Lite>""" else """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
                              <item id="video"><res protocolInfo="http-get:*:video/mp4:*">/video</res></item>
                            </DIDL-Lite>"""
                            val bytes = response(didl, if (index == 0) 2 else 1, 3).toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                            assertTrue(headers.any { it.contains("ContentDirectory:1#Browse") })
                            String(chars)
                        }
                    }
                }
                val entries = DlnaClient().browse(server("http://127.0.0.1:${listener.localPort}/control"), "0&1")
                assertEquals(listOf("folder", "video"), entries.map { it.id })
                val bodies = requests.get(5, TimeUnit.SECONDS)
                assertTrue(bodies[0].contains("<ObjectID>0&amp;1</ObjectID>"))
                assertTrue(bodies[0].contains("<StartingIndex>0</StartingIndex>"))
                assertTrue(bodies[1].contains("<StartingIndex>2</StartingIndex>"))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun server(url: String) = DlnaServer("uuid:nas", "DiskStation", url,
        "urn:schemas-upnp-org:service:ContentDirectory:1")

    private fun response(didl: String, returned: Int, total: Int): String {
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
          <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>$escaped</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches><UpdateID>1</UpdateID>
          </u:BrowseResponse></s:Body></s:Envelope>"""
    }
}
