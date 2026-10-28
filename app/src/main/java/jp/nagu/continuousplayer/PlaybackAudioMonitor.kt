package jp.nagu.continuousplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Owns focus for a live session, including temporary pauses in the file browser. */
internal class PlaybackAudioMonitor(
    private val context: Context,
    private val onInterrupted: () -> Unit,
    private val outputPreferences: BitPerfectAudioManager
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var released = false
    private var hasFocus = false
    private var track: AudioTrack? = null
    private var trackGeneration = 0L
    private var lastRoutedDevice: AudioDeviceInfo? = null
    private var lastRouteConfirmedAtMs: Long? = null
    private var routedIds = emptySet<Int>()
    private val mixerDiagnostics = AudioMixerDiagnostics(context.applicationContext)
    private val diagnosticsWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "AudioMixerDiagnostics").apply { isDaemon = true }
    }
    private var diagnosticsBusy = false
    private var mixerSnapshot: MixerRouteSnapshot? = null
    private var mixerState = mixerDiagnostics.accessState()
    private val diagnosticsSampler = object : Runnable {
        override fun run() {
            if (released) return
            handler.postDelayed(this, 3000L)
            val access = mixerDiagnostics.accessState()
            if (access != MixerDiagnosticsState.WAITING) {
                mixerState = access
                mixerSnapshot = null
                return
            }
            val current = track ?: return
            if (diagnosticsBusy) return
            val device = runCatching {
                current.routedDevice?.takeIf { current.playState == AudioTrack.PLAYSTATE_PLAYING }
            }.getOrNull() ?: return
            rememberRoute(device)
            val session = runCatching { current.audioSessionId }.getOrNull()?.takeIf { it > 0 } ?: return
            val generation = trackGeneration
            diagnosticsBusy = true
            diagnosticsWorker.execute {
                val result = runCatching { mixerDiagnostics.read(session, device.id) }
                    .onFailure { Log.w("PlaybackAudio", "Mixer diagnostics unavailable", it) }.getOrNull()
                handler.post {
                    diagnosticsBusy = false
                    // A dump may finish after a pause, format change, reroute or release.
                    if (released || track !== current || trackGeneration != generation ||
                        !runCatching { current.playState == AudioTrack.PLAYSTATE_PLAYING &&
                            current.routedDevice?.id == device.id }.getOrDefault(false)) return@post
                    mixerSnapshot = result
                    mixerState = if (result == null) MixerDiagnosticsState.UNAVAILABLE else MixerDiagnosticsState.WAITING
                }
            }
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            Log.d("PlaybackAudio", "Focus changed: $change")
            if (!released && change in setOf(AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                interrupt()
            }
            // Focus gain is never a playback instruction.
        }, handler)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!released) {
                if (track?.routedDevice == null) refreshAnticipatedRoute()
                outputPreferences.refreshOutput(environmentChanged = true)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onOutputsRemoved(removedDevices.filter { it.isSink }.map { it.id }.toSet())
            if (!released) outputPreferences.refreshOutput(environmentChanged = true)
        }
    }
    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        if (!released && router === track) {
            // Routing can fall back to the speaker before the removal callback arrives.
            onOutputsRemoved(routedIds - manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet())
            val observed = router.routedDevice?.takeIf { track?.playState == AudioTrack.PLAYSTATE_PLAYING }
            observed?.let(::rememberRoute)
            outputPreferences.onRouteChanged(trackGeneration, observed)
        }
    }

    init {
        refreshAnticipatedRoute()
        context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_EXPORTED) // This protected broadcast is sent by the system.
        manager.registerAudioDeviceCallback(devices, handler)
    }

    /** Called before every Play, including MediaSession and PlayerView commands. */
    fun requestFocus(): Boolean {
        if (released) return false
        if (!hasFocus) {
            val result = manager.requestAudioFocus(focusRequest)
            Log.d("PlaybackAudio", "Focus request result: $result")
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!hasFocus) onInterrupted()
        if (track?.routedDevice == null) refreshAnticipatedRoute()
        outputPreferences.refreshOutput()
        return hasFocus
    }

    /** AudioTrack construction runs on Media3's playback thread; all state stays on main. */
    fun observeTrack(audioTrack: AudioTrack, generation: Long) {
        handler.post {
            if (released) return@post
            track?.removeOnRoutingChangedListener(routingListener)
            track = audioTrack
            trackGeneration = generation
            lastRoutedDevice = null
            lastRouteConfirmedAtMs = null
            mixerSnapshot = null
            mixerState = mixerDiagnostics.accessState()
            handler.removeCallbacks(diagnosticsSampler)
            handler.postDelayed(diagnosticsSampler, 500L)
            audioTrack.addOnRoutingChangedListener(routingListener, handler)
            val observed = audioTrack.routedDevice?.takeIf { audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING }
            observed?.let(::rememberRoute)
            outputPreferences.onRouteChanged(generation, observed)
        }
    }

    /** Only an observed AudioTrack route is reported; connected/policy devices are not substitutes. */
    internal fun outputInformation(): AudioTrackInformation {
        val current = track?.takeUnless { released } ?: return AudioTrackInformation()
        // Some implementations retain a routed device while paused. It is not a live observation.
        val routed = runCatching {
            if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.routedDevice else null
        }.getOrNull()
        if (routed != null) rememberRoute(routed)
        val device = (routed ?: lastRoutedDevice)?.takeIf { known ->
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.id == known.id } }.getOrDefault(false)
        }
        return AudioTrackInformation(device,
            runCatching { current.sampleRate }.getOrNull(),
            runCatching { current.channelCount }.getOrNull(),
            runCatching { current.audioFormat }.getOrNull(),
            runCatching { current.format.channelMask }.getOrNull(),
            if (device == null) null else if (routed != null) AudioRouteObservation.LIVE else AudioRouteObservation.LAST_KNOWN,
            if (device == null) null else lastRouteConfirmedAtMs,
            mixerSnapshot?.takeIf { device?.id == it.deviceId &&
                it.sessionId == runCatching { current.audioSessionId }.getOrNull() },
            mixerState)
    }

    private fun rememberRoute(device: AudioDeviceInfo) {
        if (lastRoutedDevice?.id != device.id) {
            mixerSnapshot = null
            mixerState = mixerDiagnostics.accessState()
        }
        routedIds = setOf(device.id)
        lastRoutedDevice = device
        lastRouteConfirmedAtMs = System.currentTimeMillis()
    }

    internal fun onOutputsRemoved(removedIds: Set<Int>) {
        if (!released && routedIds.any { it in removedIds }) interrupt()
    }

    fun abandonFocus() {
        hasFocus = false
        manager.abandonAudioFocusRequest(focusRequest)
    }

    private fun interrupt() {
        if (released) return
        abandonFocus()
        onInterrupted()
    }

    private fun refreshAnticipatedRoute() {
        routedIds = manager.getAudioDevicesForAttributes(attributes).map { it.id }.toSet()
    }

    fun release() {
        released = true
        handler.removeCallbacks(diagnosticsSampler)
        diagnosticsWorker.shutdownNow()
        mixerSnapshot = null
        abandonFocus()
        track?.removeOnRoutingChangedListener(routingListener)
        track = null
        lastRoutedDevice = null
        lastRouteConfirmedAtMs = null
        manager.unregisterAudioDeviceCallback(devices)
        context.unregisterReceiver(noisyReceiver)
    }
}

internal data class AudioTrackInformation(
    val device: AudioDeviceInfo? = null, val sampleRate: Int? = null,
    val channels: Int? = null, val encoding: Int? = null, val channelMask: Int? = null,
    val routeObservation: AudioRouteObservation? = null, val routeConfirmedAtMs: Long? = null,
    val mixerSnapshot: MixerRouteSnapshot? = null, val mixerDiagnosticsState: MixerDiagnosticsState? = null
)
