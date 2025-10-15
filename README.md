This project aims to build an OS with Java (mostly) and a runtime written in C and ASM.

Compilation

The Graal source code is now embedded in this repository at `./graal/` with some modifications to generate kernel-friendly LLVM.

To compile:
```bash
build_grallvm.sh
```

To run with qemu:
```bash
make qemu
```
