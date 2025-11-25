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
- Perform ALU operations
- Calculate memory addresses
- Resolve branches and jumps
- Generate branch feedback

**Execution Units**:
- **ALU** (`src/main/scala/svarog/bits/ALU.scala`): Arithmetic and logic operations, 1 cycle
- **Multiplier** (`src/main/scala/svarog/bits/SimpleMultiplier.scala`): Multi-cycle multiply/divide
- **CSREx** (`src/main/scala/svarog/bits/CSR.scala`): CSR read/modify/write operations, 1 cycle

**Branch Resolution**:
- Branches resolved in Execute stage
- Static not-taken prediction
- Misprediction penalty: 2 cycles

**CSR Operations**:
- CSR reads happen in Execute stage (combinational)
- CSR writes propagate to Writeback stage
- Three operations: CSRRW (read/write), CSRRS (read/set), CSRRC (read/clear)
- Immediate forms (CSRRWI, CSRRSI, CSRRCI) use zero-extended 5-bit immediate

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

**CSR File** (`src/main/scala/svarog/bits/CSR.scala`):
- Read-only CSRs: mvendorid, marchid, mimpid, mhartid, misa
- Single-read port, single write port
- CSR writes deferred to Writeback stage
- Read-only registers ignore writes

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
```

**Resolution**: Pipeline stall until dependency clears.

### CSR Hazards

Detected when a CSR instruction in Decode depends on a pending CSR write:

```scala
def csrHazardOn(csrAddr: UInt, decodeCsrAddr: UInt, isWrite: Bool): Bool =
  isWrite && csrAddr === decodeCsrAddr

val csrHazardExec = decode.isCsrOp && csrHazardOn(execCsr, decodeCsr, execCsrWrite)
val csrHazardMem  = decode.isCsrOp && csrHazardOn(memCsr, decodeCsr, memCsrWrite)
val csrHazardWb   = decode.isCsrOp && csrHazardOn(wbCsr, decodeCsr, wbCsrWrite)

io.stall := hazardExec || hazardMem || hazardWb ||
            csrHazardExec || csrHazardMem || csrHazardWb
```

**Resolution**: Pipeline stall until CSR write completes in Writeback stage.

### Control Hazards (Branches)

**Static Prediction**: Not-taken
- Fetch continues sequentially
- On taken branch: flush Decode and Execute stages
- Penalty: 2 cycles

**Flush Logic**:
- `fetchDecodeQueue.flush` on branch taken
- `decodeExecQueue.flush` on branch taken
- Memory and Writeback not flushed (already past branch point)

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

## Related Documentation

- [Getting Started](../getting-started.md) - Setup and build
- [Development Guide](../development.md) - Testing and contribution
- [Configuration](../configuration.md) - SoC parameters
