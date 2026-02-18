# Windows PE Support in JOS

## Overview

JOS now supports loading and running Windows PE (Portable Executable) binaries through a custom loader and syscall emulation layer.

## What Works

### 1. PE Format Detection and Loading
- Auto-detection of PE vs SBF format when running programs
- Parsing of PE32+ (x64) headers
- Loading of PE sections into memory
- Entry point calculation

### 2. Windows Syscall Emulation
Basic handlers implemented for:
- `NtWriteFile` - Console output (stdout/stderr)
- `NtClose` - Handle cleanup
- `NtAllocateVirtualMemory` - Memory allocation
- `NtFreeVirtualMemory` - Memory deallocation
- `NtQueryInformationProcess` - Process info
- `NtTerminateProcess` - Process exit

### 3. Test Program Created
A minimal Windows console program (`win_hello.exe`) that:
- Prints "Hello from Windows PE!\n"
- Uses JOS syscalls (int 0x80)
- Exits cleanly

## Creating Windows Binaries

### Requirements
- Windows clang installed (e.g., in `/mnt/c/Program Files/LLVM`)

### Compilation
```bash
# Create assembly source
cat > win_hello.S << 'EOF'
    .text
    .globl mainCRTStartup

mainCRTStartup:
    pushq %rbp
    movq %rsp, %rbp
    
    leaq message(%rip), %rdi
    
    xorq %rsi, %rsi
Lcount:
    cmpb $0, (%rdi, %rsi)
    je Ldone
    incq %rsi
    jmp Lcount
Ldone:
    
    movq $1, %rax
    int $0x80
    
    movq $2, %rax
    xorq %rdi, %rdi
    int $0x80
    
Lhang:
    cli
    hlt
    jmp Lhang

    .section .rdata, "dr"
message:
    .asciz "Hello from Windows PE!\n"
EOF

# Compile with Windows clang
"/mnt/c/Program Files/LLVM/bin/clang.exe" \
    --target=x86_64-windows-gnu \
    -nostdlib -fuse-ld=lld \
    -o win_hello.exe win_hello.S
```

## Running Windows Programs in JOS

1. Add the PE file to the disk image:
```bash
make disk  # Rebuild disk with win_hello.exe
# Or manually:
python3 makedisk.py build/disk.img user/hello.sbf user/counter.sbf win_hello.exe
python3 embed_fs.py build/BB.bin build/disk.img
```

2. Run in QEMU (interactive mode):
```bash
make qemu-disk
```

3. In the JOS shell, type:
```
> run win_hello.exe
```

You should see:
```
Detected Windows PE executable
  Sections: 3
  ImageBase: 0x0000000140000000
  EntryPoint RVA: 0x00001000
  Subsystem: 3
  Loading at: 0x0000000000500000
PE loaded successfully. Entry point: 0x0000000000501000
Spawned thread 1
> Hello from Windows PE!
```

## Current Limitations

1. **No true `syscall` instruction support** - Windows programs must use `int 0x80` instead of the `syscall` CPU instruction
2. **No import table resolution** - Cannot load programs that depend on external DLLs (kernel32.dll, ntdll.dll, etc.)
3. **No relocations** - Programs must load at their preferred address
4. **Minimal syscall set** - Only basic console I/O and memory operations

## Architecture

### PE Loader Flow
```
run win_hello.exe
    |
    v
loadBinaryAuto()
    |
    +-- isPEFormat() -- Detect PE magic
    |
    +-- loadPE() -- Parse headers, load sections
    |       |
    |       +-- Allocate memory from heap
    |       +-- Copy headers and sections
    |       +-- Return entry point
    |
    +-- runProgram() -- Spawn thread at entry point
```

### Syscall Handling
```
PE Program
    |
    v
int $0x80  (JOS syscall)
    |
    v
Kernel.handleSyscall()
    |
    +-- Regular JOS syscalls (SYS_PRINT=1, SYS_EXIT=2, etc.)
```

## Files Modified/Created

- `Kernel.java` - PE loader, syscall emulation, SSDT
- `win_syscall_handler.asm` - Placeholder for future syscall instruction handler
- `Makefile` - Added new assembly file
- `user/win_hello.S` - Test Windows program
- `WINDOWS_EMULATION.md` - Technical documentation
- `WINDOWS_PE_SUPPORT.md` - This file

## Future Work

To run real Windows programs (like actual `dir.exe`):

1. **Import Table Resolution**
   - Parse Import Directory
   - Provide stub implementations of kernel32/ntdll functions
   - Fix up Import Address Table

2. **True syscall Instruction Support**
   - Set up MSR_LSTAR to point to handler
   - Save/restore CPU state
   - Dispatch to Java handlers

3. **More NT Syscalls**
   - File operations (CreateFile, ReadFile)
   - Console I/O (ReadConsole, WriteConsole)
   - Process/thread management

4. **PEB/TEB Support**
   - Process Environment Block
   - Thread Environment Block

## References

- [PE Format Specification](https://docs.microsoft.com/en-us/windows/win32/debug/pe-format)
- [Windows x64 Calling Convention](https://docs.microsoft.com/en-us/cpp/build/x64-calling-convention)
