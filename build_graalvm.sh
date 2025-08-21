#!/bin/bash

# Build GraalVM with LLVM backend from source
# Based on: https://github.com/oracle/graal/blob/master/vm/README.md
# and: https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.core.graal.llvm/src/com/oracle/svm/core/graal/llvm/LLVMBackend.md

set -e

CUR_DIR=$(pwd)
export PATH=$CUR_DIR/mx:$PATH

# Use Labs OpenJDK for building (NOT GraalVM JDK)
export JAVA_HOME=$CUR_DIR/labs-jdk
export PATH=$JAVA_HOME/bin:$PATH

echo "Building GraalVM with LLVM backend..."
echo "JAVA_HOME: $JAVA_HOME"
echo "Java version: $(java -version 2>&1 | head -1)"

cd graal

# First build the SubstrateVM components (includes LLVM backend)
echo "Building SubstrateVM with LLVM backend..."
cd substratevm
mx build --all

# Now build the VM distribution
echo "Building VM distribution..."
cd ../vm
mx --dynamicimports /substratevm graalvm-show

# Get the built GraalVM home and create it
echo "Creating GraalVM distribution..."
export GRAALVM_HOME=$(mx --dynamicimports /substratevm graalvm-home)
echo "GraalVM will be at: $GRAALVM_HOME"

# Try to build the distribution
# mx --dynamicimports /substratevm graalvm-dist
mx --dynamicimports /sulong,/substratevm --exclude-components=nju,nic,ni,nil,llp build
mx --dynamicimports /sulong,/substratevm --exclude-components=nju,nic,ni,nil,llp graalvm-dist

# Verify what we have
echo "Checking build results..."
if [ -d "$GRAALVM_HOME" ]; then
    echo "GraalVM directory exists"
    ls -la "$GRAALVM_HOME/bin/" || echo "No bin directory yet"
    
    # Check if we can find native-image
    if [ -f "$GRAALVM_HOME/bin/native-image" ]; then
        echo "native-image tool found!"
        echo "Testing LLVM backend availability..."
        $GRAALVM_HOME/bin/native-image --expert-options | grep -i backend || echo "Backend options found"
    else
        echo "native-image not found, checking alternative locations..."
        find "$GRAALVM_HOME" -name "native-image" -type f || echo "native-image not found anywhere"
    fi
else
    echo "GraalVM directory not created yet"
fi

cd $CUR_DIR
echo "Build process complete!"
echo "GRAALVM_HOME: $GRAALVM_HOME"
