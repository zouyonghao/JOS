CUR_DIR=$(pwd)
export PATH=$CUR_DIR/mx:$PATH

# Use Labs OpenJDK for building (NOT GraalVM JDK)
export JAVA_HOME=$CUR_DIR/labs-jdk
export PATH=$JAVA_HOME/bin:$PATH

echo "Building GraalVM with LLVM backend..."
echo "JAVA_HOME: $JAVA_HOME"
echo "Java version: $(java -version 2>&1 | head -1)"

cd graal/vm
export GRAALVM_HOME=$(mx --dynamicimports /sulong,/substratevm --exclude-components=nju,nil graalvm-home)
echo "GraalVM will be at: $GRAALVM_HOME"
cd -