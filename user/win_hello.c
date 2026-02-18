// win_hello.c - Minimal Windows console program for JOS testing
// Compile with: clang.exe --target=x86_64-windows-gnu -nostdlib -o win_hello.exe win_hello.c

// For JOS compatibility, we use int 0x80 instead of syscall
// Windows x64 calling convention: RCX, RDX, R8, R9

// JOS syscall numbers
#define SYS_PRINT 1
#define SYS_EXIT 2

// strlen helper
static long my_strlen(const char *s) {
    long len = 0;
    while (s[len]) len++;
    return len;
}

// Entry point - naked function to have full control
void __attribute__((naked, noreturn)) mainCRTStartup(void) {
    __asm__ volatile (
        // Set up stack frame
        "pushq %%rbp\n\t"
        "movq %%rsp, %%rbp\n\t"
        "subq $32, %%rsp\n\t"          // Shadow space for Windows
        
        // Message address in RCX (1st arg)
        "leaq message(%%rip), %%rcx\n\t"
        
        // Calculate length
        "xorq %%rdx, %%rdx\n\t"         // len = 0
        "1:\n\t"
        "cmpb $0, (%%rcx, %%rdx)\n\t"   // check s[len] == 0
        "je 2f\n\t"
        "incq %%rdx\n\t"
        "jmp 1b\n\t"
        "2:\n\t"
        
        // Save length to stack
        "pushq %%rdx\n\t"
        
        // JOS sys_print(buf, len)
        // Windows: RCX=buf, RDX=len -> JOS uses RDI,RSI
        // We need to shuffle registers for JOS
        "movq %%rcx, %%rdi\n\t"         // buf -> RDI
        "movq %%rdx, %%rsi\n\t"         // len -> RSI
        "movq $1, %%rax\n\t"            // SYS_PRINT
        "int $0x80\n\t"
        
        // Restore and exit
        "addq $40, %%rsp\n\t"           // Clean up stack
        "xorq %%rcx, %%rcx\n\t"         // status = 0
        "movq $2, %%rax\n\t"            // SYS_EXIT
        "int $0x80\n\t"
        
        // Halt if we return (shouldn't happen)
        "3:\n\t"
        "cli\n\t"
        "hlt\n\t"
        "jmp 3b\n\t"
        
        // Data
        ".section .rdata, \"dr\"\n\t"
        "message:\n\t"
        ".asciz \"Hello from Windows PE!\\n\"\n\t"
        ".text\n\t"
    );
}
