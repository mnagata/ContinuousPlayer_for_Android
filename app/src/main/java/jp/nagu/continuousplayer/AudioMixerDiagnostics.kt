package jp.nagu.continuousplayer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only, development-only access. No raw dumps are persisted by the app. */
internal class AudioMixerDiagnostics(private val context: Context) {
    fun accessState(): MixerDiagnosticsState = when {
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 -> MixerDiagnosticsState.DISABLED
        context.checkSelfPermission("android.permission.DUMP") != PackageManager.PERMISSION_GRANTED -> MixerDiagnosticsState.PERMISSION_REQUIRED
        else -> MixerDiagnosticsState.WAITING
    }

    fun read(sessionId: Int, deviceId: Int): MixerRouteSnapshot? {
        if (accessState() != MixerDiagnosticsState.WAITING) return null
        val policy = dump("media.audio_policy") ?: return null
        val flinger = dump("media.audio_flinger") ?: return null
        return MixerRouteParser.parse(policy, flinger, Process.myUid(), sessionId, deviceId, System.currentTimeMillis())
    }

    private fun dump(service: String): String? {
        val process = ProcessBuilder("/system/bin/dumpsys", "-t", "1", service).redirectErrorStream(true).start()
        val timeout = Executors.newSingleThreadScheduledExecutor()
        val stop = timeout.schedule({ process.destroyForcibly() }, 2, TimeUnit.SECONDS)
        return try {
            val text = process.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val chars = CharArray(8192)
                while (true) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    if (result.length + count > 2_000_000) return null
                    result.append(chars, 0, count)
                }
                result.toString()
            }
            if (!process.waitFor(200, TimeUnit.MILLISECONDS) || process.exitValue() != 0 ||
                text.contains("Permission Denial") || text.contains("DUMP TIMEOUT")) null else text
        } finally {
            stop.cancel(false)
            timeout.shutdownNow()
            process.destroy()
        }
    }
}
