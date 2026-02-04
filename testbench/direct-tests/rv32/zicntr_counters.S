# Counter CSR test
#
# Validates that cycle/instret and fixed HPM counters increment:
#   hpmcounter3  - branches retired
#   hpmcounter4  - branch misses
#   hpmcounter5  - hazard stall cycles

.section .text

.globl _main
_main:
    # Clear machine-mode counters (low/high halves on RV32).
    csrw 0xB00, zero
    csrw 0xB80, zero
    csrw 0xB02, zero
    csrw 0xB82, zero
    csrw 0xB03, zero
    csrw 0xB83, zero
    csrw 0xB04, zero
    csrw 0xB84, zero
    csrw 0xB05, zero
    csrw 0xB85, zero

    # Read initial user-visible aliases.
    csrr s0, 0xC00  # cycle
    csrr s1, 0xC02  # instret
    csrr s2, 0xC03  # branches retired
    csrr s3, 0xC04  # branch misses
    csrr s4, 0xC05  # hazard stalls

    li t0, 16
    la t1, test_data

loop:
    # Load/use and RAW dependencies to trigger hazard stalls.
    lw t2, 0(t1)
    addi t3, t2, 1
    addi t3, t3, 1
    addi t3, t3, 1

    # Always-taken conditional branch to exercise branch miss counter.
    beq t3, t3, branch_taken
    li a0, 99

branch_taken:
    addi t0, t0, -1
    bnez t0, loop

    # Read counters again.
    csrr s5, 0xC00
    csrr s6, 0xC02
    csrr s7, 0xC03
    csrr s8, 0xC04
    csrr s9, 0xC05

    # cycle must advance
    bgtu s5, s0, cycle_ok
    li a0, 1
    j test_fail

cycle_ok:
    # instret must advance
    bgtu s6, s1, instret_ok
    li a0, 2
    j test_fail

instret_ok:
    # branches retired must advance
    bgtu s7, s2, branches_ok
    li a0, 3
    j test_fail

branches_ok:
    # branch misses must advance
    bgtu s8, s3, branch_miss_ok
    li a0, 4
    j test_fail

branch_miss_ok:
    # hazard stall cycles must advance
    bgtu s9, s4, stalls_ok
    li a0, 5
    j test_fail

stalls_ok:
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
.globl test_data
test_data:
    .word 7

.align 4
.globl tohost
tohost:
    .word 0
