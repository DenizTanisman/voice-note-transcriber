package dev.deniz.ses.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/** Any audio Android can decode (WhatsApp's Ogg Opus, m4a, mp3, wav…) → 16 kHz mono floats. */
object Decoder {

    class Audio(val pcm16k: FloatArray) {
        val durationMs: Long get() = pcm16k.size * 1000L / Resampler.TARGET
    }

    fun decode(file: File): Audio = run(MediaExtractor().apply { setDataSource(file.absolutePath) })

    fun decode(ctx: Context, uri: Uri): Audio = run(MediaExtractor().apply { setDataSource(ctx, uri, null) })

    private fun run(ex: MediaExtractor): Audio {
        try {
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Dosyada ses izi yok")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatOut = false
            val chunks = ArrayList<ShortArray>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatOut = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val bb = buf.slice().order(ByteOrder.nativeOrder())
                            if (floatOut) {
                                val fb = bb.asFloatBuffer()
                                chunks += ShortArray(fb.remaining()) { (fb.get().coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                            } else {
                                val sb = bb.asShortBuffer()
                                chunks += ShortArray(sb.remaining()).also { sb.get(it) }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            val all = ShortArray(chunks.sumOf { it.size })
            var pos = 0
            for (c in chunks) { c.copyInto(all, pos); pos += c.size }
            return Audio(Resampler.to16k(Resampler.toMono(all, channels), rate))
        } finally {
            ex.release()
        }
    }
}
