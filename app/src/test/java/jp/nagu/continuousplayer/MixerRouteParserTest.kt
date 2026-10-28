package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test

class MixerRouteParserTest {
    private val policy = "\n" + """
        Outputs (2):
          1. Port ID: 7; I/O handle: 21; IOProfile name:low_latency_out; Latency: 43;
             AUDIO_FORMAT_PCM_16_BIT; 48000; Channel mask: 0x3
          2. Port ID: 1442; I/O handle: 2589; IOProfile name:deep_buffer_hires96_out; Latency: 129;
             AUDIO_FORMAT_PCM_FLOAT; 96000; Channel mask: 0x3
             Global active count: 1
           - AudioTrack clients (1):
              1. Port ID: 1453; Session ID: 9169; uid 10507; State: Active
                 AUDIO_FORMAT_PCM_FLOAT; 96000; Channel mask: 0x3
        Inputs (0):
        Audio Patches (1):
          1. owner uid 1041; handle 1443; HAL handle 3972
             [src  1] Mix Port ID: 1442; I/O handle: 2589;
             [sink 1] Device Port ID: 1436; {AUDIO_DEVICE_OUT_WIRED_HEADPHONE, @:}
        Audio Policy Mix:
    """.trimIndent().lines().joinToString("\n") { " $it" }
        // dumpsys output headers use two spaces, not the top-level section's one.
        .replace("   1. Port", "  1. Port").replace("   2. Port", "  2. Port")
        .replace("   1. owner", "  1. owner")

    private val flinger = """Output thread 0x123, name AudioOut_A1D, tid 16885, type 0 (MIXER):
  I/O handle: 2589
  Standby: no
  Sample rate: 96000 Hz
  HAL format: 0x5 (AUDIO_FORMAT_PCM_FLOAT)
  Channel count: 2
  Processing format: 0x5 (AUDIO_FORMAT_PCM_FLOAT)
    Type Id Active Client(pid/uid) Session Port Id S Flags Format BitPerfect InternalMute Latency
            942 yes 18684/ 10507 9169 1453 A 0x000 00000005 false false 649.43 t
"""

    private fun parse(p: String = policy, f: String = flinger, uid: Int = 10507,
        session: Int = 9169, device: Int = 1436) =
        MixerRouteParser.parse(p, f, uid, session, device, 123456L)

    @Test fun identifiesTheActiveTrackAndPhysicalPatch() {
        val actual = parse()!!
        assertEquals("deep_buffer_hires96_out", actual.profile)
        assertEquals("MIXER", actual.type)
        assertEquals(2589, actual.ioHandle)
        assertEquals(1436, actual.deviceId)
        assertEquals(DiagnosticPcmFormat("PCM Float", 96000, 2), actual.mixerFormat)
        assertEquals("PCM Float / 96000 Hz / 2 ch", actual.halFormat.display())
        assertEquals(123456L, actual.confirmedAtMs)
    }

    @Test fun halEncodingIsReadIndependentlyFromMixerEncoding() {
        val actual = parse(f = flinger.replace("HAL format: 0x5 (AUDIO_FORMAT_PCM_FLOAT)",
            "HAL format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)"))!!
        assertEquals("PCM Float", actual.mixerFormat.encoding)
        assertEquals("PCM 16-bit", actual.halFormat.encoding)
        val primary = parse(policy.replace("deep_buffer_hires96_out", "primary output")
            .replace("PCM_FLOAT", "PCM_16_BIT"),
            flinger.replace("HAL format: 0x5 (AUDIO_FORMAT_PCM_FLOAT)",
                "HAL format: 0x1 (AUDIO_FORMAT_PCM_16_BIT)"))!!
        assertEquals("primary output", primary.profile)
        assertEquals("PCM Float", primary.mixerFormat.encoding)
    }

    @Test fun recognizesUsbInteger32AndBitPerfectThread() {
        val actual = parse(policy.replace("PCM_FLOAT", "PCM_32_BIT")
            .replace("deep_buffer_hires96_out", "bit_perfect_playback"),
            flinger.replace("type 0 (MIXER)", "type 8 (BIT_PERFECT)")
                .replace("0x5 (AUDIO_FORMAT_PCM_FLOAT)", "0x3 (AUDIO_FORMAT_PCM_32_BIT)"))!!
        assertEquals("BIT_PERFECT", actual.type)
        assertEquals("PCM 32-bit", actual.halFormat.encoding)
    }

    @Test fun otherApplicationsSessionsAndOutputDevicesDoNotQualify() {
        assertNull(parse(uid = 10508))
        assertNull(parse(session = 9170))
        assertNull(parse(device = 1437))
        assertNull(parse(f = flinger.replace("10507 9169", "10508 9169")))
        assertNull(parse(f = flinger.replace("9169 1453", "9169 1454")))
        assertNull(parse(p = policy.replace("I/O handle: 2589;\n", "I/O handle: 2590;\n")))
    }

    @Test fun pausedOrStandbyTracksAreNotPresentedAsLiveMeasurements() {
        assertNull(parse(p = policy.replace("State: Active", "State: Inactive")))
        assertNull(parse(f = flinger.replace("Standby: no", "Standby: yes")))
        assertNull(parse(f = flinger.replace("942 yes", "942 no")))
    }

    @Test fun cachedFlingerThreadsCannotSupplyMissingLiveEvidence() {
        assertNull(parse(f = "Historical Thread Log\n" + flinger))
        assertNull(parse(f = flinger.replace("I/O handle: 2589", "I/O handle: 2590") +
            "Historical Thread Log\n" + flinger))
    }

    @Test fun formatChangeBetweenDumpsAndUnknownLayoutsFailClosed() {
        assertNull(parse(f = flinger.replace("96000 Hz", "192000 Hz")))
        assertNull(parse(f = flinger.replace("Channel count: 2", "Channel count: 1")))
        assertNull(parse(f = flinger.replace("AUDIO_FORMAT_PCM_FLOAT", "AUDIO_FORMAT_UNKNOWN")))
        assertNull(parse(p = "Permission Denial: requires android.permission.DUMP"))
        assertNull(parse(f = "*** DUMP TIMEOUT ***"))
        assertNull(parse(uid = 0))
    }

    @Test fun multipleActiveOutputsAreAmbiguous() {
        val duplicate = policy.substringAfter("\n  2. Port ID:").substringBefore("\n Inputs (")
        assertNull(parse(p = policy.replace("\n Inputs (", "\n  3. Port ID:$duplicate\n Inputs (")))
    }
}
