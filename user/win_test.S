# Simple Windows PE test for JOS
# Writes directly to VGA and uses syscalls

.section .text
.globl mainCRTStartup

mainCRTStartup:
    # Write 'X' directly to VGA (0xB8000) - proves code is executing
    movq $0xb8000, %rax
    movb $'X', (%rax)
    movb $0x0f, 1(%rax)  # White on black
    
    # Now try sys_print syscall
    leaq message(%rip), %rdi    # String pointer
    movq $24, %rsi              # Length
    
    movq $1, %rax               # SYS_PRINT syscall number
    int $0x80                   # Make syscall
    
    # Exit
    movq $2, %rax               # SYS_EXIT
    xorq %rdi, %rdi
    int $0x80
    
    # Should never reach here
    jmp .

.section .rdata, "dr"
message:
    .asciz "TEST: Hello from PE!\n"
