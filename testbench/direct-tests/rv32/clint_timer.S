# CLINT Timer Interrupt Test
#
# Tests that the Core Local Interrupter correctly triggers
# a machine timer interrupt when mtime >= mtimecmp.
#
# Test sequence:
# 1. Set up trap handler at mtvec
# 2. Set mtimecmp to mtime + small_delta
# 3. Enable MTIE in mie
# 4. Enable MIE in mstatus
# 5. Wait for interrupt flag to be set
# 6. Verify handler was called
# 7. PASS if flag set, FAIL if timeout

.section .text

# Memory-mapped timer registers (from SvarogSoC)
.equ TIMER_BASE,     0x02000000
.equ MTIME_ADDR,     0x02000000  # mtime address
.equ MTIMECMP_ADDR,  0x02004000  # mtimecmp[0] address

# CSR bit positions
.equ MSTATUS_MIE,    0x8         # bit 3: Machine Interrupt Enable
.equ MIE_MTIE,       0x80        # bit 7: Machine Timer Interrupt Enable

# Interrupt cause codes (with interrupt bit set)
.equ MCAUSE_MTI,     0x80000007  # Machine Timer Interrupt

# Test parameters
.equ TIMER_DELTA,    100         # Trigger interrupt after this many ticks
.equ TIMEOUT_COUNT,  100000      # Max iterations to wait for interrupt

.globl _main
_main:
    # --------------------------------------------------
    # Step 1: Set up trap handler
    # --------------------------------------------------
    la t0, trap_handler
    csrw mtvec, t0

    # --------------------------------------------------
    # Step 2: Set mtimecmp = mtime + TIMER_DELTA
    # --------------------------------------------------

    # Read current mtime (64-bit, handle wrap)
    li t0, MTIME_ADDR
read_mtime:
    lw t2, 4(t0)                 # Read mtime high first
    lw t1, 0(t0)                 # Read mtime low
    lw t3, 4(t0)                 # Read mtime high again
    bne t2, t3, read_mtime       # Retry if high changed

    # Add delta to mtime (simple 32-bit add, assuming no overflow)
    li t3, TIMER_DELTA
    add t1, t1, t3               # mtime_lo + delta

    # Write to mtimecmp (write high first to avoid spurious interrupts)
    li t0, MTIMECMP_ADDR
    li t3, 0xFFFFFFFF
    sw t3, 4(t0)                 # Set mtimecmp high to max first
    sw t1, 0(t0)                 # Set mtimecmp low
    sw t2, 4(t0)                 # Set mtimecmp high to actual value

    # --------------------------------------------------
    # Step 3: Initialize interrupt flag to 0
    # --------------------------------------------------
    la t0, interrupt_flag
    sw zero, 0(t0)

    # --------------------------------------------------
    # Step 4: Enable timer interrupt in mie
    # --------------------------------------------------
    li t0, MIE_MTIE
    csrs mie, t0

    # --------------------------------------------------
    # Step 5: Enable global interrupts in mstatus
    # --------------------------------------------------
    li t0, MSTATUS_MIE
    csrs mstatus, t0

    # --------------------------------------------------
    # Step 6: Wait for interrupt with timeout
    # --------------------------------------------------
    li t1, TIMEOUT_COUNT
    la t2, interrupt_flag

wait_loop:
    lw t0, 0(t2)                 # Check flag
    bnez t0, interrupt_received  # Flag set = interrupt happened

    addi t1, t1, -1              # Decrement timeout
    bnez t1, wait_loop           # Keep waiting if not timed out

    # Timeout - test failed
    li a0, 1                     # Test 1 failed
    j test_fail

interrupt_received:
    # --------------------------------------------------
    # Step 7: Verify the flag value
    # --------------------------------------------------
    # Flag should be 0xDEADBEEF (set by handler)
    li t3, 0xDEADBEEF
    bne t0, t3, wrong_flag

    # Success!
    j test_pass

wrong_flag:
    li a0, 2                     # Test 2 failed
    j test_fail

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
    sw t3, 12(sp)

    # Check if this is a timer interrupt
    csrr t0, mcause
    li t1, MCAUSE_MTI
    bne t0, t1, unexpected_trap

    # Set the interrupt flag
    la t0, interrupt_flag
    li t1, 0xDEADBEEF
    sw t1, 0(t0)

    # Clear pending interrupt by setting mtimecmp to far future
    li t0, MTIMECMP_ADDR
    li t1, 0xFFFFFFFF
    sw t1, 0(t0)                 # mtimecmp low
    sw t1, 4(t0)                 # mtimecmp high

    # Restore registers
    lw t0, 0(sp)
    lw t1, 4(sp)
    lw t2, 8(sp)
    lw t3, 12(sp)
    addi sp, sp, 16

    # Return from trap
    mret

unexpected_trap:
    # Unexpected trap - fail with mcause in a0
    csrr a0, mcause
    addi a0, a0, 100             # Offset to distinguish from other failures
    j test_fail

# --------------------------------------------------
# Data Section
# --------------------------------------------------
.section .data
.align 4
interrupt_flag:
    .word 0
