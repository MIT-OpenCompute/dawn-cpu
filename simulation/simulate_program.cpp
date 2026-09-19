#include "VMain.h"
#include "verilated.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>
#include <fstream>
#include <cstdlib>
#include <string>
#include <memory>
#include <chrono>


#ifndef DAWN_SLOW_CLOCK
#include "VMain___024root.h"
#endif

static constexpr int H_VISIBLE = 640;
static constexpr int H_FRONT   = 16;
static constexpr int H_SYNC    = 96;
static constexpr int H_BACK    = 48;
static constexpr int H_TOTAL   = H_VISIBLE + H_FRONT + H_SYNC + H_BACK;

static constexpr int V_VISIBLE = 480;
static constexpr int V_FRONT   = 10;
static constexpr int V_SYNC    = 2;
static constexpr int V_BACK    = 33;
static constexpr int V_TOTAL   = V_VISIBLE + V_FRONT + V_SYNC + V_BACK;

static constexpr uint32_t AXI_ADDR_MASK = 0x07FFFFFF;

static constexpr int NUM_BEATS       = 4;
static constexpr int LINE_BYTES      = NUM_BEATS * 16;
static constexpr int WORDS_PER_LINE  = NUM_BEATS * 4;


// Default cycle budget. -1 runs forever. Override at run time with argv[2]
// or the DAWN_CYCLES environment variable, so benchmarking no longer means
// recompiling the whole model.
static constexpr long long CYCLE_LIMIT = -1;


static constexpr int  READ_LATENCY_CYCLES  = 250;
static constexpr int  WRITE_LATENCY_CYCLES = 250;

static constexpr bool RANDOMIZE_LATENCY = false;
static constexpr int  READ_LATENCY_MIN  = 4;
static constexpr int  READ_LATENCY_MAX  = 40;
static constexpr int  WRITE_LATENCY_MIN = 1;
static constexpr int  WRITE_LATENCY_MAX = 20;

static inline int get_read_latency() {
    if (!RANDOMIZE_LATENCY) return READ_LATENCY_CYCLES;
    return READ_LATENCY_MIN + (std::rand() % (READ_LATENCY_MAX - READ_LATENCY_MIN + 1));
}

static inline int get_write_latency() {
    if (!RANDOMIZE_LATENCY) return WRITE_LATENCY_CYCLES;
    return WRITE_LATENCY_MIN + (std::rand() % (WRITE_LATENCY_MAX - WRITE_LATENCY_MIN + 1));
}

static inline uint32_t axi_window(uint32_t addr) {
    return addr & AXI_ADDR_MASK;
}


static constexpr size_t DDR_BYTES = size_t(AXI_ADDR_MASK) + 1;
static uint8_t* mock_ddr = nullptr;

static inline uint8_t* ddr_line(uint32_t addr) {
    return mock_ddr + (axi_window(addr) & ~uint32_t(LINE_BYTES - 1));
}

static void warn_above_window(const std::unique_ptr<VMain>& dut) {
    uint32_t raw = dut->io_mem_req_bits_addr;
    if (raw > AXI_ADDR_MASK) {
        static bool warned = false;
        if (!warned) {
            printf("WARNING: access above 27-bit AXI window: addr=0x%08X (%s) -> aliases to 0x%08X\n",
                   raw, dut->io_mem_req_bits_write ? "write" : "read", axi_window(raw));
            warned = true;
        }
    }
}


static uint32_t crc_table[256];
static bool crc_table_ready = false;

