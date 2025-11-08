.section .text
.globl _start

# Simple UART boot monitor:
# Expects host to send 32-bit little-endian length, then 32-bit load address,
# followed by payload bytes. After loading, jumps to start address.

# UART register map
.equ UART_BASE,      0x10000000
.equ UART_STATUS,    0x10000004
.equ UART_RXDATA,    0x10000008
.equ UART_TXDATA,    0x10000000
.equ BOOT_STACK,     0x00003FFC

_start:
    lui  sp, %hi(BOOT_STACK)
    addi sp, sp, %lo(BOOT_STACK)
    lui  t0, %hi(UART_STATUS)
    addi t0, t0, %lo(UART_STATUS)
    lui  t1, %hi(UART_RXDATA)
    addi t1, t1, %lo(UART_RXDATA)
    lui  t2, %hi(UART_TXDATA)
    addi t2, t2, %lo(UART_TXDATA)

    jal  ra, read_word         # length -> a0
    mv   s0, a0                # remaining bytes
    jal  ra, read_word         # destination address
    mv   s1, a0                # current pointer
    mv   s2, a0                # entry point

load_loop:
    beqz s0, jump_entry
    jal  ra, read_byte
    sb   a0, 0(s1)
    addi s1, s1, 1
    addi s0, s0, -1
    j    load_loop

jump_entry:
    jal  ra, send_ack
    jr   s2

# Wait for RX byte in a0
read_byte:
    mv   t3, t0                # status pointer
1:
    lw   t4, 0(t3)
    andi t4, t4, 0x2           # rxValid is bit 1
    beqz t4, 1b
    lw   a0, 0(t1)
    ret

# Read 32-bit little-endian word via four read_byte calls
read_word:
    addi sp, sp, -16
    sw   ra, 12(sp)
    sw   s3, 8(sp)
    sw   s4, 4(sp)
    sw   s5, 0(sp)

    jal  ra, read_byte
    mv   s3, a0
    jal  ra, read_byte
    mv   s4, a0
    jal  ra, read_byte
    mv   s5, a0
    jal  ra, read_byte

    slli t4, a0, 24
    slli t5, s5, 16
    slli t6, s4, 8
    or   t4, t4, t5
    or   t4, t4, t6
    or   a0, t4, s3

    lw   ra, 12(sp)
    lw   s3, 8(sp)
    lw   s4, 4(sp)
    lw   s5, 0(sp)
    addi sp, sp, 16
    ret

# Send single-byte ACK (0x06)
send_ack:
    li   a0, 0x06
    jal  ra, write_byte
    ret

# Blocking TX write
write_byte:
    mv   t3, t0
2:
    lw   t4, 0(t3)
    andi t4, t4, 0x1           # txReady bit 0
    beqz t4, 2b
    sb   a0, 0(t2)
    ret
