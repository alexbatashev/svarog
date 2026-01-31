# Illegal Instruction Exception Test
#
# Tests that the CPU correctly triggers illegal instruction exceptions
# for both unknown opcodes and known opcodes with invalid fields.
#
# Test sequence:
# 1. Set up trap handler at mtvec
# 2. Test unknown opcode (completely invalid)
# 3. Test known opcode with invalid funct3 (SYSTEM with funct3=010)
# 4. Test known opcode with invalid funct7 (ALU_REG with funct7=0000010)
# 5. Test valid instruction after exceptions to ensure CPU recovers
# 6. PASS if all exceptions handled correctly

.section .text

# Interrupt cause codes
.equ MCAUSE_ILLEGAL_INST, 2         # Illegal instruction exception

.globl _main
_main:
    # --------------------------------------------------
    # Step 1: Set up trap handler
    # --------------------------------------------------
    la t0, trap_handler
    csrw mtvec, t0

    # Initialize test counter
    li s0, 0                         # s0 = current test number

    # --------------------------------------------------
    # Test 1: Unknown opcode
    # --------------------------------------------------
    li s0, 1
    la t0, trap_flag
    sw zero, 0(t0)                   # Clear trap flag

    # Execute illegal instruction with unknown opcode (0x0000000B)
    .word 0x0000000B

    # Check that trap was taken
    lw t0, 0(t0)
    beqz t0, test_fail               # Fail if trap not taken
    li t1, MCAUSE_ILLEGAL_INST
    bne t0, t1, test_fail            # Fail if wrong cause

    # --------------------------------------------------
    # Test 2: SYSTEM opcode with invalid funct3
    # --------------------------------------------------
    li s0, 2
    la t0, trap_flag
    sw zero, 0(t0)                   # Clear trap flag

    # SYSTEM opcode (0x1110011) with funct3=100 (not a valid CSR operation or system instruction)
    # This is: imm12=0, rs1=0, funct3=100, rd=0, opcode=1110011
    # Binary: 000000000000_00000_100_00000_1110011 = 0x00004073
    .word 0x00004073

    # Check that trap was taken
    lw t0, 0(t0)
    beqz t0, test_fail               # Fail if trap not taken
    li t1, MCAUSE_ILLEGAL_INST
    bne t0, t1, test_fail            # Fail if wrong cause

    # --------------------------------------------------
    # Test 3: ALU_REG opcode with invalid funct7
    # --------------------------------------------------
    li s0, 3
    la t0, trap_flag
    sw zero, 0(t0)                   # Clear trap flag

    # ALU_REG opcode (0x0110011) with funct7=0000010 (not 0000000, 0100000, or 0000001)
    # This is: funct7=0000010, rs2=1, rs1=1, funct3=000, rd=2, opcode=0110011
    # Binary: 0000010_00001_00001_000_00010_0110011 = 0x04108133
    .word 0x04108133

    # Check that trap was taken
    lw t0, 0(t0)
    beqz t0, test_fail               # Fail if trap not taken
    li t1, MCAUSE_ILLEGAL_INST
    bne t0, t1, test_fail            # Fail if wrong cause

    # --------------------------------------------------
    # Test 4: MISC_MEM opcode with invalid funct3
    # --------------------------------------------------
    li s0, 4
    la t0, trap_flag
    sw zero, 0(t0)                   # Clear trap flag

    # MISC_MEM opcode (0x0001111) with funct3=010 (only 000=FENCE and 001=FENCE.I are valid)
    # Binary: imm12_00000_010_00000_0001111 = 0x0000200F
    .word 0x0000200F

    # Check that trap was taken
    lw t0, 0(t0)
    beqz t0, test_fail               # Fail if trap not taken
    li t1, MCAUSE_ILLEGAL_INST
    bne t0, t1, test_fail            # Fail if wrong cause

    # --------------------------------------------------
    # Test 5: LOAD opcode with invalid funct3
    # --------------------------------------------------
    li s0, 5
    la t0, trap_flag
    sw zero, 0(t0)                   # Clear trap flag

    # LOAD opcode (0x0000011) with funct3=011 (not a valid load size)
    # Binary: imm12_00001_011_00010_0000011 = 0x0000B103
    .word 0x0000B103

    # Check that trap was taken
    lw t0, 0(t0)
    beqz t0, test_fail               # Fail if trap not taken
    li t1, MCAUSE_ILLEGAL_INST
    bne t0, t1, test_fail            # Fail if wrong cause

    # --------------------------------------------------
    # Test 6: Verify CPU still works after exceptions
    # --------------------------------------------------
    li s0, 6
    li t0, 42
    addi t0, t0, 8
    li t1, 50
    bne t0, t1, test_fail            # Fail if arithmetic doesn't work

    # --------------------------------------------------
    # All tests passed!
    # --------------------------------------------------
    j test_pass

# --------------------------------------------------
# Trap Handler
# --------------------------------------------------
.align 4
trap_handler:
    # Save registers we'll use
    addi sp, sp, -16
    sw t0, 0(sp)
    sw t1, 4(sp)
    sw t2, 8(sp)

    # Check if this is an illegal instruction exception
    csrr t0, mcause
    li t1, MCAUSE_ILLEGAL_INST
    bne t0, t1, unexpected_trap

    # Store mcause in trap_flag to indicate trap was taken
    la t1, trap_flag
    sw t0, 0(t1)

    # Skip the illegal instruction by advancing mepc by 4
    csrr t0, mepc
    addi t0, t0, 4
    csrw mepc, t0

    # Restore registers
    lw t0, 0(sp)
    lw t1, 4(sp)
    lw t2, 8(sp)
    addi sp, sp, 16

    # Return from trap
    mret

unexpected_trap:
    # Unexpected trap - fail with mcause in a0
    csrr a0, mcause
    addi a0, a0, 100             # Offset to distinguish from other failures
    j test_fail

# --------------------------------------------------
# Test result handlers
# --------------------------------------------------
test_pass:
    li gp, 1                     # gp = 1 means PASS
    la t0, tohost
    li t1, 1
    sw t1, 0(t0)                 # Write to tohost to stop simulation
1:  j 1b

test_fail:
    # s0 contains the test number that failed
    slli gp, s0, 1               # gp = (test_num << 1)
    ori gp, gp, 1                # gp = (test_num << 1 | 1)
    la t0, tohost
    sw gp, 0(t0)                 # Write to tohost to stop simulation
1:  j 1b

# --------------------------------------------------
# Data Section
# --------------------------------------------------
.section .data
.align 4
trap_flag:
    .word 0

.align 4
.globl tohost
tohost:
    .word 0
