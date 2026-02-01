# CSR status/interrupt register behavior test
#
# Verifies:
# - mstatus write mask behavior
# - mie write mask behavior
# - mip MSIP bit write/clear behavior
# - mscratch/medeleg/mideleg/mstatush read/write support

.section .text

.equ MSTATUS_MASK, 0x1888
.equ MIE_MASK, 0x0888
.equ MSIP_MASK, 0x0008
.equ MIP_NO_MSIP_MASK, 0x0880

.globl _main
_main:
    # mstatus mask check
    li t0, -1
    csrw mstatus, t0
    csrr t1, mstatus
    li t2, MSTATUS_MASK
    bne t1, t2, fail_mstatus_mask

    csrw mstatus, zero
    csrr t1, mstatus
    bnez t1, fail_mstatus_clear

    # mie mask check
    li t0, -1
    csrw mie, t0
    csrr t1, mie
    li t2, MIE_MASK
    bne t1, t2, fail_mie_mask

    # mip MSIP write/clear behavior
    li t0, -1
    csrw mip, t0
    csrr t1, mip
    li t2, MSIP_MASK
    and t3, t1, t2
    beqz t3, fail_msip_set

    li t4, MIP_NO_MSIP_MASK
    and t5, t1, t4
    bnez t5, fail_mip_mask

    csrw mip, zero
    csrr t1, mip
    and t3, t1, t2
    bnez t3, fail_msip_clear

    # mscratch read/write
    li t0, 0xA5A5A5A5
    csrw mscratch, t0
    csrr t1, mscratch
    bne t1, t0, fail_mscratch

    # medeleg read/write
    csrw medeleg, t0
    csrr t1, medeleg
    bne t1, t0, fail_medeleg

    # mideleg read/write
    csrw mideleg, t0
    csrr t1, mideleg
    bne t1, t0, fail_mideleg

    # mstatush read/write (RV32 only)
    csrw mstatush, t0
    csrr t1, mstatush
    bne t1, t0, fail_mstatush

    j test_pass

fail_mstatus_mask:
    li a0, 1
    j test_fail

fail_mstatus_clear:
    li a0, 2
    j test_fail

fail_mie_mask:
    li a0, 3
    j test_fail

fail_msip_set:
    li a0, 4
    j test_fail

fail_mip_mask:
    li a0, 5
    j test_fail

fail_msip_clear:
    li a0, 6
    j test_fail

fail_mscratch:
    li a0, 7
    j test_fail

fail_medeleg:
    li a0, 8
    j test_fail

fail_mideleg:
    li a0, 9
    j test_fail

fail_mstatush:
    li a0, 10
    j test_fail
