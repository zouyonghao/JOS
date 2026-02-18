#!/bin/bash
# Test QEMU and capture output to file

cd /home/zyh/JOS

# Build everything
make clean && make disk

# Run QEMU with serial output to file
timeout 10 qemu-system-x86_64 \
    -nographic \
    -serial file:qemu_output.txt \
    -device isa-debug-exit,iobase=0xf4,iosize=0x04 \
    -no-reboot \
    -drive format=raw,file=build/BB.bin

# Show the output
echo "=== QEMU Output ==="
cat qemu_output.txt

# Check for success
if grep -q "Filesystem initialized" qemu_output.txt; then
    echo ""
    echo "✓ SUCCESS: Filesystem initialized!"
    exit 0
else
    echo ""
    echo "✗ Filesystem not initialized"
    exit 1
fi
