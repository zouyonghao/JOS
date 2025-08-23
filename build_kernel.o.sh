#!/bin/bash

# Simplified kernel build script, optimized for a single Kernel.java file

. use_graalvm.sh

echo "=== Java Native Kernel Build Script ==="
echo "Using GraalVM at: $GRAALVM_HOME"

cd $CUR_DIR
rm -rf generated-llvm obj/Kernel.o

echo "Compiling Java source..."
javac Kernel.java

echo "Compiling with GraalVM Native Image..."
$GRAALVM_HOME/bin/native-image \
    -H:+UnlockExperimentalVMOptions \
    -H:CompilerBackend=llvm \
    -H:TempDirectory=generated-llvm \
    -H:+BitcodeOptimizations \
    -R:StackSize=0 \
    -R:-InstallSegfaultHandler \
    -H:-PreserveFramePointer \
    -Dsvm.kernelFriendlyNames=true \
    -H:Name=java_kernel \
    --no-fallback \
    Kernel

# Clean up generated files
rm -f kernel Kernel.class java_kernel
mkdir -p obj

echo "Analyzing generated LLVM bitcode files..."

if [ -d "generated-llvm" ]; then
    LLVM_DIS=$GRAALVM_HOME/lib/llvm/bin/llvm-dis
    CLANG=$GRAALVM_HOME/lib/llvm/bin/clang
    
    # Find all Kernel-related LLVM IR files
    KERNEL_LL_FILES=($(find generated-llvm -name "Kernel*.ll" | sort))
    echo "Found ${#KERNEL_LL_FILES[@]} Kernel-related LLVM IR files"
    
    if [ ${#KERNEL_LL_FILES[@]} -eq 0 ]; then
        echo "❌ No Kernel-related LLVM IR files found"
        echo "Check contents of generated-llvm directory:"
        find generated-llvm -name "*.ll" | head -10
        exit 1
    fi
    
    # Show found Kernel files
    echo "Kernel-related LLVM IR files:"
    for ll_file in "${KERNEL_LL_FILES[@]}"; do
        size=$(stat -c%s "$ll_file" 2>/dev/null || echo "0")
        echo "  $(basename $ll_file) - ${size} bytes"
    done
    
    # Strategy: Try different ways to find a working configuration
    
    # Strategy 1: Find file containing Kernel_startKernel_Long
    MAIN_KERNEL_LL=""
    for ll_file in "${KERNEL_LL_FILES[@]}"; do
        if grep -q "Kernel_startKernel_Long" "$ll_file" 2>/dev/null; then
            echo "Found main kernel file: $(basename $ll_file)"
            MAIN_KERNEL_LL="$ll_file"
            break
        fi
    done
    
    # Strategy 2: If not found, look for filename containing startKernel
    if [ -z "$MAIN_KERNEL_LL" ]; then
        for ll_file in "${KERNEL_LL_FILES[@]}"; do
            if [[ "$(basename $ll_file)" == *"startKernel"* ]]; then
                echo "Found file containing startKernel: $(basename $ll_file)"
                MAIN_KERNEL_LL="$ll_file"
                break
            fi
        done
    fi
    
    # Strategy 3: Use the largest Kernel LLVM IR file
    if [ -z "$MAIN_KERNEL_LL" ]; then
        echo "No clear startKernel file found, using largest Kernel LLVM IR file..."
        LARGEST_SIZE=0
        for ll_file in "${KERNEL_LL_FILES[@]}"; do
            size=$(stat -c%s "$ll_file" 2>/dev/null || echo "0")
            if [ "$size" -gt "$LARGEST_SIZE" ]; then
                LARGEST_SIZE=$size
                MAIN_KERNEL_LL="$ll_file"
            fi
        done
        echo "Selected file: $(basename $MAIN_KERNEL_LL) (size: $LARGEST_SIZE bytes)"
    fi
    
    # Compile main kernel file
    if [ -n "$MAIN_KERNEL_LL" ]; then
        echo "Compiling kernel object file..."
        
        # Save main kernel LLVM IR for debugging (already in text format)
        cp "$MAIN_KERNEL_LL" "obj/kernel_main.ll"
        
        # Try linking all Kernel-related .ll files together
        echo "Trying to link all Kernel-related LLVM IR files..."
        
        # Use llvm-link to merge all Kernel .ll files
        MERGED_LL="obj/kernel_merged.ll"
        if $GRAALVM_HOME/lib/llvm/bin/llvm-link "${KERNEL_LL_FILES[@]}" -S -o "$MERGED_LL" 2>/dev/null; then
            echo "✅ Successfully merged Kernel LLVM IR files"
            
            # Compile merged file
            if $CLANG "$MERGED_LL" -c -fno-omit-frame-pointer -o obj/Kernel.o; then
                echo "✅ Successfully generated obj/Kernel.o"
                
                # Show symbol info
                echo "Symbols in kernel object file:"
                nm obj/Kernel.o 2>/dev/null | grep -E "startKernel|Kernel_" | head -10
                
                echo "Build complete!"
                echo "Generated files:"
                echo "  - obj/Kernel.o (kernel object file)"
                echo "  - obj/kernel_main.ll (main Kernel LLVM IR, for debugging)"
                echo "  - obj/kernel_merged.ll (merged Kernel LLVM IR, for debugging)"
                
            else
                echo "❌ Merged file compilation failed, trying to compile main kernel file alone..."
                # Fallback to compiling main kernel file alone
                if $CLANG "$MAIN_KERNEL_LL" -c -fno-omit-frame-pointer -o obj/Kernel.o; then
                    echo "✅ Successfully compiled using main kernel file alone"
                else
                    echo "❌ Main kernel file compilation also failed"
                    exit 1
                fi
            fi
        else
            echo "⚠️  Merge failed, trying to compile main kernel file alone..."
            # Compile main kernel file
            if $CLANG "$MAIN_KERNEL_LL" -c -fno-omit-frame-pointer -o obj/Kernel.o; then
                echo "✅ Successfully generated obj/Kernel.o"
                
                # Show symbol info
                echo "Symbols in kernel object file:"
                nm obj/Kernel.o 2>/dev/null | grep -E "startKernel|Kernel_" | head -10
                
                echo "Build complete!"
                echo "Generated files:"
                echo "  - obj/Kernel.o (kernel object file)"
                echo "  - obj/kernel_main.ll (main Kernel LLVM IR, for debugging)"
                
            else
                echo "❌ Compiling main kernel file alone failed, trying other Kernel files..."
                
                # Try compiling other Kernel files
                SUCCESS=false
                for ll_file in "${KERNEL_LL_FILES[@]}"; do
                    echo "Trying to compile: $(basename $ll_file)"
                    if $CLANG "$ll_file" -c -fno-omit-frame-pointer -o obj/Kernel.o 2>/dev/null; then
                        echo "✅ Successfully compiled using $(basename $ll_file)"
                        cp "$ll_file" "obj/kernel_main.ll"
                        SUCCESS=true
                        break
                    fi
                done
                
                if [ "$SUCCESS" = false ]; then
                    echo "❌ All Kernel file compilations failed"
                    exit 1
                fi
            fi
        fi
    else
        echo "❌ No usable Kernel LLVM IR file found"
        exit 1
    fi
    
else
    echo "❌ generated-llvm directory not found"
    exit 1
fi