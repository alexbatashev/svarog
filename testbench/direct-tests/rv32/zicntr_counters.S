# Zicntr counters test
#
# Validates that cycle and instret counters increment.

.section .text

.globl _main
_main:
    # Read initial counters
    csrr t0, cycle
    csrr t1, instret

    # Execute a few instructions
    li t2, 8
loop:
    addi t3, t3, 1
    addi t3, t3, 1
    addi t3, t3, 1
    addi t3, t3, 1
    addi t3, t3, 1
    addi t3, t3, 1
    addi t3, t3, 1
    addi t3, t3, 1
    addi t2, t2, -1
    bnez t2, loop

    # Read counters again
    csrr t4, cycle
    csrr t5, instret

    # cycle must advance
    bgtu t4, t0, cycle_ok
    li a0, 1
    j test_fail

cycle_ok:
    # instret must advance
    bgtu t5, t1, instret_ok
    li a0, 2
    j test_fail

instret_ok:
    j test_pass

test_pass:
    li gp, 1
    la t0, tohost
    li t1, 1
    sw t1, 0(t0)
1:  j 1b

test_fail:
    slli a0, a0, 1
    ori gp, a0, 1
    la t0, tohost
    sw gp, 0(t0)
2:  j 2b

.section .data
.align 4
.globl tohost
tohost:
    .word 0
