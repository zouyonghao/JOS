# Windows PE Loader and Syscall Emulation

This document describes the Windows PE (Portable Executable) loader and syscall emulation support added to JOS.

## Overview

JOS now has the ability to load and run simple Windows PE executables (`.exe` files). The implementation includes:

1. **PE Format Parser** - Reads Windows PE headers and loads sections into memory
2. **Auto-detect Loader** - Automatically detects PE vs SBF format when running programs
3. **Windows Syscall Emulation (SSDT)** - Basic handlers for common Windows NT syscalls
4. **Handle Table** - Manages Windows-style handles for I/O operations

## Architecture

### File Format Detection

When you run a file using the `run <filename>` command, the kernel:

1. Reads the file from the embedded filesystem
2. Checks for PE magic (`MZ` at offset 0, `PE\0\0` at offset specified by DOS header)
3. If PE: uses the PE loader
4. If not PE: falls back to SBF loader (original format)

### PE Loader (`loadPE`)

The PE loader:

1. Parses DOS header to find PE header offset
2. Validates PE signature and machine type (requires x64/AMD64)
3. Extracts key information:
   - Entry point RVA (Relative Virtual Address)
   - Preferred load address (ImageBase)
   - Section table
4. Allocates memory for the image
5. Copies headers and sections to the allocated memory
6. Returns the entry point address

### Windows Syscall Emulation

Windows x64 programs use the `syscall` instruction to invoke kernel services. JOS provides a compatibility layer:

**Supported Syscalls:**

| Syscall | Number | Description |
|---------|--------|-------------|
| NtWriteFile | 0x08 | Write to file/handle (stdout/stderr supported) |
| NtClose | 0x0F | Close a handle |
| NtAllocateVirtualMemory | 0x18 | Allocate memory |
| NtFreeVirtualMemory | 0x1E | Free memory |
| NtQueryInformationProcess | 0x19 | Get process info |
| NtTerminateProcess | 0x2C | Exit process |

**NT Status Codes:**

- `STATUS_SUCCESS` (0x00000000) - Operation succeeded
- `STATUS_INVALID_HANDLE` (0xC0000008) - Invalid handle
- `STATUS_INVALID_PARAMETER` (0xC000000D) - Invalid parameter
- `STATUS_NOT_IMPLEMENTED` (0xC0000002) - Syscall not implemented

## Limitations

### Current Limitations

1. **No True syscall Instruction Support**: The actual `syscall` CPU instruction handler (MSR_LSTAR) is a placeholder. Windows programs must either:
   - Be compiled to use `int 0x80` instead of `syscall`
   - Use a custom ntdll.dll that translates syscalls to `int 0x80`
   - Have their imports redirected to JOS-compatible stubs

2. **No Import Table Resolution**: The loader does not resolve imports from external DLLs (kernel32.dll, ntdll.dll, etc.). Programs must be:
   - Statically linked
   - Or have imports manually resolved before loading

3. **No Relocations**: If the preferred load address (ImageBase) is not available, the program will fail to load. Real Windows loaders handle relocations via the `.reloc` section.

4. **Limited Syscalls**: Only basic console I/O and memory allocation are supported. File operations, threading, registry access, etc. are not implemented.

5. **Subsystem Support**: Only console subsystem (`IMAGE_SUBSYSTEM_WINDOWS_CUI = 3`) programs are expected to work. GUI programs will likely fail.

6. **No ASLR/DEP**: Address Space Layout Randomization and Data Execution Prevention are not implemented.

## Usage

### Loading a Windows Program

```
> run program.exe
```

The kernel will auto-detect the format and attempt to load it.

### Expected Output

For a successfully loaded PE:
```
Detected Windows PE executable
  Sections: 3
  ImageBase: 0x0000000140000000
  EntryPoint RVA: 0x00001000
  Subsystem: 3
  Loading at: 0x0000000000500000 (size: 65536 bytes)
PE loaded successfully. Entry point: 0x0000000000501000
Spawned thread 1
```

## Technical Details

### PE Constants

```java
// DOS Header
DOS_MAGIC = 0x5A4D ("MZ")
DOS_LFANEW_OFFSET = 0x3C

// PE Header
PE_MAGIC = 0x00004550 ("PE\0\0")
PE32PLUS_MAGIC = 0x20B
IMAGE_FILE_MACHINE_AMD64 = 0x8664

// Subsystems
IMAGE_SUBSYSTEM_WINDOWS_CUI = 3 (Console)
IMAGE_SUBSYSTEM_WINDOWS_GUI = 2 (GUI)
```

### Memory Layout

- PE images are loaded into heap memory (starting at 4MB)
- Each PE gets its own allocation
- No virtual address space isolation between PEs (all share kernel space)

### Handle Table

- Fixed size array (64 handles)
- Handles 0, 1, 2 reserved for stdin, stdout, stderr
- Simple allocation/deallocation

## Future Work

To fully support Windows programs, the following would need to be implemented:

1. **Proper syscall Instruction Handler**:
   - Set up MSR_LSTAR to point to assembly handler
   - Save/restore CPU state
   - Dispatch to Java handlers

2. **Import Table Resolution**:
   - Parse Import Directory
   - Load required DLLs (or provide stubs)
   - Fix up IAT (Import Address Table)

3. **Relocation Support**:
   - Parse Base Relocation Table
   - Apply fixups if loaded at non-preferred address

4. **More Syscalls**:
   - File operations (CreateFile, ReadFile, etc.)
   - Console I/O (ReadConsole, WriteConsole)
   - Process/Thread management
   - Synchronization primitives

5. **DLL Loading**:
   - Proper ntdll.dll implementation
   - kernel32.dll stubs
   - Dependency resolution

6. **PEB/TEB**:
   - Process Environment Block
   - Thread Environment Block
   - TLS (Thread Local Storage) support

## References

- [PE Format Specification](https://docs.microsoft.com/en-us/windows/win32/debug/pe-format)
- [Windows Internals](https://docs.microsoft.com/en-us/windows-hardware/drivers/kernel/)
- [SSDT (System Service Descriptor Table)](https://en.wikipedia.org/wiki/System_Service_Descriptor_Table)
