# RISC-V Tests Integration

This document describes the automated RISC-V tests integration that downloads and builds the official RISC-V ISA tests.

## Prerequisites

You must have the RISC-V GNU toolchain installed and available on your PATH:

```bash
# Check if toolchain is available
riscv32-unknown-elf-gcc --version
riscv32-unknown-elf-objcopy --version
```

If not installed, you can:
- Download prebuilt binaries from: https://github.com/riscv-collab/riscv-gnu-toolchain/releases
- Or build from source: https://github.com/riscv-collab/riscv-gnu-toolchain

Additionally, you need:
- `autoconf` for building riscv-tests
- `git` for cloning the repository

## What Happens During Build

When you run `cargo build`, the `build.rs` script automatically:

1. **Generates Verilog**: Runs `mill svarog.runMain svarog.GenerateVerilatorTop` to create the RTL
2. **Clones riscv-tests**: Downloads the official RISC-V tests repository (if not present)
3. **Initializes submodules**: Sets up the test environment
4. **Configures build**: Runs `autoconf` and `./configure`
5. **Builds rv32ui tests**: Compiles all RV32UI (user-level integer) tests
6. **Generates hex files**: Converts ELF binaries to hex format for loading

The entire process is cached - subsequent builds only regenerate if needed.

## Output Structure

After building, you'll have:

```
testbench/
├── riscv-tests/              # Cloned repository (gitignored)
│   └── isa/
│       ├── rv32ui-p-add      # ELF test binaries
│       ├── rv32ui-p-addi
│       └── ...
└── tests/                    # Generated test files (gitignored)
    ├── manifest.rs           # Auto-generated test list
    ├── rv32ui-p-add.hex      # Simple hex format (one word per line)
    ├── rv32ui-p-add.vh       # Verilog hex format
    ├── rv32ui-p-add.bin      # Raw binary
    └── ...
```

## RV32UI Test Suite

The RV32UI test suite includes tests for:

- **Arithmetic**: add, sub, addi
- **Logical**: and, or, xor, andi, ori, xori
- **Shifts**: sll, srl, sra, slli, srli, srai
- **Comparisons**: slt, sltu, slti, sltiu
- **Branches**: beq, bne, blt, bltu, bge, bgeu
- **Loads**: lb, lh, lw, lbu, lhu
- **Stores**: sb, sh, sw
- **Jump**: jal, jalr
- **Upper immediates**: lui, auipc

Each test is self-checking and will indicate pass/fail.

## Hex File Format

The generated `.hex` files contain 32-bit words in hexadecimal, one per line:

```
00500093
00700113
002081b3
0000006f
```

This format is easy to parse and load into the instruction ROM.

## Running Tests

The test runner is integrated into the crate:

```bash
# Run all rv32ui tests
cargo test test_rv32ui_tests -- --nocapture

# Run a specific test
cargo test test_simple_program
```

The test manifest is auto-generated and includes all found rv32ui tests.

## Test Results

RISC-V tests use the HTIF (Host-Target Interface) or "tohost" mechanism to report results:
- Writing to a specific address indicates pass/fail
- The test number identifies which assertion failed

Currently, our test harness looks for:
- PC getting stuck at 0 (test completion)
- Timeout after N cycles

You may need to enhance the test detection logic for more accurate results.

## Customization

### Building Different Test Sets

To build other test suites (rv32um, rv32ua, etc.), modify `build.rs`:

```rust
// Change this line:
cmd!(sh, "make -j isa/rv32ui-p-add")

// To:
cmd!(sh, "make -j isa/rv32um-p-mul")
```

### Adjusting Test Timeout

Modify the timeout in `lib.rs`:

```rust
// Change max_cycles:
let result = run_test(&mut dut, 10000);  // 10k cycles
```

### Adding Test Filters

Filter which tests to run by modifying the loop in `test_rv32ui_tests`:

```rust
for test_name in RV32UI_TESTS {
    if !test_name.contains("add") {
        continue;  // Skip non-add tests
    }
    // ... run test
}
```

## Troubleshooting

### Build fails with "riscv32-unknown-elf-gcc: command not found"

Install the RISC-V GNU toolchain and ensure it's on your PATH.

### Build fails with "autoconf: command not found"

Install autoconf: `brew install autoconf` (macOS) or `apt-get install autoconf` (Linux)

### Tests timeout

- Increase the cycle limit in `run_test()`
- Check if your CPU is executing instructions (use debug prints)
- Verify the program loaded correctly

### No tests found

Check that:
1. Build completed successfully (`cargo build --verbose`)
2. `testbench/tests/` directory exists and contains `.hex` files
3. `testbench/tests/manifest.rs` was generated

## Performance

Building all rv32ui tests takes approximately:
- First time: 2-5 minutes (cloning + building)
- Subsequent: 5-15 seconds (only changed files)

Running all tests in Verilator:
- ~40 tests × ~10k cycles = ~400k cycles
- Typical simulation speed: 10-50 kHz
- Total time: 10-60 seconds (depends on CPU performance)

## Next Steps

To create a complete validation harness:

1. **Implement proper test detection**: Detect HTIF/tohost writes
2. **Add Spike integration**: Run same tests on Spike ISA simulator
3. **Compare traces**: Validate PC and register values match
4. **Generate reports**: Create pass/fail summary with details
5. **Add more test suites**: rv32um (multiply), rv32ua (atomics), etc.
