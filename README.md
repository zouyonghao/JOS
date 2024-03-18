This project aims to build a OS with Java (mostly) and a runtime written in C and ASM.

Currently it can use Graal's LLVM backend to compile Java class to IR and then compile it to binary.
But for now, you have to apply the `do_not_insert_stackoverflow_check.patch` to Graal (must install in ~/graal).

It now can boot with qemu, just run `make qemu`.
