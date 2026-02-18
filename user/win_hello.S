// win_hello.asm - Minimal Windows PE for JOS
// Assemble with: clang --target=x86_64-windows-gnu -nostdlib -o win_hello.exe win_hello.asm

    .text
    .globl mainCRTStartup

mainCRTStartup:
    // Set up stack
    pushq %rbp
    movq %rsp, %rbp
    
    // JOS sys_print(buf, len)
    // Uses: RAX=syscall_num, RDI=buf, RSI=len
    
    // Load message address
    leaq message(%rip), %rdi
    
    // Calculate string length
    xorq %rsi, %rsi         // len = 0
Lcount:
    cmpb $0, (%rdi, %rsi)   // check s[len] == 0
    je Ldone
    incq %rsi
    jmp Lcount
Ldone:
    
    // Call sys_print
    movq $1, %rax           // SYS_PRINT
    int $0x80
    
    // Exit
    movq $2, %rax           // SYS_EXIT
    xorq %rdi, %rdi         // status = 0
    int $0x80
    
    // Halt (shouldn't reach here)
Lhang:
    cli
    hlt
    jmp Lhang

    .section .rdata, "dr"
message:
    .asciz "Hello from Windows PE!\n"
