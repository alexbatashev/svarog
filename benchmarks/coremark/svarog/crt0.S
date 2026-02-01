# Minimal startup for CoreMark on Svarog SoC (RV32)

.section .text.init
.globl _start

_start:
    la sp, _stack_top

    # Copy .data from ROM to RAM (noop for RAM config)
    la t0, __data_load
    la t1, __data_start
    la t2, __data_end
1:
    beq t1, t2, 2f
    lw t3, 0(t0)
    sw t3, 0(t1)
    addi t0, t0, 4
    addi t1, t1, 4
    j 1b

    # Zero .bss
2:
    la t1, __bss_start
    la t2, __bss_end
3:
    beq t1, t2, 4f
    sw zero, 0(t1)
    addi t1, t1, 4
    j 3b

4:
    call main
5:
    j 5b
