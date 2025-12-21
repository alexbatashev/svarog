/* Linker script for RV32I program running on Svarog SoC */

/* Entry point */
ENTRY(_start)

/* Memory layout */
MEMORY
{
    ROM (rx)  : ORIGIN = 0x00480000, LENGTH = 64K
    RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64K
}

/* Section definitions */
SECTIONS
{
    /* Code and read-only data in ROM */
    .text : {
        *(.text._start)
        *(.text*)
    } > ROM

    .rodata : {
        *(.rodata*)
    } > ROM

    /* Data section - initialized data */
    .data : {
        *(.data*)
        *(.sdata*)
    } > RAM AT > ROM

    /* BSS section - uninitialized data */
    .bss : {
        __bss_start = .;
        *(.bss*)
        *(.sbss*)
        *(COMMON)
        __bss_end = .;
    } > RAM

    /* Stack grows downward from top of RAM */
    /* Stack pointer is initialized in code to 0x80010000 */

    /* Discard sections we don't need */
    /DISCARD/ : {
        *(.comment)
        *(.note*)
        *(.eh_frame*)
    }
}
