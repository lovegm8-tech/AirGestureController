#include <jni.h>
#include <sentencepiece_processor.h>
#include <memory>
#include <string>
#include <vector>

namespace {
using Processor = sentencepiece::SentencePieceProcessor;
Processor* asProcessor(jlong handle) { return reinterpret_cast<Processor*>(handle); }

bool clearJavaException(JNIEnv* env) {
    return env->ExceptionCheck() == JNI_TRUE;
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwIllegalState(JNIEnv* env, const char* message) {
    if (!clearJavaException(env)) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, message);
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_tw_airgesture_transferbeta_subtitle_NllbSentencePiece_nativeOpen(
    JNIEnv* env, jclass, jstring path) {
    if (!path) { throwIllegalState(env, "Missing model path"); return 0; }
    const std::string filename = fromJString(env, path);
    if (clearJavaException(env)) return 0;
    auto processor = std::make_unique<Processor>();
    const auto status = processor->Load(filename);
    if (!status.ok()) {
        throwIllegalState(env, "Unable to load SentencePiece model");
        return 0;
    }
    return reinterpret_cast<jlong>(processor.release());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_tw_airgesture_transferbeta_subtitle_NllbSentencePiece_nativeEncode(
    JNIEnv* env, jclass, jlong handle, jstring text) {
    Processor* processor = asProcessor(handle);
    if (!processor || !text) { throwIllegalState(env, "Tokenizer closed or text missing"); return nullptr; }
    const std::string input = fromJString(env, text);
    if (clearJavaException(env)) return nullptr;
    std::vector<int> pieces;
    const auto status = processor->Encode(input, &pieces);
    if (!status.ok()) { throwIllegalState(env, "SentencePiece encoding failed"); return nullptr; }
    jintArray output = env->NewIntArray(static_cast<jsize>(pieces.size()));
    if (!output) return nullptr;
    if (!pieces.empty()) {
        env->SetIntArrayRegion(output, 0, static_cast<jsize>(pieces.size()),
                               reinterpret_cast<const jint*>(pieces.data()));
    }
    return output;
}

extern "C" JNIEXPORT jstring JNICALL
Java_tw_airgesture_transferbeta_subtitle_NllbSentencePiece_nativeDecode(
    JNIEnv* env, jclass, jlong handle, jintArray ids) {
    Processor* processor = asProcessor(handle);
    if (!processor || !ids) { throwIllegalState(env, "Tokenizer closed or ids missing"); return nullptr; }
    const jsize count = env->GetArrayLength(ids);
    std::vector<jint> raw(static_cast<size_t>(count));
    if (count) env->GetIntArrayRegion(ids, 0, count, raw.data());
    if (clearJavaException(env)) return nullptr;
    std::vector<int> pieces(raw.begin(), raw.end());
    std::string result;
    const auto status = processor->Decode(pieces, &result);
    if (!status.ok()) { throwIllegalState(env, "SentencePiece decoding failed"); return nullptr; }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_tw_airgesture_transferbeta_subtitle_NllbSentencePiece_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    delete asProcessor(handle);
}
