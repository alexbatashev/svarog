# Svarog Micro - 5-Stage In-Order RISC-V Core

Svarog Micro is a 5-stage pipelined in-order RISC-V processor implementing RV32IM, designed in Chisel.

## Quick Overview

- **Pipeline**: 5 stages (Fetch, Decode, Execute, Memory, Writeback)
- **ISA**: RV32IM_Zicsr (32-bit base integer + multiply/divide + CSR access)
- **Execution**: In-order, single-issue
- **HDL**: Chisel (generates Verilog)
- **Hazard Handling**: Stall-based with CSR and register hazard detection
- **Branch Prediction**: Static not-taken

## Documentation

- **[Getting Started](../getting-started.md)** - Setup and build instructions
- **[Architecture](architecture.md)** - Pipeline and microarchitecture details
- **[Development](development.md)** - Testing and contribution guide
- **[Configuration](configuration.md)** - SoC configuration options

## Supported ISA Extensions

| Extension | Description | Status |
|-----------|-------------|--------|
| **RV32I** | Base Integer Instruction Set (2.1) | ✅ 38 instructions implemented |
| **M** | Integer Multiplication and Division | ✅ 8 instructions implemented |
| **Zicsr** | Control and Status Register | ✅ 6 instructions implemented (CSRRW/CSRRS/CSRRC + immediate forms) |
| **A** | Atomic Instructions | ❌ Not implemented |
| **F** | Single-Precision Floating-Point | ❌ Not implemented |
| **D** | Double-Precision Floating-Point | ❌ Not implemented |
| **C** | Compressed Instructions | ❌ Not implemented |

**Note**: FENCE and EBREAK instructions are not yet implemented.

## Performance Characteristics

| Metric | Value |
|--------|-------|
| **Ideal CPI** | 1.0 |
| **Branch Penalty** | 2 cycles (taken branches) |
| **Load Latency** | 1 cycle + memory latency |
| **Max Frequency** | ~50 MHz (target, FPGA-dependent) |

## Pipeline Stages

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐
│  FETCH  │───▶│ DECODE  │───▶│ EXECUTE │───▶│ MEMORY  │───▶│WRITEBACK │
└─────────┘    └─────────┘    └─────────┘    └─────────┘    └──────────┘
     ▲                              │
     └──────── Branch Feedback ─────┘
```

See [Architecture](architecture.md) for detailed pipeline documentation.

## File Structure

```
src/main/scala/svarog/
├── micro/              # Pipeline stages
│   ├── Cpu.scala       # Top-level CPU
│   ├── Fetch.scala     # Instruction Fetch
│   ├── Execute.scala   # Execute stage
│   ├── Memory.scala    # Memory access
│   ├── Writeback.scala # Register writeback
│   └── HazardUnit.scala
├── decoder/            # Instruction decode
│   ├── SimpleDecoder.scala
│   ├── BaseInstructions.scala
│   ├── ZicsrInstructions.scala
│   └── ImmGen.scala
├── bits/               # Basic components
│   ├── ALU.scala
│   ├── RegFile.scala
│   └── CSR.scala       # CSR file and execution
├── memory/             # Memory subsystem
│   └── MemoryInterface.scala
└── soc/                # SoC integration
    ├── SvarogSoC.scala
    └── SvarogConfig.scala
```

## Quick Start

### Prerequisites

- JDK 11 or newer
- SBT or Mill build tool
- Verilator (for simulation)

### Build and Test

```bash
# Using Mill
./mill svarog.test

# Using SBT
sbt test

# Run specific test
./mill svarog.test.testOnly svarog.micro.PipelineSpec
```

See [Getting Started](getting-started.md) for detailed setup instructions.

## Contributing

See [Development Guide](development.md) for information on:
- Code structure
- Testing strategy
- Adding new features
- Debugging

## References

- [RISC-V ISA Specifications](https://riscv.org/technical/specifications/)
- [Chisel Documentation](https://www.chisel-lang.org/)
