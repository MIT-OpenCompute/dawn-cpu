#include "VMain.h"
#include "verilated.h"
#include <cstdio>
#include <vector>
#include <fstream>
#include <cstdlib>
#include <string>
#include <memory>
#include <map>

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


static constexpr long long CYCLE_LIMIT = -1;


static constexpr int  READ_LATENCY_CYCLES  = 4;
static constexpr int  WRITE_LATENCY_CYCLES = 4;

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

// Commits a write request's line-width wdata into the mock DDR row at the
// aligned address. Loop bound and row size both scale with NUM_BEATS.
static void handle_mem_write(std::unique_ptr<VMain>& dut,
                              std::map<uint32_t, std::vector<uint8_t>>& mock_ddr3) {
    uint32_t addr = axi_window(dut->io_mem_req_bits_addr);
    uint32_t line_base_addr = (addr / LINE_BYTES) * LINE_BYTES;

    if (mock_ddr3.find(line_base_addr) == mock_ddr3.end()) {
        mock_ddr3[line_base_addr] = std::vector<uint8_t>(LINE_BYTES, 0);
    }
    auto& data_row = mock_ddr3[line_base_addr];

    for (int w = 0; w < WORDS_PER_LINE; w++) {
        *(uint32_t*)&data_row[w * 4] = dut->io_mem_req_bits_wdata[w];
    }

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

// Copies a mock DDR row's contents out into io_mem_resp (or zeros if the
// line was never written). Loop bound scales with NUM_BEATS.
static void handle_mem_read_resp(std::unique_ptr<VMain>& dut,
                                  std::map<uint32_t, std::vector<uint8_t>>& mock_ddr3,
                                  uint32_t active_read_addr) {
    uint32_t target_aligned_addr = (active_read_addr / LINE_BYTES) * LINE_BYTES;

    auto it = mock_ddr3.find(target_aligned_addr);
    if (it != mock_ddr3.end()) {
        auto& data_row = it->second;
        for (int w = 0; w < WORDS_PER_LINE; w++) {
            dut->io_mem_resp[w] = *(uint32_t*)&data_row[w * 4];
        }
    } else {
        for (int w = 0; w < WORDS_PER_LINE; w++) {
            dut->io_mem_resp[w] = 0;
        }
    }
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    auto dut = std::make_unique<VMain>();

    if (RANDOMIZE_LATENCY) {
        std::srand(12345); // fixed seed -- reproducible runs; change or use time(nullptr) for varied runs
    }

    long long total_cycles = 0;
    bool limited = CYCLE_LIMIT >= 0;

    auto limit_reached = [&]() {
        return limited && total_cycles >= CYCLE_LIMIT;
    };

    dut->io_execute = 0;
    // dut->io_flash   = 0;
    // dut->io_flash_address = 0;
    // dut->io_flash_value   = 0;
    dut->reset      = 1;
    dut->clock      = 0;
    dut->io_vga_clk = 0;
    dut->io_rxd = 1;

    std::map<uint32_t, std::vector<uint8_t>> mock_ddr3;

    if (argc < 2) {
        fprintf(stderr, "Usage: %s <path-to-bin-file>\n", argv[0]);
        return 1;
    }

    std::ifstream file(argv[1], std::ios::binary);
    if (!file) {
        fprintf(stderr, "Failed to open file: %s\n", argv[1]);
        return 1;
    }

    uint32_t current_byte_addr = 0;
    uint32_t instruction;

    while (file.read(reinterpret_cast<char*>(&instruction), sizeof(instruction))) {
        uint32_t line_base_addr = (axi_window(current_byte_addr) / LINE_BYTES) * LINE_BYTES;
        uint32_t byte_offset    = current_byte_addr % LINE_BYTES;

        if (mock_ddr3.find(line_base_addr) == mock_ddr3.end()) {
            mock_ddr3[line_base_addr] = std::vector<uint8_t>(LINE_BYTES, 0);
        }

        mock_ddr3[line_base_addr][byte_offset + 0] = (instruction >> 0)  & 0xFF;
        mock_ddr3[line_base_addr][byte_offset + 1] = (instruction >> 8)  & 0xFF;
        mock_ddr3[line_base_addr][byte_offset + 2] = (instruction >> 16) & 0xFF;
        mock_ddr3[line_base_addr][byte_offset + 3] = (instruction >> 24) & 0xFF;

        current_byte_addr += 4;
    }
    printf("Preloaded %d instructions into mock DDR4 space (NUM_BEATS=%d, LINE_BYTES=%d).\n",
           current_byte_addr / 4, NUM_BEATS, LINE_BYTES);
    if (limited) {
        printf("Cycle limit set: will stop after %lld cycles.\n", CYCLE_LIMIT);
    } else {
        printf("No cycle limit set: running forever.\n");
    }

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

    // --- State variables for tracking memory operations ---
    // NOTE: this models a memory controller that can only have ONE
    // request in flight at a time -- io_mem_req_ready is only ever
    // asserted while both write_in_progress and read_in_progress are
    // false. This mirrors the real ddr4_line_memory (req_ready only
    // true in S_IDLE) and the DCache's own single-outstanding-miss
    // guarantee. Previously ready was asserted unconditionally whenever
    // valid was high, which let a second request appear "accepted" while
    // one was already in flight -- silently dropped rather than stalled,
    // invisible at low request pressure but exactly the kind of bug that
    // shows up once non-blocking loads start overlapping requests.
    bool read_in_progress = false;
    int  read_latency_counter = 0;
    uint32_t active_read_addr = 0;

    bool write_in_progress = false;
    int  write_latency_counter = 0;

    while (!limit_reached()) {
        pixelIdx = 0;

        while (true) {
            dut->clock = 1;
            dut->io_vga_clk = 1;

            // 1. Process Incoming Handshakes
            // ready is only ever high when nothing is currently in flight.
            dut->io_mem_req_ready = (!write_in_progress && !read_in_progress) ? 1 : 0;

            if (dut->io_mem_req_valid && dut->io_mem_req_ready) {
                if (dut->io_mem_req_bits_write) {
                    handle_mem_write(dut, mock_ddr3);
                    write_in_progress = true;
                    write_latency_counter = get_write_latency();
                } else {
                    read_in_progress = true;
                    read_latency_counter = get_read_latency();
                    active_read_addr = axi_window(dut->io_mem_req_bits_addr);
                }
            }

            // 2. Return Responses / Manage Timing
            if (write_in_progress) {
                if (write_latency_counter > 0) {
                    write_latency_counter--;
                    dut->io_mem_valid = 0;
                } else {
                    dut->io_mem_valid = 1; // Pulse valid high for write acknowledgement
                    write_in_progress = false;
                }
            } else if (read_in_progress) {
                if (read_latency_counter > 0) {
                    read_latency_counter--;
                    dut->io_mem_valid = 0;
                } else {
                    dut->io_mem_valid = 1;
                    handle_mem_read_resp(dut, mock_ddr3, active_read_addr);
                    read_in_progress = false;
                }
            } else {
                dut->io_mem_valid = 0;
            }

            dut->eval();

            bool vsync = dut->io_vsync;

            dut->clock = 0;
            dut->io_vga_clk = 0;
            dut->eval();

            total_cycles++;
            if (limit_reached()) break;

            if (prev_vsync && !vsync) break;
            prev_vsync = vsync;
        }
        prev_vsync = 0;
        if (limit_reached()) break;

        for (int cycle = 0; cycle < H_TOTAL * V_TOTAL; cycle++) {
            dut->clock = 1;
            dut->io_vga_clk = 1;

            // 1. Process Incoming Handshakes
            dut->io_mem_req_ready = (!write_in_progress && !read_in_progress) ? 1 : 0;

            if (dut->io_mem_req_valid && dut->io_mem_req_ready) {
                if (dut->io_mem_req_bits_write) {
                    handle_mem_write(dut, mock_ddr3);
                    write_in_progress = true;
                    write_latency_counter = get_write_latency();
                } else {
                    read_in_progress = true;
                    read_latency_counter = get_read_latency();
                    active_read_addr = dut->io_mem_req_bits_addr;
                }
            }

            // 2. Return Responses / Manage Timing
            if (write_in_progress) {
                if (write_latency_counter > 0) {
                    write_latency_counter--;
                    dut->io_mem_valid = 0;
                } else {
                    dut->io_mem_valid = 1;
                    write_in_progress = false;
                }
            } else if (read_in_progress) {
                if (read_latency_counter > 0) {
                    read_latency_counter--;
                    dut->io_mem_valid = 0;
                } else {
                    dut->io_mem_valid = 1;
                    handle_mem_read_resp(dut, mock_ddr3, active_read_addr);
                    read_in_progress = false;
                }
            } else {
                dut->io_mem_valid = 0;
            }

            dut->eval();

            bool vsync    = dut->io_vsync;
            bool blanking = dut->io_blanking;
            uint16_t rgb12 = dut->io_rgb;

            dut->clock = 0;
            dut->io_vga_clk = 0;
            dut->eval();

            total_cycles++;

            if (prev_vsync && !vsync) {
                // printf("vsync mid-frame at cycle %d — counter mismatch!\n", cycle);
            }
            prev_vsync = vsync;

            if (!blanking && pixelIdx < H_VISIBLE * V_VISIBLE) {
                pixels[pixelIdx * 3 + 0] = ((rgb12 >> 8) & 0xF) * 17;
                pixels[pixelIdx * 3 + 1] = ((rgb12 >> 4) & 0xF) * 17;
                pixels[pixelIdx * 3 + 2] = ((rgb12 >> 0) & 0xF) * 17;
                pixelIdx++;
            }

            if (limit_reached()) break;
        }

        // printf("Captured %d pixels (expected %d)\n", pixelIdx, H_VISIBLE * V_VISIBLE);

        FILE* f = fopen("frame.ppm", "wb");
        if (!f) { perror("fopen"); return 1; }
        fprintf(f, "P6\n%d %d\n255\n", H_VISIBLE, V_VISIBLE);
        fwrite(pixels.data(), 1, pixels.size(), f);
        fclose(f);
        if (system("ffmpeg -i frame.ppm frame.png -y > /dev/null 2>&1") != 0) {
             printf("Frame dumped out to local disk as frame.ppm safely.\n");
        }
    }

    if (limited) {
        printf("Stopped after %lld cycles (limit=%lld).\n", total_cycles, CYCLE_LIMIT);
    }

    dut->final();
    return 0;
}