package dev.deniz.ses.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Windowed-sinc resampler to Whisper's 16 kHz mono. Voice notes are short, so a direct form is plenty fast. */
object Resampler {
    const val TARGET = 16_000

    /** Interleaved 16-bit PCM → mono floats in [-1, 1]. */
    fun toMono(pcm: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) return FloatArray(pcm.size) { pcm[it] / 32768f }
        val frames = pcm.size / channels
        return FloatArray(frames) { f ->
            var s = 0f
            for (c in 0 until channels) s += pcm[f * channels + c]
            s / channels / 32768f
        }
    }

    fun to16k(input: FloatArray, rate: Int, halfTaps: Int = 16): FloatArray {
        if (rate == TARGET || input.isEmpty()) return input
        if (rate > TARGET && rate % TARGET == 0) return decimate(input, rate / TARGET, halfTaps)
        val ratio = TARGET.toDouble() / rate
        val outLen = floor(input.size * ratio).toInt()
        val out = FloatArray(outLen)
        // Low-pass at the lower Nyquist, slightly under it to keep the transition band out of the passband.
        val cutoff = min(1.0, ratio) * 0.95
        val step = 1.0 / ratio
        for (i in 0 until outLen) {
            val center = i * step
            val c = floor(center).toInt()
            val span = (halfTaps / min(1.0, ratio)).toInt()
            var acc = 0.0
            var wsum = 0.0
            for (j in c - span + 1..c + span) {
                if (j < 0 || j >= input.size) continue
                val x = (center - j)
                val w = sinc(x * cutoff) * blackman(x / (span + 1))
                acc += input[j] * w
                wsum += w
            }
            out[i] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    /** Integer factor (48 kHz Opus → 16 kHz): same kernel for every output sample, computed once. */
    private fun decimate(input: FloatArray, factor: Int, halfTaps: Int): FloatArray {
        val span = halfTaps * factor
        val cutoff = 0.95 / factor
        val k = DoubleArray(2 * span) { idx ->
            val x = (span - 1 - idx).toDouble() // offsets span-1 … -span, matching j = c-span+1 … c+span
            sinc(x * cutoff) * blackman(x / (span + 1))
        }
        val ksum = k.sum()
        val outLen = input.size / factor
        return FloatArray(outLen) { i ->
            val c = i * factor
            var acc = 0.0
            var wsum = 0.0
            for (t in k.indices) {
                val j = c - span + 1 + t
                if (j < 0 || j >= input.size) continue
                acc += input[j] * k[t]; wsum += k[t]
            }
            if (j0Inside(c, span, input.size)) (acc / ksum).toFloat() else if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
    }

    private fun j0Inside(c: Int, span: Int, n: Int) = c - span + 1 >= 0 && c + span < n

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Blackman window over [-1, 1]. */
    private fun blackman(t: Double): Double {
        if (t <= -1.0 || t >= 1.0) return 0.0
        val n = (t + 1.0) / 2.0
        return 0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n)
    }
}
