#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include <MNN/expr/Executor.hpp>
#include <MNN/expr/Module.hpp>

namespace EnglishReader {

// Instances are owned by the serial synthesis worker. Only cancel() is cross-thread.
class SupertonicEngine {
public:
    SupertonicEngine(int sampleRate, int baseChunkSize, int compress, int latentDim);
    bool load(const std::string& directory);
    bool synthesize(const std::vector<int>& tokens, const std::vector<float>& ttl, const std::vector<int>& ttlShape,
                    const std::vector<float>& dp, const std::vector<int>& dpShape, float speed,
                    std::vector<int16_t>& pcm);
    void cancel();
    const std::string& error() const { return mError; }

private:
    bool fail(const char* error);
    int mSampleRate;
    int mBaseChunkSize;
    int mCompress;
    int mLatentDim;
    std::atomic<uint64_t> mCancellation{0};
    std::string mError;
    std::shared_ptr<MNN::Express::Executor> mExecutor;
    std::shared_ptr<MNN::Express::Module> mDuration;
    std::shared_ptr<MNN::Express::Module> mEncoder;
    std::shared_ptr<MNN::Express::Module> mEstimator;
    std::shared_ptr<MNN::Express::Module> mVocoder;
};
} // namespace EnglishReader
