# =============================================================================
# Windows Syscall Support via syscall instruction
# =============================================================================
# This module handles the syscall instruction for Windows-compatible binaries.
# 
# On x86_64, syscall uses these MSRs:
#   MSR_LSTAR (0xC0000082) - Target RIP
#   MSR_STAR (0xC0000081) - Segments (bits 63:48 = CS for syscall, bits 47:32 = CS for sysret)
#   MSR_SYSCALL_MASK (0xC0000084) - RFLAGS mask
#
# Syscall calling convention:
#   RAX = syscall number
#   RCX, RDX, R8, R9 = arguments (Windows x64 calling convention)
#   R10, R11 = clobbered
#   RIP saved to RCX, RFLAGS saved to R11
# =============================================================================

.include "constants.inc"

.code64

.section .text

# -----------------------------------------------------------------------------
# syscall_entry - Handler for syscall instruction (via MSR_LSTAR)
# -----------------------------------------------------------------------------
# This is entered when user code executes 'syscall' instruction
# We need to:
#   1. Save user context
#   2. Set up kernel stack
#   3. Call Java handler
#   4. Restore context and sysret
# -----------------------------------------------------------------------------
.globl syscall_entry
syscall_entry:
    # At entry:
    #   RAX = syscall number
    #   RCX = user RIP (syscall saves this)
    #   R11 = user RFLAGS (syscall saves this)
    #   CS = kernel code segment (0x08)
    #   SS = kernel data segment (0x10)
    
    # Save user context on kernel stack
    pushq %rcx              # User RIP
    pushq %r11              # User RFLAGS
    pushq %rax              # Syscall number
    pushq %rdx              # Arg 2
    pushq %r8               # Arg 3
    pushq %r9               # Arg 4
    pushq %r10              # Extra saved reg
    pushq %rbx
    pushq %rbp
    pushq %rsi
    pushq %rdi
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15
    
    # Save user stack pointer and switch to kernel stack
    movq %rsp, user_rsp_save
    movq kernel_stack_top, %rsp
    
    # Set up arguments for Java handler
    # RAX = syscall number (already in place from push)
    movq 0(%rsp), %rax      # Get syscall number from saved context
    movq %rax, kernel_Syscalls_winSyscallNum(%rip)
    
    movq 64(%rsp), %rcx     # Get original RCX (arg1) from saved context
    movq %rcx, kernel_Syscalls_winSyscallArg1(%rip)
    
    movq 56(%rsp), %rdx     # Get original RDX (arg2) from saved context
    movq %rdx, kernel_Syscalls_winSyscallArg2(%rip)
    
    movq 48(%rsp), %r8      # Get original R8 (arg3) from saved context
    movq %r8, kernel_Syscalls_winSyscallArg3(%rip)
    
    movq 40(%rsp), %r9      # Get original R9 (arg4) from saved context
    movq %r9, kernel_Syscalls_winSyscallArg4(%rip)
    
    # Mark as Windows syscall
    movq $1, kernel_Syscalls_isWindowsSyscall(%rip)
    
    # Call Java handler
    call kernel_Syscalls_handleWindowsSyscall_V
    
    # Get return value
    movq kernel_Syscalls_winSyscallRet(%rip), %rax
    
    # Restore user stack
    movq user_rsp_save, %rsp
    
    # Restore context
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rdi
    popq %rsi
    popq %rbp
    popq %rbx
    popq %r10
    popq %r9
    popq %r8
    popq %rdx
    # Don't pop RAX - that's our return value
    addq $8, %rsp
    popq %r11
    popq %rcx
    
    # Return to user space
    sysretq

# -----------------------------------------------------------------------------
# init_syscall_msr - Set up MSRs for syscall/sysret
# -----------------------------------------------------------------------------
# Called once during kernel initialization
# -----------------------------------------------------------------------------
.globl init_syscall_msr
init_syscall_msr:
    pushq %rax
    pushq %rcx
    pushq %rdx
    
    # MSR_STAR (0xC0000081)
    # bits 63:48 = CS for syscall (kernel) = 0x08
    # bits 47:32 = CS for sysret (user) = 0x1B (with RPL=3)
    # SS is CS+8
    movl $0xC0000081, %ecx
    xorl %eax, %eax
    movl $0x001B0008, %eax   # User CS = 0x1B, Kernel CS = 0x08
    shlq $32, %rax
    movl $0x001B0008, %edx   # High bits
    wrmsr
    
    # MSR_LSTAR (0xC0000082) - syscall entry point
    movl $0xC0000082, %ecx
    leaq syscall_entry(%rip), %rax
    movq %rax, %rdx
    shrq $32, %rdx
    wrmsr
    
    # MSR_SYSCALL_MASK (0xC0000084) - RFLAGS to clear on syscall
    # Clear IF (interrupt flag), TF (trap flag), etc.
    movl $0xC0000084, %ecx
    movl $0x00000200, %eax   # Clear IF (bit 9)
    xorl %edx, %edx
    wrmsr
    
    popq %rdx
    popq %rcx
    popq %rax
    ret

# -----------------------------------------------------------------------------
# Data
# -----------------------------------------------------------------------------
.section .data
    .align 8
user_rsp_save:
    .quad 0
kernel_stack_top:
    .quad 0x900000    # Temporary - should be set during init

# External Java globals (defined in Syscalls.java)
.extern kernel_Syscalls_winSyscallNum
.extern kernel_Syscalls_winSyscallArg1
.extern kernel_Syscalls_winSyscallArg2
.extern kernel_Syscalls_winSyscallArg3
.extern kernel_Syscalls_winSyscallArg4
.extern kernel_Syscalls_isWindowsSyscall
.extern kernel_Syscalls_winSyscallRet
.extern kernel_Syscalls_handleWindowsSyscall_V
