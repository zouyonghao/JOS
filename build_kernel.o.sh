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
	-H:LLVMPreserveFunctionsRegex=Kernel_.* \
	-H:+ExitAfterCompilation \
	-H:Name=java_kernel \
	--no-fallback \
	Kernel

# Clean up generated files
rm -f kernel Kernel.class java_kernel
mkdir -p obj

echo "Looking for ALL Kernel LLVM files in generated-llvm..."

if [ -d "generated-llvm" ]; then
	CLANG=$GRAALVM_HOME/lib/llvm/bin/clang

	# Find ALL LLVM files that contain Kernel functions
	KERNEL_LL_FILES=$(find generated-llvm -name "Kernel_*.ll" | sort)

	if [ -n "$KERNEL_LL_FILES" ]; then
		echo "Found Kernel LLVM files:"
		echo "$KERNEL_LL_FILES" | while read file; do echo "  - $(basename $file)"; done

		echo "Linking ALL Kernel LLVM files into single IR file..."
		LLVM_LINK=$GRAALVM_HOME/lib/llvm/bin/llvm-link

		# First link all LLVM IR files into one
		if $LLVM_LINK $KERNEL_LL_FILES -o generated-llvm/kernel_combined.ll; then
			echo "✅ Successfully linked all LLVM IR files"
			echo "Compiling combined LLVM IR to object file..."

			llvm-dis generated-llvm/kernel_combined.ll -o generated-llvm/kernel_combined.ll.txt

			# Now compile the combined IR file
			if $CLANG generated-llvm/kernel_combined.ll -c -fno-omit-frame-pointer -o obj/Kernel.o; then
				echo "✅ Successfully generated obj/Kernel.o from all Kernel functions"

				# Show what we got
				echo "Generated obj/Kernel.o contains these symbols:"
				nm obj/Kernel.o 2>/dev/null | grep -E "^[0-9a-f]+ [TtWw] " | head -10

				echo "Build complete! Native methods will be resolved from runtime.c during linking."
			else
				echo "❌ Failed to compile combined LLVM IR"
				exit 1
			fi
		else
			echo "❌ Failed to link LLVM IR files"
			exit 1
		fi
	else
		echo "❌ No Kernel_*.ll LLVM IR files found"
		echo "Available LLVM files:"
		find generated-llvm -name "*.ll" | head -5
		exit 1
	fi

else
	echo "❌ generated-llvm directory not found"
	exit 1
fi
