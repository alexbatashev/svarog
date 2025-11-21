# Svarog Micro Architecture

This document describes the microarchitecture of the Svarog Micro core, a 5-stage in-order RISC-V pipeline.

## Pipeline Overview

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌──────────┐
│  FETCH  │───▶│ DECODE  │───▶│ EXECUTE │───▶│ MEMORY  │───▶│WRITEBACK │
│   (IF)  │    │   (ID)  │    │   (EX)  │    │  (MEM)  │    │   (WB)   │
└─────────┘    └─────────┘    └─────────┘    └─────────┘    └──────────┘
     ▲                              │                              │
     │         Branch Feedback      │        Bypass Data           │
     └──────────────────────────────┘◀─────────────────────────────┘
```

### Stage Summary

| Stage | Function | Key Components | Latency |
|-------|----------|----------------|---------|
| **IF** | Fetch instructions | PC, Instruction memory | 1 cycle + mem latency |
| **ID** | Decode instructions | Decoder, ImmGen | 1 cycle |
| **EX** | Execute operations | ALU, Branch unit | 1 cycle |
| **MEM** | Memory access | Data memory | 1 cycle + mem latency |
| **WB** | Register writeback | Register file write | 1 cycle |

### Pipeline Registers

Stages communicate via Chisel Queue modules (depth=1, pipe=true):

```
IF → fetchDecodeQueue → ID → decodeExecQueue → EX → execMemQueue → MEM → memWbQueue → WB
```

**Data Structures:**
- `InstWord`: PC + instruction (32-bit each)
- `MicroOp`: Decoded operation with all control signals
- `ExecuteResult`: ALU/address result + memory info
- `MemResult`: Final result ready for writeback

## Detailed Stage Descriptions

### Stage 1: Instruction Fetch (IF)

**Location**: `src/main/scala/svarog/micro/Fetch.scala`

**Responsibilities**:
- Maintain Program Counter (PC)
- Fetch instructions from instruction memory
- Handle branch redirections

**PC Update Priority**:
1. Debug override (highest priority)
2. Branch feedback from Execute
3. PC + 4 (sequential)

**Key Registers**:
- `pc_reg`: Current program counter
- `reqPending`, `respPending`: Track async memory requests

### Stage 2: Instruction Decode (ID)

**Location**: `src/main/scala/svarog/decoder/SimpleDecoder.scala`

**Responsibilities**:
- Parse 32-bit instruction into MicroOp
- Extract opcode, function codes, registers
- Generate immediates (via ImmGen)
- Provide register addresses for hazard detection

**Decoder Components**:
- `BaseInstructions`: Core opcode decoding
- `ImmGen`: Immediate value generation (I/S/B/U/J formats)
- `Opcodes`: Opcode definitions

**Output**: `MicroOp` bundle containing:
- Operation type (ALU_REG, ALU_IMM, LOAD, STORE, BRANCH, etc.)
- ALU operation
- Register addresses (rd, rs1, rs2)
- Immediate value
- Control signals

### Stage 3: Execute (EX)

**Location**: `src/main/scala/svarog/micro/Execute.scala`

**Responsibilities**:
- Perform ALU operations (10 operations: ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND)
- Calculate memory addresses
- Resolve branches and jumps
- Generate branch feedback

**ALU Operations** (`src/main/scala/svarog/bits/ALU.scala`):
All operations complete in one cycle, fully combinational.

**Branch Resolution**:
- Branches resolved in Execute stage
- Static not-taken prediction
- Misprediction penalty: 2 cycles

**Bypass Logic**:
Writeback-to-Execute bypass for immediate register availability:
```scala
def bypass(readAddr: UInt, readData: UInt): UInt = {
  Mux(wbWriteEn && (wbWriteAddr =/= 0.U) && (wbWriteAddr === readAddr),
    wbWriteData,  // Use bypassed value
    readData      // Use register file value
  )
}
```

### Stage 4: Memory Access (MEM)

**Location**: `src/main/scala/svarog/micro/Memory.scala`

**Responsibilities**:
- Issue load/store requests to data memory
- Handle asynchronous memory responses
- Extract and sign/zero-extend load data
- Track pending loads

**Memory Access Widths**:
- BYTE (8-bit): LB, LBU, SB
- HALF (16-bit): LH, LHU, SH
- WORD (32-bit): LW, SW

**Load Handling**:
1. Issue request to data memory
2. Set `pendingLoad` flag
3. Wait for asynchronous response
4. Extract correct bytes based on address alignment
5. Sign/zero extend to 32 bits

**Store Handling**:
Fire-and-forget - no pipeline stall.

### Stage 5: Writeback (WB)

**Location**: `src/main/scala/svarog/micro/Writeback.scala`

**Responsibilities**:
- Write results to register file
- Provide bypass data to Execute
- Broadcast hazard information
- Never stalls (always ready)

**Register File** (`src/main/scala/svarog/bits/RegFile.scala`):
- 32 registers (x0-x31), 32-bit each
- x0 hardwired to zero
- Dual-read ports, single write port
- Synchronous write, combinational read

## Hazard Detection and Handling

**Location**: `src/main/scala/svarog/micro/HazardUnit.scala`

### RAW (Read-After-Write) Hazards

Detected when instruction in Decode reads register being written by earlier instruction:

```scala
def hazardOn(reg: UInt, rs: UInt): Bool =
  reg =/= 0.U && rs =/= 0.U && reg === rs

