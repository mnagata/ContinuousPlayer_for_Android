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

/** Owns focus for a live session, including temporary pauses in the file browser. */
internal class PlaybackAudioMonitor(
    private val context: Context,
    private val onInterrupted: () -> Unit
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
    private var routedIds = emptySet<Int>()
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
            if (!released && track?.routedDevice == null) refreshAnticipatedRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onOutputsRemoved(removedDevices.filter { it.isSink }.map { it.id }.toSet())
        }
    }
    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        if (!released && router === track) {
            // Routing can fall back to the speaker before the removal callback arrives.
            onOutputsRemoved(routedIds - manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet())
            router.routedDevice?.let { routedIds = setOf(it.id) }
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
        return hasFocus
    }

    /** AudioTrack construction runs on Media3's playback thread; all state stays on main. */
    fun observeTrack(audioTrack: AudioTrack) {
        handler.post {
            if (released) return@post
            track?.removeOnRoutingChangedListener(routingListener)
            track = audioTrack
            audioTrack.addOnRoutingChangedListener(routingListener, handler)
            audioTrack.routedDevice?.let { routedIds = setOf(it.id) }
        }
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
        abandonFocus()
        track?.removeOnRoutingChangedListener(routingListener)
        track = null
        manager.unregisterAudioDeviceCallback(devices)
        context.unregisterReceiver(noisyReceiver)
    }
}
