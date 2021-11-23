;
; Adapted from osdev.org's Bare Bones tutorial http://wiki.osdev.org/Bare_Bones
;

global loader
global magic
global mbd

; Multiboot stuff
MODULEALIGN equ  1<<0
MEMINFO     equ  1<<1
FLAGS       equ  MODULEALIGN | MEMINFO
MAGIC       equ  0x1BADB002
CHECKSUM    equ -(MAGIC + FLAGS)

section .text

align 4
MultiBootHeader:
    dd MAGIC
    dd FLAGS
    dd CHECKSUM

STACKSIZE equ 0x4000  ; Define our stack size at 16k
STACKPTR equ stack + STACKSIZE

loader:
    mov  esp, stack + STACKSIZE ; Setup stack pointer

    ; Check multiboot bootloader
    cmp     eax, 0x2BADB002
    jne     .hang

    mov  [magic], eax
    mov  [mbd], ebx

    call go.kernel.Kmain   ; Jump to Go's kernel.Kmain

    ;cli
.hang:
    hlt
    jmp  .hang

section .bss

align 4
stack: resb STACKSIZE   ; Reserve 16k for stack
magic: resd 1
mbd:   resd 1
