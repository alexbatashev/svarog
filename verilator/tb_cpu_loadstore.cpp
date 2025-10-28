#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VCpu.h"
#include <iostream>
#include <iomanip>
#include <map>

// Simple memory model for data cache
std::map<uint32_t, uint32_t> memory;

// Program to test load/store
// ADDI x1, x0, 42   -> x1 = 42
// SW x1, 0(x0)      -> mem[0] = 42
// LW x2, 0(x0)      -> x2 = mem[0] = 42
const uint32_t PROGRAM[] = {
    0x02A00093,  // ADDI x1, x0, 42
    0x00102023,  // SW x1, 0(x0)
    0x00002103,  // LW x2, 0(x0)
    0x00000013,  // NOP (ADDI x0, x0, 0)
};
const int PROGRAM_SIZE = sizeof(PROGRAM) / sizeof(PROGRAM[0]);

int main(int argc, char** argv) {
    // Initialize Verilator
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    // Create instance of our module
    VCpu* cpu = new VCpu;

    // Setup VCD tracing
    VerilatedVcdC* tfp = new VerilatedVcdC;
    cpu->trace(tfp, 99);  // Trace 99 levels of hierarchy
    tfp->open("cpu_loadstore.vcd");

    // Initialize signals
    cpu->clock = 0;
    cpu->reset = 1;

    // Initialize dcache signals
    cpu->io_dcache_req_ready = 1;  // Always ready to accept requests
    cpu->io_dcache_resp_valid = 0;
    cpu->io_dcache_resp_bits_data = 0;

    std::cout << "=== CPU Load/Store Test with Waveform Tracing ===" << std::endl;
    std::cout << "Program:" << std::endl;
    std::cout << "  0: ADDI x1, x0, 42  # x1 = 42" << std::endl;
    std::cout << "  1: SW   x1, 0(x0)   # mem[0] = 42" << std::endl;
    std::cout << "  2: LW   x2, 0(x0)   # x2 = mem[0]" << std::endl;
    std::cout << "  3: NOP" << std::endl;
    std::cout << std::endl;
    std::cout << "Expected: x1 = 42, x2 = 42" << std::endl;
    std::cout << std::endl;

    // Track previous request to detect edges
    bool prev_req_valid = false;
    uint32_t pending_load_addr = 0;
    bool load_pending = false;

    // Simulation loop
    uint64_t timestamp = 0;
    int cycle = 0;
    const int MAX_CYCLES = 50;
    uint32_t x1_value = 0;
    uint32_t x2_value = 0;

    while (cycle < MAX_CYCLES && !Verilated::gotFinish()) {
        // Toggle clock
        cpu->clock = !cpu->clock;

        if (cpu->clock) {
            // Rising edge

            // Deassert reset after first cycle
            if (cycle == 0) {
                cpu->reset = 0;
            }

            // Simulate instruction cache
            uint32_t pc = cpu->io_debug_pc;
            uint32_t instruction_idx = pc / 4;

            if (instruction_idx < PROGRAM_SIZE) {
                cpu->io_icache_respValid = 1;
                cpu->io_icache_data = PROGRAM[instruction_idx];
            } else {
                cpu->io_icache_respValid = 0;
                cpu->io_icache_data = 0;
            }

            // Simulate data cache (memory)
            bool curr_req_valid = cpu->io_dcache_req_valid;

            // Default: no response
            cpu->io_dcache_resp_valid = 0;

            // If there's a request this cycle
            if (curr_req_valid && cpu->io_dcache_req_ready) {
                uint32_t addr = cpu->io_dcache_req_bits_addr;
                uint32_t data = cpu->io_dcache_req_bits_data;
                bool is_write = cpu->io_dcache_req_bits_write;

                std::cout << "Cycle " << std::setw(2) << cycle << " [DCACHE]: ";

                if (is_write) {
                    // STORE
                    memory[addr] = data;
                    std::cout << "STORE addr=0x" << std::hex << addr
                              << " data=0x" << data << std::dec << std::endl;
                } else {
                    // LOAD - respond immediately (combinational memory)
                    uint32_t load_data = (memory.count(addr) > 0) ? memory[addr] : 0;
                    cpu->io_dcache_resp_valid = 1;
                    cpu->io_dcache_resp_bits_data = load_data;

                    std::cout << "LOAD  addr=0x" << std::hex << addr
                              << " data=0x" << load_data << std::dec << std::endl;
                }
            }

            // Check for register writes
            if (cpu->io_debug_regWrite) {
                uint32_t addr = cpu->io_debug_writeAddr;
                uint32_t data = cpu->io_debug_writeData;

                std::cout << "Cycle " << std::setw(2) << cycle
                          << " [REGWR]:  x" << std::setw(2) << addr
                          << " <= " << std::setw(3) << data
                          << " (0x" << std::hex << std::setw(8) << std::setfill('0')
                          << data << std::dec << std::setfill(' ') << ")"
                          << std::endl;

                if (addr == 1) x1_value = data;
                if (addr == 2) x2_value = data;
            }

            cycle++;
        }

        // Evaluate model
        cpu->eval();

        // Dump waveform
        tfp->dump(timestamp);
        timestamp += 5;  // 5 time units per half-clock (10 units per cycle)
    }

    // Final evaluation
    cpu->eval();
    tfp->dump(timestamp);

    // Cleanup
    tfp->close();
    cpu->final();
    delete cpu;

    std::cout << "\n=== Simulation Results ===" << std::endl;
    std::cout << "Simulation complete after " << cycle << " cycles" << std::endl;
    std::cout << "Waveform saved to: cpu_loadstore.vcd" << std::endl;
    std::cout << "View with: gtkwave cpu_loadstore.vcd" << std::endl;
    std::cout << std::endl;

    std::cout << "Final register values:" << std::endl;
    std::cout << "  x1 = " << x1_value << " (expected: 42)" << std::endl;
    std::cout << "  x2 = " << x2_value << " (expected: 42)" << std::endl;
    std::cout << std::endl;

    if (x1_value == 42 && x2_value == 42) {
        std::cout << "✓ Test PASSED! Load/Store working correctly." << std::endl;
        return 0;
    } else {
        std::cout << "✗ Test FAILED!" << std::endl;
        if (x1_value != 42) {
            std::cout << "  x1 should be 42, got " << x1_value << std::endl;
        }
        if (x2_value != 42) {
            std::cout << "  x2 should be 42, got " << x2_value << std::endl;
        }
        std::cout << "  Check waveform for details: gtkwave cpu_loadstore.vcd" << std::endl;
        return 1;
    }
}
