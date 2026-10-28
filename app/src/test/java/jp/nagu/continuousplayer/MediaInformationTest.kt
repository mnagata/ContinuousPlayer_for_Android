package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test

class MediaInformationTest {
    private fun info(size:Long=-1,duration:Long=-1,video:MediaTrackInformation?=null,audio:MediaTrackInformation?=null,hasVideo:Boolean?=null,hasAudio:Boolean?=null)=
        MediaInformation("Original.part.1.mp4",size,duration,video=video,audio=audio,hasVideo=hasVideo,hasAudio=hasAudio)
    private fun value(rows:List<InformationRow>,label:Int)=rows.single { it.label==label }.value
    private fun text(value:InformationValue)=(value as InformationValue.Text).value

    @Test fun routeConfirmationTimeUsesTheDeviceTimeZone() {
        val time=java.time.Instant.parse("2026-10-28T00:01:02Z").toEpochMilli()
        assertEquals("09:01:02",text(MediaInformationText.routeConfirmationTime(time,java.time.ZoneId.of("Asia/Tokyo"))))
        assertEquals("00:01:02",text(MediaInformationText.routeConfirmationTime(time,java.time.ZoneId.of("UTC"))))
    }
    @Test fun missingConfirmationDoesNotInventTheCurrentTime() {
        assertEquals(InformationValue.Unavailable,MediaInformationText.routeConfirmationTime(null))
        assertEquals(InformationValue.Unavailable,MediaInformationText.routeConfirmationTime(-1))
    }

