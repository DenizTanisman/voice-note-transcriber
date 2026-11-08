package dev.deniz.ses.asr

/** JNI surface of app/src/main/cpp/whisper_jni.cpp. */
object WhisperNative {
    init { System.loadLibrary("sesjni") }

    external fun init(modelPath: String): Long
    external fun free(handle: Long)
    external fun cancel()
    external fun systemInfo(): String

    /** UTF-8 lines "startMs\tendMs\tnoSpeechProb\ttext", or null on failure/cancel. */
    external fun transcribe(
        handle: Long, pcm16k: FloatArray, lang: String, threads: Int, prompt: String?, beam: Int, audioCtx: Int,
    ): ByteArray?
}
