package jp.nagu.continuousplayer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compatibility conversion. Consumed 16-bit bytes are mapped back to the original Float samples. */
internal class FloatPcm16Writer {
    private var source: ByteBuffer? = null
    private var sourceStart = 0
    private var converted = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    fun write(buffer: ByteBuffer, output: (ByteBuffer) -> Boolean): Boolean {
        if (source == null) {
            require(buffer.remaining() % 4 == 0)
            val size = buffer.remaining() / 2
            if (converted.capacity() < size) {
                converted = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            }
            converted.clear()
            val samples = buffer.duplicate().order(ByteOrder.nativeOrder())
            while (samples.hasRemaining()) {
                val sample = samples.float
                val value = when {
                    sample.isNaN() -> 0
                    sample >= 1f -> Short.MAX_VALUE.toInt()
                    sample <= -1f -> Short.MIN_VALUE.toInt()
                    else -> (sample.toDouble() * 32768.0).toInt()
                }
                converted.putShort(value.toShort())
            }
            converted.flip()
            sourceStart = buffer.position()
            source = buffer
        }
        check(source === buffer) { "A partially written PCM buffer must be retried before replacing it" }
        val handled = output(converted)
        // An incomplete output sample must not consume any part of the next Float sample.
        buffer.position(sourceStart + converted.position() / 2 * 4)
        if (handled) {
            check(!converted.hasRemaining())
            source = null
        }
        return handled
    }

    fun reset() { source = null }
}
