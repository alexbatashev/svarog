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

    /* tohost/fromhost for test signaling */
    /* Place at known address for watchpoint */
    . = 0x80001000;
    .tohost : {
        tohost = .;
        . += 8;
        fromhost = .;
        . += 8;
    } > RAM

    /* Stack at end of RAM */
    . = 0x8000F000;
    .stack : {
        . += 0x1000;
        _stack_top = .;
    } > RAM

    /* Discard unneeded sections */
    /DISCARD/ : {
        *(.comment)
        *(.note*)
        *(.eh_frame*)
    }
}