    @Test fun unknownSizeAndDurationAreUnavailableRatherThanNegativeNumbers() {
        val rows=MediaInformationText.file(info(size=-1,duration=-9223372036854775807L))
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_file_size))
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_duration))
    }
    @Test fun zeroByteSizeAndDurationAreKnownValues() {
        val rows=MediaInformationText.file(info(0,0))
        assertEquals("0 B",text(value(rows,R.string.info_file_size)))
        assertEquals("0:00",text(value(rows,R.string.info_duration)))
    }
    @Test fun sizesUseBinaryUnitsAndHandleLargeValues() {
        for((size,expected) in listOf(1023L to "1023 B",1024L to "1.0 KiB",1048576L to "1.0 MiB",1073741824L to "1.0 GiB"))
            assertEquals(expected,text(value(MediaInformationText.file(info(size)),R.string.info_file_size)))
        assertFalse(text(value(MediaInformationText.file(info(Long.MAX_VALUE)),R.string.info_file_size)).contains("-"))
    }
    @Test fun durationKeepsHoursAndMinutes() {
        assertEquals("1:30",text(value(MediaInformationText.file(info(duration=90_000)),R.string.info_duration)))
        assertEquals("2:03:04",text(value(MediaInformationText.file(info(duration=7_384_000)),R.string.info_duration)))
    }
    @Test fun missingCodecFallsBackToMimeWithoutGuessing() {
        val rows=MediaInformationText.formats(info(audio=MediaTrackInformation(mime="audio/flac")))
        assertEquals("audio/flac",text(value(rows,R.string.info_audio_codec)))
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_video_codec))
    }
    @Test fun codecAndMimeAreBothAvailableWithoutDuplicates() {
        val rows=MediaInformationText.formats(info(video=MediaTrackInformation(codec="avc1.640028",mime="video/avc"),audio=MediaTrackInformation(codec="audio/raw",mime="audio/raw")))
        assertEquals("avc1.640028 / video/avc",text(value(rows,R.string.info_video_codec)))
        assertEquals("audio/raw",text(value(rows,R.string.info_audio_codec)))
    }
    @Test fun partialResolutionAndInvalidFrameRatesRemainUnavailable() {
        for(fps in listOf(-1f,0f,Float.NaN,Float.POSITIVE_INFINITY)) {
            val rows=MediaInformationText.formats(info(video=MediaTrackInformation(width=1920,height=-1,frameRate=fps)))
            assertEquals(InformationValue.Unavailable,value(rows,R.string.info_resolution))
            assertEquals(InformationValue.Unavailable,value(rows,R.string.info_frame_rate))
        }
        val rows=MediaInformationText.formats(info(video=MediaTrackInformation(width=1920,height=1080,frameRate=23.976f)))
        assertEquals("1920 × 1080",text(value(rows,R.string.info_resolution)))
        assertEquals("23.98 fps",text(value(rows,R.string.info_frame_rate)))
    }
    @Test fun nonPositiveAudioNumbersDoNotAppearAsValidFormats() {
        val rows=MediaInformationText.formats(info(audio=MediaTrackInformation(sampleRate=-1,channels=0)))
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_sample_rate))
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_channels))
    }
    @Test fun audioOnlyHasNoVideoInsteadOfUnsupportedVideo() {
        val rows=MediaInformationText.formats(info(audio=MediaTrackInformation(mime="audio/raw",sampleRate=8000,channels=1),hasVideo=false,hasAudio=true))
        assertEquals(InformationValue.None,value(rows,R.string.info_video_track))
        assertFalse(rows.any { it.label==R.string.info_resolution })
        assertEquals("8000 Hz",text(value(rows,R.string.info_sample_rate)))
    }
    @Test fun videoOnlyHasNoAudioOutputRatherThanInventedDevice() {
        val rows=MediaInformationText.formats(info(hasVideo=true,hasAudio=false))
        assertEquals(InformationValue.None,value(rows,R.string.info_audio_track))
        assertEquals(listOf(InformationRow(R.string.info_output,InformationValue.None)),MediaInformationText.output(AudioOutputInformation(),false))
    }
    @Test fun unknownTracksKeepUnknownFieldsInsteadOfClaimingNoTracks() {
        val rows=MediaInformationText.formats(info())
        assertEquals(6,rows.size);assertTrue(rows.all { it.value==InformationValue.Unavailable })
    }
    @Test fun unsupportedUnavailableAndUnsetRenderDifferentLabels() {
        val rows=listOf(InformationRow(1,InformationValue.Unavailable),InformationRow(2,InformationValue.Unsupported),InformationRow(3,InformationValue.NotSet))
        val rendered=MediaInformationText.render(rows) { when(it) { R.string.info_unavailable->"取得できません";R.string.info_unsupported->"未対応";R.string.info_not_set->"未設定";else->"項目$it" } }
        assertEquals("項目1：取得できません\n項目2：未対応\n項目3：未設定",rendered)
    }
    @Test fun unsetMixerDoesNotInventItsConfiguration() {
        val unset=MediaInformationText.output(AudioOutputInformation(mixer=InformationValue.NotSet),true)
        assertFalse(unset.any { it.label==R.string.info_mixer_encoding })
        val configured=MediaInformationText.output(AudioOutputInformation(mixer=InformationValue.Text("Bit-Perfect")),true)
        assertEquals(InformationValue.Unavailable,value(configured,R.string.info_mixer_encoding))
        assertTrue(MediaInformationText.output(AudioOutputInformation(),null).all { it.value==InformationValue.Unavailable })
    }
    @Test fun measuredMixerAndPreferredMixerAreKeptSeparate() {
        val rows=MediaInformationText.output(AudioOutputInformation(
            mixer=InformationValue.Unsupported,
            mixerDiagnostics=InformationValue.Text("再生中の取得結果"),
            actualMixer=InformationValue.Text("deep_buffer_hires96_out (MIXER, I/O 2589)"),
            actualMixerFormat=InformationValue.Text("PCM Float / 96000 Hz / 2 ch"),
            halFormat=InformationValue.Text("PCM 16-bit / 96000 Hz / 2 ch")),true)
        assertEquals(InformationValue.Unsupported,value(rows,R.string.info_mixer))
        assertEquals("PCM Float / 96000 Hz / 2 ch",text(value(rows,R.string.info_actual_mixer_format)))
        assertEquals("PCM 16-bit / 96000 Hz / 2 ch",text(value(rows,R.string.info_hal_format)))
        assertFalse(rows.any { it.label==R.string.info_mixer_encoding })
    }
    @Test fun missingDiagnosticPermissionDoesNotInventMixerOrHalFormats() {
        val rows=MediaInformationText.output(AudioOutputInformation(
            mixerDiagnostics=InformationValue.Text("診断権限が未付与です")),true)
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_actual_mixer))
        assertEquals(InformationValue.Unavailable,value(rows,R.string.info_hal_format))
        assertFalse(MediaInformationText.output(AudioOutputInformation(),true).any { it.label==R.string.info_actual_mixer })
    }
    @Test fun sourceAndOutputSampleRatesRemainIndependent() {
        val source=MediaInformationText.formats(info(audio=MediaTrackInformation(sampleRate=44100,channels=2)))
        val output=MediaInformationText.output(AudioOutputInformation(sampleRate=InformationValue.Text("48000 Hz"),channels=InformationValue.Text("6 ch")),true)
        assertEquals("44100 Hz",text(value(source,R.string.info_sample_rate)))
        assertEquals("48000 Hz",text(value(output,R.string.info_track_sample_rate)))
        assertEquals("2 ch",text(value(source,R.string.info_channels)))
        assertEquals("6 ch",text(value(output,R.string.info_track_channels)))
    }
}
