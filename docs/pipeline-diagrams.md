# Svarog CPU Pipeline Diagrams

This document provides detailed visual representations of the Svarog CPU pipeline, including data flow, control signals, hazard handling, and instruction execution examples.

---

## Table of Contents

1. [Basic Pipeline Structure](#basic-pipeline-structure)
2. [Pipeline Registers and Data Structures](#pipeline-registers-and-data-structures)
3. [Instruction Flow Examples](#instruction-flow-examples)
4. [Hazard Detection and Handling](#hazard-detection-and-handling)
5. [Branch Handling](#branch-handling)
6. [Memory Interface Timing](#memory-interface-timing)

---

## Basic Pipeline Structure

### High-Level Pipeline View

```
        ┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐
        │         │      │         │      │         │      │         │      │         │
        │  FETCH  │─────▶│ DECODE  │─────▶│ EXECUTE │─────▶│ MEMORY  │─────▶│WRITEBACK│
        │         │      │         │      │         │      │         │      │         │
        └────▲────┘      └─────────┘      └────┬────┘      └─────────┘      └────┬────┘
             │                                  │                                  │
             │                                  │                                  │
             │         Branch Feedback          │         Bypass Data              │
             └──────────────────────────────────┘◀─────────────────────────────────┘
```

### Detailed Pipeline with Register File

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                                                               │
│   ┌───────────────────────────────────────────────────────────────────┐     │
│   │                     REGISTER FILE (32 x 32-bit)                   │     │
│   │                                                                   │     │
│   │  x0 (zero) │ x1 │ x2 │ x3 │ ... │ x28 │ x29 │ x30 │ x31 (t6)    │     │
│   └────────────┬──────────────────────────────────────────┬───────────┘     │
│                │                                          │                 │
│                │  Read Ports (rs1, rs2)                   │ Write Port (rd) │
│                ▼                                          ▲                 │
│   ┌─────────────────────────────────────────────────────┼─────────────┐   │
│   │                       EXECUTE                        │             │   │
│   └──────────────────────────────────────────────────────┼─────────────┘   │
│                                                           │                 │
│                                                           │                 │
│   ┌───────────────────────────────────────────────────────────────────┐   │
│   │                        WRITEBACK                                  │   │
│   └───────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Pipeline Registers and Data Structures

### Pipeline Register Contents

```
┌─────────┐        ┌──────────────────┐        ┌─────────┐
│  FETCH  │───────▶│ fetchDecodeQueue │───────▶│ DECODE  │
└─────────┘        └──────────────────┘        └─────────┘
                    InstWord {
                      pc: UInt(32)
                      instruction: UInt(32)
                    }


┌─────────┐        ┌──────────────────┐        ┌─────────┐
│ DECODE  │───────▶│ decodeExecQueue  │───────▶│ EXECUTE │
└─────────┘        └──────────────────┘        └─────────┘
                    MicroOp {
                      opType: OpType
                      aluOp: ALUOp
                      rd, rs1, rs2: UInt(5)
                      hasImm: Bool
                      imm: UInt(32)
                      memWidth: MemWidth
                      memUnsigned: Bool
                      branchFunc: BranchOp
                      regWrite: Bool
                      pc: UInt(32)
                      isEcall: Bool
                    }


┌─────────┐        ┌──────────────────┐        ┌─────────┐
│ EXECUTE │───────▶│  execMemQueue    │───────▶│ MEMORY  │
└─────────┘        └──────────────────┘        └─────────┘
                    ExecuteResult {
                      opType: OpType
                      rd: UInt(5)
                      regWrite: Bool
                      intResult: UInt(32)
                      memAddress: UInt(32)
                      memWidth: MemWidth
                      memUnsigned: Bool
                      storeData: UInt(32)
                      pc: UInt(32)
                    }


┌─────────┐        ┌──────────────────┐        ┌──────────┐
│ MEMORY  │───────▶│   memWbQueue     │───────▶│WRITEBACK │
└─────────┘        └──────────────────┘        └──────────┘
                    MemResult {
                      opType: OpType
                      rd: UInt(5)
                      regWrite: Bool
                      regData: UInt(32)
                      pc: UInt(32)
                      storeAddr: UInt(32)
                      isStore: Bool
                    }
```

### Data Path Width Summary

```
┌──────────────┬──────────┬────────────────────────────────┐
│ Signal       │ Width    │ Description                    │
├──────────────┼──────────┼────────────────────────────────┤
│ PC           │ 32 bits  │ Program counter                │
│ Instruction  │ 32 bits  │ Instruction word               │
│ Register Addr│  5 bits  │ Register index (0-31)          │
│ Register Data│ 32 bits  │ Register value                 │
│ Immediate    │ 32 bits  │ Sign-extended immediate        │
│ ALU operands │ 32 bits  │ ALU inputs/outputs             │
│ Memory Addr  │ 32 bits  │ Memory address                 │
│ Memory Data  │ 32 bits  │ Load/store data                │
└──────────────┴──────────┴────────────────────────────────┘
```

---

## Instruction Flow Examples

### Example 1: Simple ALU Instruction (ADDI)

**Instruction**: `addi x1, x0, 5`  (x1 = x0 + 5)
**Encoding**: `0x00500093`

```
Cycle 1: FETCH
┌──────────────────────────────────────┐
│ PC = 0x80000000                      │
│ Request instruction from I-MEM       │
│ PC_next = PC + 4 = 0x80000004       │
└──────────────────────────────────────┘

Cycle 2: DECODE
┌──────────────────────────────────────┐
│ InstWord received                    │
│   - PC: 0x80000000                  │
│   - Inst: 0x00500093                │
│                                      │
│ Decode:                             │
│   opcode  = 0010011 (ALU_IMM)       │
│   rd      = 00001 (x1)              │
│   funct3  = 000 (ADDI)              │
│   rs1     = 00000 (x0)              │
│   imm     = 000000000101 (5)        │
│                                      │
│ MicroOp generated:                  │
│   opType  = ALU_IMM                 │
│   aluOp   = ADD                     │
│   rd      = 1                       │
│   rs1     = 0                       │
│   hasImm  = true                    │
│   imm     = 5                       │
│   regWrite= true                    │
└──────────────────────────────────────┘

Cycle 3: EXECUTE
┌──────────────────────────────────────┐
│ Read Register File:                  │
│   rs1_data = RF[0] = 0              │
│                                      │
│ ALU Operation:                      │
│   input1 = rs1_data = 0             │
│   input2 = imm = 5                  │
│   operation = ADD                   │
│   result = 0 + 5 = 5                │
│                                      │
│ ExecuteResult:                      │
│   rd = 1                            │
│   intResult = 5                     │
│   regWrite = true                   │
└──────────────────────────────────────┘

Cycle 4: MEMORY
┌──────────────────────────────────────┐
│ No memory operation                  │
│ Pass through ExecuteResult           │
│                                      │
│ MemResult:                          │
│   rd = 1                            │
│   regData = 5                       │
│   regWrite = true                   │
└──────────────────────────────────────┘

Cycle 5: WRITEBACK
┌──────────────────────────────────────┐
│ Write to Register File:              │
│   RF[1] = 5                         │
│                                      │
│ Instruction complete!               │
│ x1 now contains value 5             │
└──────────────────────────────────────┘
```

**Total Latency**: 5 cycles (but pipeline can accept new instruction every cycle)

---

### Example 2: Dependent Instructions with Hazard

**Instructions**:
```
addi x1, x0, 5    # x1 = 0 + 5 = 5
addi x2, x1, 3    # x2 = x1 + 3 = 8 (depends on x1)
```

```
Cycle  │  IF   │  ID   │  EX   │  MEM  │  WB   │ Notes
───────┼───────┼───────┼───────┼───────┼───────┼──────────────────────
   1   │ Inst1 │       │       │       │       │ Fetch ADDI x1, x0, 5
   2   │ Inst2 │ Inst1 │       │       │       │ Fetch ADDI x2, x1, 3
   3   │ Inst3 │ Inst2 │ Inst1 │       │       │ HAZARD DETECTED!
       │       │ STALL │       │       │       │ Inst2 reads x1, Inst1 writes x1
   4   │ Inst3 │ Inst2 │ STALL │ Inst1 │       │ Execute stalled
       │       │ STALL │       │       │       │ Decode stalled
   5   │ Inst3 │ Inst2 │ STALL │ STALL │ Inst1 │ Pipeline draining
       │       │ STALL │       │       │       │ x1 written to RF
   6   │ Inst3 │ Inst2 │ Inst2 │ STALL │ STALL │ Hazard cleared
       │       │       │       │       │       │ Inst2 can now execute
   7   │ Inst4 │ Inst3 │ Inst3 │ Inst2 │ STALL │
   8   │ Inst5 │ Inst4 │ Inst4 │ Inst3 │ Inst2 │ x2 written = 8
```

**Hazard Detection Timeline**:

```
Cycle 3: Decode stage decodes "addi x2, x1, 3"
┌─────────────────────────────────────────────┐
│ HazardUnit checks:                          │
│                                             │
│ Decode instruction uses: rs1 = x1          │
│                                             │
│ Execute has: rd = x1, regWrite = true      │
│                                             │
│ HAZARD: x1 dependency detected!            │
│                                             │
│ Action: Assert stall signal                │
│         - Fetch continues                  │
│         - Decode stalls (holds Inst2)      │
│         - Execute stalls (no new work)     │
└─────────────────────────────────────────────┘

Cycle 6: Hazard resolved
┌─────────────────────────────────────────────┐
│ x1 has been written to register file        │
│                                             │
│ Decode can now read correct value of x1    │
│                                             │
│ Action: De-assert stall signal             │
│         Pipeline resumes normal flow       │
└─────────────────────────────────────────────┘
```

**Total Cycles for 2 instructions**: 8 cycles (3 stall cycles)

---

### Example 3: Load Instruction

**Instruction**: `lw x1, 0(x2)`  (Load word from address in x2)

Assume x2 contains address 0x80001000

```
Cycle 1-2: FETCH, DECODE (similar to previous examples)

Cycle 3: EXECUTE
┌──────────────────────────────────────┐
│ Read Register File:                  │
│   rs1_data = RF[2] = 0x80001000     │
│                                      │
│ Address Calculation:                │
│   addr = rs1_data + imm             │
│   addr = 0x80001000 + 0             │
│   addr = 0x80001000                 │
│                                      │
│ ExecuteResult:                      │
│   opType = LOAD                     │
│   rd = 1                            │
│   memAddress = 0x80001000           │
│   memWidth = WORD                   │
└──────────────────────────────────────┘

Cycle 4: MEMORY (Request)
┌──────────────────────────────────────┐
│ Issue memory request:                │
│   dmem.req.valid = true             │
│   dmem.req.addr = 0x80001000        │
│   dmem.req.writeEn = 0000 (read)    │
│                                      │
│ Set pendingLoad flag                │
│ Wait for memory response...          │
└──────────────────────────────────────┘

Cycle 5+N: MEMORY (Response)
┌──────────────────────────────────────┐
│ Memory responds (N cycles later):    │
│   dmem.resp.valid = true            │
│   dmem.resp.data = 0xDEADBEEF       │
│                                      │
│ Extract word from bytes              │
│   readData = 0xDEADBEEF             │
│                                      │
│ Clear pendingLoad flag              │
│                                      │
│ MemResult:                          │
│   rd = 1                            │
│   regData = 0xDEADBEEF              │
│   regWrite = true                   │
└──────────────────────────────────────┘

Cycle 6+N: WRITEBACK
┌──────────────────────────────────────┐
│ Write to Register File:              │
│   RF[1] = 0xDEADBEEF                │
└──────────────────────────────────────┘
```

**Load-Use Hazard**:
If the next instruction uses x1, it must wait for the load to complete:

```
lw   x1, 0(x2)      # Load into x1
addi x3, x1, 5      # Use x1 (HAZARD!)

Cycle  │  IF   │  ID   │  EX   │  MEM  │  WB   │
───────┼───────┼───────┼───────┼───────┼───────┤
   1   │ LW    │       │       │       │       │
   2   │ ADDI  │ LW    │       │       │       │
   3   │ ...   │ ADDI  │ LW    │       │       │ HAZARD!
   4   │ ...   │ STALL │ STALL │ LW    │       │ Stall until load completes
   5   │ ...   │ STALL │ STALL │ LW    │       │ (waiting for memory)
   6   │ ...   │ ADDI  │ ADDI  │ STALL │ LW    │ Load completes, resume
```

---

### Example 4: Store Instruction

**Instruction**: `sw x1, 4(x2)`  (Store x1 to address x2+4)

Assume x2 = 0x80001000, x1 = 0x12345678

```
Cycle 3: EXECUTE
┌──────────────────────────────────────┐
│ Read Register File:                  │
│   rs1_data = RF[2] = 0x80001000     │
│   rs2_data = RF[1] = 0x12345678     │
│                                      │
│ Address Calculation:                │
│   addr = 0x80001000 + 4 = 0x80001004│
│                                      │
│ ExecuteResult:                      │
│   opType = STORE                    │
│   memAddress = 0x80001004           │
│   storeData = 0x12345678            │
│   memWidth = WORD                   │
└──────────────────────────────────────┘

Cycle 4: MEMORY
┌──────────────────────────────────────┐
│ Issue memory write:                  │
│   dmem.req.valid = true             │
│   dmem.req.addr = 0x80001004        │
│   dmem.req.writeData = [0x78, 0x56, │
│                         0x34, 0x12] │
│   dmem.req.writeEn = 1111 (all)     │
│                                      │
│ No wait needed (fire-and-forget)    │
│                                      │
│ MemResult:                          │
│   regWrite = false (no RF write)    │
│   isStore = true                    │
└──────────────────────────────────────┘

Cycle 5: WRITEBACK
┌──────────────────────────────────────┐
│ No register write                    │
│ Store completes asynchronously       │
└──────────────────────────────────────┘
```

**Note**: Stores do not stall the pipeline in this design.

---

## Hazard Detection and Handling

### Data Hazard Detection Logic

```
┌─────────────────────────────────────────────────────────────┐
│                      HAZARD UNIT                            │
│                                                             │
│  Inputs from Decode stage:                                 │
│    - decode_rs1: UInt(5)    // Source register 1           │
│    - decode_rs2: UInt(5)    // Source register 2           │
│                                                             │
│  Inputs from Execute stage:                                │
│    - exec_rd: UInt(5)       // Destination register        │
│    - exec_regWrite: Bool    // Will write to rd            │
│                                                             │
│  Inputs from Memory stage:                                 │
│    - mem_rd: UInt(5)        // Destination register        │
│    - mem_regWrite: Bool     // Will write to rd            │
│                                                             │
│  Inputs from Writeback stage:                              │
│    - wb_rd: UInt(5)         // Destination register        │
│    - wb_regWrite: Bool      // Will write to rd            │
│                                                             │
│  ┌───────────────────────────────────────────────┐         │
│  │ Hazard Check Function                         │         │
│  │                                               │         │
│  │ def hazardOn(reg: UInt, rs: UInt): Bool = {  │         │
│  │   reg =/= 0.U &&     // Not x0               │         │
│  │   rs =/= 0.U &&      // Not x0               │         │
│  │   reg === rs         // Same register         │         │
│  │ }                                             │         │
│  └───────────────────────────────────────────────┘         │
│                                                             │
│  Hazard Detection:                                         │
│  ┌─────────────────────────────────────────────┐           │
│  │ hazard_exec_rs1 = hazardOn(exec_rd, decode_rs1)        │
│  │ hazard_exec_rs2 = hazardOn(exec_rd, decode_rs2)        │
│  │ hazard_exec = hazard_exec_rs1 || hazard_exec_rs2       │
│  │                                                         │
│  │ hazard_mem_rs1 = hazardOn(mem_rd, decode_rs1)          │
│  │ hazard_mem_rs2 = hazardOn(mem_rd, decode_rs2)          │
│  │ hazard_mem = hazard_mem_rs1 || hazard_mem_rs2          │
│  │                                                         │
│  │ hazard_wb_rs1 = hazardOn(wb_rd, decode_rs1)            │
│  │ hazard_wb_rs2 = hazardOn(wb_rd, decode_rs2)            │
│  │ hazard_wb = hazard_wb_rs1 || hazard_wb_rs2             │
│  │                                                         │
│  │ stall = hazard_exec || hazard_mem || hazard_wb         │
│  └─────────────────────────────────────────────┘           │
│                                                             │
│  Output:                                                   │
│    - io.stall: Bool         // Pipeline stall signal       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Hazard Types and Resolution

```
┌──────────────────┬────────────────────────┬─────────────────────────┐
│ Hazard Type      │ Detection              │ Resolution              │
├──────────────────┼────────────────────────┼─────────────────────────┤
│ RAW              │ rd(EX/MEM/WB) ==       │ Pipeline stall          │
│ (Read After      │   rs1/rs2(ID)          │ Wait for value          │
│  Write)          │                        │                         │
├──────────────────┼────────────────────────┼─────────────────────────┤
│ WAW              │ Not possible           │ N/A                     │
│ (Write After     │ (in-order pipeline)    │ (in-order execution)    │
│  Write)          │                        │                         │
├──────────────────┼────────────────────────┼─────────────────────────┤
│ WAR              │ Not possible           │ N/A                     │
│ (Write After     │ (in-order pipeline)    │ (reads before writes)   │
│  Read)           │                        │                         │
├──────────────────┼────────────────────────┼─────────────────────────┤
│ Load-Use         │ rd(MEM/load) ==        │ Pipeline stall          │
│                  │   rs1/rs2(ID)          │ Extra cycle for load    │
└──────────────────┴────────────────────────┴─────────────────────────┘
```

### Bypass/Forwarding Mechanism

The CPU implements **Writeback-to-Execute bypass**:

```
┌────────────────────────────────────────────────────────────┐
│                    BYPASS LOGIC                            │
│                                                            │
│  Located in: Execute stage                                │
│                                                            │
│  ┌──────────────────────────────────────────────┐         │
│  │ def bypass(readAddr: UInt,                   │         │
│  │            readData: UInt): UInt = {         │         │
│  │                                              │         │
│  │   Mux(                                       │         │
│  │     // Bypass condition:                    │         │
│  │     wb_writeEn &&                           │         │
│  │     (wb_writeAddr =/= 0.U) &&               │         │
│  │     (wb_writeAddr === readAddr),            │         │
│  │                                              │         │
│  │     wb_writeData,  // Bypassed value        │         │
│  │     readData       // Register file value   │         │
│  │   )                                          │         │
│  │ }                                            │         │
│  └──────────────────────────────────────────────┘         │
│                                                            │
│  Applied to both register reads:                          │
│    rs1_data = bypass(rs1, regFile.read1)                  │
│    rs2_data = bypass(rs2, regFile.read2)                  │
│                                                            │
└────────────────────────────────────────────────────────────┘

Visual representation:

        WRITEBACK                    EXECUTE
┌─────────────────────┐      ┌─────────────────────┐
│ rd = 5              │      │ Need to read r5     │
│ data = 0xABCD       │      │                     │
│ regWrite = true     │      │ RegFile[5] = old    │
└──────────┬──────────┘      └──────────┬──────────┘
           │                            │
           │    Bypass Path             │
           └───────────────────────────▶│
                                        │
                              ┌─────────▼──────────┐
                              │ Mux selects bypass │
                              │ data = 0xABCD      │
                              └────────────────────┘
```

**Note**: Bypass only works for Writeback → Execute. For Execute → Execute or Memory → Execute dependencies, the pipeline must stall.

---

## Branch Handling

### Branch/Jump Instructions

```
┌─────────────┬──────────────────────────────────────────────────┐
│ Instruction │ Behavior                                         │
├─────────────┼──────────────────────────────────────────────────┤
│ BEQ         │ if (rs1 == rs2) PC = PC + imm                   │
│ BNE         │ if (rs1 != rs2) PC = PC + imm                   │
│ BLT         │ if (rs1 < rs2 signed) PC = PC + imm             │
│ BGE         │ if (rs1 >= rs2 signed) PC = PC + imm            │
│ BLTU        │ if (rs1 < rs2 unsigned) PC = PC + imm           │
│ BGEU        │ if (rs1 >= rs2 unsigned) PC = PC + imm          │
│ JAL         │ rd = PC + 4; PC = PC + imm                      │
│ JALR        │ rd = PC + 4; PC = (rs1 + imm) & ~1              │
└─────────────┴──────────────────────────────────────────────────┘
```

### Branch Resolution Timeline

**Static Prediction**: Always predict **not taken**

#### Case 1: Branch Not Taken (Correct Prediction)

```
Instruction: beq x1, x2, offset  (branch not taken)

Cycle  │  IF   │  ID   │  EX   │  MEM  │  WB   │ Notes
───────┼───────┼───────┼───────┼───────┼───────┼─────────────────
   1   │ BEQ   │       │       │       │       │ Fetch branch
   2   │ PC+4  │ BEQ   │       │       │       │ Fetch next (predict not taken)
   3   │ PC+8  │ PC+4  │ BEQ   │       │       │ Execute resolves: not taken
       │       │       │   ✓   │       │       │ Prediction CORRECT!
   4   │ PC+12 │ PC+8  │ PC+4  │ BEQ   │       │ Continue normally
   5   │ PC+16 │ PC+12 │ PC+8  │ PC+4  │ BEQ   │ No penalty!
```

**Penalty**: 0 cycles

#### Case 2: Branch Taken (Misprediction)

```
Instruction: beq x1, x2, offset  (branch taken to target)

Cycle  │  IF   │  ID   │  EX   │  MEM  │  WB   │ Notes
───────┼───────┼───────┼───────┼───────┼───────┼─────────────────
   1   │ BEQ   │       │       │       │       │ Fetch branch
   2   │ PC+4  │ BEQ   │       │       │       │ Fetch next (wrong path)
   3   │ PC+8  │ PC+4  │ BEQ   │       │       │ Execute resolves: TAKEN!
       │       │       │   ✗   │       │       │ Misprediction detected
       │       │       │       │       │       │ Send branch feedback
   4   │TARGET │FLUSH  │ PC+4  │ BEQ   │       │ Redirect fetch to target
       │       │       │ FLUSH │       │       │ Flush decode queue
   5   │TARG+4 │TARGET │ FLUSH │ PC+4  │ BEQ   │ Flush execute queue
       │       │       │       │ FLUSH │       │
   6   │TARG+8 │TARG+4 │TARGET │ FLUSH │ PC+4  │ Resume from target
       │       │       │       │       │ FLUSH │
```

**Penalty**: 2 cycles (flushed PC+4 and PC+8)

### Branch Feedback Mechanism

```
┌────────────────────────────────────────────────────────────┐
│                  BRANCH FEEDBACK PATH                      │
│                                                            │
│  EXECUTE STAGE                                             │
│  ┌──────────────────────────────────────────────┐         │
│  │ Branch Resolution:                           │         │
│  │                                              │         │
│  │ val branchTaken = (opType === BRANCH &&     │         │
│  │                    branchCondition) ||      │         │
│  │                   (opType === JAL) ||       │         │
│  │                   (opType === JALR)         │         │
│  │                                              │         │
│  │ when (branchTaken) {                        │         │
│  │   io.branch.valid := true.B                 │         │
│  │   io.branch.bits.targetPC := target         │         │
│  │ }                                            │         │
│  └──────────────────┬───────────────────────────┘         │
│                     │                                     │
│                     │ BranchFeedback                      │
│                     │ {                                   │
│                     │   targetPC: UInt(32)                │
│                     │ }                                   │
│                     │                                     │
│                     ▼                                     │
│  ┌──────────────────────────────────────────────┐         │
│  │ Pipeline Register (1 cycle delay)            │         │
│  └──────────────────┬───────────────────────────┘         │
│                     │                                     │
│                     ▼                                     │
│  FETCH STAGE                                              │
│  ┌──────────────────────────────────────────────┐         │
│  │ PC Update Priority:                          │         │
│  │                                              │         │
│  │ when (debug_pc.valid) {                     │         │
│  │   PC := debug_pc.bits                       │         │
│  │ } .elsewhen (branch_feedback.valid) {       │         │
│  │   PC := branch_feedback.bits.targetPC       │◀────────┤
│  │ } .otherwise {                               │         │
│  │   PC := PC + 4                              │         │
│  │ }                                            │         │
│  └──────────────────────────────────────────────┘         │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Pipeline Flush Logic

```
┌────────────────────────────────────────────────────────────┐
│                    FLUSH SIGNALS                           │
│                                                            │
│  Flush Conditions:                                         │
│  ┌──────────────────────────────────────────────┐         │
│  │ val flushDecode = branchTaken || debugPCSet  │         │
│  │ val flushExec = branchTaken || debugPCSet    │         │
│  └──────────────────────────────────────────────┘         │
│                                                            │
│  Queue Flush Implementation:                              │
│  ┌──────────────────────────────────────────────┐         │
│  │ fetchDecodeQueue.io.flush := flushDecode     │         │
│  │ decodeExecQueue.io.flush := flushExec        │         │
│  │                                              │         │
│  │ // When flushed:                            │         │
│  │ // - Queue becomes empty                    │         │
│  │ // - valid signal de-asserted               │         │
│  │ // - Pipeline inserts bubble                │         │
│  └──────────────────────────────────────────────┘         │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

---

## Memory Interface Timing

### Load Operation Timing Diagram

```
Memory request-response protocol (asynchronous)

Clock   │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │
────────┼───┼───┼───┼───┼───┼───┼───┼───┤
req     │   │ ▀▀▀▀▀▀▀▀▀▀▀▀   │   │   │   │ Memory request
.valid  │   │               │   │   │   │
────────┼───┼───┼───┼───┼───┼───┼───┼───┤
req     │   │ ▀▀▀▀▀▀▀▀▀▀▀▀   │   │   │   │ Request ready
.ready  │   │               │   │   │   │
────────┼───┼───┼───┼───┼───┼───┼───┼───┤
req     │   │ [0x1000]      │   │   │   │ Address
.addr   │   │               │   │   │   │
────────┼───┼───┼───┼───┼───┼───┼───┼───┤
resp    │   │   │   │ ▀▀▀▀▀▀▀▀▀▀▀▀       │ Response valid
.valid  │   │   │   │           │       │ (after N cycles)
────────┼───┼───┼───┼───┼───┼───┼───┼───┤
resp    │   │   │   │ ▀▀▀▀▀▀▀▀▀▀▀▀       │ CPU ready
.ready  │   │   │   │           │       │
────────┼───┼───┼───┼───┼───┼───┼───┼───┤
resp    │   │   │   │ [DATA]    │       │ Read data
.data   │   │   │   │           │       │
────────┴───┴───┴───┴───┴───┴───┴───┴───┘

Stage   │ Cycle │ Activity
────────┼───────┼──────────────────────────────
EXECUTE │   1   │ Compute address = 0x1000
MEMORY  │   2   │ Issue request (req.valid = 1)
MEMORY  │   3   │ Wait for response (pending)
MEMORY  │   4   │ Receive response (resp.valid = 1)
MEMORY  │   5   │ Extract data, output to WB
WB      │   6   │ Write to register file
```

### Store Operation Timing Diagram

```
Store operation (fire-and-forget)

Clock   │ 0 │ 1 │ 2 │ 3 │ 4 │
────────┼───┼───┼───┼───┼───┤
req     │   │ ▀▀▀▀▀▀▀▀▀▀▀▀   │ Memory request
.valid  │   │               │
────────┼───┼───┼───┼───┼───┤
req     │   │ ▀▀▀▀▀▀▀▀▀▀▀▀   │ Request ready
.ready  │   │               │
────────┼───┼───┼───┼───┼───┤
req     │   │ [0x1004]      │ Address
.addr   │   │               │
────────┼───┼───┼───┼───┼───┤
req     │   │ [DATA]        │ Write data
.data   │   │               │
────────┼───┼───┼───┼───┼───┤
req     │   │ [1111]        │ Write enable (all bytes)
.writeEn│   │               │
────────┴───┴───┴───┴───┴───┘

Stage   │ Cycle │ Activity
────────┼───────┼──────────────────────────────
EXECUTE │   1   │ Compute addr, prepare data
MEMORY  │   2   │ Issue store (req.valid = 1)
        │       │ No wait needed!
MEMORY  │   3   │ Pass through to WB
WB      │   4   │ No register write
```

**Key Difference**: Stores don't wait for memory completion, reducing pipeline stalls.

---

## Control Flow Examples

### Example: JAL (Jump and Link)

**Instruction**: `jal x1, offset`  (x1 = PC+4, PC = PC+offset)

```
Assume: PC = 0x80000000, offset = 0x100

Cycle 1: FETCH
┌──────────────────────────────────┐
│ Fetch JAL instruction            │
│ PC = 0x80000000                 │
└──────────────────────────────────┘

Cycle 2: DECODE
┌──────────────────────────────────┐
│ Decode JAL                       │
│ rd = x1                          │
│ imm = 0x100 (offset)            │
└──────────────────────────────────┘

Cycle 3: EXECUTE
┌──────────────────────────────────┐
│ Compute:                         │
│   targetPC = PC + imm            │
│   targetPC = 0x80000000 + 0x100  │
│   targetPC = 0x80000100          │
│                                  │
│   returnAddr = PC + 4            │
│   returnAddr = 0x80000004        │
│                                  │
│ Send branch feedback:            │
│   branch.valid = true            │
│   branch.targetPC = 0x80000100   │
│                                  │
│ Result:                          │
│   rd = x1                        │
│   intResult = 0x80000004         │
└──────────────────────────────────┘

Cycle 4: MEMORY
┌──────────────────────────────────┐
│ Pass through                     │
│ regData = 0x80000004             │
│                                  │
│ Meanwhile, FETCH receives:       │
│   New PC = 0x80000100            │
└──────────────────────────────────┘

Cycle 5: WRITEBACK
┌──────────────────────────────────┐
│ Write to register file:          │
│   RF[x1] = 0x80000004            │
│                                  │
│ Fetch now at target:             │
│   PC = 0x80000100                │
└──────────────────────────────────┘
```

---

## Summary Diagrams

### Pipeline Stage Delays

```
Instruction journey through pipeline:

IF → ID → EX → MEM → WB
│    │    │    │     │
1    1    1   1-N    1   cycles per stage
     cycle cycle cycles cycle

Total latency: 5+ cycles
Throughput: 1 instruction/cycle (ideal)
```

### Critical Path (Timing)

```
Longest combinational path (determines max clock frequency):

Option 1: ALU path
RegFile Read → ALU Compute → Result Mux → ExecuteResult Register
│              │              │             │
~1ns          ~2ns           ~0.5ns        ~0.5ns = ~4ns

Option 2: Memory path
Address Calc → Memory Access → Data Extract → MemResult Register
│              │                │             │
~1ns          ~varies          ~1ns          ~0.5ns = ~2.5ns + mem latency
```

---

## Optimization Opportunities

### Potential Forwarding Paths

```
Current design has limited forwarding. Potential additions:

┌──────────────────────────────────────────────────┐
│ EXECUTE → EXECUTE Forwarding                     │
│ (Eliminate 1-cycle ALU dependencies)             │
│                                                  │
│   add x1, x2, x3  ◀──┐                          │
│   add x4, x1, x5     │ Forward x1 value         │
│                      │                           │
│   Instead of stall, forward ALU result directly │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│ MEMORY → EXECUTE Forwarding                      │
│ (Reduce load-use stall to 1 cycle)               │
│                                                  │
│   lw  x1, 0(x2)   ◀──┐                          │
│   add x3, x1, x4     │ Forward loaded value     │
│                      │                           │
│   Stall only 1 cycle instead of 2               │
└──────────────────────────────────────────────────┘
```

### Branch Prediction Improvements

```
Current: Static not-taken (50% accuracy for taken branches)

Improvements:
┌─────────────────────────────────────┐
│ 1-bit Branch Predictor              │
│ - Track last outcome per branch     │
│ - ~80% accuracy                     │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ 2-bit Saturating Counter            │
│ - More stable predictions           │
│ - ~85-90% accuracy                  │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ Branch Target Buffer (BTB)          │
│ - Cache target addresses            │
│ - Reduce fetch latency on taken     │
└─────────────────────────────────────┘
```

---

*Document Version: 1.0*
*Last Updated: 2025-11-21*
