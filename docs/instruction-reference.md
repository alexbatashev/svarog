# RV32I Instruction Reference for Svarog CPU

This document provides a complete reference for all RV32I instructions supported by the Svarog CPU, including encoding, behavior, and implementation notes.

---

## Table of Contents

1. [Instruction Format Overview](#instruction-format-overview)
2. [Register Conventions](#register-conventions)
3. [Integer Computational Instructions](#integer-computational-instructions)
4. [Load and Store Instructions](#load-and-store-instructions)
5. [Control Transfer Instructions](#control-transfer-instructions)
6. [System Instructions](#system-instructions)
7. [Encoding Reference](#encoding-reference)

---

## Instruction Format Overview

RISC-V uses 6 basic instruction formats, all 32 bits wide:

```
R-type: Register-Register operations
┌────────┬─────┬─────┬────────┬─────┬────────┐
│ funct7 │ rs2 │ rs1 │ funct3 │ rd  │ opcode │
│ [31:25]│[24:20][19:15][14:12]│[11:7]│ [6:0] │
└────────┴─────┴─────┴────────┴─────┴────────┘

I-type: Immediate and Load operations
┌──────────────┬─────┬────────┬─────┬────────┐
│   imm[11:0]  │ rs1 │ funct3 │ rd  │ opcode │
│   [31:20]    │[19:15][14:12]│[11:7]│ [6:0] │
└──────────────┴─────┴────────┴─────┴────────┘

S-type: Store operations
┌────────┬─────┬─────┬────────┬────────┬────────┐
│imm[11:5│ rs2 │ rs1 │ funct3 │imm[4:0]│ opcode │
│ [31:25]│[24:20][19:15][14:12]│[11:7]  │ [6:0] │
└────────┴─────┴─────┴────────┴────────┴────────┘

B-type: Branch operations
┌──┬──────┬─────┬─────┬────────┬────┬──┬────────┐
│im│imm   │ rs2 │ rs1 │ funct3 │imm │im│ opcode │
│12│[10:5]│[24:20][19:15][14:12]│[4:1│11│ [6:0] │
│31│[30:25]│     │     │        │11:8│7 │        │
└──┴──────┴─────┴─────┴────────┴────┴──┴────────┘

U-type: Upper immediate operations
┌──────────────────────┬─────┬────────┐
│      imm[31:12]      │ rd  │ opcode │
│      [31:12]         │[11:7]│ [6:0] │
└──────────────────────┴─────┴────────┘

J-type: Jump operations
┌──┬──────────┬──┬────────┬─────┬────────┐
│im│imm[10:1] │im│imm[19:1│ rd  │ opcode │
│20│          │11│  12]   │     │        │
│31│ [30:21]  │20│[19:12] │[11:7]│ [6:0] │
└──┴──────────┴──┴────────┴─────┴────────┘
```

### Immediate Encoding

All immediates are **sign-extended** to 32 bits:

```
I-immediate: imm[11:0] = inst[31:20]
             Sign-extended to 32 bits

S-immediate: imm[11:5] = inst[31:25]
             imm[4:0]  = inst[11:7]
             Sign-extended to 32 bits

B-immediate: imm[12]   = inst[31]
             imm[10:5] = inst[30:25]
             imm[4:1]  = inst[11:8]
             imm[11]   = inst[7]
             imm[0]    = 0
             Sign-extended to 32 bits

U-immediate: imm[31:12] = inst[31:12]
             imm[11:0]  = 0

J-immediate: imm[20]    = inst[31]
             imm[10:1]  = inst[30:21]
             imm[11]    = inst[20]
             imm[19:12] = inst[19:12]
             imm[0]     = 0
             Sign-extended to 32 bits
```

---

## Register Conventions

### Register File

- **32 integer registers** (x0-x31), each 32 bits
- **x0** is hardwired to zero (reads always 0, writes ignored)

### ABI Names and Usage

```
┌─────┬─────────┬───────────────┬────────────────────────┐
│ Reg │ ABI Name│ Description   │ Saver                  │
├─────┼─────────┼───────────────┼────────────────────────┤
│ x0  │ zero    │ Hardwired zero│ —                      │
│ x1  │ ra      │ Return address│ Caller                 │
│ x2  │ sp      │ Stack pointer │ Callee                 │
│ x3  │ gp      │ Global pointer│ —                      │
│ x4  │ tp      │ Thread pointer│ —                      │
│ x5  │ t0      │ Temporary     │ Caller                 │
│ x6  │ t1      │ Temporary     │ Caller                 │
│ x7  │ t2      │ Temporary     │ Caller                 │
│ x8  │ s0/fp   │ Saved/frame   │ Callee                 │
│ x9  │ s1      │ Saved         │ Callee                 │
│ x10 │ a0      │ Arg/return    │ Caller                 │
│ x11 │ a1      │ Arg/return    │ Caller                 │
│ x12 │ a2      │ Argument      │ Caller                 │
│ x13 │ a3      │ Argument      │ Caller                 │
│ x14 │ a4      │ Argument      │ Caller                 │
│ x15 │ a5      │ Argument      │ Caller                 │
│ x16 │ a6      │ Argument      │ Caller                 │
│ x17 │ a7      │ Argument      │ Caller                 │
│ x18 │ s2      │ Saved         │ Callee                 │
│ x19 │ s3      │ Saved         │ Callee                 │
│ x20 │ s4      │ Saved         │ Callee                 │
│ x21 │ s5      │ Saved         │ Callee                 │
│ x22 │ s6      │ Saved         │ Callee                 │
│ x23 │ s7      │ Saved         │ Callee                 │
│ x24 │ s8      │ Saved         │ Callee                 │
│ x25 │ s9      │ Saved         │ Callee                 │
│ x26 │ s10     │ Saved         │ Callee                 │
│ x27 │ s11     │ Saved         │ Callee                 │
│ x28 │ t3      │ Temporary     │ Caller                 │
│ x29 │ t4      │ Temporary     │ Caller                 │
│ x30 │ t5      │ Temporary     │ Caller                 │
│ x31 │ t6      │ Temporary     │ Caller                 │
└─────┴─────────┴───────────────┴────────────────────────┘
```

---

## Integer Computational Instructions

### Integer Register-Immediate Instructions

#### ADDI - Add Immediate
```
Format: I-type
Syntax: addi rd, rs1, imm
Opcode: 0010011
Funct3: 000

Operation: rd = rs1 + sign_extend(imm)
Example:   addi x1, x0, 5      # x1 = 0 + 5 = 5
           addi x2, x1, -3     # x2 = x1 - 3

Notes:
- Sign-extends 12-bit immediate to 32 bits
- ADDI x0, x0, 0 is canonical NOP
- No overflow exception

Pipeline behavior:
  IF → ID → EX (ALU) → MEM (pass) → WB
  Total: 5 cycles (throughput: 1/cycle)
```

#### SLTI - Set Less Than Immediate
```
Format: I-type
Syntax: slti rd, rs1, imm
Opcode: 0010011
Funct3: 010

Operation: rd = (rs1 < sign_extend(imm)) ? 1 : 0  (signed comparison)
Example:   slti x1, x2, 10     # x1 = (x2 < 10) ? 1 : 0

Notes:
- Signed comparison
- Result is 0 or 1
```

#### SLTIU - Set Less Than Immediate Unsigned
```
Format: I-type
Syntax: sltiu rd, rs1, imm
Opcode: 0010011
Funct3: 011

Operation: rd = (rs1 < zero_extend(imm)) ? 1 : 0  (unsigned comparison)
Example:   sltiu x1, x2, 10    # x1 = (x2 < 10u) ? 1 : 0

Notes:
- Unsigned comparison
- Immediate is still sign-extended, then compared unsigned
- sltiu rd, x0, 1 sets rd=1 (used to set register to 1)
```

#### ANDI - AND Immediate
```
Format: I-type
Syntax: andi rd, rs1, imm
Opcode: 0010011
Funct3: 111

Operation: rd = rs1 & sign_extend(imm)
Example:   andi x1, x2, 0xFF   # x1 = x2 & 0xFF (mask low byte)
```

#### ORI - OR Immediate
```
Format: I-type
Syntax: ori rd, rs1, imm
Opcode: 0010011
Funct3: 110

Operation: rd = rs1 | sign_extend(imm)
Example:   ori x1, x2, 0x10    # x1 = x2 | 0x10 (set bit 4)
```

#### XORI - XOR Immediate
```
Format: I-type
Syntax: xori rd, rs1, imm
Opcode: 0010011
Funct3: 100

Operation: rd = rs1 ^ sign_extend(imm)
Example:   xori x1, x2, -1     # x1 = ~x2 (bitwise NOT)

Notes:
- xori rd, rs, -1 implements bitwise NOT
```

#### SLLI - Shift Left Logical Immediate
```
Format: I-type (special)
Syntax: slli rd, rs1, shamt
Opcode: 0010011
Funct3: 001
Funct7: 0000000

Operation: rd = rs1 << shamt
Example:   slli x1, x2, 2      # x1 = x2 << 2 (multiply by 4)

Notes:
- shamt is imm[4:0] (only 5 bits used)
- imm[11:5] must be 0000000
- Logical shift (fills with zeros)
```

#### SRLI - Shift Right Logical Immediate
```
Format: I-type (special)
Syntax: srli rd, rs1, shamt
Opcode: 0010011
Funct3: 101
Funct7: 0000000

Operation: rd = rs1 >> shamt  (logical)
Example:   srli x1, x2, 2      # x1 = x2 >> 2 (unsigned divide by 4)

Notes:
- shamt is imm[4:0] (only 5 bits used)
- imm[11:5] must be 0000000
- Logical shift (fills with zeros)
```

#### SRAI - Shift Right Arithmetic Immediate
```
Format: I-type (special)
Syntax: srai rd, rs1, shamt
Opcode: 0010011
Funct3: 101
Funct7: 0100000

Operation: rd = rs1 >> shamt  (arithmetic)
Example:   srai x1, x2, 2      # x1 = x2 >> 2 (signed divide by 4)

Notes:
- shamt is imm[4:0] (only 5 bits used)
- imm[11:5] must be 0100000
- Arithmetic shift (fills with sign bit)
```

#### LUI - Load Upper Immediate
```
Format: U-type
Syntax: lui rd, imm
Opcode: 0110111

Operation: rd = imm << 12
Example:   lui x1, 0x12345     # x1 = 0x12345000

Notes:
- Loads 20-bit immediate into upper 20 bits
- Lower 12 bits set to zero
- Often paired with ADDI to load 32-bit constant:
    lui  x1, 0x12345
    addi x1, x1, 0x678  # x1 = 0x12345678
```

#### AUIPC - Add Upper Immediate to PC
```
Format: U-type
Syntax: auipc rd, imm
Opcode: 0010111

Operation: rd = PC + (imm << 12)
Example:   auipc x1, 0x1000    # x1 = PC + 0x1000000

Notes:
- Used for PC-relative addressing
- Often paired with JALR for PC-relative jump:
    auipc x1, offset[31:12]
    jalr  x0, offset[11:0](x1)
```

---

### Integer Register-Register Instructions

#### ADD - Add
```
Format: R-type
Syntax: add rd, rs1, rs2
Opcode: 0110011
Funct3: 000
Funct7: 0000000

Operation: rd = rs1 + rs2
Example:   add x1, x2, x3      # x1 = x2 + x3

Notes:
- No overflow exception
- Wraps on overflow
```

#### SUB - Subtract
```
Format: R-type
Syntax: sub rd, rs1, rs2
Opcode: 0110011
Funct3: 000
Funct7: 0100000

Operation: rd = rs1 - rs2
Example:   sub x1, x2, x3      # x1 = x2 - x3

Notes:
- No overflow exception
- Wraps on underflow
```

#### SLT - Set Less Than
```
Format: R-type
Syntax: slt rd, rs1, rs2
Opcode: 0110011
Funct3: 010
Funct7: 0000000

Operation: rd = (rs1 < rs2) ? 1 : 0  (signed)
Example:   slt x1, x2, x3      # x1 = (x2 < x3) ? 1 : 0
```

#### SLTU - Set Less Than Unsigned
```
Format: R-type
Syntax: sltu rd, rs1, rs2
Opcode: 0110011
Funct3: 011
Funct7: 0000000

Operation: rd = (rs1 < rs2) ? 1 : 0  (unsigned)
Example:   sltu x1, x2, x3     # x1 = (x2 < x3) ? 1 : 0 (unsigned)

Notes:
- sltu rd, x0, rs2 sets rd=1 if rs2≠0 (test for non-zero)
```

#### AND - Bitwise AND
```
Format: R-type
Syntax: and rd, rs1, rs2
Opcode: 0110011
Funct3: 111
Funct7: 0000000

Operation: rd = rs1 & rs2
Example:   and x1, x2, x3      # x1 = x2 & x3
```

#### OR - Bitwise OR
```
Format: R-type
Syntax: or rd, rs1, rs2
Opcode: 0110011
Funct3: 110
Funct7: 0000000

Operation: rd = rs1 | rs2
Example:   or x1, x2, x3       # x1 = x2 | x3
```

#### XOR - Bitwise XOR
```
Format: R-type
Syntax: xor rd, rs1, rs2
Opcode: 0110011
Funct3: 100
Funct7: 0000000

Operation: rd = rs1 ^ rs2
Example:   xor x1, x2, x3      # x1 = x2 ^ x3

Notes:
- xor rd, rs, rs clears rd to 0
```

#### SLL - Shift Left Logical
```
Format: R-type
Syntax: sll rd, rs1, rs2
Opcode: 0110011
Funct3: 001
Funct7: 0000000

Operation: rd = rs1 << rs2[4:0]
Example:   sll x1, x2, x3      # x1 = x2 << (x3 & 0x1F)

Notes:
- Only lower 5 bits of rs2 used (shift amount 0-31)
```

#### SRL - Shift Right Logical
```
Format: R-type
Syntax: srl rd, rs1, rs2
Opcode: 0110011
Funct3: 101
Funct7: 0000000

Operation: rd = rs1 >> rs2[4:0]  (logical)
Example:   srl x1, x2, x3      # x1 = x2 >> (x3 & 0x1F)

Notes:
- Only lower 5 bits of rs2 used
- Logical shift (fills with zeros)
```

#### SRA - Shift Right Arithmetic
```
Format: R-type
Syntax: sra rd, rs1, rs2
Opcode: 0110011
Funct3: 101
Funct7: 0100000

Operation: rd = rs1 >> rs2[4:0]  (arithmetic)
Example:   sra x1, x2, x3      # x1 = x2 >> (x3 & 0x1F) (sign-extend)

Notes:
- Only lower 5 bits of rs2 used
- Arithmetic shift (fills with sign bit)
```

---

## Load and Store Instructions

### Load Instructions

All loads sign/zero-extend to 32 bits.

#### LB - Load Byte
```
Format: I-type
Syntax: lb rd, offset(rs1)
Opcode: 0000011
Funct3: 000

Operation: rd = sign_extend(mem[rs1 + offset][7:0])
Example:   lb x1, 4(x2)        # x1 = sign_extend(byte at x2+4)

Notes:
- Loads 8 bits, sign-extends to 32 bits
- Offset is signed 12-bit immediate

Pipeline behavior:
  IF → ID → EX (addr) → MEM (load + wait) → WB
  Total: 5+ cycles (depends on memory latency)
```

#### LH - Load Halfword
```
Format: I-type
Syntax: lh rd, offset(rs1)
Opcode: 0000011
Funct3: 001

Operation: rd = sign_extend(mem[rs1 + offset][15:0])
Example:   lh x1, 4(x2)        # x1 = sign_extend(halfword at x2+4)

Notes:
- Loads 16 bits, sign-extends to 32 bits
- Address should be 2-byte aligned (not enforced in this implementation)
```

#### LW - Load Word
```
Format: I-type
Syntax: lw rd, offset(rs1)
Opcode: 0000011
Funct3: 010

Operation: rd = mem[rs1 + offset][31:0]
Example:   lw x1, 4(x2)        # x1 = word at x2+4

Notes:
- Loads 32 bits
- Address should be 4-byte aligned (not enforced in this implementation)
```

#### LBU - Load Byte Unsigned
```
Format: I-type
Syntax: lbu rd, offset(rs1)
Opcode: 0000011
Funct3: 100

Operation: rd = zero_extend(mem[rs1 + offset][7:0])
Example:   lbu x1, 4(x2)       # x1 = zero_extend(byte at x2+4)

Notes:
- Loads 8 bits, zero-extends to 32 bits
```

#### LHU - Load Halfword Unsigned
```
Format: I-type
Syntax: lhu rd, offset(rs1)
Opcode: 0000011
Funct3: 101

Operation: rd = zero_extend(mem[rs1 + offset][15:0])
Example:   lhu x1, 4(x2)       # x1 = zero_extend(halfword at x2+4)

Notes:
- Loads 16 bits, zero-extends to 32 bits
```

---

### Store Instructions

#### SB - Store Byte
```
Format: S-type
Syntax: sb rs2, offset(rs1)
Opcode: 0100011
Funct3: 000

Operation: mem[rs1 + offset][7:0] = rs2[7:0]
Example:   sb x1, 4(x2)        # Store low byte of x1 to x2+4

Notes:
- Stores lower 8 bits of rs2
- Upper bits of rs2 ignored

Pipeline behavior:
  IF → ID → EX (addr) → MEM (store) → WB (pass)
  Total: 5 cycles (store is fire-and-forget)
```

#### SH - Store Halfword
```
Format: S-type
Syntax: sh rs2, offset(rs1)
Opcode: 0100011
Funct3: 001

Operation: mem[rs1 + offset][15:0] = rs2[15:0]
Example:   sh x1, 4(x2)        # Store low halfword of x1 to x2+4

Notes:
- Stores lower 16 bits of rs2
- Address should be 2-byte aligned
```

#### SW - Store Word
```
Format: S-type
Syntax: sw rs2, offset(rs1)
Opcode: 0100011
Funct3: 010

Operation: mem[rs1 + offset][31:0] = rs2[31:0]
Example:   sw x1, 4(x2)        # Store x1 to x2+4

Notes:
- Stores full 32 bits of rs2
- Address should be 4-byte aligned
```

---

## Control Transfer Instructions

### Conditional Branches

All branches use PC-relative addressing. Branch target = PC + sign_extend(imm).

#### BEQ - Branch if Equal
```
Format: B-type
Syntax: beq rs1, rs2, offset
Opcode: 1100011
Funct3: 000

Operation: if (rs1 == rs2) PC += sign_extend(offset)
Example:   beq x1, x2, loop    # if (x1 == x2) goto loop

Notes:
- Offset is 13-bit signed immediate (offset[0] always 0)
- Range: ±4 KiB

Pipeline behavior (taken):
  IF → ID → EX (compare) → [flush] → [target] → ...
  Penalty: 2 cycles (flush IF+1, ID+1)

Pipeline behavior (not taken):
  IF → ID → EX (compare) → MEM → WB
  Penalty: 0 cycles (correct prediction)
```

#### BNE - Branch if Not Equal
```
Format: B-type
Syntax: bne rs1, rs2, offset
Opcode: 1100011
Funct3: 001

Operation: if (rs1 != rs2) PC += sign_extend(offset)
Example:   bne x1, x0, skip    # if (x1 != 0) goto skip
```

#### BLT - Branch if Less Than
```
Format: B-type
Syntax: blt rs1, rs2, offset
Opcode: 1100011
Funct3: 100

Operation: if (rs1 < rs2) PC += sign_extend(offset)  (signed)
Example:   blt x1, x2, less    # if (x1 < x2) goto less

Notes:
- Signed comparison
```

#### BGE - Branch if Greater or Equal
```
Format: B-type
Syntax: bge rs1, rs2, offset
Opcode: 1100011
Funct3: 101

Operation: if (rs1 >= rs2) PC += sign_extend(offset)  (signed)
Example:   bge x1, x2, geq     # if (x1 >= x2) goto geq

Notes:
- Signed comparison
```

#### BLTU - Branch if Less Than Unsigned
```
Format: B-type
Syntax: bltu rs1, rs2, offset
Opcode: 1100011
Funct3: 110

Operation: if (rs1 < rs2) PC += sign_extend(offset)  (unsigned)
Example:   bltu x1, x2, less   # if (x1 < x2) goto less (unsigned)

Notes:
- Unsigned comparison
```

#### BGEU - Branch if Greater or Equal Unsigned
```
Format: B-type
Syntax: bgeu rs1, rs2, offset
Opcode: 1100011
Funct3: 111

Operation: if (rs1 >= rs2) PC += sign_extend(offset)  (unsigned)
Example:   bgeu x1, x2, geq    # if (x1 >= x2) goto geq (unsigned)

Notes:
- Unsigned comparison
```

---

### Unconditional Jumps

#### JAL - Jump and Link
```
Format: J-type
Syntax: jal rd, offset
Opcode: 1101111

Operation: rd = PC + 4
           PC += sign_extend(offset)

Example:   jal x1, function    # Call function (x1 = return address)
           jal x0, target      # Unconditional jump (no link)

Notes:
- Offset is 21-bit signed immediate (offset[0] always 0)
- Range: ±1 MiB
- jal x0, offset is unconditional jump (no return address saved)
- jal x1, offset is function call (return address in x1/ra)

Pipeline behavior:
  IF → ID → EX (compute target) → [flush] → [target] → ...
  Total: 3 cycles (2-cycle penalty for flush)
```

#### JALR - Jump and Link Register
```
Format: I-type
Syntax: jalr rd, offset(rs1)
Opcode: 1100111
Funct3: 000

Operation: rd = PC + 4
           PC = (rs1 + sign_extend(offset)) & ~1

Example:   jalr x0, 0(x1)      # Return from function (PC = x1)
           jalr x1, 0(x2)      # Indirect call (x1 = return addr, PC = x2)

Notes:
- Target address = (rs1 + offset) with LSB cleared
- Range: Full 32-bit address space
- jalr x0, 0(x1) is return (assuming x1 = ra)
- jalr x1, offset(x2) is indirect call

Common patterns:
  ret  ≡  jalr x0, 0(x1)      # Return (pseudoinstruction)
  jr rs  ≡  jalr x0, 0(rs)    # Jump register (pseudoinstruction)
```

---

## System Instructions

#### ECALL - Environment Call
```
Format: I-type
Syntax: ecall
Opcode: 1110011
Funct3: 000
Immediate: 000000000000

Operation: Raise environment call exception
Example:   ecall               # System call

Notes:
- Encoded as: 0x00000073
- Transfers control to OS/supervisor
- In Svarog: Basic support (marked in MicroOp)
- Actual exception handling not yet implemented
```

#### EBREAK - Environment Break
```
Format: I-type
Syntax: ebreak
Opcode: 1110011
Funct3: 000
Immediate: 000000000001

Operation: Raise breakpoint exception
Example:   ebreak              # Breakpoint

Notes:
- Encoded as: 0x00100073
- Used by debuggers
- In Svarog: Not yet fully implemented
```

---

## Encoding Reference

### Opcode Map

```
┌────────┬──────────────────────────────────────────┐
│ Opcode │ Instruction Type                         │
├────────┼──────────────────────────────────────────┤
│0110011 │ R-type: ADD, SUB, SLL, SLT, SLTU, XOR,   │
│        │         SRL, SRA, OR, AND                │
│0010011 │ I-type: ADDI, SLTI, SLTIU, XORI, ORI,    │
│        │         ANDI, SLLI, SRLI, SRAI           │
│0000011 │ Load: LB, LH, LW, LBU, LHU               │
│0100011 │ Store: SB, SH, SW                        │
│1100011 │ Branch: BEQ, BNE, BLT, BGE, BLTU, BGEU   │
│1101111 │ JAL                                      │
│1100111 │ JALR                                     │
│0110111 │ LUI                                      │
│0010111 │ AUIPC                                    │
│1110011 │ System: ECALL, EBREAK                    │
└────────┴──────────────────────────────────────────┘
```

### Funct3 Encoding for ALU Operations

```
ALU Register-Immediate (opcode 0010011):
┌────────┬─────────┬────────────────┐
│ Funct3 │ Funct7  │ Instruction    │
├────────┼─────────┼────────────────┤
│  000   │    -    │ ADDI           │
│  010   │    -    │ SLTI           │
│  011   │    -    │ SLTIU          │
│  100   │    -    │ XORI           │
│  110   │    -    │ ORI            │
│  111   │    -    │ ANDI           │
│  001   │ 0000000 │ SLLI           │
│  101   │ 0000000 │ SRLI           │
│  101   │ 0100000 │ SRAI           │
└────────┴─────────┴────────────────┘

ALU Register-Register (opcode 0110011):
┌────────┬─────────┬────────────────┐
│ Funct3 │ Funct7  │ Instruction    │
├────────┼─────────┼────────────────┤
│  000   │ 0000000 │ ADD            │
│  000   │ 0100000 │ SUB            │
│  001   │ 0000000 │ SLL            │
│  010   │ 0000000 │ SLT            │
│  011   │ 0000000 │ SLTU           │
│  100   │ 0000000 │ XOR            │
│  101   │ 0000000 │ SRL            │
│  101   │ 0100000 │ SRA            │
│  110   │ 0000000 │ OR             │
│  111   │ 0000000 │ AND            │
└────────┴─────────┴────────────────┘
```

### Complete Instruction Encoding Table

```
┌──────┬────────┬────────┬────────┬────────────────────────────────┐
│ Inst │ Format │ Opcode │ Funct3 │ Funct7/Imm                     │
├──────┼────────┼────────┼────────┼────────────────────────────────┤
│ ADD  │   R    │0110011 │  000   │ 0000000                        │
│ SUB  │   R    │0110011 │  000   │ 0100000                        │
│ SLL  │   R    │0110011 │  001   │ 0000000                        │
│ SLT  │   R    │0110011 │  010   │ 0000000                        │
│ SLTU │   R    │0110011 │  011   │ 0000000                        │
│ XOR  │   R    │0110011 │  100   │ 0000000                        │
│ SRL  │   R    │0110011 │  101   │ 0000000                        │
│ SRA  │   R    │0110011 │  101   │ 0100000                        │
│ OR   │   R    │0110011 │  110   │ 0000000                        │
│ AND  │   R    │0110011 │  111   │ 0000000                        │
│ ADDI │   I    │0010011 │  000   │ imm[11:0]                      │
│ SLTI │   I    │0010011 │  010   │ imm[11:0]                      │
│ SLTIU│   I    │0010011 │  011   │ imm[11:0]                      │
│ XORI │   I    │0010011 │  100   │ imm[11:0]                      │
│ ORI  │   I    │0010011 │  110   │ imm[11:0]                      │
│ ANDI │   I    │0010011 │  111   │ imm[11:0]                      │
│ SLLI │   I    │0010011 │  001   │ 0000000 | shamt[4:0]           │
│ SRLI │   I    │0010011 │  101   │ 0000000 | shamt[4:0]           │
│ SRAI │   I    │0010011 │  101   │ 0100000 | shamt[4:0]           │
│ LB   │   I    │0000011 │  000   │ offset[11:0]                   │
│ LH   │   I    │0000011 │  001   │ offset[11:0]                   │
│ LW   │   I    │0000011 │  010   │ offset[11:0]                   │
│ LBU  │   I    │0000011 │  100   │ offset[11:0]                   │
│ LHU  │   I    │0000011 │  101   │ offset[11:0]                   │
│ SB   │   S    │0100011 │  000   │ offset[11:5] | offset[4:0]     │
│ SH   │   S    │0100011 │  001   │ offset[11:5] | offset[4:0]     │
│ SW   │   S    │0100011 │  010   │ offset[11:5] | offset[4:0]     │
│ BEQ  │   B    │1100011 │  000   │ offset[12|10:5] | offset[4:1|11│
│ BNE  │   B    │1100011 │  001   │ offset[12|10:5] | offset[4:1|11│
│ BLT  │   B    │1100011 │  100   │ offset[12|10:5] | offset[4:1|11│
│ BGE  │   B    │1100011 │  101   │ offset[12|10:5] | offset[4:1|11│
│ BLTU │   B    │1100011 │  110   │ offset[12|10:5] | offset[4:1|11│
│ BGEU │   B    │1100011 │  111   │ offset[12|10:5] | offset[4:1|11│
│ JAL  │   J    │1101111 │   -    │ offset[20|10:1|11|19:12]       │
│ JALR │   I    │1100111 │  000   │ offset[11:0]                   │
│ LUI  │   U    │0110111 │   -    │ imm[31:12]                     │
│ AUIPC│   U    │0010111 │   -    │ imm[31:12]                     │
│ ECALL│   I    │1110011 │  000   │ 000000000000                   │
│EBREAK│   I    │1110011 │  000   │ 000000000001                   │
└──────┴────────┴────────┴────────┴────────────────────────────────┘
```

---

## Pseudoinstructions

RISC-V assembly supports pseudoinstructions that map to real instructions:

```
┌─────────────┬────────────────────────┬─────────────────────┐
│ Pseudo      │ Base Instruction       │ Description         │
├─────────────┼────────────────────────┼─────────────────────┤
│ nop         │ addi x0, x0, 0         │ No operation        │
│ li rd, imm  │ addi rd, x0, imm       │ Load immediate      │
│ mv rd, rs   │ addi rd, rs, 0         │ Move register       │
│ not rd, rs  │ xori rd, rs, -1        │ Bitwise NOT         │
│ neg rd, rs  │ sub rd, x0, rs         │ Negate              │
│ seqz rd, rs │ sltiu rd, rs, 1        │ Set if equal zero   │
│ snez rd, rs │ sltu rd, x0, rs        │ Set if not zero     │
│ sltz rd, rs │ slt rd, rs, x0         │ Set if < zero       │
│ sgtz rd, rs │ slt rd, x0, rs         │ Set if > zero       │
│ beqz rs, off│ beq rs, x0, offset     │ Branch if zero      │
│ bnez rs, off│ bne rs, x0, offset     │ Branch if not zero  │
│ blez rs, off│ bge x0, rs, offset     │ Branch if <= zero   │
│ bgez rs, off│ bge rs, x0, offset     │ Branch if >= zero   │
│ bltz rs, off│ blt rs, x0, offset     │ Branch if < zero    │
│ bgtz rs, off│ blt x0, rs, offset     │ Branch if > zero    │
│ j offset    │ jal x0, offset         │ Jump                │
│ jal offset  │ jal x1, offset         │ Jump and link       │
│ jr rs       │ jalr x0, 0(rs)         │ Jump register       │
│ jalr rs     │ jalr x1, 0(rs)         │ Jump and link reg   │
│ ret         │ jalr x0, 0(x1)         │ Return              │
│ call offset │ auipc x1, offset[31:12]│ Call far function   │
│             │ jalr x1, offset[11:0]  │                     │
└─────────────┴────────────────────────┴─────────────────────┘
```

---

## Implementation Notes for Svarog

### Supported Instructions

**Fully implemented**: All 40 RV32I instructions
- Integer arithmetic: 10 operations
- Immediate operations: 9 operations
- Load: 5 variants
- Store: 3 variants
- Branch: 6 variants
- Jump: 2 instructions
- Upper immediate: 2 instructions
- System: 2 instructions (basic support)

### Pipeline Characteristics

```
┌──────────────────┬─────────┬────────────────────────┐
│ Instruction Type │ Latency │ Throughput (ideal CPI) │
├──────────────────┼─────────┼────────────────────────┤
│ ALU (reg/imm)    │ 5 cycles│ 1.0                    │
│ Load             │ 5+ cyc  │ 1.0 (+ mem latency)    │
│ Store            │ 5 cycles│ 1.0                    │
│ Branch (not taken│ 5 cycles│ 1.0                    │
│ Branch (taken)   │ 7 cycles│ 3.0 (2-cycle penalty)  │
│ JAL/JALR         │ 7 cycles│ 3.0 (2-cycle penalty)  │
└──────────────────┴─────────┴────────────────────────┘
```

### Hazards and Bypassing

- **RAW hazards**: Detected and stalled
- **Bypass**: Writeback → Execute only
- **Load-use**: Stalls until load completes
- **No forwarding**: Between Execute → Execute or Memory → Execute

### Exceptions and Interrupts

**Not yet implemented**:
- Illegal instruction exceptions
- Misaligned address exceptions
- Interrupts
- Privilege modes
- CSR instructions (beyond basic ECALL)

---

## References

- [RISC-V ISA Specification v2.2](https://riscv.org/wp-content/uploads/2017/05/riscv-spec-v2.2.pdf)
- [RISC-V Instruction Set Manual](https://github.com/riscv/riscv-isa-manual)
- [RISC-V Assembly Programmer's Manual](https://github.com/riscv-non-isa/riscv-asm-manual)

---

*Document Version: 1.0*
*Last Updated: 2025-11-21*
*CPU Implementation: Svarog v1.0*
