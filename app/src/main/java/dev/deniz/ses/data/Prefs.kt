package dev.deniz.ses.data

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("ses", Context.MODE_PRIVATE)

    /** Transcribe incoming WhatsApp voice notes as their notification arrives. */
    var auto: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** "tr" or "auto". Fixed Turkish avoids Whisper drifting into English on short or noisy clips. */
    var lang: String
        get() = sp.getString("lang", "tr")!!
        set(v) = sp.edit().putString("lang", v).apply()

    /** First pass: fast, so the text shows up within seconds. File name; empty = smallest found. */
    var fastModel: String
        get() = sp.getString("fast_model", "")!!
        set(v) = sp.edit().putString("fast_model", v).apply()

    /** Second pass replaces the text with a more accurate one. File name; empty = best found. */
    var goodModel: String
        get() = sp.getString("good_model", "")!!
        set(v) = sp.edit().putString("good_model", v).apply()

    /** Run the second, accurate pass after the fast one. */
    var refine: Boolean
        get() = sp.getBoolean("refine", true)
        set(v) = sp.edit().putBoolean("refine", v).apply()

    var threads: Int
        get() = sp.getInt("threads", 6)
        set(v) = sp.edit().putInt("threads", v).apply()

}
