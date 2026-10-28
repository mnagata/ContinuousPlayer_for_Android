package jp.nagu.continuousplayer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatPcm16WriterTest {
    private fun floats(vararg values: Float) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).apply { values.forEach { putFloat(it) }; flip() }

    @Test fun convertsSamplesAndClipsWithoutModifyingTheSource() {
        val input = floats(-2f, -1f, -0.5f, -1f / 32768, -0f, Float.NaN, 1f / 32768, 0.5f, 1f, Float.POSITIVE_INFINITY)
        val original = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        val actual = mutableListOf<Short>()
        assertTrue(FloatPcm16Writer().write(input) { output ->
            while (output.hasRemaining()) actual.add(output.short)
            true
        })
        assertEquals(listOf(-32768, -32768, -16384, -1, 0, 0, 1, 16384, 32767, 32767).map { it.toShort() },actual)
        assertArrayEquals(original,ByteArray(original.size).also { input.duplicate().apply { clear() }.get(it) })
        assertEquals(input.limit(),input.position())
    }

    @Test fun partialAndZeroWritesConsumeOnlyCorrespondingFloatSamples() {
        val input=floats(1f,0.25f,-0.25f,0f).apply { position(4);limit(12) }
        val writer=FloatPcm16Writer()
        var converted:ByteBuffer?=null
        assertFalse(writer.write(input) { converted=it;assertEquals(4,it.remaining());false })
        assertEquals(4,input.position())
        assertFalse(writer.write(input) { assertSame(converted,it);assertEquals(8192,it.short.toInt());false })
        assertEquals(8,input.position())
        assertTrue(writer.write(input) { assertSame(converted,it);assertEquals(-8192,it.short.toInt());true })
        assertEquals(12,input.position())
        assertTrue(writer.write(floats(0.5f)) { assertEquals(16384,it.short.toInt());true })
    }

    @Test fun aPartialOutputSampleDoesNotAdvanceIntoAFloatSample() {
        val input=floats(0.5f,-0.5f)
        val writer=FloatPcm16Writer()
        assertFalse(writer.write(input) { it.get();false })
        assertEquals(0,input.position())
        assertFalse(writer.write(input) { it.get();false })
        assertEquals(4,input.position())
        assertTrue(writer.write(input) { assertEquals(-16384,it.short.toInt());true })
        assertEquals(8,input.position())
    }

    @Test fun all16BitValuesSurviveFloatRoundTrip() {
        val input=ByteBuffer.allocateDirect(65536*4).order(ByteOrder.nativeOrder())
        for (sample in Short.MIN_VALUE..Short.MAX_VALUE) input.putFloat(sample/32768f)
        input.flip()
        FloatPcm16Writer().write(input) { output ->
            for (sample in Short.MIN_VALUE..Short.MAX_VALUE) assertEquals(sample,output.short.toInt())
            true
        }
    }

    @Test fun flushDiscardsUnwrittenDataAndAllowsANewBuffer() {
        val old=floats(0.5f)
        val writer=FloatPcm16Writer()
        writer.write(old) { false };writer.reset()
        assertTrue(writer.write(floats(-0.5f)) { assertEquals(-16384,it.short.toInt());true })
        assertEquals(0,old.position())
    }

    @Test fun refusesReplacementAndIncompleteInputSamples() {
        val writer=FloatPcm16Writer()
        writer.write(floats(0f)) { false }
        assertThrows(IllegalStateException::class.java) { writer.write(floats(1f)) { true } }
        writer.reset()
        assertThrows(IllegalArgumentException::class.java) { writer.write(ByteBuffer.allocateDirect(3)) { true } }
    }
}
