CUR_DIR=$(pwd)
cd ~/graal/vm
export JAVA_HOME=$(mx --dynamicimports /substratevm graalvm-home)
echo $JAVA_HOME
export PATH=$JAVA_HOME/bin:$PATH

cd $CUR_DIR
rm -rf generated-llvm
javac Kernel.java
native-image -H:CompilerBackend=llvm -H:TempDirectory=generated-llvm -H:LLVMMaxFunctionsPerBatch=1 -R:StackSize=0 -R:-InstallSegfaultHandler -H:-PreserveFramePointer Kernel
rm -f kernel Kernel.class

BC_FILE=$(grep Kernel_start ./generated-llvm/*/llvm/f[0-9].bc -l)
echo $BC_FILE
$JAVA_HOME/lib/llvm/bin/clang $BC_FILE -o obj/kernel.ll -S -emit-llvm -fno-omit-frame-pointer
$JAVA_HOME/lib/llvm/bin/clang -c obj/kernel.ll -fno-omit-frame-pointer -o obj/Kernel.o

$JAVA_HOME/lib/llvm/bin/clang -c runtime.c -o obj/runtime.o
# make BB.bin
# qemu-system-x86_64 -curses -drive file=build/BB.bin,format=raw