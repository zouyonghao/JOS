# kernel_api.asm - Assembly wrappers for kernel calls
# These functions use int 0x80 to invoke kernel syscalls
#
# GNU as syntax (AT&T style)
# Syscall numbers:
#   SYS_PRINT  = 1  (rdi = str ptr, rsi = len)
#   SYS_EXIT   = 2  (rdi = status)
#   SYS_YIELD  = 3
#   SYS_GETPID = 4

    .text
    .globl kernel_print
    .globl kernel_exit
    .globl kernel_yield
    .globl kernel_getpid

# kernel_print - Print a string to the console
# Input: %rdi = pointer to string
#        %rsi = length of string
kernel_print:
    movq $1, %rax          # SYS_PRINT syscall number
    int $0x80              # Trigger syscall
    ret

# kernel_exit - Exit the user program (terminates thread)
# Input: %rdi = exit status
kernel_exit:
    movq $2, %rax          # SYS_EXIT syscall number
    int $0x80              # Trigger syscall
    ret

# kernel_yield - Voluntarily yield CPU to another thread
kernel_yield:
    movq $3, %rax          # SYS_YIELD syscall number
    int $0x80
    ret

# kernel_getpid - Get current thread ID
# Returns: thread ID in %rax
kernel_getpid:
    movq $4, %rax          # SYS_GETPID syscall number
    int $0x80
    ret
