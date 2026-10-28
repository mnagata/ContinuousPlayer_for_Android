package jp.nagu.continuousplayer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same-width conversion at the output boundary. Source bytes are never modified. */
internal class FloatPcm32Writer {
    private var source: ByteBuffer? = null
    private var converted = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    fun write(buffer: ByteBuffer, output: (ByteBuffer) -> Boolean): Boolean {
        if (source == null) {
            require(buffer.remaining() % 4 == 0)
            if (converted.capacity() < buffer.remaining()) {
                converted = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.nativeOrder())
            }
            converted.clear()
            val samples = buffer.duplicate().order(ByteOrder.nativeOrder())
            while (samples.hasRemaining()) {
                val sample = samples.float
                val integer = when {
                    sample.isNaN() -> 0
                    sample >= 1f -> Int.MAX_VALUE
                    sample <= -1f -> Int.MIN_VALUE
                    else -> (sample.toDouble() * 2147483648.0).toInt()
                }
                converted.putInt(integer)
            }
            converted.flip()
            source = buffer
        }
        check(source === buffer) { "A partially written PCM buffer must be retried before replacing it" }
        val before = converted.position()
        val handled = output(converted)
        buffer.position(buffer.position() + converted.position() - before)
        if (handled) {
            check(!converted.hasRemaining())
            source = null
        }
        return handled
    }

    fun reset() { source = null }
}

/** No sample-rate conversion or reduction to 16 bit is allowed for this alternative. */
internal fun selectUsbPcmFormat(input: PcmOutputFormat,
    supported: List<OutputMixerConfiguration>, floatEncoding: Int, pcm32Encoding: Int): PcmOutputFormat {
    if (input.encoding != floatEncoding || supported.any { it.format == input }) return input
    val integer = input.copy(encoding = pcm32Encoding)
    return integer.takeIf { candidate -> supported.any { it.format == candidate } } ?: input
}
