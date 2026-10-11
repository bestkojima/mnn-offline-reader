#include "kokoro_engine.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <thread>

namespace {
void writeInt(std::ofstream& file, uint32_t value, int bytes) {
    for (int i = 0; i < bytes; ++i)
        file.put(static_cast<char>((value >> (8 * i)) & 255));
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 5) {
        std::cerr << "kokoro_smoke <tts.mnn> <voice.bin> <tokens.txt> <output.wav>\n";
        return 1;
    }
    std::ifstream tokenFile(argv[3]);
    std::vector<int> tokens;
    int id;
    while (tokenFile >> id)
        tokens.push_back(id);
    const int count = static_cast<int>(tokens.size()) - 2;
    if (count < 1 || count > 200)
        return 2;
    std::ifstream voiceFile(argv[2], std::ios::binary);
    std::vector<float> style(256);
    voiceFile.seekg(count * 256 * sizeof(float));
    voiceFile.read(reinterpret_cast<char*>(style.data()), style.size() * sizeof(float));
    if (!voiceFile.good())
        return 3;
    EnglishReader::KokoroEngine engine;
    if (!engine.load(argv[1])) {
        std::cerr << engine.error();
        return 4;
    }
    std::vector<int16_t> pcm;
    if (engine.synthesize({}, style, 1.0f, pcm))
        return 5;
    std::thread canceller([&]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        engine.cancel();
    });
    const bool completed = engine.synthesize(tokens, style, 1.0f, pcm);
    canceller.join();
    if (completed || engine.error() != "Cancelled")
        return 6;
    const auto start = std::chrono::steady_clock::now();
    if (!engine.synthesize(tokens, style, 1.0f, pcm)) {
        std::cerr << engine.error();
        return 7;
    }
    std::ofstream file(argv[4], std::ios::binary);
    file.write("RIFF", 4);
    writeInt(file, 36 + pcm.size() * 2, 4);
    file.write("WAVEfmt ", 8);
    writeInt(file, 16, 4);
    writeInt(file, 1, 2);
    writeInt(file, 1, 2);
    writeInt(file, 24000, 4);
    writeInt(file, 48000, 4);
    writeInt(file, 2, 2);
    writeInt(file, 16, 2);
    file.write("data", 4);
    writeInt(file, pcm.size() * 2, 4);
    for (auto sample : pcm)
        writeInt(file, static_cast<uint16_t>(sample), 2);
    std::cout << "sample_rate=24000 samples=" << pcm.size() << " synthesis_seconds="
              << std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count() << '\n';
    return file.good() && !pcm.empty() ? 0 : 8;
}
