package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatPcm32WriterTest {
    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).apply {
            values.forEach { putFloat(it) }; flip()
        }

    @Test fun preserves24BitIntegerSamplesIncludingExtremes() {
        val samples = intArrayOf(-8388608, -8388607, -1234567, -1, 0, 1, 1234567, 8388607)
        val input = floats(*samples.map { it / 8388608f }.toFloatArray())
        val bytes = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        val actual = mutableListOf<Int>()
        assertTrue(FloatPcm32Writer().write(input) { converted ->
            while (converted.hasRemaining()) actual.add(converted.int)
            true
        })
        assertEquals(samples.map { it shl 8 }, actual)
        assertArrayEquals(bytes, ByteArray(bytes.size).also { input.duplicate().apply { clear() }.get(it) })
    }

    @Test fun clipsOutOfRangeAndSilencesNaN() {
        val input = floats(-Float.MAX_VALUE, -1f, -0.5f, -0f, Float.NaN, 0.5f, 1f, Float.POSITIVE_INFINITY)
        val actual = mutableListOf<Int>()
        FloatPcm32Writer().write(input) { output ->
            while (output.hasRemaining()) actual.add(output.int)
            true
        }
        assertEquals(listOf(Int.MIN_VALUE, Int.MIN_VALUE, -1073741824, 0, 0, 1073741824,
            Int.MAX_VALUE, Int.MAX_VALUE), actual)
    }

    @Test fun partialAndZeroWritesAdvanceOnlyConsumedBytesAndReuseConvertedSamples() {
        val input = floats(1f, 0.25f, -0.25f, 0f).apply { position(4); limit(12) }
        val writer = FloatPcm32Writer()
        var converted: ByteBuffer? = null
        assertFalse(writer.write(input) { converted = it; false })
        assertEquals(4, input.position())
        assertFalse(writer.write(input) {
            assertSame(converted, it)
            assertEquals(536870912, it.int)
            false
        })
        assertEquals(8, input.position())
        assertTrue(writer.write(input) {
            assertSame(converted, it)
            assertEquals(-536870912, it.int)
            true
        })
        assertEquals(12, input.position())
        assertTrue(writer.write(floats(0.5f)) { assertEquals(1073741824, it.int); true })
    }

    @Test fun flushDiscardsUnwrittenDataBeforeSeek() {
        val writer = FloatPcm32Writer()
        val old = floats(0.5f)
        assertFalse(writer.write(old) { false })
        writer.reset()
        assertTrue(writer.write(floats(-0.5f)) { assertEquals(-1073741824, it.int); true })
        assertEquals(0, old.position())
    }

    @Test fun refusesDifferentBufferWhilePartialWriteIsPending() {
        val writer = FloatPcm32Writer()
        writer.write(floats(0f)) { false }
        assertThrows(IllegalStateException::class.java) { writer.write(floats(1f)) { true } }
    }

    @Test fun selectsInteger32OnlyWhenSameRateAndChannelsAreSupported() {
        val input = PcmOutputFormat(4, 96000, 12)
        val integer = input.copy(encoding = 22)
        fun select(vararg formats: PcmOutputFormat) = selectUsbPcmFormat(input,
            formats.map { OutputMixerConfiguration(it, OutputMixerBehavior.BIT_PERFECT) }, 4, 22)
        assertEquals(integer, select(integer))
        assertEquals(input, select(integer.copy(sampleRate = 384000)))
        assertEquals(input, select(integer.copy(channelMask = 4)))
        assertEquals(input, select(integer.copy(encoding = 2)))
        assertEquals(input, select(input, integer))
        assertEquals(input, select())
        assertEquals(input.copy(encoding = 2), selectUsbPcmFormat(input.copy(encoding = 2),
            listOf(OutputMixerConfiguration(integer, OutputMixerBehavior.BIT_PERFECT)), 4, 22))
    }
}
