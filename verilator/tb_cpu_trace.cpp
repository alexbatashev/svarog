#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VCpu.h"
#include <iostream>
#include <iomanip>

// Program with RAW hazard
// ADDI x1, x0, 10   -> x1 = 10
// ADD  x2, x1, x1   -> x2 = 20 (hazard on x1!)
const uint32_t PROGRAM[] = {
    0x00A00093,  // ADDI x1, x0, 10
    0x00108133,  // ADD x2, x1, x1
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
    tfp->open("cpu_trace.vcd");

    // Initialize signals
    cpu->clock = 0;
    cpu->reset = 1;

    std::cout << "=== CPU Verilator Simulation with Waveform Tracing ===" << std::endl;
    std::cout << "Program:" << std::endl;
    std::cout << "  0: ADDI x1, x0, 10  # x1 = 10" << std::endl;
    std::cout << "  1: ADD  x2, x1, x1  # x2 = 20 (RAW hazard!)" << std::endl;
    std::cout << std::endl;
    std::cout << "Expected: Hazard detection should stall the pipeline" << std::endl;
    std::cout << "Result: x1 = 10, x2 = 20" << std::endl;
    std::cout << std::endl;

    // Simulation loop
    uint64_t timestamp = 0;
    int cycle = 0;
    const int MAX_CYCLES = 30;
    bool test_passed = false;

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

            // Check for register writes
            if (cpu->io_debug_regWrite) {
                uint32_t addr = cpu->io_debug_writeAddr;
                uint32_t data = cpu->io_debug_writeData;

                std::cout << "Cycle " << std::setw(2) << cycle
                          << ": x" << std::setw(2) << addr
                          << " <= " << std::setw(3) << data
                          << " (0x" << std::hex << std::setw(8) << std::setfill('0')
                          << data << std::dec << std::setfill(' ') << ")"
                          << std::endl;

                // Check if test passed
                if (addr == 2 && data == 20) {
                    test_passed = true;
                }
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

    std::cout << "\nSimulation complete after " << cycle << " cycles" << std::endl;
    std::cout << "Waveform saved to: cpu_trace.vcd" << std::endl;
    std::cout << "View with: gtkwave cpu_trace.vcd" << std::endl;
    std::cout << std::endl;

    if (test_passed) {
        std::cout << "✓ Test PASSED! Hazard detection working correctly." << std::endl;
        return 0;
    } else {
        std::cout << "✗ Test FAILED! Check waveform for details." << std::endl;
        return 1;
    }
}
