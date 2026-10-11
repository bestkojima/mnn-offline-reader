#include "supertonic_engine.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>
#include <nlohmann/json.hpp>

namespace {
nlohmann::json readJson(const std::string& path) {
    std::ifstream input(path);
    return nlohmann::json::parse(input, nullptr, false);
}
void flatten(const nlohmann::json& value, std::vector<float>& result) {
    if (value.is_array())
        for (const auto& child : value)
            flatten(child, result);
    else if (value.is_number())
        result.push_back(value.get<float>());
}
void writeInt(std::ofstream& file, uint32_t value, int bytes) {
    for (int i = 0; i < bytes; ++i)
        file.put(static_cast<char>((value >> (8 * i)) & 255));
}
} // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "reader_smoke <v3-directory> <output.wav> [voice] [tagged-text]\n";
        return 1;
    }
    const std::string root(argv[1]);
    const std::string voice = argc > 3 ? argv[3] : "F1";
    const std::string text = argc > 4 ? argv[4] : "<en>curious.</en>";
    auto config = readJson(root + "/mnn_models/tts.json");
    auto indexer = readJson(root + "/mnn_models/unicode_indexer.json");
    auto style = readJson(root + "/voice_styles/" + voice + ".json");
    if (!config.is_object() || !indexer.is_array() || !style.is_object())
        return 2;
    int rate = config["ae"]["sample_rate"];
    EnglishReader::SupertonicEngine engine(rate, config["ae"]["base_chunk_size"],
                                           config["ttl"]["chunk_compress_factor"], config["ttl"]["latent_dim"]);
    if (!engine.load(root + "/mnn_models/int8")) {
        std::cerr << engine.error();
        return 3;
    }
    std::vector<int> tokens;
    // CLI text is already normalized/tagged, but may contain any UTF-8 script.
    for (size_t offset = 0; offset < text.size();) {
        const uint8_t first = static_cast<uint8_t>(text[offset++]);
        uint32_t cp = first;
        int remaining = 0;
        uint32_t minimum = 0;
        if (first >= 0xc2 && first <= 0xdf) {
            cp = first & 0x1f;
            remaining = 1;
            minimum = 0x80;
        } else if (first >= 0xe0 && first <= 0xef) {
            cp = first & 0x0f;
            remaining = 2;
            minimum = 0x800;
        } else if (first >= 0xf0 && first <= 0xf4) {
            cp = first & 7;
            remaining = 3;
            minimum = 0x10000;
        } else if (first >= 0x80)
            return 8;
        for (int i = 0; i < remaining; ++i) {
            if (offset >= text.size())
                return 8;
            const uint8_t next = static_cast<uint8_t>(text[offset++]);
            if ((next & 0xc0) != 0x80)
                return 8;
            cp = (cp << 6) | (next & 0x3f);
        }
        if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) || cp >= indexer.size())
            return 8;
        const int id = indexer[cp].get<int>();
        if (id < 0)
            return 8;
        tokens.push_back(id);
    }
    std::vector<float> ttl, dp;
    flatten(style["style_ttl"]["data"], ttl);
    flatten(style["style_dp"]["data"], dp);
    std::vector<int16_t> pcm;
    const auto ttlShape = style["style_ttl"]["dims"].get<std::vector<int>>();
    const auto dpShape = style["style_dp"]["dims"].get<std::vector<int>>();
    if (engine.synthesize({}, ttl, ttlShape, dp, dpShape, 1.0f, pcm))
        return 6;
    std::thread canceller([&]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        engine.cancel();
    });
    bool completed = engine.synthesize(tokens, ttl, ttlShape, dp, dpShape, 1.0f, pcm);
    canceller.join();
    if (completed || engine.error() != "Cancelled") {
        std::cerr << "In-flight cancellation failed\n";
        return 7;
    }
    auto begin = std::chrono::steady_clock::now();
    if (!engine.synthesize(tokens, ttl, ttlShape, dp, dpShape, 1.0f, pcm)) {
        std::cerr << engine.error();
        return 4;
    }
    std::ofstream file(argv[2], std::ios::binary);
    file.write("RIFF", 4);
    writeInt(file, 36 + pcm.size() * 2, 4);
    file.write("WAVEfmt ", 8);
    writeInt(file, 16, 4);
    writeInt(file, 1, 2);
    writeInt(file, 1, 2);
    writeInt(file, rate, 4);
    writeInt(file, rate * 2, 4);
    writeInt(file, 2, 2);
    writeInt(file, 16, 2);
    file.write("data", 4);
    writeInt(file, pcm.size() * 2, 4);
    for (auto sample : pcm)
        writeInt(file, static_cast<uint16_t>(sample), 2);
    std::cout << "sample_rate=" << rate << " samples=" << pcm.size() << " synthesis_seconds="
              << std::chrono::duration<double>(std::chrono::steady_clock::now() - begin).count() << '\n';
    return file.good() && !pcm.empty() ? 0 : 5;
}
