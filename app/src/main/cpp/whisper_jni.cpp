// Thin JNI bridge to whisper.cpp. One context at a time, used from a single Kotlin worker thread.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include "whisper.h"

#define TAG "SesWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

static bool abort_cb(void *) { return g_abort.load(); }

static void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_deniz_ses_asr_WhisperNative_init(JNIEnv *env, jobject, jstring jpath) {
    whisper_log_set(log_cb, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    LOGI("init %s -> %p", path, ctx);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_deniz_ses_asr_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_deniz_ses_asr_WhisperNative_cancel(JNIEnv *, jobject) { g_abort.store(true); }

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deniz_ses_asr_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

/**
 * Returns UTF-8 bytes: one line per segment, "startMs\tendMs\tnoSpeechProb\ttext".
 * Bytes, not jstring: NewStringUTF wants modified UTF-8 and aborts on some valid sequences.
 * Returns null when whisper_full fails or was cancelled.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_deniz_ses_asr_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray jpcm,
                                                jstring jlang, jint threads, jstring jprompt,
                                                jint beam, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return nullptr;
    g_abort.store(false);

    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    const char *prompt = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;
    jsize n = env->GetArrayLength(jpcm);
    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);

    whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_nst = true;
    p.initial_prompt = (prompt && prompt[0]) ? prompt : nullptr;
    p.audio_ctx = audioCtx;
    if (beam > 1) p.beam_search.beam_size = beam;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, p, pcm, n);

    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(jlang, lang);
    if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);
    if (rc != 0 || g_abort.load()) return nullptr;

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10);
        out += '\t';
        out += std::to_string(whisper_full_get_segment_t1(ctx, i) * 10);
        out += '\t';
        out += std::to_string(whisper_full_get_segment_no_speech_prob(ctx, i));
        out += '\t';
        std::string t = whisper_full_get_segment_text(ctx, i);
        for (char &c : t) if (c == '\n' || c == '\t') c = ' ';
        out += t;
        out += '\n';
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}
