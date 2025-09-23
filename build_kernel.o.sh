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

echo "Looking for startKernel function in generated LLVM files..."

if [ -d "generated-llvm" ]; then
	CLANG=$GRAALVM_HOME/lib/llvm/bin/clang

	# Find the startKernel LLVM file - this is our main entry point
	# writeMemory is a native method, so it's provided by runtime.c, not LLVM
	STARTKERNEL_LL=$(find generated-llvm -name "*startKernel*" -name "*.ll" | head -1)

	if [ -n "$STARTKERNEL_LL" ]; then
		echo "Found startKernel file: $(basename $STARTKERNEL_LL)"
		echo "Note: writeMemory is a native method provided by runtime.c"

		# Simply compile the startKernel file - writeMemory will be linked from runtime.c
		echo "Compiling startKernel LLVM IR to object file..."
		if $CLANG "$STARTKERNEL_LL" -c -fno-omit-frame-pointer -o obj/Kernel.o; then
			echo "✅ Successfully generated obj/Kernel.o from startKernel"

			# Show what we got
			echo "Generated obj/Kernel.o contains:"
			nm obj/Kernel.o 2>/dev/null | grep -E "startKernel|writeMemory" || echo "No matching symbols found"

			echo "Build complete! writeMemory will be resolved from runtime.c during linking."
		else
			echo "❌ Failed to compile startKernel LLVM IR"
			exit 1
		fi
	else
		echo "❌ No startKernel LLVM IR file found"
		echo "Available LLVM files:"
		find generated-llvm -name "*.ll" | head -5
		exit 1
	fi

else
	echo "❌ generated-llvm directory not found"
	exit 1
fi
