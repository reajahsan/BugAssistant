#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Session {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool>  cancel{false};
};

static Session* S(jlong h) { return reinterpret_cast<Session*>(h); }

// ---- byte[] helpers: avoid JNI "modified UTF-8" problems with emoji etc. ----
static std::string fromBytes(JNIEnv* env, jbyteArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::string s(n, '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(s.data()));
    return s;
}

static jbyteArray toBytes(JNIEnv* env, const char* data, size_t n) {
    jbyteArray arr = env->NewByteArray((jsize) n);
    env->SetByteArrayRegion(arr, 0, (jsize) n, reinterpret_cast<const jbyte*>(data));
    return arr;
}

// Tokens can end mid-character. Return the length of the complete UTF-8 prefix.
static size_t completeUtf8Prefix(const std::string& s) {
    size_t i = 0, last = 0;
    while (i < s.size()) {
        unsigned char c = s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
        last = i;
    }
    return last;
}

static void logCallback(ggml_log_level level, const char* text, void*) {
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                        TAG, "%s", text);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_reaj_bugassistant_llama_LlamaNative_backendInit(JNIEnv*, jobject) {
    llama_log_set(logCallback, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_reaj_bugassistant_llama_LlamaNative_loadModel(JNIEnv* env, jobject, jstring jpath,
                                                 jint nCtx, jint nThreads) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                       // CPU first; experiment with GPU later
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("model load failed"); return 0; }

    auto cparams = llama_context_default_params();
    cparams.n_ctx           = nCtx;
    cparams.n_batch         = nCtx;                 // whole prompt in one batch
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    auto* s  = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    LOGI("model loaded: ctx=%d threads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(s);
}

// Applies the model's own chat template (embedded in the GGUF).
JNIEXPORT jbyteArray JNICALL
Java_com_reaj_bugassistant_llama_LlamaNative_formatChat(JNIEnv* env, jobject, jlong h,
                                                  jbyteArray jsys, jbyteArray juser) {
    Session* s = S(h);
    std::string sys = fromBytes(env, jsys), user = fromBytes(env, juser);

    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", user.c_str()});

    const char* tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(sys.size() + user.size() + 1024);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    if (n < 0) {                                    // no template: fall back to raw text
        std::string raw = sys.empty() ? user : sys + "\n\n" + user;
        return toBytes(env, raw.data(), raw.size());
    }
    return toBytes(env, buf.data(), n);
}

// Blocking. Runs on the caller's (Kotlin background) thread, so env is valid here.
// Returns the number of generated tokens, or a negative error code.
JNIEXPORT jint JNICALL
Java_com_reaj_bugassistant_llama_LlamaNative_generate(JNIEnv* env, jobject, jlong h,
                                                jbyteArray jprompt, jstring jgrammar,
                                                jint maxTokens, jobject callback) {
    Session* s = S(h);
    s->cancel = false;

    // Fresh conversation each call (no multi-turn in this demo)
    llama_memory_clear(llama_get_memory(s->ctx), true);

    std::string prompt = fromBytes(env, jprompt);
    int nPrompt = -llama_tokenize(s->vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(nPrompt);
    if (llama_tokenize(s->vocab, prompt.c_str(), (int) prompt.size(),
                       tokens.data(), nPrompt, true, true) < 0) return -1;

    if (nPrompt + maxTokens > (int) llama_n_ctx(s->ctx)) {
        LOGE("prompt (%d) + maxTokens (%d) exceeds context", nPrompt, maxTokens);
        return -2;
    }

    // Sampler chain: optional grammar constraint, then greedy (deterministic)
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const char* grammar = env->GetStringUTFChars(jgrammar, nullptr);
    if (grammar[0] != '\0') {
        llama_sampler* g = llama_sampler_init_grammar(s->vocab, grammar, "root");
        if (!g) {
            LOGE("grammar failed to parse");
            env->ReleaseStringUTFChars(jgrammar, grammar);
            llama_sampler_free(smpl);
            return -4;
        }
        llama_sampler_chain_add(smpl, g);
    }
    env->ReleaseStringUTFChars(jgrammar, grammar);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    jmethodID onToken = env->GetMethodID(env->GetObjectClass(callback), "onToken", "([B)V");

    llama_batch batch = llama_batch_get_one(tokens.data(), nPrompt);
    llama_token tok;
    std::string pending;
    int generated = 0;

    while (generated < maxTokens && !s->cancel) {
        if (llama_decode(s->ctx, batch) != 0) { generated = -3; break; }

        tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        generated++;

        char piece[256];
        int n = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, n);

        size_t ready = completeUtf8Prefix(pending);
        if (ready > 0) {
            jbyteArray arr = toBytes(env, pending.data(), ready);
            env->CallVoidMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);               // avoid local reference table overflow
            pending.erase(0, ready);
            if (env->ExceptionCheck()) break;
        }

        batch = llama_batch_get_one(&tok, 1);
    }

    llama_sampler_free(smpl);
    return generated;
}

JNIEXPORT void JNICALL
Java_com_reaj_bugassistant_llama_LlamaNative_cancel(JNIEnv*, jobject, jlong h) {
    if (h) S(h)->cancel = true;
}

JNIEXPORT void JNICALL
Java_com_reaj_bugassistant_llama_LlamaNative_free(JNIEnv*, jobject, jlong h) {
    Session* s = S(h);
    if (!s) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

} // extern "C"