# Repository Guidelines

## Project Structure & Module Organization
This Java-first OS runs through our patched Graal toolchain: `Kernel.java` holds kernel entry points, `runtime.c` implements native glue, and `bootloader.asm` seeds execution. `Makefile`, `build_kernel.o.sh`, `use_graalvm.sh`, and `build_graalvm.sh` drive compilation into `obj/` artifacts and the final image `build/BB.bin`. The vendored Graal sources sit in `graal/`; temporary LLVM IR lands in `generated-llvm/` and can be purged with `make clean`.

## GraalVM Development Loop
Kernel work often starts in `graal/`. Patch the compiler/runtime, run `./build_graalvm.sh` to rebuild Graal, then regenerate kernel objects (`./build_kernel.o.sh` or `make`) so the refreshed toolchain emits updated code.

## Runtime Constraints & Known Limitations
Graal still mishandles static Java globals that need storage, so avoid `static` `String` or array fields in kernel classes. Prefer method-scoped literals or move persistent buffers into `runtime.c`, exposing accessors and noting lifetimes in comments.

## Build, Test, and Development Commands
Run `make` (alias for `make BB.bin`) to assemble the bootable image. The standard validation loop is `make clean && make && timeout 5 make qemu`, which performs a clean rebuild and boots QEMU long enough to capture VGA output without manual shutdown. Source `./use_graalvm.sh` before invoking Graal-supplied clang or LLVM utilities directly.

## Coding Style & Naming Conventions
Use four-space indentation in Java, two spaces in C; open braces start on new lines. Java types follow UpperCamelCase with lowerCamelCase methods, and exported natives stay descriptive (`writeCharAt`, `startKernel`). C helpers use snake_case but keep Graal-generated entry points untouched. Assembly listings retain uppercase mnemonics with inline comments around column 32.

## Testing & Validation Guidelines
No automated suite yet. Lean on the QEMU loop above, review `qemu_debug.log`, and add temporary assertions or serial prints when introducing runtime helpers—remove them before committing. Document manual test steps in pull requests and keep diagnostics inside `generated-llvm/` or `build/`.

## Commit & Pull Request Guidelines
Recent history favors concise, sentence-case subjects with optional scope prefixes (`Graal:`, `Support`). Note the main module in the subject and explain toolchain changes in the body. Pull requests should call out prerequisites (JDK 21, GCC, qemu), list manual test evidence (e.g., observed console text), and attach screenshots or logs only when they clarify VGA changes.
