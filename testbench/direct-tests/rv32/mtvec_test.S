# Test setting up mtvec without triggering exception

.section .text

.globl _main
_main:
    # Set up trap handler
    la t0, trap_handler
    csrw mtvec, t0

    # Don't trigger any exception, just pass
    li gp, 1
    la t0, tohost
    li t1, 1
    sw t1, 0(t0)
1:  j 1b

trap_handler:
    # Should never get here
    li gp, 99
    la t0, tohost
    sw gp, 0(t0)
1:  j 1b

.section .data
.align 4
.globl tohost
tohost:
    .word 0
