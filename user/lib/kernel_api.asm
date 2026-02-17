# kernel_api.asm - Assembly wrappers for kernel calls
# These functions provide the interface between user code and kernel services
# For now, these are stubs that will be replaced with proper syscalls later
#
# GNU as syntax (AT&T style)

    .text
    .globl kernel_print
    .globl kernel_exit

# kernel_print - Print a null-terminated string
# Input: %rdi = pointer to string
# For now, this is a placeholder that infinite loops
# The kernel will patch this or use a different mechanism
kernel_print:
    # Placeholder: infinite loop for now
    jmp .

# kernel_exit - Exit the user program
kernel_exit:
    # Placeholder: infinite loop for now
    jmp .
