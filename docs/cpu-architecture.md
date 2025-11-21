# Svarog CPU Architecture

## Overview

Svarog is a **5-stage pipelined RISC-V processor** implementing the **RV32I** base integer instruction set. The CPU is designed in [Chisel](https://www.chisel-lang.org/), a modern hardware description language embedded in Scala, and generates synthesizable Verilog.

### Key Features

- **5-stage classic RISC pipeline**: Fetch, Decode, Execute, Memory, Writeback
- **RV32I ISA**: Complete support for the 32-bit RISC-V base integer instruction set
- **In-order execution**: Instructions complete in program order
- **Hazard detection**: Automatic detection and handling of data dependencies
- **Type-safe design**: Leverages Chisel's strong typing for correctness
- **Debug support**: Hardware debug interface for halting and inspection
- **Configurable**: Memory size, clock frequency, and other parameters

### Design Philosophy

The Svarog CPU follows a clean, modular architecture where each pipeline stage is a separate, well-defined component with:
- **Decoupled interfaces**: Valid-Ready handshaking between stages
- **Clear data structures**: Type-safe bundles (MicroOp, ExecuteResult, MemResult)
- **Separation of concerns**: Distinct modules for decoding, execution, memory access
- **Testability**: Each stage can be tested independently

---

## Pipeline Architecture

### Pipeline Overview

The CPU implements a classic 5-stage RISC pipeline:

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│  FETCH  │───▶│ DECODE  │───▶│ EXECUTE │───▶│ MEMORY  │───▶│WRITEBACK│
│   (IF)  │    │   (ID)  │    │   (EX)  │    │  (MEM)  │    │   (WB)  │
└─────────┘    └─────────┘    └─────────┘    └─────────┘    └─────────┘
     ▲                                 │
     │         Branch Feedback         │
     └─────────────────────────────────┘
```

### Pipeline Stages

| Stage | Name | Primary Function | Key Components |
|-------|------|------------------|----------------|
| **IF** | Instruction Fetch | Fetch instructions from memory | PC register, Instruction memory interface |
| **ID** | Instruction Decode | Decode instructions into micro-ops | Decoder, Immediate generator, Register file read |
| **EX** | Execute | Arithmetic/logic operations, branch resolution | ALU, Branch unit, Address calculator |
| **MEM** | Memory Access | Load/store data memory operations | Data memory interface, Load/store unit |
| **WB** | Writeback | Write results to register file | Register file write port |

### Inter-Stage Communication

Pipeline stages communicate using **Chisel Queue modules** (depth=1, pipe=true) that implement valid-ready handshaking:

```
┌──────────────────────────────────────────────────────────────────────┐
│                    COMPLETE PIPELINE DATAPATH                         │
└──────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────┐
  │ FETCH STAGE                                                     │
  │  ┌──────┐    ┌──────────┐    ┌──────────────┐                 │
  │  │  PC  │───▶│ InstMem  │───▶│ Instruction  │                 │
  │  └──────┘    │ Request  │    │   + PC       │                 │
  │      ▲       └──────────┘    └──────────────┘                 │
  │      │                              │                          │
  │      │ PC+4 / Branch                │ InstWord                │
  └──────┼──────────────────────────────┼──────────────────────────┘
         │                              ▼
         │                       [fetchDecodeQueue]
         │                              │
  ┌──────┼──────────────────────────────┼──────────────────────────┐
  │      │  DECODE STAGE                ▼                          │
  │      │      ┌────────────────────────────┐                     │
  │      │      │  Instruction Decoder       │                     │
  │      │      │  - Opcode lookup           │                     │
  │      │      │  - Immediate generation    │                     │
  │      │      │  - Register extraction     │                     │
  │      │      └────────────────────────────┘                     │
  │      │                    │                                    │
  │      │                    │ MicroOp                            │
  └──────┼────────────────────┼────────────────────────────────────┘
         │                    ▼
         │             [decodeExecQueue]
         │                    │
  ┌──────┼────────────────────┼────────────────────────────────────┐
  │      │  EXECUTE STAGE     ▼                                    │
  │      │      ┌──────────────────────────┐                       │
  │      │      │  RegFile  │  RegFile     │                       │
  │      │      │  Read     │  Read        │                       │
  │      │      │  (rs1)    │  (rs2)       │                       │
  │      │      └─────┬──────────┬─────────┘                       │
  │      │            │          │                                 │
  │      │            ▼          ▼                                 │
  │      │      ┌─────────────────────────┐                        │
  │      │      │        ALU              │                        │
  │      │      │  - Arithmetic ops       │                        │
  │      │      │  - Logic ops            │                        │
  │      │      │  - Comparisons          │                        │
  │      │      └─────────────────────────┘                        │
  │      │               │                                         │
  │      │               │ Branch taken?                           │
  │      └───────────────┘                                         │
  │                      │                                         │
  │                      │ ExecuteResult                           │
  └──────────────────────┼─────────────────────────────────────────┘
                         ▼
                  [execMemQueue]
                         │
  ┌──────────────────────┼─────────────────────────────────────────┐
  │  MEMORY STAGE        ▼                                         │
  │      ┌──────────────────────────┐                              │
  │      │  Data Memory Interface   │                              │
  │      │  - Load operations       │                              │
  │      │  - Store operations      │                              │
  │      │  - Width extraction      │                              │
  │      │  - Sign extension        │                              │
  │      └──────────────────────────┘                              │
  │                     │                                          │
  │                     │ MemResult                                │
  └─────────────────────┼──────────────────────────────────────────┘
                        ▼
                  [memWbQueue]
                        │
  ┌─────────────────────┼──────────────────────────────────────────┐
  │  WRITEBACK STAGE    ▼                                          │
  │      ┌──────────────────────────┐                              │
  │      │  Register File Write     │                              │
  │      │  - Write enable          │                              │
  │      │  - Destination (rd)      │                              │
  │      │  - Write data            │                              │
  │      └──────────────────────────┘                              │
  │                     │                                          │
  │                     │ Bypass to Execute                        │
  └─────────────────────┼──────────────────────────────────────────┘
                        ▼
                  (Updated Registers)
```

---

## Detailed Stage Descriptions

### Stage 1: Instruction Fetch (IF)

**File**: `src/main/scala/svarog/micro/Fetch.scala`

#### Responsibilities

- Maintain the **Program Counter (PC)** register
- Issue memory requests to instruction memory
- Handle asynchronous instruction memory responses
- Produce (PC, Instruction) pairs for the decode stage
- Support branch redirection from Execute stage
- Support debug PC override

#### Key Features

**PC Update Logic** (priority order):
1. **Debug override** (highest priority) - For debug interface control
2. **Branch feedback** - Target PC from Execute stage when branch taken
3. **Sequential PC+4** (default) - Normal instruction sequence

**Asynchronous Memory Handling**:
- Tracks pending requests with `reqPending` flag
- Tracks pending responses with `respPending` flag
- Buffers response data and PC for valid-ready handshaking

#### Interface

**Inputs**:
- `io.imem`: Decoupled interface to instruction memory
- `io.branch`: Valid signal with branch target PC
- `io.debugPC`: Debug override for PC
- `io.halted`: Halt signal from debug interface

**Outputs**:
- `io.inst_out`: Decoupled[InstWord] containing PC and instruction

#### State Diagram

```
      ┌───────────────┐
      │     IDLE      │
      │  (no pending  │
      │   requests)   │
      └───────┬───────┘
              │ ready && !halted
              ▼
      ┌───────────────┐
      │ REQUEST_SENT  │
      │  reqPending   │
      └───────┬───────┘
              │ imem.resp.valid
              ▼
      ┌───────────────┐
      │RESPONSE_READY │
      │  respPending  │
      └───────┬───────┘
              │ inst_out.ready
              └──────▶ IDLE
```

---

### Stage 2: Instruction Decode (ID)

**File**: `src/main/scala/svarog/decoder/SimpleDecoder.scala`

#### Responsibilities

- Parse 32-bit instruction word into structured MicroOp
- Extract opcode, function codes, register addresses
- Generate immediate values in proper format
- Identify instruction type for downstream stages
- Signal register dependencies for hazard detection

#### Key Components

1. **SimpleDecoder**: Top-level wrapper managing the decode pipeline stage
2. **BaseInstructions**: Core decoder logic implementing instruction parsing
3. **ImmGen**: Immediate value generator supporting all RISC-V formats

#### Instruction Decoding

The decoder supports all RV32I instruction formats:

| Format | Fields | Instructions |
|--------|--------|--------------|
| **R-type** | opcode, rd, funct3, rs1, rs2, funct7 | ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU |
| **I-type** | opcode, rd, funct3, rs1, imm[11:0] | ADDI, SLTI, SLTIU, XORI, ORI, ANDI, SLLI, SRLI, SRAI, LB, LH, LW, LBU, LHU, JALR |
| **S-type** | opcode, funct3, rs1, rs2, imm[11:0] | SB, SH, SW |
| **B-type** | opcode, funct3, rs1, rs2, imm[12:1] | BEQ, BNE, BLT, BGE, BLTU, BGEU |
| **U-type** | opcode, rd, imm[31:12] | LUI, AUIPC |
| **J-type** | opcode, rd, imm[20:1] | JAL |

#### MicroOp Structure

```scala
class MicroOp(xlen: Int) extends Bundle {
  val opType: OpType          // Instruction category
  val aluOp: ALUOp            // ALU operation
  val rd: UInt(5.W)           // Destination register
  val rs1: UInt(5.W)          // Source register 1
  val rs2: UInt(5.W)          // Source register 2
  val hasImm: Bool            // Has immediate?
  val imm: UInt(xlen.W)       // Immediate value
  val memWidth: MemWidth      // Memory access width
  val memUnsigned: Bool       // Unsigned memory operation?
  val branchFunc: BranchOp    // Branch condition
  val regWrite: Bool          // Should write to rd?
  val pc: UInt(xlen.W)        // Program counter
  val isEcall: Bool           // System call?
}
```

#### Operation Types

```scala
object OpType extends ChiselEnum {
  val ALU_REG    // Register-register ALU ops
  val ALU_IMM    // Register-immediate ALU ops
  val LOAD       // Memory load
  val STORE      // Memory store
  val BRANCH     // Conditional branch
  val JAL        // Jump and link
  val JALR       // Jump and link register
  val LUI        // Load upper immediate
  val AUIPC      // Add upper immediate to PC
  val SYSTEM     // System instructions (ECALL, CSR)
}
```

#### Immediate Generation

The ImmGen module handles sign-extension and bit arrangement for different formats:

```
I-format: imm[11:0] = inst[31:20]
          Sign-extend to 32 bits

S-format: imm[11:5] = inst[31:25]
          imm[4:0]  = inst[11:7]
          Sign-extend to 32 bits

B-format: imm[12]   = inst[31]
          imm[10:5] = inst[30:25]
          imm[4:1]  = inst[11:8]
          imm[11]   = inst[7]
          imm[0]    = 0 (always)
          Sign-extend to 32 bits

U-format: imm[31:12] = inst[31:12]
          imm[11:0]  = 0

J-format: imm[20]    = inst[31]
          imm[10:1]  = inst[30:21]
          imm[11]    = inst[20]
          imm[19:12] = inst[19:12]
          imm[0]     = 0 (always)
          Sign-extend to 32 bits
```

---

### Stage 3: Execute (EX)

**File**: `src/main/scala/svarog/micro/Execute.scala`

#### Responsibilities

- Perform arithmetic and logic operations via ALU
- Calculate memory addresses for load/store operations
- Resolve branch conditions and compute branch targets
- Compute jump targets for JAL and JALR
- Forward computed results for writeback bypass
- Generate branch feedback for instruction fetch

#### Key Features

**ALU Operations**:
- All operations complete in a single cycle
- Supports 10 operations: ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND
- 32-bit operands and results

**Branch Resolution**:
The Execute stage resolves all branches and jumps:

```
BEQ:  Branch if rs1 == rs2
BNE:  Branch if rs1 != rs2
BLT:  Branch if rs1 < rs2 (signed)
BGE:  Branch if rs1 >= rs2 (signed)
BLTU: Branch if rs1 < rs2 (unsigned)
BGEU: Branch if rs1 >= rs2 (unsigned)
JAL:  Unconditional jump (PC-relative)
JALR: Unconditional jump (register + immediate)
```

**Branch Prediction**: Static **not-taken** prediction
- Branches assumed not taken during fetch
- When branch is taken, Execute stage sends feedback
- Pipeline flushes wrong-path instructions

**Writeback Bypass**:
```scala
def bypass(readAddr: UInt, readData: UInt): UInt = {
  Mux(
    wbWriteEn && (wbWriteAddr =/= 0.U) && (wbWriteAddr === readAddr),
    wbWriteData,  // Use bypassed value
    readData      // Use register file value
  )
}
```

#### ExecuteResult Structure

```scala
class ExecuteResult(xlen: Int) extends Bundle {
  val opType: OpType          // Instruction type
  val rd: UInt(5.W)           // Destination register
  val regWrite: Bool          // Should write register?
  val intResult: UInt(xlen.W) // ALU/computation result
  val memAddress: UInt(xlen.W)// Memory address
  val memWidth: MemWidth      // Memory access width
  val memUnsigned: Bool       // Unsigned load?
  val storeData: UInt(xlen.W) // Data to store
  val pc: UInt(xlen.W)        // Program counter
}
```

---

### Stage 4: Memory Access (MEM)

**File**: `src/main/scala/svarog/micro/Memory.scala`

#### Responsibilities

- Issue load/store requests to data memory
- Handle asynchronous data memory responses
- Extract and sign-extend loaded data based on width
- Forward store operations (no memory latency for stores in this design)
- Track pending loads for hazard detection

#### Memory Access Widths

| Width | Size | Load Variants | Description |
|-------|------|---------------|-------------|
| BYTE | 8 bits | LB, LBU | Load byte (signed/unsigned) |
| HALF | 16 bits | LH, LHU | Load halfword (signed/unsigned) |
| WORD | 32 bits | LW | Load word |

#### Load Operation Flow

```
1. Execute stage computes address (base + offset)
2. Memory stage issues load request to data memory
3. Set pendingLoad flag
4. Wait for memory response (asynchronous)
5. Extract relevant bytes based on width
6. Sign-extend (if signed operation)
7. Output result to writeback
```

#### Store Operation Flow

```
1. Execute stage computes address and prepares data
2. Memory stage issues store request immediately
3. No pipeline stall (fire-and-forget)
4. Store completes asynchronously
```

#### Data Extraction

For loads, the Memory stage extracts the correct bytes based on address alignment and width:

```
Address[1:0] = 00: Use bytes [7:0] of memory word
Address[1:0] = 01: Use bytes [15:8] of memory word
Address[1:0] = 10: Use bytes [23:16] of memory word
Address[1:0] = 11: Use bytes [31:24] of memory word

BYTE:  8 bits  → Sign/zero extend to 32 bits
HALF: 16 bits  → Sign/zero extend to 32 bits
WORD: 32 bits  → Use as-is
```

#### MemResult Structure

```scala
class MemResult(xlen: Int) extends Bundle {
  val opType: OpType          // Instruction type
  val rd: UInt(5.W)           // Destination register
  val regWrite: Bool          // Should write register?
  val regData: UInt(xlen.W)   // Data to write back
  val pc: UInt(xlen.W)        // Program counter
  val storeAddr: UInt(xlen.W) // Store address (for debug)
  val isStore: Bool           // Is this a store?
}
```

---

### Stage 5: Writeback (WB)

**File**: `src/main/scala/svarog/micro/Writeback.scala`

#### Responsibilities

- Write computation results to register file
- Provide bypass values to Execute stage
- Track which register was written for hazard detection
- Support debug interface for register inspection
- Never stall (always ready)

#### Key Features

**Register File Write**:
```scala
io.regFile.writeEn   := io.mem.valid && io.mem.bits.regWrite
io.regFile.writeAddr := io.mem.bits.rd
io.regFile.writeData := io.mem.bits.regData
```

**Register x0 Protection**:
- x0 is hardwired to 0 in RISC-V
- Register file ignores writes to x0
- Reads from x0 always return 0

**Hazard Broadcasting**:
The Writeback stage provides information to the hazard unit about which register is being written:
```scala
io.hazard.rd       := Mux(io.mem.valid, io.mem.bits.rd, 0.U)
io.hazard.regWrite := io.mem.valid && io.mem.bits.regWrite
```

**Debug Support**:
- Exposes current PC for debug tracking
- Exposes store addresses for watchpoint support
- Allows debug interface to monitor writeback activity

---

## Supporting Components

### Register File

**File**: `src/main/scala/svarog/bits/RegFile.scala`

#### Architecture

- **32 registers** (x0-x31), each 32 bits wide
- **Dual-read ports**: Supports reading two registers simultaneously
- **Single write port**: One register written per cycle
- **Register x0**: Hardwired to zero (reads return 0, writes ignored)
- **Synchronous write**: Updates on rising clock edge
- **Asynchronous read**: Combinational read paths

#### Interface

```scala
// Read interface
io.read.readAddr1: UInt(5.W)   // First register address
io.read.readAddr2: UInt(5.W)   // Second register address
io.read.readData1: UInt(32.W)  // First register value
io.read.readData2: UInt(32.W)  // Second register value

// Write interface
io.write.writeEn: Bool         // Write enable
io.write.writeAddr: UInt(5.W)  // Target register address
io.write.writeData: UInt(32.W) // Value to write
```

---

### Arithmetic Logic Unit (ALU)

**File**: `src/main/scala/svarog/bits/ALU.scala`

#### Supported Operations

| Operation | Function | Description |
|-----------|----------|-------------|
| **ADD** | input1 + input2 | Addition |
| **SUB** | input1 - input2 | Subtraction |
| **SLL** | input1 << input2[4:0] | Shift left logical |
| **SLT** | input1 < input2 (signed) | Set less than |
| **SLTU** | input1 < input2 (unsigned) | Set less than unsigned |
| **XOR** | input1 ^ input2 | Bitwise XOR |
| **SRL** | input1 >> input2[4:0] | Shift right logical |
| **SRA** | input1 >> input2[4:0] (arithmetic) | Shift right arithmetic |
| **OR** | input1 | input2 | Bitwise OR |
| **AND** | input1 & input2 | Bitwise AND |

All operations are **combinational** (complete in same cycle).

---

## Hazard Detection and Control

### Hazard Types

The Svarog CPU handles the following hazards:

1. **Data Hazards (RAW - Read After Write)**:
   - Detected when an instruction reads a register being written by a prior instruction
   - Handled by pipeline stalling

2. **Control Hazards (Branch/Jump)**:
   - Static not-taken branch prediction
   - Pipeline flush on misprediction
   - 1-cycle branch penalty

### Hazard Unit

**File**: `src/main/scala/svarog/micro/HazardUnit.scala`

#### Data Hazard Detection

The hazard unit checks if the current instruction in Decode stage depends on instructions in Execute, Memory, or Writeback stages:

```scala
def hazardOn(reg: UInt, rs: UInt): Bool =
  reg =/= 0.U && rs =/= 0.U && reg === rs

val hazardExec = hazardOn(execRd, decodeRs1) || hazardOn(execRd, decodeRs2)
val hazardMem  = hazardOn(memRd, decodeRs1)  || hazardOn(memRd, decodeRs2)
val hazardWb   = hazardOn(wbRd, decodeRs1)   || hazardOn(wbRd, decodeRs2)

io.stall := hazardExec || hazardMem || hazardWb
```

**Note**: The writeback hazard is eliminated by bypass, but checked for completeness.

#### Pipeline Stall

When a hazard is detected:
1. **Execute stage stalls**: Does not accept new instructions
2. **Fetch and Decode continue**: Can still fetch/decode (pipelined queues)
3. **No bubbles inserted**: Pipeline drains naturally
4. **Stall released**: When hazard clears (dependent instruction moves forward)

### Control Flow Handling

#### Branch Prediction

**Strategy**: Static not-taken
- All branches assumed not-taken
- Fetch continues sequentially (PC+4)
- If branch taken, redirect and flush

#### Pipeline Flush on Branch

When Execute resolves a taken branch:

```
Cycle N:   Execute resolves branch, sends feedback
Cycle N+1: Fetch receives new PC
           Decode queue flushed
Cycle N+2: Execute queue flushed
           New instruction path begins
```

**Flushed stages**: Decode, Execute
**Not flushed**: Memory, Writeback (already past branch resolution)

#### Branch Penalty

- **Not taken, correct**: 0 cycles (no penalty)
- **Taken (mispredicted)**: 2 cycles (flush Decode and Execute)

---

## Pipeline Timing and Performance

### Instruction Latency

| Instruction Type | Latency (Cycles) | Notes |
|------------------|------------------|-------|
| ALU (register) | 1 | Single-cycle execution |
| ALU (immediate) | 1 | Single-cycle execution |
| Branch (not taken) | 1 | No penalty if predicted correctly |
| Branch (taken) | 3 | 1 cycle execute + 2 cycle flush penalty |
| JAL | 3 | Always taken, 2 cycle flush |
| JALR | 3 | Always taken, 2 cycle flush |
| Load | 2+ | 1 cycle + memory latency |
| Store | 1 | Fire-and-forget (async completion) |

### Throughput

**Ideal CPI (Cycles Per Instruction)**: 1.0
- Pipeline can retire one instruction per cycle (when no hazards)

**Actual CPI**: Depends on:
- Data hazard frequency (causes stalls)
- Branch/jump frequency (causes flushes)
- Load instruction frequency (memory latency)

**Best case**: Straight-line ALU code with no dependencies = CPI 1.0
**Worst case**: Dependent loads with taken branches = CPI > 3.0

---

## Memory Interface

### Memory Hierarchy

```
┌────────────────────────────────────────────────┐
│                    CPU Core                     │
│  ┌──────────────┐          ┌──────────────┐   │
│  │ Fetch Stage  │          │Memory Stage  │   │
│  │   (I-MEM)    │          │   (D-MEM)    │   │
│  └──────┬───────┘          └──────┬───────┘   │
└─────────┼────────────────────────┼─────────────┘
          │                        │
          ▼                        ▼
   ┌─────────────┐          ┌─────────────┐
   │ Instruction │          │    Data     │
   │   Memory    │          │   Memory    │
   │  Interface  │          │  Interface  │
   └─────┬───────┘          └─────┬───────┘
         │                        │
         └────────────┬───────────┘
                      ▼
              ┌───────────────┐
              │  TCM / Memory │
              │   Subsystem   │
              └───────────────┘
```

### Memory Interface Protocol

**File**: `src/main/scala/svarog/memory/MemoryInterface.scala`

Both instruction and data memory use decoupled **request-response** protocol:

```scala
// Request
io.req.valid: Bool             // Request valid
io.req.ready: Bool             // Memory ready to accept
io.req.bits.address: UInt      // Memory address
io.req.bits.writeData: Vec[UInt(8.W)]  // Write data (stores)
io.req.bits.writeEn: Vec[Bool] // Byte write enables

// Response
io.resp.valid: Bool            // Response valid
io.resp.ready: Bool            // CPU ready to accept
io.resp.bits.readData: Vec[UInt(8.W)]  // Read data (loads)
```

**Characteristics**:
- **Asynchronous**: Request and response decoupled
- **Variable latency**: Memory can take multiple cycles
- **Byte-addressable**: 4-byte words split into bytes
- **Write masking**: Individual byte enables for partial stores

---

## Configuration

**File**: `src/main/scala/svarog/soc/SvarogConfig.scala`

### Configurable Parameters

```scala
case class SvarogConfig(
  xlen: Int = 32,                    // Data width (RV32)
  memSizeBytes: Int = 0,             // Memory size
  clockHz: Int = 50_000_000,         // Clock frequency
  uartInitialBaud: Int = 115200,     // UART baud rate
  programEntryPoint: Long = 0x80000000L,  // Reset PC
  enableDebugInterface: Boolean = false   // Enable debug
)
```

### Design Parameters

- **xlen**: Fixed at 32 for RV32I
- **memSizeBytes**: Determines TCM size
- **clockHz**: Used for peripherals (UART, etc.)
- **programEntryPoint**: Initial PC value after reset
- **enableDebugInterface**: Enables halt/step/inspect capability

---

## Debug Support

### Debug Interface

The CPU includes a **HartDebugModule** for debugging:

**Capabilities**:
- **Halt**: Stop CPU execution
- **Resume**: Continue execution
- **Single-step**: Execute one instruction
- **Register read/write**: Inspect/modify register file
- **PC override**: Force CPU to specific address
- **Watchpoints**: Monitor store addresses

**Use cases**:
- Software debugging
- Hardware verification
- System bring-up
- Embedded development

### Debug Signals

```scala
io.debug.halted: Bool          // CPU is halted
io.debug.debugPC: Valid[UInt]  // Override PC
io.debug.regRead: RegFileReadIO    // Read registers
io.debug.regWrite: RegFileWriteIO  // Write registers
io.debugStore: Valid[UInt]     // Store address
```

---

## Testing

### Test Strategy

Each pipeline stage has dedicated unit tests:

| Test File | Coverage | Key Tests |
|-----------|----------|-----------|
| **FetchSpec.scala** | Fetch stage | PC advancement, memory interface, branch handling |
| **SimpleDecoderSpec.scala** | Decode stage | Instruction parsing, immediate generation |
| **ExecuteSpec.scala** | Execute stage | ALU operations, branch resolution, stalls |
| **PipelineSpec.scala** | Full pipeline | Multi-instruction sequences, dependencies |
| **CpuDebugSpec.scala** | Debug interface | Halt, step, register access |

### Running Tests

```bash
# Using Mill
./mill svarog.test

# Using SBT
sbt test

# Run specific test
./mill svarog.test.testOnly svarog.micro.PipelineSpec
```

### Test Examples

**Simple ADDI**:
```scala
// addi x1, x0, 5  (x1 = 0 + 5)
val instruction = 0x00500093.U
// Verify: x1 written with value 5
```

**Dependent instructions**:
```scala
// addi x1, x0, 5   (x1 = 5)
// addi x2, x1, 3   (x2 = 5 + 3 = 8)
// Tests hazard detection and bypass
```

---

## Future Enhancements

### Potential Improvements

1. **Forwarding paths**:
   - Execute → Execute forwarding (eliminate some stalls)
   - Memory → Execute forwarding (reduce load-use penalty)

2. **Branch prediction**:
   - Dynamic branch prediction (1-bit, 2-bit counters)
   - Branch Target Buffer (BTB)
   - Return Address Stack (RAS)

3. **Caching**:
   - Instruction cache
   - Data cache
   - Cache coherency

4. **Extended ISA**:
   - M extension (multiply/divide)
   - A extension (atomic operations)
   - F/D extensions (floating point)

5. **Out-of-order execution**:
   - Instruction reordering
   - Register renaming
   - Speculative execution

---

## References

### RISC-V Specifications

- [RISC-V ISA Specification](https://riscv.org/technical/specifications/)
- [RV32I Base Integer Instruction Set](https://riscv.org/wp-content/uploads/2017/05/riscv-spec-v2.2.pdf)

### Chisel Resources

- [Chisel Official Documentation](https://www.chisel-lang.org/)
- [Chisel Bootcamp](https://github.com/freechipsproject/chisel-bootcamp)

### Pipeline Design

- *Computer Organization and Design: The Hardware/Software Interface* by Patterson & Hennessy
- *Computer Architecture: A Quantitative Approach* by Hennessy & Patterson

---

## File Reference

### Core Pipeline Files

- **CPU Top-level**: `src/main/scala/svarog/micro/Cpu.scala:1`
- **Fetch Stage**: `src/main/scala/svarog/micro/Fetch.scala:1`
- **Decode Stage**: `src/main/scala/svarog/decoder/SimpleDecoder.scala:1`
- **Execute Stage**: `src/main/scala/svarog/micro/Execute.scala:1`
- **Memory Stage**: `src/main/scala/svarog/micro/Memory.scala:1`
- **Writeback Stage**: `src/main/scala/svarog/micro/Writeback.scala:1`

### Support Components

- **ALU**: `src/main/scala/svarog/bits/ALU.scala:1`
- **Register File**: `src/main/scala/svarog/bits/RegFile.scala:1`
- **Hazard Unit**: `src/main/scala/svarog/micro/HazardUnit.scala:1`
- **Decoder**: `src/main/scala/svarog/decoder/BaseInstructions.scala:1`
- **Immediate Gen**: `src/main/scala/svarog/decoder/ImmGen.scala:1`

---

*Document Version: 1.0*
*Last Updated: 2025-11-21*
*Author: Svarog Development Team*
