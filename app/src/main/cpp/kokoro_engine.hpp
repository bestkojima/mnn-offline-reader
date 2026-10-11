#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include <MNN/expr/Executor.hpp>
#include <MNN/expr/Module.hpp>

namespace EnglishReader {
// The serial worker owns inference; cancel() may be called from the UI thread.
class KokoroEngine {
public:
    bool load(const std::string& path);
    bool synthesize(const std::vector<int>& tokens, const std::vector<float>& style, float speed,
                    std::vector<int16_t>& pcm);
    void cancel() { mCancellation.fetch_add(1, std::memory_order_relaxed); }
    const std::string& error() const { return mError; }

private:
    bool fail(const char* message) {
        mError = message;
        return false;
    }
    std::atomic<uint64_t> mCancellation{0};
    std::string mError;
    std::shared_ptr<MNN::Express::Executor> mExecutor;
    std::shared_ptr<MNN::Express::Module> mModel;
};
} // namespace EnglishReader
