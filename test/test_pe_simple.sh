#!/bin/bash
set -e

echo "=========================================="
echo "JOS PE Loader Test"
echo "=========================================="
echo ""

# Kill any existing QEMU
pkill -9 qemu-system-x86_64 2>/dev/null || true
sleep 2

# Run QEMU with output capture
echo "Starting QEMU..."
timeout 60 qemu-system-x86_64 \
    -nographic \
    -device isa-debug-exit,iobase=0xf4,iosize=0x04 \
    -no-reboot \
    -drive format=raw,file=build/BB.bin \
    | tee /tmp/qemu_pe_test.log &

QEMU_PID=$!
echo "QEMU PID: $QEMU_PID"

# Wait for boot
echo "Waiting for boot (15s)..."
sleep 15

# Check if still running
if ! kill -0 $QEMU_PID 2>/dev/null; then
    echo "✗ QEMU exited prematurely"
    exit 1
fi

echo "✓ QEMU is running"

# Send commands using xdotool if available, otherwise manual instruction
if command -v xdotool &> /dev/null; then
    echo "Sending 'ls' command via xdotool..."
    xdotool search --name " QEMU" type "ls" 2>/dev/null || echo "Could not find QEMU window"
    sleep 1
    xdotool search --name " QEMU" key Return 2>/dev/null || true
    sleep 3
    
    echo "Sending 'run win_hello.exe'..."
    xdotool search --name " QEMU" type "run win_hello.exe" 2>/dev/null || true
    sleep 1
    xdotool search --name " QEMU" key Return 2>/dev/null || true
    sleep 10
    
    # Kill QEMU
    kill $QEMU_PID 2>/dev/null || true
    sleep 1
    kill -9 $QEMU_PID 2>/dev/null || true
else
    echo ""
    echo "=========================================="
    echo "Manual test required:"
    echo "1. Type: ls"
    echo "2. Press Enter"
    echo "3. Type: run win_hello.exe"
    echo "4. Press Enter"
    echo "5. Observe output"
    echo "=========================================="
    echo ""
    echo "Waiting 60 seconds for manual test..."
    sleep 60
    kill $QEMU_PID 2>/dev/null || true
fi

# Analyze output
echo ""
echo "=========================================="
echo "Test Results Analysis"
echo "=========================================="

if [ -f /tmp/qemu_pe_test.log ]; then
    OUTPUT=$(cat /tmp/qemu_pe_test.log)
    
    if echo "$OUTPUT" | grep -q "win_hello.exe"; then
        echo "✓ win_hello.exe found in filesystem"
    else
        echo "✗ win_hello.exe NOT found"
    fi
    
    if echo "$OUTPUT" | grep -q "Detected Windows PE executable"; then
        echo "✓ PE format detection works"
    else
        echo "✗ PE format detection failed"
    fi
    
    if echo "$OUTPUT" | grep -q "PE loaded successfully"; then
        echo "✓ PE loaded successfully"
    else
        echo "✗ PE loading failed"
    fi
    
    if echo "$OUTPUT" | grep -q "Spawned thread"; then
        echo "✓ Thread spawned"
    else
        echo "✗ Thread spawning failed"
    fi
    
    if echo "$OUTPUT" | grep -q "Hello from Windows PE"; then
        echo "✓✓✓ Program executed successfully!"
        echo ""
        echo "PE LOADER TEST PASSED!"
        exit 0
    else
        echo "? Program output not detected"
    fi
    
    echo ""
    echo "Last 1000 chars of output:"
    echo "$OUTPUT" | tail -c 1000
else
    echo "✗ No output log found"
fi

exit 1
