package jp.nagu.continuousplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DlnaAddressTest {
    @Test fun acceptsTrimmedHostsAndIpv6() {
        for ((input,host) in listOf(" 192.168.1.10 " to "192.168.1.10","nas.local" to "nas.local","[::1]" to "::1","::1" to "::1"))
            assertEquals(DlnaAddress.Host(host),DlnaAddress.parse(input))
    }
    @Test fun acceptsHttpDescriptionsAndMissingSchemeWithPathOrPort() {
        assertEquals(DlnaAddress.Description("https://nas:8200/desc.xml?token=abc%20def"),DlnaAddress.parse("https://nas:8200/desc.xml?token=abc%20def"))
        assertEquals(DlnaAddress.Description("http://nas:8200/rootDesc.xml"),DlnaAddress.parse("nas:8200/rootDesc.xml"))
        assertEquals(DlnaAddress.Description("http://nas/desc.xml"),DlnaAddress.parse("nas/desc.xml"))
        assertEquals(DlnaAddress.Description("http://nas:8200"),DlnaAddress.parse("nas:8200"))
    }
    @Test fun rejectsInvalidAndUnsupportedAddressesBeforeNetworking() {
        for (input in listOf(""," ","file:///etc/passwd","ftp://nas/file","http://user:secret@nas/desc","http://nas/#fragment","http://nas:0/","http://nas:65536/","http://nas:bad/","http://bad host/","nas\r\nHOST: other","nas?x=1","x".repeat(2049)))
            assertTrue(input,runCatching { DlnaAddress.parse(input) }.isFailure)
    }
    @Test fun descriptionKeepsOriginalLocationDespiteUrlBase() {
        val server=DlnaProtocol.server(description.replace("<device>","<URLBase>http://nas:8000/media/</URLBase><device>"),"http://nas:9000/device.xml")
        assertEquals("http://nas:9000/device.xml",server.descriptionUrl)
        assertEquals("http://nas:8000/media/control",server.controlUrl)
    }
    @Test fun connectsToDescriptionWithRealHttpGet()=runBlocking {
        http(description) { url ->
            val server=DlnaClient().connect(url)
            assertEquals("uuid:d23",server.id);assertEquals("D23 NAS",server.name)
            assertEquals(url,server.descriptionUrl);assertEquals(url.substringBeforeLast('/')+"/control",server.controlUrl)
        }
    }
    @Test fun reportsInvalidDescriptionInsteadOfAcceptingWebPage()=runBlocking {
        http("<html><body>NAS login</body></html>") { url -> assertTrue(runCatching { DlnaClient().connect(url) }.isFailure) }
    }
    @Test fun reportsHttpFailure()=runBlocking {
        http("Unavailable",503) { url -> assertTrue(runCatching { DlnaClient().connect(url) }.exceptionOrNull()!!.message!!.contains("503")) }
    }
    @Test fun directedDiscoveryUsesTargetHostAndRetrievesDescription()=runBlocking {
        http(description) { url ->
            DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use { udp ->
                udp.soTimeout=5000
                val executor=Executors.newSingleThreadExecutor()
                try {
                    val response=executor.submit<String> {
                        val packet=DatagramPacket(ByteArray(8192),8192);udp.receive(packet)
                        val request=String(packet.data,0,packet.length,Charsets.US_ASCII)
                        val bytes="HTTP/1.1 200 OK\r\nLOCATION: $url\r\n\r\n".toByteArray()
                        udp.send(DatagramPacket(bytes,bytes.size,packet.address,packet.port));request
                    }
                    val server=DlnaClient().discoverAt("127.0.0.1",udp.localPort)
                    val request=response.get(5,TimeUnit.SECONDS)
                    assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
                    assertTrue(request.contains("HOST: 127.0.0.1:${udp.localPort}\r\n"))
                    assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
                    assertFalse(request.contains("\r\nMX:"));assertFalse(request.contains("239.255.255.250"))
                    assertEquals(url,server.descriptionUrl)
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun directedDiscoveryPollsCancellationWithoutWaitingForTimeout()=runBlocking {
        DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use { udp ->
            udp.soTimeout=5000
            val job=launch(Dispatchers.IO) { DlnaClient().discoverAt("127.0.0.1",udp.localPort) }
            udp.receive(DatagramPacket(ByteArray(8192),8192))
            val before=System.nanoTime();job.cancelAndJoin()
            assertTrue(job.isCancelled);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<3000)
        }
    }
    @Test fun unavailableMulticastFailsBeforeSearching()=runBlocking {
        val client=object:DlnaClient() { override fun supportsDiscovery()=false }
        assertTrue(runCatching { client.discover() }.exceptionOrNull()!!.message!!.contains("unavailable"))
    }
    private suspend fun http(body:String,status:Int=200,action:suspend (String)->Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout=5000
            val executor=Executors.newSingleThreadExecutor()
            try {
                val response=executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader()
                        val lines=generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val bytes=body.toByteArray()
                        socket.getOutputStream().apply { write("HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
                        lines.first()
                    }
                }
                action("http://127.0.0.1:${server.localPort}/device.xml")
                assertEquals("GET /device.xml HTTP/1.1",response.get(5,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
    }
    private val description="""<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>D23 NAS</friendlyName><UDN>uuid:d23</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>control</controlURL></service></serviceList></device></root>"""
}
