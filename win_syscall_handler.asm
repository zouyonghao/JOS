# =============================================================================
# Windows Syscall Support
# =============================================================================
# Minimal support for Windows PE programs
# For now, Windows programs need to use int 0x80 instead of syscall instruction
# or we provide a minimal ntdll.dll stub that converts syscall to int 0x80
# =============================================================================

.include "constants.inc"

.code64

.section .text

# Placeholder for future Windows syscall MSR_LSTAR handler
# For now, we require Windows programs to use a compatibility layer
.globl win_syscall_entry
win_syscall_entry:
    # If we get here, a Windows program executed 'syscall' directly
    # Save error code and halt
    movq $0xDEAD0001, %rax
    # Could write to a debug port or memory location here
1:  jmp 1b

# External Java globals (defined in Kernel.java)
.extern kernel_Syscalls_winSyscallNum
.extern kernel_Syscalls_winSyscallArg1
.extern kernel_Syscalls_winSyscallArg2
.extern kernel_Syscalls_winSyscallArg3
.extern kernel_Syscalls_winSyscallArg4
.extern kernel_Syscalls_isWindowsSyscall
.extern kernel_Syscalls_winSyscallRet
