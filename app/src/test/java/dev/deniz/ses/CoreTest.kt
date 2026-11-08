package dev.deniz.ses

import dev.deniz.ses.asr.Segment
import dev.deniz.ses.asr.Segments
import dev.deniz.ses.audio.Resampler
import dev.deniz.ses.wa.Matcher
import dev.deniz.ses.wa.Msg
import dev.deniz.ses.wa.NotifData
import dev.deniz.ses.wa.VoiceFile
import dev.deniz.ses.wa.VoiceHint
import dev.deniz.ses.wa.VoiceNotif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceNotifTest {
    @Test fun turkishVoiceLine() {
        assertEquals(12, VoiceNotif.voiceDuration("🎤 Sesli mesaj (0:12)"))
        assertEquals(65, VoiceNotif.voiceDuration("Sesli mesaj (1:05)"))
        assertEquals(7, VoiceNotif.voiceDuration("🎤 Voice message (0:07)"))
        assertEquals(-1, VoiceNotif.voiceDuration("🎤 Sesli mesaj"))
        assertEquals(-1, VoiceNotif.voiceDuration("🎤"))
    }

    @Test fun ordinaryTextIsNotVoice() {
        assertNull(VoiceNotif.voiceDuration("sesli mesaj atar mısın"))
        assertNull(VoiceNotif.voiceDuration("Merhaba (0:12)"))
        assertNull(VoiceNotif.voiceDuration("📷 Fotoğraf"))
        assertNull(VoiceNotif.voiceDuration(""))
    }

    private fun n(vararg m: Msg, group: Boolean = false, summary: Boolean = false) =
        NotifData("com.whatsapp", if (group) "Aile" else "Ayşe", if (group) "Aile" else null, group, m.toList(), 1_000_000, summary)

    @Test fun directChat() {
        val h = VoiceNotif.hints(n(Msg("Selam", 900_000, "Ayşe"), Msg("🎤 Sesli mesaj (0:09)", 990_000, "Ayşe")))
        assertEquals(1, h.size)
        assertEquals("Ayşe", h[0].sender); assertNull(h[0].chat); assertEquals(9, h[0].durationSec)
        assertEquals("Ayşe", h[0].label)
    }

    @Test fun groupChatLabelsSenderAndGroup() {
        val h = VoiceNotif.hints(n(Msg("🎤 Sesli mesaj (0:30)", 990_000, "Ali"), group = true))
        assertEquals("Ali · Aile", h.single().label)
    }

    @Test fun ownMessagesAndSummariesSkipped() {
        assertTrue(VoiceNotif.hints(n(Msg("🎤 Sesli mesaj (0:05)", 990_000, null, self = true))).isEmpty())
        assertTrue(VoiceNotif.hints(n(Msg("🎤 Sesli mesaj (0:05)", 990_000, "Ali"), summary = true)).isEmpty())
        assertTrue(VoiceNotif.hints(n(Msg("🎤 Sesli mesaj (0:05)", 990_000, "Ali")).copy(pkg = "org.telegram")).isEmpty())
    }

    @Test fun keyStableAcrossReposts() {
        val a = VoiceNotif.hints(n(Msg("🎤 Sesli mesaj (0:05)", 990_000, "Ali"))).single()
        val b = VoiceNotif.hints(n(Msg("🎤 Sesli mesaj (0:05)", 990_000, "Ali"), Msg("naber", 995_000, "Ali")).copy(postTime = 2_000_000)).single()
        assertEquals(a.key, b.key)
    }
}

class MatcherTest {
    private fun hint(msg: Long, post: Long, dur: Int?) = VoiceHint("com.whatsapp", "Ali", null, msg, post, dur)

    @Test fun picksFileWrittenJustBeforeNotification() {
        val files = listOf(VoiceFile("old", 100_000, 5_000), VoiceFile("new", 998_500, 5_000))
        assertEquals("new", Matcher.pick(hint(990_000, 1_000_000, 5), files, emptySet())?.path)
    }

    @Test fun offlinePhoneUsesPostTimeNotMessageTime() {
        // Sent hours ago, downloaded now.
        val f = VoiceFile("f", 10_000_000, 3_000)
        assertEquals("f", Matcher.pick(hint(1_000, 10_001_000, 3), listOf(f), emptySet())?.path)
    }

