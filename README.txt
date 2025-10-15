This project aims to build an OS with Java (mostly) and a runtime written in C and ASM.

The Graal source code is embedded in this repository at ./graal with modifications to generate kernel-friendly LLVM.

To compile, use build_grallvm.sh.

To run with qemu, use "make qemu".
