package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test

class AudioOutputPolicyTest {
    private val float96 = PcmOutputFormat(4, 96_000, 3)
    private val float192 = float96.copy(sampleRate = 192_000)
    private fun bitPerfect(format: PcmOutputFormat = float96) =
        OutputMixerConfiguration(format, OutputMixerBehavior.BIT_PERFECT)
    private fun standard(format: PcmOutputFormat = float96) =
        OutputMixerConfiguration(format, OutputMixerBehavior.DEFAULT)

    private class Backend : OutputMixerBackend {
        var profiles = listOf<OutputMixerConfiguration>()
        val events = mutableListOf<Pair<String, PreferredOutputMixer?>>()
        val rejected = mutableSetOf<OutputMixerConfiguration>()
        var readFails = false
        var clearFails = false
        override fun supported(deviceId: Int): List<OutputMixerConfiguration> {
            if (readFails) error("Device query failed")
            return profiles
        }
        override fun set(preference: PreferredOutputMixer): Boolean {
            events.add("set" to preference)
            return preference.configuration !in rejected
        }
        override fun clear(deviceId: Int) {
            events.add("clear:$deviceId" to null)
            if (clearFails) error("Cannot clear")
        }
    }

    @Test fun prefersExactBitPerfectOverDefaultRegardlessOfEnumerationOrder() {
        val backend = Backend().apply { profiles = listOf(standard(), bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        assertEquals(PreferredOutputMixer(10, bitPerfect()), policy.applied)
    }

    @Test fun rejectsDifferentEncodingEvenWithSameRateAndChannels() {
        val backend = Backend().apply { profiles = listOf(bitPerfect(float96.copy(encoding = 21))) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        assertNull(policy.applied)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun rejectsDifferentChannelMaskOrRateInsteadOfUsingFirstProfile() {
        val backend = Backend().apply {
            profiles = listOf(bitPerfect(float96.copy(channelMask = 12)), bitPerfect(float192))
        }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        assertNull(policy.applied)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun triesDefaultWhenBitPerfectPreferenceIsDenied() {
        val backend = Backend().apply {
            profiles = listOf(bitPerfect(), standard())
            rejected.add(bitPerfect())
        }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        assertEquals(standard(), policy.applied?.configuration)
        assertEquals(2, backend.events.size)
    }

    @Test fun leavesSystemOutputWhenAllPreferencesAreDenied() {
        val backend = Backend().apply {
            profiles = listOf(bitPerfect(), standard())
            rejected.addAll(profiles)
        }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        assertNull(policy.applied)
    }

    @Test fun changingTrackFormatClearsOldPreferenceBeforeInstallingNewOne() {
        val backend = Backend().apply { profiles = listOf(bitPerfect(), bitPerfect(float192)) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        policy.configure(10, float192)
        assertEquals(listOf("set", "clear:10", "set"), backend.events.map { it.first })
        assertEquals(float192, policy.applied?.configuration?.format)
    }

    @Test fun deviceChangeClearsOldUsbBeforeSettingNewUsb() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        policy.configure(20, float96)
        assertEquals(listOf("set", "clear:10", "set"), backend.events.map { it.first })
        assertEquals(20, policy.applied?.deviceId)
    }

    @Test fun switchingToSpeakerOrHeadphonesClearsUsbWithoutSelectingAnotherConnectedUsb() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        policy.configure(null, float96)
        assertNull(policy.applied)
        assertEquals(listOf("set", "clear:10"), backend.events.map { it.first })
    }

    @Test fun repeatedRouteNotificationsDoNotReinstallTheSamePreference() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        repeat(5) { policy.configure(10, float96) }
        assertEquals(1, backend.events.size)
    }

    @Test fun queryFailureOrLostSupportClearsStalePreference() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        backend.readFails = true
        policy.configure(10, float96)
        assertNull(policy.applied)
        assertEquals("clear:10", backend.events.last().first)
    }

    @Test fun encodedPassthroughClearsPcmPreference() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        policy.configure(10, null)
        assertNull(policy.applied)
    }

    @Test fun initializationFailureIsNotReappliedByRouteNotifications() {
        val backend = Backend().apply { profiles = listOf(bitPerfect(), standard()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        assertTrue(policy.fallBack())
        policy.configure(10, float96)
        assertEquals(standard(), policy.applied?.configuration)
        assertTrue(policy.fallBack())
        policy.configure(10, float96)
        assertNull(policy.applied)
        assertFalse(policy.fallBack())
    }

    @Test fun reconnectAllowsRetryingAnInitializationFailure() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        policy.fallBack()
        policy.configure(10, float96)
        assertNull(policy.applied)
        policy.environmentChanged()
        policy.configure(10, float96)
        assertEquals(bitPerfect(), policy.applied?.configuration)
    }

    @Test fun releaseClearsPreferenceAndRejectsLateTrackOrDeviceCallbacks() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        policy.close()
        policy.configure(20, float96)
        assertNull(policy.applied)
        assertEquals(listOf("set", "clear:10"), backend.events.map { it.first })
    }

    @Test fun failedClearDoesNotLeavePreferencesOnTwoOutputs() {
        val backend = Backend().apply { profiles = listOf(bitPerfect()) }
        val policy = AudioOutputPolicy(backend)
        policy.configure(10, float96)
        backend.clearFails = true
        policy.configure(20, float96)
        assertEquals(10, policy.applied?.deviceId)
        assertEquals(listOf("set", "clear:10"), backend.events.map { it.first })
        backend.clearFails = false
        policy.configure(20, float96)
        assertEquals(20, policy.applied?.deviceId)
    }
}
