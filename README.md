# Svarog

Svarog is a family of RISC-V processor cores written in Chisel.

## Current Status

**Svarog Micro** - 5-stage in-order RV32I pipeline
- ✅ Implemented and tested
- See [docs/micro/](docs/micro/) for architecture and usage

## Prerequisites

### For Chisel Development

- **JDK 11 or newer** - [Download from Adoptium](https://adoptium.net/)
- **Mill** - Included via `./mill` bootstrap script (no install needed)
- **Verilator** - For ChiselTest simulations - [Install guide](https://verilator.org/guide/latest/install.html)

### For Integration Tests (Optional)

Additional tools required to run `testbench/` integration tests:

- **Rust toolchain** - [Install from rustup.rs](https://rustup.rs/)
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

## Project Structure

```
svarog/
├── src/
│   ├── main/scala/svarog/
│   │   ├── micro/          # Micro core pipeline stages
│   │   ├── decoder/        # Instruction decoder
│   │   ├── bits/           # Basic components (ALU, RegFile)
│   │   ├── memory/         # Memory interfaces
│   │   ├── soc/            # SoC integration
│   │   ├── debug/          # Debug interface
│   │   └── GenerateVerilatorTop.scala
│   └── test/scala/svarog/  # Unit tests
├── testbench/              # Integration tests (Rust + Verilator)
│   ├── src/lib.rs
│   ├── tests/riscv-tests.rs
│   └── build.rs
├── docs/                   # Documentation
│   └── micro/              # Micro core docs
└── build.mill              # Mill build configuration
```

## Documentation

- **[Getting Started](docs/micro/getting-started.md)** - Detailed setup and build instructions
- **[Architecture](docs/micro/architecture.md)** - Pipeline and microarchitecture details
- **[Development](docs/micro/development.md)** - Testing and contributing guide
- **[Configuration](docs/micro/configuration.md)** - SoC configuration options

## Svarog Micro Features

- **ISA**: RV32I base integer instruction set
- **Pipeline**: 5 stages (Fetch, Decode, Execute, Memory, Writeback)
- **Execution**: In-order, single-issue
- **CPI**: ~1.0 for straight-line code
- **Branch Prediction**: Static not-taken
- **Debug**: Optional hardware debug interface

See [docs/micro/README.md](docs/micro/README.md) for detailed specifications.

## Build Requirements

The build system expects a `.mill-jvm-opts` file in the repository root:
```
-Dchisel.project.root=${PWD}
```

This is needed for Chisel to properly locate test directories and output files.

## License

See LICENSE file in the repository root.

## Resources

- **Chisel**: [chisel-lang.org](https://www.chisel-lang.org/)
- **RISC-V**: [riscv.org](https://riscv.org/)
- **Chisel Bootcamp**: [github.com/freechipsproject/chisel-bootcamp](https://github.com/freechipsproject/chisel-bootcamp)
