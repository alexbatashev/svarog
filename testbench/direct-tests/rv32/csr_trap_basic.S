# CSR trap + mscratch sanity test
#
# Verifies:
# - mtvec points to handler
# - mcause reports ecall from M-mode
# - mepc captures the ecall PC
# - mscratch is readable/writable and can be used as a scratch pointer

.section .text

.equ MCAUSE_ECALL_M, 11

.globl _main
_main:
    # Initialize scratch pointer for trap handler
    la t0, scratch_area
    csrw mscratch, t0

    # Install trap handler
    la t0, trap_handler
    csrw mtvec, t0

    # Clear trap flag
    la t0, trap_flag
    sw zero, 0(t0)

    # Trigger trap
ecall_here:
    ecall

after_ecall:
    # Verify handler ran
    la t0, trap_flag
    lw t1, 0(t0)
    li t2, 1
    bne t1, t2, fail_flag

    # Verify handler wrote to scratch area
    la t0, scratch_area
    lw t1, 0(t0)
    li t2, 0xA5A5A5A5
    bne t1, t2, fail_scratch

    j test_pass

fail_flag:
    li a0, 3
    j test_fail

fail_scratch:
    li a0, 4
    j test_fail

# --------------------------------------------------
# Trap Handler
# --------------------------------------------------
.align 4
trap_handler:
    addi sp, sp, -16
    sw t0, 0(sp)
    sw t1, 4(sp)
    sw t2, 8(sp)
    sw t3, 12(sp)

    csrr t0, mcause
    li t1, MCAUSE_ECALL_M
    bne t0, t1, trap_fail_cause

    csrr t0, mepc
    la t1, ecall_here
    bne t0, t1, trap_fail_epc

    addi t0, t0, 4
    csrw mepc, t0

    csrr t0, mscratch
    li t1, 0xA5A5A5A5
    sw t1, 0(t0)

    la t2, trap_flag
    li t3, 1
    sw t3, 0(t2)

    lw t0, 0(sp)
    lw t1, 4(sp)
    lw t2, 8(sp)
    lw t3, 12(sp)
    addi sp, sp, 16

    mret

trap_fail_cause:
    li a0, 1
    j test_fail

trap_fail_epc:
    li a0, 2
    j test_fail

.section .data
.align 4
trap_flag:
    .word 0

.align 4
scratch_area:
    .word 0
