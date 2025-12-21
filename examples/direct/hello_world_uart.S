# RV32I Hello World UART Program
# Prints "Hello, World!" continuously to UART0

.section .text
.globl _start

# UART0 base address and register offsets
.equ UART0_BASE,    0x00100000
.equ UART_DATA,     0x00        # Data register offset
.equ UART_STATUS,   0x04        # Status register offset
.equ TX_READY_BIT,  0x01        # Bit 0: TX ready flag

# Memory addresses
.equ RAM_BASE,      0x80000000
.equ RAM_SIZE,      65536
.equ STACK_TOP,     RAM_BASE + RAM_SIZE

_start:
    # Initialize stack pointer to top of RAM
    li sp, STACK_TOP

main_loop:
    # Load address of hello string
    la a0, hello_string
    call print_string

    # Add delay between messages
    li a0, 500000       # Delay count
    call delay

    # Repeat forever
    j main_loop

# Function: print_string
# Args: a0 = pointer to null-terminated string
print_string:
    # Save return address
    addi sp, sp, -8
    sw ra, 4(sp)
    sw s0, 0(sp)

    # s0 = string pointer
    mv s0, a0

print_loop:
    # Load next character
    lbu a0, 0(s0)

    # Check for null terminator
    beqz a0, print_done

    # Print character
    call print_char

    # Advance to next character
    addi s0, s0, 1
    j print_loop

print_done:
    # Restore registers and return
    lw s0, 0(sp)
    lw ra, 4(sp)
    addi sp, sp, 8
    ret

# Function: print_char
# Args: a0 = character to print
print_char:
    # Save character in t0
    mv t0, a0

    # Load UART base address
    li t1, UART0_BASE

wait_tx_ready:
    # Read UART status register
    lw t2, UART_STATUS(t1)

    # Check if TX ready (bit 0)
    andi t2, t2, TX_READY_BIT
    beqz t2, wait_tx_ready

    # Write character to UART data register
    sw t0, UART_DATA(t1)

    ret

# Function: delay
# Args: a0 = delay count
delay:
    beqz a0, delay_done
    addi a0, a0, -1
    j delay
delay_done:
    ret

# Data section
.section .rodata
hello_string:
    .asciz "Hello, World!\n"
