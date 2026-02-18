#!/bin/bash

echo "========================================"
echo "JOS PE Loader Test - Output Capture"
echo "========================================"

# Kill any existing QEMU
pkill -9 qemu-system-x86_64 2>/dev/null
sleep 2

LOGFILE="/tmp/qemu_pe_test.log"

# Run QEMU and capture output
echo "Starting QEMU (60 second test)..."
timeout 60 qemu-system-x86_64 \
    -nographic \
    -device isa-debug-exit,iobase=0xf4,iosize=0x04 \
    -no-reboot \
    -drive format=raw,file=build/BB.bin > "$LOGFILE" 2>&1 &

QEMU_PID=$!
echo "QEMU PID: $QEMU_PID"

# Wait for boot
echo "Waiting 20s for boot..."
sleep 20

# Send commands via expect
expect << 'EXPECT_SCRIPT'
set timeout 5
spawn sh -c "cat > /tmp/qemu_stdin"
sleep 1
send "ls\r"
sleep 3
send "run win_hello.exe\r"
sleep 15
EXPECT_SCRIPT

# Alternative: use echo to send to QEMU's stdin
echo -e "ls\n" > /proc/$QEMU_PID/fd/0 2>/dev/null || true
sleep 3
echo -e "run win_hello.exe\n" > /proc/$QEMU_PID/fd/0 2>/dev/null || true
sleep 15

# Kill QEMU
kill $QEMU_PID 2>/dev/null || true
sleep 1
kill -9 $QEMU_PID 2>/dev/null || true

# Analyze output
echo ""
echo "========================================"
echo "Analyzing output..."
echo "========================================"

if [ ! -f "$LOGFILE" ]; then
    echo "✗ No log file created"
    exit 1
fi

# Clean the output (remove spinner)
CLEAN=$(cat "$LOGFILE" | tr -d '|/-\\' | sed 's/\x1b\[[0-9;?]*[a-zA-Z]//g')

# Check for key markers
if echo "$CLEAN" | grep -q "win_hello.exe"; then
    echo "✓ win_hello.exe found"
else
    echo "✗ win_hello.exe not found"
fi

if echo "$CLEAN" | grep -q "Detected Windows PE executable"; then
    echo "✓ PE detection works"
else
    echo "✗ PE detection not seen"
fi

if echo "$CLEAN" | grep -q "PE loaded successfully"; then
    echo "✓ PE loading works"
else
    echo "✗ PE loading not seen"
fi

if echo "$CLEAN" | grep -q "Spawned thread"; then
    echo "✓ Thread spawn works"
else
    echo "✗ Thread spawn not seen"
fi

if echo "$CLEAN" | grep -q "Hello from Windows PE"; then
    echo "✓✓✓ Program execution works!"
    echo ""
    echo "TEST PASSED!"
    exit 0
else
    echo "? Program output not detected"
fi

echo ""
echo "Last 1500 chars of cleaned output:"
echo "$CLEAN" | tail -c 1500

exit 1
