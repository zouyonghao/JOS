.include "constants.inc"

.code64

# =============================================================================
# BSS Section - IDT storage
# =============================================================================
.section .bss
.align 16
.globl idt_table
idt_table:
    .space 256 * 16          # 256 entries, 16 bytes each

idt_ptr:
    .short 0                 # Limit (size - 1)
    .quad 0                  # Base address

# =============================================================================
# Text Section - ISR stubs and handlers
# =============================================================================
.section .text

# Macro to create a simple interrupt handler that pushes the vector number
.macro isr_stub vector
.globl isr_handler_\vector
isr_handler_\vector:
    pushq $\vector
    jmp common_isr_handler
.endm

# Create stubs for vectors 0-47 (CPU exceptions + PIC IRQs)
isr_stub 0
isr_stub 1
isr_stub 2
isr_stub 3
isr_stub 4
isr_stub 5
isr_stub 6
isr_stub 7
isr_stub 8
isr_stub 9
isr_stub 10
isr_stub 11
isr_stub 12
isr_stub 13
isr_stub 14
isr_stub 15
isr_stub 16
isr_stub 17
isr_stub 18
isr_stub 19
isr_stub 20
isr_stub 21
isr_stub 22
isr_stub 23
isr_stub 24
isr_stub 25
isr_stub 26
isr_stub 27
isr_stub 28
isr_stub 29
isr_stub 30
isr_stub 31
isr_stub 32  # Timer (IRQ0)
isr_stub 33  # Keyboard (IRQ1)
isr_stub 34
isr_stub 35
isr_stub 36
isr_stub 37
isr_stub 38
isr_stub 39
isr_stub 40
isr_stub 41
isr_stub 42
isr_stub 43
isr_stub 44
isr_stub 45
isr_stub 46
isr_stub 47

# Syscall handler (vector 0x80) - special handling for system calls
.globl isr_handler_128
isr_handler_128:
    pushq $128
    jmp common_isr_handler

# Common handler that saves all registers and calls into C
common_isr_handler:
    # Save all general purpose registers
    pushq %rax
    pushq %rcx
    pushq %rdx
    pushq %rbx
    pushq %rbp
    pushq %rsi
    pushq %rdi
    pushq %r8
    pushq %r9
    pushq %r10
    pushq %r11
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15

    # Call C interrupt handler with vector number as argument
    # Vector number is at 120(%rsp) = 15 regs * 8 + original push

    # For syscalls (vector 0x80), save register args to Java globals
    cmpq $0x80, 120(%rsp)
    jne .not_syscall_save
    movq 112(%rsp), %rax
    movq %rax, Kernel_syscallNum(%rip)        # RAX = syscall number
    movq 64(%rsp), %rax
    movq %rax, Kernel_syscallArg1(%rip)       # RDI = arg1
    movq 72(%rsp), %rax
    movq %rax, Kernel_syscallArg2(%rip)       # RSI = arg2
    movq 96(%rsp), %rax
    movq %rax, Kernel_syscallArg3(%rip)       # RDX = arg3
.not_syscall_save:

    movq 120(%rsp), %rdi
    call interrupt_dispatch

    # For syscalls, write return value back to saved RAX
    cmpq $0x80, 120(%rsp)
    jne .not_syscall_ret
    movq Kernel_syscallRet(%rip), %rax
    movq %rax, 112(%rsp)
.not_syscall_ret:

    # === Context switch check ===
    # Java scheduler sets ctxLoadRSP to switch threads
    movq Kernel_ctxLoadRSP(%rip), %rax
    testq %rax, %rax
    jz .no_context_switch

    # Optionally save current RSP to thread's slot
    movq Kernel_ctxSaveRSPAddr(%rip), %rbx
    testq %rbx, %rbx
    jz .skip_save
    movq %rsp, (%rbx)
.skip_save:
    # Load new thread's RSP
    movq %rax, %rsp
    # Clear switch flags
    movq $0, Kernel_ctxLoadRSP(%rip)
    movq $0, Kernel_ctxSaveRSPAddr(%rip)
.no_context_switch:

    # Restore all general purpose registers
    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %r11
    popq %r10
    popq %r9
    popq %r8
    popq %rdi
    popq %rsi
    popq %rbp
    popq %rbx
    popq %rdx
    popq %rcx
    popq %rax

    # Remove vector number from stack
    addq $8, %rsp

    iretq

