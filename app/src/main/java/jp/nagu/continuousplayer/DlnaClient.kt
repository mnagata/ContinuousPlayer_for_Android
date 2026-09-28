package jp.nagu.continuousplayer

import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Read-only UPnP MediaServer client. All methods run on Dispatchers.IO. */
open class DlnaClient {
    open suspend fun discover(): List<DlnaServer> {
        val locations = linkedSetOf<String>()
        MulticastSocket().use { socket ->
            socket.timeToLive = 2
            socket.soTimeout = 300
            val request = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
            val packet = DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900)
            val end = System.nanoTime() + 4_000_000_000L
            var nextSearch = 0L
            while (System.nanoTime() < end) {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= nextSearch) {
                    socket.send(packet)
                    nextSearch = System.nanoTime() + 1_500_000_000L
                }
                val response = DatagramPacket(ByteArray(16_384), 16_384)
                try {
                    socket.receive(response)
                    DlnaProtocol.location(String(response.data, 0, response.length, Charsets.UTF_8))
                        ?.let { if (locations.size < 32) locations.add(it) }
                } catch (_: SocketTimeoutException) {
                    // Poll cancellation and resend to tolerate lost UDP packets.
                }
            }
        }
        val servers = mutableListOf<DlnaServer>()
        var failure: IOException? = null
        for (location in locations) {
            coroutineContext.ensureActive()
            try {
                servers.add(DlnaProtocol.server(request(location), location))
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                failure = IOException("サーバー情報を取得できません: ${error.message}", error)
            }
        }
        if (servers.isEmpty() && failure != null) throw failure
        return servers.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    open suspend fun browse(server: DlnaServer, objectId: String): List<DlnaEntry> {
        val entries = linkedMapOf<String, DlnaEntry>()
        var start = 0
        var previousIds: List<String>? = null
        do {
            coroutineContext.ensureActive()
            val body = DlnaProtocol.browseRequest(server, objectId, start, 200)
            val page = DlnaProtocol.page(request(server.controlUrl, body, server.serviceType), server.controlUrl)
            if (page.returned == 0) {
                if (start < page.total) throw IOException("一覧の取得が途中で停止しました。再読み込みしてください")
                break
            }
            val ids = page.entries.map { it.id }
            if (ids.isNotEmpty() && ids == previousIds) throw IOException("サーバーが同じページを返しました")
            previousIds = ids
            page.entries.forEach { entries[it.id] = it }
            if (page.returned > 100_000 - start) throw IOException("一覧が大きすぎます")
            start += page.returned
            if (start >= page.total) break
            if (start >= 100_000) throw IOException("一覧が大きすぎます。NASのフォルダーを分割してください")
        } while (true)
        return entries.values.toList()
    }

    private suspend fun request(url: String, body: String? = null, serviceType: String? = null): String {
        coroutineContext.ensureActive()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ContinuousPlayer/1.0 UPnP/1.0 DLNADOC/1.50")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                connection.setRequestProperty("SOAPAction", "\"$serviceType#Browse\"")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val xml = stream?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = it.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 4 * 1024 * 1024) throw IOException("サーバー応答が大きすぎます")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            if (status !in 200..299) {
                if (body != null && xml.isNotBlank()) DlnaProtocol.page(xml, url) // Report SOAP faults.
                throw IOException("HTTP $status")
            }
            return xml
        } finally {
            connection.disconnect()
        }
    }
}
