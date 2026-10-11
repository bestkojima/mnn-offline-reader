#include "supertonic_engine.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <random>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/ExprCreator.hpp>

namespace EnglishReader {
using namespace MNN::Express;

namespace {
VARP floatInput(const std::vector<int>& shape, const std::vector<float>& data) {
    auto value = _Input(shape, NCHW, halide_type_of<float>());
    auto target = value->writeMap<float>();
    if (!target)
        return nullptr;
    std::memcpy(target, data.data(), data.size() * sizeof(float));
    return value;
}

bool validStyle(const std::vector<int>& shape, const std::vector<float>& values) {
    if (shape.size() != 3 || shape[0] != 1 || shape[1] < 1 || shape[2] < 1 || shape[1] > 256 || shape[2] > 1024) {
        return false;
    }
    if (static_cast<size_t>(shape[1] * shape[2]) != values.size())
        return false;
    for (float value : values)
        if (!std::isfinite(value))
            return false;
    return true;
}

bool outputData(const std::vector<VARP>& outputs, std::vector<float>& data, std::vector<int>* shape = nullptr) {
    if (outputs.empty() || outputs[0].get() == nullptr)
        return false;
    auto value = _Convert(outputs[0], NCHW);
    const auto info = value->getInfo();
    if (!info || info->size < 1 || info->size > 16000000 || info->type != halide_type_of<float>())
        return false;
    const auto source = value->readMap<float>();
    if (!source)
        return false;
    data.assign(source, source + info->size);
    if (shape)
        *shape = info->dim;
    return true;
}
} // namespace

SupertonicEngine::SupertonicEngine(int sampleRate, int baseChunkSize, int compress, int latentDim)
    : mSampleRate(sampleRate), mBaseChunkSize(baseChunkSize), mCompress(compress), mLatentDim(latentDim) {}

bool SupertonicEngine::fail(const char* error) {
    mError = error;
    return false;
}

bool SupertonicEngine::load(const std::string& directory) {
    if (mSampleRate < 8000 || mSampleRate > 96000 || mBaseChunkSize < 1 || mBaseChunkSize > 4096 || mCompress < 1 ||
        mCompress > 32 || mLatentDim < 1 || mLatentDim > 256)
        return fail("Invalid v3 configuration");
    MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Normal;
    backend.memory = MNN::BackendConfig::Memory_Low;
    mExecutor = Executor::newExecutor(MNN_FORWARD_CPU, backend, 4);
    if (!mExecutor)
        return fail("Cannot create MNN executor");
    ExecutorScope scope(mExecutor);
    auto loadModel = [&](const char* name, const std::vector<std::string>& inputs,
                         const std::vector<std::string>& outputs) {
        const auto path = directory + "/" + name + ".mnn";
        return std::shared_ptr<Module>(Module::load(inputs, outputs, path.c_str()));
    };
    mDuration = loadModel("duration_predictor", {"text_ids", "style_dp", "text_mask"}, {"duration"});
    mEncoder = loadModel("text_encoder", {"text_ids", "style_ttl", "text_mask"}, {"text_emb"});
    mEstimator =
        loadModel("vector_estimator",
                  {"noisy_latent", "text_emb", "style_ttl", "latent_mask", "text_mask", "current_step", "total_step"},
                  {"denoised_latent"});
    mVocoder = loadModel("vocoder", {"latent"}, {"wav_tts"});
    if (!mDuration || !mEncoder || !mEstimator || !mVocoder)
        return fail("Cannot load v3/int8 modules");
    return true;
}

void SupertonicEngine::cancel() {
    mCancellation.fetch_add(1, std::memory_order_relaxed);
}

bool SupertonicEngine::synthesize(const std::vector<int>& tokens, const std::vector<float>& ttl,
                                  const std::vector<int>& ttlShape, const std::vector<float>& dp,
                                  const std::vector<int>& dpShape, float speed, std::vector<int16_t>& pcm) {
    pcm.clear();
    mError.clear();
    if (!mVocoder)
        return fail("Model not initialized");
    if (tokens.empty() || tokens.size() > 1024 || !validStyle(ttlShape, ttl) || !validStyle(dpShape, dp) ||
        !std::isfinite(speed) || speed < 0.5f || speed > 2.0f)
        return fail("Invalid synthesis input");
    const auto ticket = mCancellation.load(std::memory_order_relaxed);
    auto cancelled = [&]() { return mCancellation.load(std::memory_order_relaxed) != ticket; };
    ExecutorScope scope(mExecutor);
    const int textLength = static_cast<int>(tokens.size());
    auto ids = _Input({1, textLength}, NCHW, halide_type_of<int>());
    auto idData = ids->writeMap<int>();
    if (!idData)
        return fail("Cannot allocate text input");
    std::memcpy(idData, tokens.data(), tokens.size() * sizeof(int));
    auto mask = floatInput({1, 1, textLength}, std::vector<float>(textLength, 1.0f));
    auto dpStyle = floatInput(dpShape, dp);
    auto ttlStyle = floatInput(ttlShape, ttl);
    if (mask.get() == nullptr || dpStyle.get() == nullptr || ttlStyle.get() == nullptr)
        return fail("Cannot allocate style inputs");
    std::vector<float> duration;
    if (!outputData(mDuration->onForward({ids, dpStyle, mask}), duration))
        return fail("Duration inference failed");
    if (cancelled())
        return fail("Cancelled");
    const float seconds = duration[0] / speed;
    if (!std::isfinite(seconds) || seconds <= 0 || seconds > 30)
        return fail("Select a shorter text fragment");
    const int wavLength = static_cast<int>(seconds * mSampleRate);
    const int chunkSize = mBaseChunkSize * mCompress;
    const int latentLength = std::max(1, (wavLength + chunkSize - 1) / chunkSize);
    const int latentChannels = mLatentDim * mCompress;
    std::vector<float> embedding;
    std::vector<int> embeddingShape;
    if (!outputData(mEncoder->onForward({ids, ttlStyle, mask}), embedding, &embeddingShape)) {
        return fail("Text encoder inference failed");
    }
    if (cancelled())
        return fail("Cancelled");
    auto textEmbedding = floatInput(embeddingShape, embedding);
    auto latentMask = floatInput({1, 1, latentLength}, std::vector<float>(latentLength, 1.0f));
    if (textEmbedding.get() == nullptr || latentMask.get() == nullptr)
        return fail("Cannot allocate latent inputs");
    const std::vector<int> latentShape = {1, latentChannels, latentLength};
    std::vector<float> latent(static_cast<size_t>(latentChannels) * latentLength);
    std::mt19937 random(std::random_device{}());
    std::normal_distribution<float> gaussian(0.0f, 1.0f);
    for (auto& value : latent)
        value = gaussian(random);
    constexpr int steps = 8;
    auto totalStep = floatInput({1}, {static_cast<float>(steps)});
    for (int step = 0; step < steps; ++step) {
        if (cancelled())
            return fail("Cancelled");
        auto noisy = floatInput(latentShape, latent);
        auto current = floatInput({1}, {static_cast<float>(step)});
        if (noisy.get() == nullptr || current.get() == nullptr || totalStep.get() == nullptr)
            return fail("Cannot allocate estimator inputs");
        std::vector<float> next;
        if (!outputData(mEstimator->onForward({noisy, textEmbedding, ttlStyle, latentMask, mask, current, totalStep}),
                        next) ||
            next.size() != latent.size())
            return fail("Vector estimator inference failed");
        latent.swap(next);
    }
    if (cancelled())
        return fail("Cancelled");
    auto finalLatent = floatInput(latentShape, latent);
    if (finalLatent.get() == nullptr)
        return fail("Cannot allocate vocoder input");
    std::vector<float> wave;
    if (!outputData(mVocoder->onForward({finalLatent}), wave) || wave.size() > static_cast<size_t>(mSampleRate) * 32) {
        return fail("Vocoder inference failed");
    }
    if (cancelled())
        return fail("Cancelled");
    pcm.reserve(wave.size());
    for (float value : wave) {
        if (!std::isfinite(value))
            return fail("Non-finite audio output");
        pcm.push_back(static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, value * 32768.0f))));
    }
    return true;
}
} // namespace EnglishReader
