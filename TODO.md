# JOS TODO

## Translator (JavaToLLVM.java)
- [x] Array support (`newarray`, `iastore`, `iaload`, `arraylength`, `bastore`, `baload`, etc.)
- [x] Multi-class support (translate multiple .class files, resolve cross-class references)
- [x] `tableswitch` / `lookupswitch` opcodes (for switch statements)
- [x] `ineg` / `lneg` opcodes
- [x] Constant pool `Integer` entries used by `ldc` (currently only handles strings)

## Kernel
- [x] Proper heap allocator with free (replace bump allocator)
- [x] More shell commands (memstat, dump, peek, poke, cat, stat, history)
- [x] Serial port output (COM1) for better QEMU debugging
- [x] ATA PIO disk read support (port I/O based)
- [x] Simple read-only filesystem (load data from disk)

## OS Features
- [x] Preemptive multithreading (kernel threads, round-robin scheduler, timer-driven context switch)
- [x] Thread management syscalls (yield, getpid, exit with cleanup)
- [x] Shell `ps` command (list active threads with TID and state)
- [x] Simple binary loader (SBF format, load user programs from disk)
- [x] Windows PE loader (PE32+ x86_64 support with import resolution)
- [x] kernel32.dll emulation (60 functions: handles, heap, files, threads, console, TLS, etc.)
- [x] msvcrt.dll emulation (50 functions: printf, malloc, string ops, wide-char, CRT init, etc.)
- [x] Run real Windows binaries (help.exe from System32)
- [ ] Run more complex Windows binaries (.exe from System32 or other meaningful executables)
- [ ] Run a GUI Windows binary
- [ ] User mode (ring 3) with syscall/sysret interface
- [ ] Process abstraction (per-process page tables, register save/restore)
- [ ] ELF loader (proper ELF format support)
- [ ] Writable filesystem (write support for disk filesystem)
- [ ] Network stack (basic TCP/IP via virtio-net or e1000)

## Infrastructure
- [x] Automated test harness (boot QEMU, capture serial output, check for PASS/FAIL)
- [x] Extended PE test suite (memtest, printf, threads, fileio)
