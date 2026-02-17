# kernel_api.asm - Assembly wrappers for kernel calls
# These functions use int 0x80 to invoke kernel syscalls
#
# GNU as syntax (AT&T style)
# Syscall numbers:
#   SYS_PRINT = 1  (rdi = str ptr, rsi = len)
#   SYS_EXIT  = 2  (rdi = status)

    .text
    .globl kernel_print
    .globl kernel_exit

# kernel_print - Print a string to the console
# Input: %rdi = pointer to string
#        %rsi = length of string
kernel_print:
    movq $1, %rax          # SYS_PRINT syscall number
    int $0x80              # Trigger syscall
    ret

# kernel_exit - Exit the user program
# Input: %rdi = exit status
kernel_exit:
    movq $2, %rax          # SYS_EXIT syscall number
    int $0x80              # Trigger syscall
    ret                    # Return to caller so _start can unwind back to kernel