    @Test fun durationRulesOutNeighbour() {
        val files = listOf(VoiceFile("short", 999_900, 2_000), VoiceFile("long", 999_000, 41_000))
        assertEquals("long", Matcher.pick(hint(990_000, 1_000_000, 40), files, emptySet())?.path)
    }

    @Test fun claimedAndFarFilesIgnored() {
        val files = listOf(VoiceFile("a", 999_000, null), VoiceFile("b", 1_000_000 + Matcher.WINDOW_MS + 1, null))
        assertNull(Matcher.pick(hint(1_000_000, 1_000_000, null), files, setOf("a")))
    }

    @Test fun burstAssignedInOrder() {
        val h1 = hint(990_000, 1_000_000, null); val h2 = hint(991_000, 1_000_000, null)
        val files = listOf(VoiceFile("2", 999_800, null), VoiceFile("1", 999_500, null))
        val m = Matcher.assign(listOf(h2, h1), files, emptySet())
        assertEquals(2, m.size)
        assertTrue(m.values.map { it.path }.toSet() == setOf("1", "2"))
        assertTrue(m[h1]!!.path != m[h2]!!.path)
    }
}

class SegmentsTest {
    private fun s(t: String, ns: Float = 0f) = Segment(0, 1000, ns, t)

    @Test fun parsesNativeOutput() {
        val segs = Segments.parse("0\t2500\t0.01\t Merhaba, nasılsın?\n2500\t5000\t0.02\t İyiyim.\n")
        assertEquals(2, segs.size)
        assertEquals(2500L, segs[0].endMs)
        assertEquals("Merhaba, nasılsın?", segs[0].text)
    }

    @Test fun dropsSubtitleCredits() {
        assertEquals("Akşam geliyorum.", Segments.clean(listOf(s("Akşam geliyorum."), s("Altyazı M.K."), s("[Müzik]"))))
        assertEquals("", Segments.clean(listOf(s("Altyazı M.K."))))
        assertEquals("", Segments.clean(listOf(s("İzlediğiniz için teşekkürler."))))
    }

    @Test fun keepsRealSentencesThatMentionSubtitles() {
        val t = "Altyazıyı açınca filmi daha iyi anladım ama sonu çok kötüydü bence, sen ne düşünüyorsun?"
        assertEquals(t, Segments.clean(listOf(s(t))))
        assertEquals("Altyazıyı aç.", Segments.clean(listOf(s("Altyazıyı aç."))))
        assertEquals("", Segments.clean(listOf(s("Altyazı: Ahmet Yılmaz"))))
    }

    @Test fun collapsesLoopsAndRepeatedSegments() {
        assertEquals("evet evet tamam", Segments.clean(listOf(s("evet evet evet evet evet evet tamam"))))
        assertEquals("Tamam.", Segments.clean(listOf(s("Tamam."), s("tamam"))))
    }
}

class ResamplerTest {
    private fun tone(rate: Int, hz: Double, sec: Double) = FloatArray((rate * sec).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }
    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0; for (i in from until to) s += a[i] * a[i].toDouble(); return sqrt(s / (to - from))
    }

    @Test fun lengthAndPassband48k() {
        val out = Resampler.to16k(tone(48_000, 440.0, 1.0), 48_000)
        assertEquals(16_000, out.size)
        assertTrue(abs(rms(out, 500, 15_500) - 0.5 / sqrt(2.0)) < 0.01)
    }

    @Test fun aliasingRemoved48k() {
        // 12 kHz is above the new 8 kHz Nyquist: must not fold back into the speech band.
        val out = Resampler.to16k(tone(48_000, 12_000.0, 1.0), 48_000)
        assertTrue(rms(out, 500, 15_500) < 0.01)
    }

    @Test fun fractionalRate44k() {
        val out = Resampler.to16k(tone(44_100, 1_000.0, 1.0), 44_100)
        assertEquals(16_000, out.size)
        assertTrue(abs(rms(out, 500, 15_500) - 0.5 / sqrt(2.0)) < 0.01)
        assertTrue(rms(Resampler.to16k(tone(44_100, 11_000.0, 1.0), 44_100), 500, 15_500) < 0.02)
    }

    @Test fun stereoToMono() {
        val m = Resampler.toMono(shortArrayOf(1000, 3000, -2000, 2000), 2)
        assertEquals(2, m.size)
        assertEquals(2000 / 32768f, m[0], 1e-6f)
        assertEquals(0f, m[1], 1e-6f)
    }
}
