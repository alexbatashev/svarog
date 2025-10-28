#include <verilated.h>
#include "VCpu.h"
#include <iostream>
#include <iomanip>

// Simple program: ADDI x1, x0, 42
// Encoded as: 0x02A00093
const uint32_t PROGRAM[] = {
    0x02A00093,  // ADDI x1, x0, 42
};
const int PROGRAM_SIZE = sizeof(PROGRAM) / sizeof(PROGRAM[0]);

int main(int argc, char** argv) {
    // Initialize Verilator
    Verilated::commandArgs(argc, argv);

    // Create instance of our module
    VCpu* cpu = new VCpu;

    // Initialize signals
    cpu->clock = 0;
    cpu->reset = 1;

    std::cout << "=== CPU Verilator Simulation ===" << std::endl;
    std::cout << "Program: ADDI x1, x0, 42" << std::endl;
    std::cout << std::endl;

    // Simulation loop
    int cycle = 0;
    const int MAX_CYCLES = 20;

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
            // In a real system, this would be your cache controller
            // For now, we'll just respond to PC with the appropriate instruction
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
                          << " <= " << data
                          << " (0x" << std::hex << data << std::dec << ")"
                          << std::endl;

                // Exit when we see the write to x1
                if (addr == 1 && data == 42) {
                    std::cout << "\n✓ Test passed! x1 = 42" << std::endl;
                    break;
                }
            }

            cycle++;
        }

        // Evaluate model
        cpu->eval();
    }

    // Cleanup
    cpu->final();
    delete cpu;

    std::cout << "\nSimulation complete after " << cycle << " cycles" << std::endl;

    return 0;
}
