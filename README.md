# Svarog

A RISC-V processor core written in Chisel.

## Prerequisites

### For Chisel Development

- **JDK 11 or newer** - [Download from Adoptium](https://adoptium.net/)
- **Mill** - Included via `./mill` bootstrap script (no install needed)
- **Verilator** - For ChiselTest simulations - [Install guide](https://verilator.org/guide/latest/install.html)

### For Integration Tests (Optional)

Additional tools required to run `testbench/` integration tests:

- **Rust toolchain** - [Install from rustup.rs](https://rustup.rs/)
- **C++ compiler** - For building Verilator wrapper (g++ or clang++)
  ```bash
  # Ubuntu/Debian
  sudo apt-get install build-essential

  # macOS
  xcode-select --install
  ```
- **RISC-V GNU toolchain** - For building test binaries
  - Needs `riscv32-unknown-elf-gcc` and related tools
  - [Installation guide](https://github.com/riscv-collab/riscv-gnu-toolchain)
- **Spike ISA simulator** - Golden reference for comparison
  - [Installation guide](https://github.com/riscv-software-src/riscv-isa-sim)
- **autoconf** and **make** - For building riscv-tests
  ```bash
  # Ubuntu/Debian
  sudo apt-get install autoconf make

  # macOS
  brew install autoconf make
  ```

## Quick Start

### Build and Run Unit Tests

```bash
# Run Chisel unit tests
./mill svarog.test

# Run specific test
./mill svarog.test.testOnly svarog.micro.PipelineSpec
```

### Generate Verilog

```bash
# Generate Verilog for Verilator testbench
./mill -i svarog.runMain svarog.GenerateVerilatorTop --target-dir=target/generated/
```

Options:
- `--xlen=32` - Data width (default: 32)
- `--ram-size-kb=16` - RAM size in KB (default: 16)
- `--clock-hz=50000000` - Clock frequency (default: 50MHz)
- `--target-dir=path` - Output directory (default: `target/generated/`)

### Run Integration Tests

Integration tests use the official RISC-V test suite:

```bash
cd testbench
cargo test
```

This will:
1. Generate Verilog using Mill
2. Clone and build riscv-tests (rv32ui suite)
3. Run each test in Verilator
4. Compare results against Spike (ISA simulator)

Set `SVAROG_MAX_CYCLES` to control simulation timeout:
```bash
SVAROG_MAX_CYCLES=50000 cargo test
```

## Documentation

- **[Getting Started](docs/micro/getting-started.md)** - Detailed setup and build instructions
- **[Architecture](docs/micro/architecture.md)** - Pipeline and microarchitecture details
- **[Development](docs/micro/development.md)** - Testing and contributing guide
- **[Configuration](docs/micro/configuration.md)** - SoC configuration options

## Svarog Micro Features

- **ISA**: RV32IM_Zicsr (base integer + multiply/divide + CSR access)
- **Pipeline**: 5 stages (Fetch, Decode, Execute, Memory, Writeback)
- **Execution**: In-order, single-issue
- **Branch Prediction**: Static not-taken
- **Debug**: Optional hardware debug interface

See [docs/micro/README.md](docs/micro/README.md) for detailed specifications.

## License

See LICENSE file in the repository root.
