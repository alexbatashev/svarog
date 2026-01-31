# Test triggering exception with simple trap handler

.section .text

.globl _main
_main:
    # Set up trap handler
    la t0, trap_handler
    csrw mtvec, t0

    # Trigger illegal instruction
    .word 0x0000000B

    # Should never reach here
    li gp, 99
    la t0, tohost
    sw gp, 0(t0)
1:  j 1b

trap_handler:
    # Just pass (doesn't do MRET, so will loop here)
    li gp, 1
    la t0, tohost
    li t1, 1
    sw t1, 0(t0)
1:  j 1b

.section .data
.align 4
.globl tohost
tohost:
    .word 0