static void init_crc_table() {
    for (uint32_t n = 0; n < 256; n++) {
        uint32_t c = n;
        for (int k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        crc_table[n] = c;
    }
    crc_table_ready = true;
}

static uint32_t crc32_buf(uint32_t crc, const uint8_t* buf, size_t len) {
    for (size_t i = 0; i < len; i++) crc = crc_table[(crc ^ buf[i]) & 0xFF] ^ (crc >> 8);
    return crc;
}

static void put_be32(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back((x >> 24) & 0xFF); v.push_back((x >> 16) & 0xFF);
    v.push_back((x >> 8) & 0xFF);  v.push_back(x & 0xFF);
}

static void png_chunk(FILE* f, const char* type, const std::vector<uint8_t>& data) {
    uint8_t len[4] = { uint8_t(data.size() >> 24), uint8_t(data.size() >> 16),
                       uint8_t(data.size() >> 8),  uint8_t(data.size()) };
    fwrite(len, 1, 4, f);
    fwrite(type, 1, 4, f);
    if (!data.empty()) fwrite(data.data(), 1, data.size(), f);
    uint32_t crc = crc32_buf(0xFFFFFFFFu, reinterpret_cast<const uint8_t*>(type), 4);
    crc = crc32_buf(crc, data.data(), data.size()) ^ 0xFFFFFFFFu;
    uint8_t c[4] = { uint8_t(crc >> 24), uint8_t(crc >> 16), uint8_t(crc >> 8), uint8_t(crc) };
    fwrite(c, 1, 4, f);
}

static bool write_png(const char* path, const uint8_t* rgb, int w, int h) {
    if (!crc_table_ready) init_crc_table();

    // Raw scanlines, each prefixed with filter type 0 (None).
    const size_t stride = size_t(w) * 3;
    std::vector<uint8_t> raw(size_t(h) * (stride + 1));
    for (int y = 0; y < h; y++) {
        raw[size_t(y) * (stride + 1)] = 0;
        memcpy(&raw[size_t(y) * (stride + 1) + 1], rgb + size_t(y) * stride, stride);
    }

    // zlib stream wrapping stored deflate blocks.
    std::vector<uint8_t> z;
    z.reserve(raw.size() + raw.size() / 65535 * 5 + 16);
    z.push_back(0x78); z.push_back(0x01);
    size_t off = 0;
    while (off < raw.size()) {
        size_t n = raw.size() - off;
        if (n > 65535) n = 65535;
        bool final = (off + n == raw.size());
        z.push_back(final ? 1 : 0);
        z.push_back(n & 0xFF);           z.push_back((n >> 8) & 0xFF);
        z.push_back((~n) & 0xFF);        z.push_back(((~n) >> 8) & 0xFF);
        z.insert(z.end(), raw.begin() + off, raw.begin() + off + n);
        off += n;
    }
    uint32_t a = 1, b = 0;
    for (size_t i = 0; i < raw.size(); i++) { a = (a + raw[i]) % 65521; b = (b + a) % 65521; }
    put_be32(z, (b << 16) | a);

    FILE* f = fopen(path, "wb");
    if (!f) return false;
    static const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
    fwrite(sig, 1, 8, f);

    std::vector<uint8_t> ihdr;
    put_be32(ihdr, uint32_t(w));
    put_be32(ihdr, uint32_t(h));
    ihdr.push_back(8);  // bit depth
    ihdr.push_back(2);  // colour type: truecolour RGB
    ihdr.push_back(0); ihdr.push_back(0); ihdr.push_back(0);
    png_chunk(f, "IHDR", ihdr);
    png_chunk(f, "IDAT", z);
    png_chunk(f, "IEND", {});
    fclose(f);
    return true;
}


struct MemModel {
    bool     read_in_progress = false;
    int      read_latency_counter = 0;
    uint32_t active_read_addr = 0;

    bool write_in_progress = false;
    int  write_latency_counter = 0;
};

static inline void mem_step(const std::unique_ptr<VMain>& dut, MemModel& m) {
    dut->io_mem_req_ready = (!m.write_in_progress && !m.read_in_progress) ? 1 : 0;

    if (dut->io_mem_req_valid && dut->io_mem_req_ready) {
        if (dut->io_mem_req_bits_write) {
            memcpy(ddr_line(dut->io_mem_req_bits_addr),
                   &dut->io_mem_req_bits_wdata[0], LINE_BYTES);
            warn_above_window(dut);
            m.write_in_progress = true;
            m.write_latency_counter = get_write_latency();
        } else {
            m.read_in_progress = true;
            m.read_latency_counter = get_read_latency();
            m.active_read_addr = axi_window(dut->io_mem_req_bits_addr);
        }
    }

    if (m.write_in_progress) {
        if (m.write_latency_counter > 0) {
            m.write_latency_counter--;
            dut->io_mem_valid = 0;
        } else {
            dut->io_mem_valid = 1;  // Pulse valid high for write acknowledgement
            m.write_in_progress = false;
        }
    } else if (m.read_in_progress) {
        if (m.read_latency_counter > 0) {
            m.read_latency_counter--;
            dut->io_mem_valid = 0;
        } else {
            dut->io_mem_valid = 1;
            memcpy(&dut->io_mem_resp[0], ddr_line(m.active_read_addr), LINE_BYTES);
            m.read_in_progress = false;
        }
    } else {
        dut->io_mem_valid = 0;
    }
}


static inline void advance_cycle(const std::unique_ptr<VMain>& dut) {
    dut->clock = 1;
    dut->io_vga_clk = 1;
    dut->eval();
#ifdef DAWN_SLOW_CLOCK
    dut->clock = 0;
    dut->io_vga_clk = 0;
    dut->eval();
#else
    dut->clock = 0;
    dut->io_vga_clk = 0;
    dut->rootp->__Vtrigprevexpr___TOP__clock__0 = 0;
    dut->rootp->__Vtrigprevexpr___TOP__io_vga_clk__0 = 0;
#endif
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    auto dut = std::make_unique<VMain>();

    if (RANDOMIZE_LATENCY) {
        std::srand(12345); // fixed seed -- reproducible runs; change or use time(nullptr) for varied runs
    }

    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-bin-file> [cycle-limit]\n", argv[0]);
        return 1;
    }

    auto parse_limit = [](const char* s, long long& out) {
        if (!s) return;
        while (*s == ' ' || *s == '\t') s++;
        if (!*s) return;
        out = atoll(s);
    };

    long long cycle_limit = CYCLE_LIMIT;
    parse_limit(getenv("DAWN_CYCLES"), cycle_limit);
    if (argc >= 3) parse_limit(argv[2], cycle_limit);

    long long frame_limit = -1;
    parse_limit(getenv("DAWN_FRAMES"), frame_limit);

    const bool keep_ppm = getenv("DAWN_PPM") != nullptr;

    long long total_cycles = 0;
    bool limited = cycle_limit >= 0;

    auto limit_reached = [&]() {
        return limited && total_cycles >= cycle_limit;
    };

    dut->io_execute = 0;
    // dut->io_flash   = 0;
    // dut->io_flash_address = 0;
    // dut->io_flash_value   = 0;
    dut->reset      = 1;
    dut->clock      = 0;
    dut->io_vga_clk = 0;
    dut->io_rxd = 1;

    mock_ddr = static_cast<uint8_t*>(calloc(DDR_BYTES, 1));
    if (!mock_ddr) {
        fprintf(stderr, "Failed to allocate %zu MiB of mock DDR\n", DDR_BYTES >> 20);
        return 1;
    }

  
    std::ifstream file(argv[1], std::ios::binary | std::ios::ate);
    if (!file) {
        fprintf(stderr, "Failed to open file: %s\n", argv[1]);
        return 1;
    }
    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);
    if (size < 0) size = 0;
    size_t load_bytes = size_t(size) & ~size_t(3);  // whole 32-bit words only, as before
    if (load_bytes > DDR_BYTES) {
        fprintf(stderr, "Image is %zu bytes, larger than the %zu MiB AXI window\n",
                load_bytes, DDR_BYTES >> 20);
        return 1;
    }
    if (load_bytes && !file.read(reinterpret_cast<char*>(mock_ddr), load_bytes)) {
        fprintf(stderr, "Failed to read %zu bytes from %s\n", load_bytes, argv[1]);
        return 1;
    }

    printf("Preloaded %zu instructions into mock DDR4 space (NUM_BEATS=%d, LINE_BYTES=%d).\n",
           load_bytes / 4, NUM_BEATS, LINE_BYTES);
    if (limited) {
        printf("Cycle limit set: will stop after %lld cycles.\n", cycle_limit);
    } else {
        printf("No cycle limit set: running forever.\n");
    }
    fflush(stdout);

    for (int i = 0; i < 10; i++) {
        dut->clock ^= 1;
        dut->io_vga_clk = dut->clock;
        dut->eval();
    }
    dut->reset = 0;
    dut->io_execute = 1;
    std::vector<uint8_t> pixels(H_VISIBLE * V_VISIBLE * 3, 0);
    bool prev_vsync = 1;
    int pixelIdx = 0;

    MemModel mem;

    const auto t_start = std::chrono::steady_clock::now();
    auto t_frame = t_start;
    long long frame_start_cycle = 0;
    long long frames = 0;

    while (!limit_reached()) {
        pixelIdx = 0;

        while (true) {
            mem_step(dut, mem);
            advance_cycle(dut);

            bool vsync = dut->io_vsync;

            total_cycles++;
            if (limit_reached()) break;

            if (prev_vsync && !vsync) break;
            prev_vsync = vsync;
        }
        prev_vsync = 0;
        if (limit_reached()) break;

        for (int cycle = 0; cycle < H_TOTAL * V_TOTAL; cycle++) {
            mem_step(dut, mem);
            advance_cycle(dut);

            bool vsync    = dut->io_vsync;
            bool blanking = dut->io_blanking;
            uint16_t rgb12 = dut->io_rgb;

            total_cycles++;

            prev_vsync = vsync;

            if (!blanking && pixelIdx < H_VISIBLE * V_VISIBLE) {
                pixels[pixelIdx * 3 + 0] = ((rgb12 >> 8) & 0xF) * 17;
                pixels[pixelIdx * 3 + 1] = ((rgb12 >> 4) & 0xF) * 17;
                pixels[pixelIdx * 3 + 2] = ((rgb12 >> 0) & 0xF) * 17;
                pixelIdx++;
            }

            if (limit_reached()) break;
        }

        if (keep_ppm) {
            FILE* f = fopen("frame.ppm", "wb");
            if (f) {
                fprintf(f, "P6\n%d %d\n255\n", H_VISIBLE, V_VISIBLE);
                fwrite(pixels.data(), 1, pixels.size(), f);
                fclose(f);
            }
        }
        if (!write_png("frame.png", pixels.data(), H_VISIBLE, V_VISIBLE)) {
            perror("frame.png");
        }

        frames++;
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::chrono::duration<double>(now - t_frame).count();
        // printf("[frame %lld] %lld cycles in %.2fs (%.1f kHz, %.1f kHz avg)\n",
        //        frames, total_cycles - frame_start_cycle, dt,
        //        dt > 0 ? (total_cycles - frame_start_cycle) / dt / 1000.0 : 0.0,
        //        total_cycles / std::chrono::duration<double>(now - t_start).count() / 1000.0);
        fflush(stdout);
        t_frame = now;
        frame_start_cycle = total_cycles;

        if (frame_limit >= 0 && frames >= frame_limit) break;
    }

    const double elapsed = std::chrono::duration<double>(
        std::chrono::steady_clock::now() - t_start).count();
    printf("Ran %lld cycles in %.2fs (%.1f kHz)%s\n", total_cycles, elapsed,
           elapsed > 0 ? total_cycles / elapsed / 1000.0 : 0.0,
           limited ? "" : " -- stopped");

    dut->final();
    free(mock_ddr);
    return 0;
}
