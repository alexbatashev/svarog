/* Linker script for CoreMark on Svarog SoC (RV32, boot ROM) */

ENTRY(_start)

MEMORY
{
    ROM (rx)  : ORIGIN = 0x00480000, LENGTH = 64K
    RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64K
}

SECTIONS
{
    .text : {
        *(.text.init)
        *(.text*)
    } > ROM

    .rodata : {
        *(.rodata*)
    } > ROM

    .data : {
        __data_start = .;
        *(.data*)
        *(.sdata*)
        __data_end = .;
    } > RAM AT > ROM

    __data_load = LOADADDR(.data);

    .bss : {
        __bss_start = .;
        *(.bss*)
        *(.sbss*)
        *(COMMON)
        __bss_end = .;
    } > RAM

    _stack_top = ORIGIN(RAM) + LENGTH(RAM);

    /DISCARD/ : {
        *(.comment)
        *(.note*)
        *(.eh_frame*)
    }
}
