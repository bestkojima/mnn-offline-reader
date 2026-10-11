#include "kokoro_engine.hpp"

#include <algorithm>
#include <cmath>
#include <MNN/expr/ExecutorScope.hpp>
#include <MNN/expr/ExprCreator.hpp>

namespace EnglishReader {
using namespace MNN::Express;

bool KokoroEngine::load(const std::string& path) {
    MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_Normal;
    backend.memory = MNN::BackendConfig::Memory_Low;
    mExecutor = Executor::newExecutor(MNN_FORWARD_CPU, backend, 4);
    if (!mExecutor)
        return fail("Cannot create MNN executor");
    ExecutorScope scope(mExecutor);
    Module::Config config;
    config.shapeMutable = true;
    config.rearrange = true;
    mModel.reset(Module::load({"style", "input_ids", "speed"}, {"waveform"}, path.c_str(), nullptr, &config));
    return mModel ? true : fail("Cannot load Kokoro module");
}

bool KokoroEngine::synthesize(const std::vector<int>& tokens, const std::vector<float>& style, float speed,
                              std::vector<int16_t>& pcm) {
    pcm.clear();
    mError.clear();
    if (!mModel)
        return fail("Kokoro not initialized");
    // Limit each forward; cancellation can only take effect after the current forward.
    if (tokens.size() < 3 || tokens.size() > 202 || tokens.front() != 0 || tokens.back() != 0 || style.size() != 256 ||
        !std::isfinite(speed) || speed < 0.5f || speed > 2.0f)
        return fail("Invalid Kokoro inputs");
    for (int id : tokens)
        if (id < 0 || id >= 178)
            return fail("Invalid Kokoro token");
    for (float value : style)
        if (!std::isfinite(value))
            return fail("Invalid Kokoro style");
    const auto cancellation = mCancellation.load(std::memory_order_relaxed);
    ExecutorScope scope(mExecutor);
    auto ids = _Const(tokens.data(), {1, static_cast<int>(tokens.size())}, NCHW, halide_type_of<int>());
    auto voice = _Const(style.data(), {1, 256}, NCHW, halide_type_of<float>());
    auto rate = _Const(&speed, {1}, NCHW, halide_type_of<float>());
    auto outputs = mModel->onForward({voice, ids, rate});
    if (mCancellation.load(std::memory_order_relaxed) != cancellation)
        return fail("Cancelled");
    if (outputs.empty() || outputs[0].get() == nullptr)
        return fail("Empty Kokoro output");
    auto audio = _Convert(outputs[0], NCHW);
    const auto info = audio->getInfo();
    if (!info || info->type != halide_type_of<float>() || info->size < 1 || info->size > 24000 * 30)
        return fail("Invalid Kokoro waveform");
    const auto data = audio->readMap<float>();
    if (!data)
        return fail("Cannot read Kokoro waveform");
    pcm.resize(info->size);
    for (int i = 0; i < info->size; ++i) {
        if (!std::isfinite(data[i])) {
            pcm.clear();
            return fail("Nonfinite Kokoro waveform");
        }
        pcm[i] = static_cast<int16_t>(std::round(std::max(-1.0f, std::min(1.0f, data[i])) * 32767.0f));
    }
    if (mCancellation.load(std::memory_order_relaxed) != cancellation) {
        pcm.clear();
        return fail("Cancelled");
    }
    return true;
}
} // namespace EnglishReader
