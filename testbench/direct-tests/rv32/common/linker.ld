/* Linker script for direct tests on Svarog SoC */
/* Memory layout matches SvarogSoC configuration */

ENTRY(_start)

MEMORY
{
    RAM (rwx) : ORIGIN = 0x80000000, LENGTH = 64K
}

SECTIONS
{
    /* Code section - start with init code */
    .text : {
        *(.text.init)
        *(.text*)
    } > RAM

    /* Read-only data */
    .rodata : {
        *(.rodata*)
    } > RAM

    /* Initialized data */
    .data : {
        *(.data*)
        *(.sdata*)
    } > RAM

    /* Uninitialized data */
    .bss : {
        __bss_start = .;
        *(.bss*)
        *(.sbss*)
        *(COMMON)
        __bss_end = .;
    } > RAM

    /* Stack grows down from end of RAM (must fit within 64K: 0x80000000-0x8000FFFF) */
    /* Place stack first to ensure it fits, then tohost after */
    . = ALIGN(16);
    .stack : {
        . += 0xE000;  /* Allocate most of remaining RAM for stack */
        _stack_top = .;
    } > RAM

    /* tohost/fromhost for test signaling */
    . = ALIGN(16);
    .tohost : {
        tohost = .;
        . += 8;
        fromhost = .;
        . += 8;
    } > RAM

    /* Discard unneeded sections */
    /DISCARD/ : {
        *(.comment)
        *(.note*)
        *(.eh_frame*)
    }
}
