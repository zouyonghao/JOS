cd ~/graal/vm
export JAVA_HOME=$(mx --dynamicimports /substratevm graalvm-home)
echo $JAVA_HOME
export PATH=$JAVA_HOME/bin:$PATH
cd -