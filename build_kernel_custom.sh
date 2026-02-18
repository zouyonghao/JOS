#!/bin/bash
set -e

echo "=== JOS Modular Java->LLVM Build ==="

CUR_DIR=$(dirname "$(readlink -f "$0")")
cd "$CUR_DIR"

# Step 1: Compile all Java kernel modules
echo "Compiling kernel modules..."
javac kernel/*.java

# Step 2: Compile the translator (if needed)
if [ ! -f JavaToLLVM.class ] || [ JavaToLLVM.java -nt JavaToLLVM.class ]; then
    echo "Compiling translator..."
    javac JavaToLLVM.java
fi

# Step 3: Run translator with all class files
rm -rf generated-llvm obj/Kernel.o
mkdir -p generated-llvm obj

echo "Translating bytecode to LLVM IR..."
# Pass all kernel .class files to the translator
java JavaToLLVM kernel/*.class generated-llvm/Kernel.ll

# Step 4: Compile LLVM IR to object file
echo "Compiling LLVM IR to object file..."
clang -c generated-llvm/Kernel.ll -o obj/Kernel.o -O0 -fno-pic -mcmodel=small -mno-red-zone -mno-mmx -mno-sse -mno-sse2

echo "Generated obj/Kernel.o:"
nm obj/Kernel.o | grep -E "^[0-9a-f]+ [TtWw] " | head -20

# Clean up
rm -f kernel/*.class

echo "=== Build complete ==="
