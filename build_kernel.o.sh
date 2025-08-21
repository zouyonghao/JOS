#!/bin/bash

set -e

CUR_DIR=$(pwd)
export PATH=$CUR_DIR/mx:$PATH

# Use Labs JDK to avoid JVMCI version issues
export JAVA_HOME=$CUR_DIR/labs-jdk
export PATH=$JAVA_HOME/bin:$PATH

echo "Using Labs JDK and building GraalVM..."
echo "JAVA_HOME: $JAVA_HOME"

# Build the complete GraalVM distribution using mx
cd graal/vm
echo "Building GraalVM distribution with SubstrateVM..."
mx --dynamicimports /substratevm build

# Get the built GraalVM
export GRAALVM_HOME=$(mx --dynamicimports /substratevm graalvm-home)
echo "Using built GraalVM at: $GRAALVM_HOME"

cd $CUR_DIR
rm -rf generated-llvm

echo "Compiling Java source..."
javac Kernel.java

echo "Using native-image with LLVM backend..."
$GRAALVM_HOME/bin/native-image \
    -H:CompilerBackend=llvm \
    -H:TempDirectory=generated-llvm \
    -H:+BitcodeOptimizations \
    -R:StackSize=0 \
    -R:-InstallSegfaultHandler \
    -H:-PreserveFramePointer \
    Kernel

rm -f kernel Kernel.class

# Check if we got LLVM IR files
if [ -d "generated-llvm" ]; then
    echo "Generated LLVM directory exists, looking for bitcode files..."
    find generated-llvm -name "*.bc" -o -name "*.ll" | head -5
    
    BC_FILE=$(find generated-llvm -name "*.bc" | head -1)
    if [ -n "$BC_FILE" ]; then
        echo "Using bitcode file: $BC_FILE"
        
        # Use system clang
        CLANG="clang"
        echo "Using system clang: $(which clang)"
        
        $CLANG $BC_FILE -o obj/kernel.ll -S -emit-llvm -fno-omit-frame-pointer
        $CLANG -c obj/kernel.ll -fno-omit-frame-pointer -o obj/Kernel.o
        $CLANG -c runtime.c -o obj/runtime.o
        
        echo "Successfully compiled kernel with LLVM backend!"
    else
        echo "No bitcode files found, LLVM backend may not be working"
        exit 1
    fi
else
    echo "No generated-llvm directory found"
    exit 1
fi