# =============================================================================
# IDT setup functions
# =============================================================================

# void idt_set_gate(int vector, void* handler, uint8_t type_attr)
.globl idt_set_gate
idt_set_gate:
    pushq %rbp
    movq %rsp, %rbp
    pushq %rbx
    
    # Calculate entry address: idt_table + vector * 16
    movslq %edi, %rax
    shlq $4, %rax
    leaq idt_table(%rip), %r8
    addq %r8, %rax
    
    # rsi = handler address
    # Store low 16 bits of handler
    movw %si, (%rax)
    
    # Store code segment selector
    movw $KERNEL_CODE_SEG, 2(%rax)
    
    # Store IST (0)
    movb $0, 4(%rax)
    
    # Store type_attr
    movb %dl, 5(%rax)
    
    # Store middle 16 bits of handler
    shrq $16, %rsi
    movw %si, 6(%rax)
    
    # Store high 32 bits of handler
    shrq $16, %rsi
    movl %esi, 8(%rax)
    
    # Store reserved (0)
    movl $0, 12(%rax)
    
    popq %rbx
    popq %rbp
    ret

# void idt_load(void)
.globl idt_load
idt_load:
    pushq %rbp
    movq %rsp, %rbp
    
    # Initialize IDT pointer
    leaq idt_ptr(%rip), %rax
    movw $0x0FFF, (%rax)
    leaq idt_table(%rip), %rcx
    movq %rcx, 2(%rax)
    
    # Load IDT
    lidtq (%rax)
    
    popq %rbp
    ret

# void enable_interrupts(void)
.globl enable_interrupts
enable_interrupts:
    sti
    ret

# void disable_interrupts(void)
.globl disable_interrupts
disable_interrupts:
    cli
    ret

# void pic_send_eoi(uint8_t irq)
.globl pic_send_eoi
pic_send_eoi:
    movl %edi, %eax
    cmpl $8, %eax
    jl .master_eoi
    movb $0x20, %al
    outb %al, $0xA0
.master_eoi:
    movb $0x20, %al
    outb %al, $0x20
    ret

# void io_wait(void)
.globl io_wait
io_wait:
    outb %al, $0x80
    ret

# Table of ISR handler addresses
.section .data
.align 8
.globl isr_stub_table
isr_stub_table:
    .quad isr_handler_0
    .quad isr_handler_1
    .quad isr_handler_2
    .quad isr_handler_3
    .quad isr_handler_4
    .quad isr_handler_5
    .quad isr_handler_6
    .quad isr_handler_7
    .quad isr_handler_8
    .quad isr_handler_9
    .quad isr_handler_10
    .quad isr_handler_11
    .quad isr_handler_12
    .quad isr_handler_13
    .quad isr_handler_14
    .quad isr_handler_15
    .quad isr_handler_16
    .quad isr_handler_17
    .quad isr_handler_18
    .quad isr_handler_19
    .quad isr_handler_20
    .quad isr_handler_21
    .quad isr_handler_22
    .quad isr_handler_23
    .quad isr_handler_24
    .quad isr_handler_25
    .quad isr_handler_26
    .quad isr_handler_27
    .quad isr_handler_28
    .quad isr_handler_29
    .quad isr_handler_30
    .quad isr_handler_31
    .quad isr_handler_32
    .quad isr_handler_33
    .quad isr_handler_34
    .quad isr_handler_35
    .quad isr_handler_36
    .quad isr_handler_37
    .quad isr_handler_38
    .quad isr_handler_39
    .quad isr_handler_40
    .quad isr_handler_41
    .quad isr_handler_42
    .quad isr_handler_43
    .quad isr_handler_44
    .quad isr_handler_45
    .quad isr_handler_46
    .quad isr_handler_47

# Syscall handler address - stored separately to avoid large padding
.globl syscall_handler_addr
syscall_handler_addr:
    .quad isr_handler_128

# Switch back to text section for the getter function
.section .text

# Function to get the syscall handler address (for C code)
.globl get_syscall_handler
get_syscall_handler:
    leaq isr_handler_128(%rip), %rax
    ret