val hazardExec = hazardOn(execRd, decodeRs1) || hazardOn(execRd, decodeRs2)
val hazardMem  = hazardOn(memRd, decodeRs1)  || hazardOn(memRd, decodeRs2)
val hazardWb   = hazardOn(wbRd, decodeRs1)   || hazardOn(wbRd, decodeRs2)

io.stall := hazardExec || hazardMem || hazardWb
```

**Resolution**: Pipeline stall until dependency clears.

### Control Hazards (Branches)

**Static Prediction**: Not-taken
- Fetch continues sequentially
- On taken branch: flush Decode and Execute stages
- Penalty: 2 cycles

**Flush Logic**:
- `fetchDecodeQueue.flush` on branch taken
- `decodeExecQueue.flush` on branch taken
- Memory and Writeback not flushed (already past branch point)

## Pipeline Timing

### Instruction Latency

```
┌──────────────────┬─────────┬────────────────────┐
│ Instruction Type │ Latency │ Notes              │
├──────────────────┼─────────┼────────────────────┤
│ ALU              │ 5 cycles│ No dependencies    │
│ Load             │ 5+ cyc  │ + memory latency   │
│ Store            │ 5 cycles│ Fire-and-forget    │
│ Branch not taken │ 5 cycles│ Correct prediction │
│ Branch taken     │ 7 cycles│ 2-cycle penalty    │
│ JAL/JALR         │ 7 cycles│ Always taken       │
└──────────────────┴─────────┴────────────────────┘
```

### Example: Instruction Timeline

```
Cycle │  IF   │  ID   │  EX   │  MEM  │  WB   │
──────┼───────┼───────┼───────┼───────┼───────┤
  1   │ Inst1 │       │       │       │       │
  2   │ Inst2 │ Inst1 │       │       │       │
  3   │ Inst3 │ Inst2 │ Inst1 │       │       │
  4   │ Inst4 │ Inst3 │ Inst2 │ Inst1 │       │
  5   │ Inst5 │ Inst4 │ Inst3 │ Inst2 │ Inst1 │ ← Inst1 completes
  6   │ Inst6 │ Inst5 │ Inst4 │ Inst3 │ Inst2 │ ← Inst2 completes
```

**Ideal throughput**: 1 instruction per cycle (CPI = 1.0)

### Example: Data Hazard

```
Instructions:
  addi x1, x0, 5   (Inst1)
  addi x2, x1, 3   (Inst2) - depends on x1

