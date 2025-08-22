#!/bin/bash

# set -e # TODO: Fix compilation errors of current Graal since we modified the ForeignFunctionsCode.

. use_graalvm.sh

echo "Using built GraalVM at: $GRAALVM_HOME"

cd $CUR_DIR
rm -rf generated-llvm

echo "Compiling Java source..."
javac Kernel.java

echo "Using native-image with LLVM backend..."
$GRAALVM_HOME/bin/native-image \
    -H:+UnlockExperimentalVMOptions \
    -H:CompilerBackend=llvm \
    -H:TempDirectory=generated-llvm \
    -H:+BitcodeOptimizations \
    -R:StackSize=0 \
    -R:-InstallSegfaultHandler \
    -H:-PreserveFramePointer \
    Kernel

rm -f kernel Kernel.class
mkdir obj 2>/dev/null

# Check if we got LLVM IR files
if [ -d "generated-llvm" ]; then
    echo "Generated LLVM directory exists, looking for bitcode files..."
    find generated-llvm -name "*.bc" -o -name "*.ll" | head -5
    
    BC_FILE=$(grep startKernel generated-llvm/*/llvm/f*.bc -a -l | head -1)
    if [ -n "$BC_FILE" ]; then
        echo "Using bitcode file: $BC_FILE"
        
        # Use Graal clang
        CLANG=$GRAALVM_HOME/lib/llvm/bin/clang
        
        $CLANG $BC_FILE -o obj/kernel.ll -S -emit-llvm -fno-omit-frame-pointer
        $CLANG -c obj/kernel.ll -fno-omit-frame-pointer -o obj/Kernel.o
        # $CLANG -c runtime.c -o obj/runtime.o
        
        echo "Successfully compiled kernel with LLVM backend!"
    else
        echo "No bitcode files found, LLVM backend may not be working"
        exit 1
    fi
else
    echo "No generated-llvm directory found"
    exit 1
fi