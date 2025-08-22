sudo apt install build-essential cmake zlib1g-dev ninja-build libstdc++-14-dev qemu-system-x86
wget https://github.com/graalvm/mx/archive/refs/tags/7.60.2.tar.gz
tar zxf 7.60.2.tar.gz
mv mx-7.60.2/ mx
rm 7.60.2.tar.gz

wget https://github.com/graalvm/labs-openjdk/releases/download/26%2B11-jvmci-b01/labsjdk-ce-26+11-jvmci-b01-linux-amd64.tar.gz
tar zxvf labsjdk-ce-26+11-jvmci-b01-linux-amd64.tar.gz
mv labsjdk-ce-26-jvmci-b01 labs-jdk
rm labsjdk-ce-26+11-jvmci-b01-linux-amd64.tar.gz