Cycle │  IF   │  ID   │  EX   │  MEM  │  WB   │
──────┼───────┼───────┼───────┼───────┼───────┤
  1   │ Inst1 │       │       │       │       │
  2   │ Inst2 │ Inst1 │       │       │       │
  3   │ Inst3 │ Inst2 │ Inst1 │       │       │ ← HAZARD! x1 dependency
  4   │ Inst3 │ STALL │ STALL │ Inst1 │       │ ← Stall until x1 ready
  5   │ Inst3 │ STALL │ STALL │ STALL │ Inst1 │ ← x1 written
  6   │ Inst3 │ Inst2 │ Inst2 │ STALL │ STALL │ ← Resume
```

**Stall penalty**: 3 cycles for Execute-stage dependency

## Memory Interface

**Location**: `src/main/scala/svarog/memory/MemoryInterface.scala`

### Request-Response Protocol

Both instruction and data memory use decoupled (valid-ready) protocol:

```
Request:
  - valid: Bool             // Request is valid
  - ready: Bool             // Memory accepts request
  - address: UInt(32)       // Memory address
  - writeData: Vec[UInt(8)] // Data to write (stores)
  - writeEn: Vec[Bool]      // Byte write enables

Response:
  - valid: Bool             // Response is valid
  - ready: Bool             // CPU accepts response
  - readData: Vec[UInt(8)]  // Data read (loads)
```

**Characteristics**:
- Asynchronous (variable latency)
- Byte-addressable (4-byte words)
- Byte-granular write enables

## Performance Characteristics

### Best Case Performance

**Straight-line ALU code (no dependencies)**:
- CPI = 1.0
- One instruction retires per cycle

### Typical Performance

Depends on code characteristics:
- **Data hazards**: +3 cycles per RAW dependency (without bypass elimination)
- **Branches taken**: +2 cycles per misprediction
- **Loads**: +memory latency cycles

### Bottlenecks

1. **Load-use dependencies**: No forwarding from Memory to Execute
2. **Branch mispredictions**: Static not-taken prediction (50% accuracy for taken branches)
3. **Serial execution**: No instruction-level parallelism

## Optimization Opportunities

### Forwarding Paths

**Execute → Execute forwarding**:
- Eliminate 1-cycle ALU dependencies
- Requires adding forwarding mux in Execute stage

**Memory → Execute forwarding**:
- Reduce load-use penalty from 3 cycles to 1 cycle
- Requires forwarding from Memory stage output

### Branch Prediction

**1-bit predictor**:
- Track last outcome per branch
- ~80% accuracy improvement

**2-bit saturating counter**:
- More stable predictions
- ~85-90% accuracy

**Branch Target Buffer (BTB)**:
- Cache branch targets
- Eliminate fetch latency on taken branches

## Debug Support

**Location**: `src/main/scala/svarog/debug/HartDebug.scala`

**Capabilities**:
- Halt/resume execution
- Single-step mode
- Read/write register file
- PC override
- Watchpoint support (store addresses)

**Use Cases**:
- Software debugging
- Hardware verification
- System bring-up

## File Reference

```
src/main/scala/svarog/
├── micro/
│   ├── Cpu.scala          # Top-level CPU integration
│   ├── Fetch.scala        # Stage 1
│   ├── Execute.scala      # Stage 3
│   ├── Memory.scala       # Stage 4
│   ├── Writeback.scala    # Stage 5
│   └── HazardUnit.scala   # Hazard detection
├── decoder/
│   ├── SimpleDecoder.scala     # Stage 2 (top-level)
│   ├── BaseInstructions.scala  # Instruction decode logic
│   ├── ImmGen.scala            # Immediate generation
│   ├── MicroOp.scala           # MicroOp bundle definition
│   └── Opcodes.scala           # Opcode constants
├── bits/
│   ├── ALU.scala          # Arithmetic Logic Unit
│   └── RegFile.scala      # Register file
├── memory/
│   ├── MemoryInterface.scala  # Memory protocol
│   └── TCM.scala              # Tightly-Coupled Memory
└── soc/
    ├── SvarogSoC.scala    # SoC top-level
    └── SvarogConfig.scala # Configuration
```

## Related Documentation

- [Getting Started](getting-started.md) - Setup and build
- [Development Guide](development.md) - Testing and contribution
- [Configuration](configuration.md) - SoC parameters
