# Test MRET instruction

.section .text

.globl _main
_main:
    # Set up trap handler
    la t0, trap_handler
    csrw mtvec, t0

    # Trigger illegal instruction
    .word 0x0000000B

    # Should reach here after MRET
    li gp, 1
    la t0, tohost
    li t1, 1
    sw t1, 0(t0)
1:  j 1b

trap_handler:
    # Read mepc, add 4, write back, then MRET
    csrr t0, mepc
    addi t0, t0, 4
    csrw mepc, t0
    mret

.section .data
.align 4
.globl tohost
tohost:
    .word 0
