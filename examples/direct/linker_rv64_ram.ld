/* Linker script for RV64I program running on Svarog SoC */
/* Configuration: rv64_ram - Code in RAM at 0x80000000 */

/* Entry point */
ENTRY(_start)

/* Memory layout */
MEMORY
{
    RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64K
}

/* Section definitions */
SECTIONS
{
    /* Code and read-only data in RAM */
    .text : {
        *(.text._start)
        *(.text*)
    } > RAM

    .rodata : {
        *(.rodata*)
    } > RAM

    /* Data section - initialized data */
    .data : {
        *(.data*)
        *(.sdata*)
    } > RAM

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
