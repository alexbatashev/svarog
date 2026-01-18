# Common startup code for direct tests
# Provides minimal initialization and tohost signaling

.section .text.init
.globl _start
.globl write_tohost

# Test result register (matches riscv-tests convention)
.equ TESTNUM, 3  # gp register

_start:
    # Initialize all registers to zero
    li x1, 0
    li x2, 0
    li x3, 0
    li x4, 0
    li x5, 0
    li x6, 0
    li x7, 0
    li x8, 0
    li x9, 0
    li x10, 0
    li x11, 0
    li x12, 0
    li x13, 0
    li x14, 0
    li x15, 0
    li x16, 0
    li x17, 0
    li x18, 0
    li x19, 0
    li x20, 0
    li x21, 0
    li x22, 0
    li x23, 0
    li x24, 0
    li x25, 0
    li x26, 0
    li x27, 0
    li x28, 0
    li x29, 0
    li x30, 0
    li x31, 0

    # Initialize stack pointer
    la sp, _stack_top

    # Initialize test number to 0
    li gp, 0

    # Jump to test main
    j _main

# Write result to tohost and spin
# gp (x3) contains: 1 = PASS, (test_num << 1 | 1) = FAIL
write_tohost:
    la t0, tohost
    sw gp, 0(t0)
    sw zero, 4(t0)
1:  j 1b

# Test passes: set gp=1 and write to tohost
.globl test_pass
test_pass:
    li gp, 1
    j write_tohost

# Test fails: set gp=(test_num << 1 | 1) and write to tohost
# a0 = test number that failed
.globl test_fail
test_fail:
    slli gp, a0, 1
    ori gp, gp, 1
    j write_tohost
