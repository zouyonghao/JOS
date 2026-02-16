ENTRY(boot)
OUTPUT_FORMAT("binary")
SECTIONS {
    . = 0x7C00;
    .text :
    {
        *(.boot)
        *(.text)
    }
    .rodata :
    {
        __rodata_start = .;
        *(.rodata)
        *(.rodata.*)
        __rodata_end = .;
    }
    .data :
    {
        *(.data)
    }
    .bss :
    {
        *(.bss)
    }
    /DISCARD/ :
    {
        *(.eh_frame)
        *(.note.*)
        *(.comment)
    }
}