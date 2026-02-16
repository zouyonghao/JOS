This project aims to build an OS with Java (mostly) and a runtime written in C and ASM.

Build pipeline:
  javac Kernel.java -> JavaToLLVM.java translator -> clang -> ld (flat binary)

Key files:
  Kernel.java         - Kernel source (Java)
  JavaToLLVM.java     - Custom bytecode-to-LLVM-IR translator
  runtime.c           - Native methods and string operations
  bootloader.asm      - x86-64 bootloader
  linker.ld           - Linker script
  build_kernel_custom.sh - Build script for Kernel.o

To compile: make
To run with QEMU: make qemu
