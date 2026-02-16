#!/bin/bash
set -e

echo "=== Custom Java->LLVM Translator Build ==="

CUR_DIR=$(dirname "$(readlink -f "$0")")
cd "$CUR_DIR"

# Step 1: Compile Kernel.java
echo "Compiling Java source..."
javac Kernel.java

# Step 2: Compile the translator (if needed)
if [ ! -f JavaToLLVM.class ] || [ JavaToLLVM.java -nt JavaToLLVM.class ]; then
    echo "Compiling translator..."
    javac JavaToLLVM.java
fi

# Step 3: Run translator
rm -rf generated-llvm obj/Kernel.o
mkdir -p generated-llvm obj

echo "Translating bytecode to LLVM IR..."
java JavaToLLVM Kernel.class generated-llvm/Kernel.ll

# Step 4: Compile LLVM IR to object file
echo "Compiling LLVM IR to object file..."
clang -c generated-llvm/Kernel.ll -o obj/Kernel.o -O0 -fno-pic -mcmodel=small

echo "Generated obj/Kernel.o:"
nm obj/Kernel.o | grep -E "^[0-9a-f]+ [TtWw] " | head -10

# Clean up
rm -f Kernel.class

echo "=== Build complete ==="
