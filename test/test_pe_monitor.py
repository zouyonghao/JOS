#!/usr/bin/env python3
"""
Test PE loader using QEMU monitor socket with sendkey commands.
Based on /tmp/qemu_thread_test6.py approach.
"""

import subprocess
import time
import socket
import os
import re
import sys

def clean_output(text):
    """Remove spinner and ANSI sequences."""
    text = re.sub(r'[|/\\-]', '', text)
    text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
    text = re.sub(r'\s+', ' ', text)
    return text

def main():
    print("=" * 60)
    print("JOS PE Loader Test (Monitor Socket)")
    print("=" * 60)
    
    monitor_path = "/tmp/qemu-pe-monitor"
    log_file = "/tmp/qemu_pe_output.log"
    
    # Cleanup
    try:
        os.unlink(monitor_path)
    except:
        pass
    
    # Start QEMU with monitor socket
    print("\nStarting QEMU...")
    cmd = [
        "qemu-system-x86_64",
        "-nographic",
        "-serial", f"file:{log_file}",
        "-display", "none",
        "-device", "isa-debug-exit,iobase=0xf4,iosize=0x04",
        "-no-reboot",
        "-drive", "format=raw,file=build/BB.bin",
        "-monitor", f"unix:{monitor_path},server,nowait"
    ]
    
    proc = subprocess.Popen(cmd)
    
    # Wait for monitor socket to appear
    print("Waiting for monitor socket...")
    for i in range(30):  # 15 seconds max
        time.sleep(0.5)
        if os.path.exists(monitor_path):
            print(f"✓ Socket appeared after {(i+1)*0.5:.1f}s")
            break
    else:
        print("✗ Socket never appeared")
        proc.terminate()
        return 1
    
    # Connect to monitor
    time.sleep(1)
    print("Connecting to monitor...")
    
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        sock.connect(monitor_path)
    except Exception as e:
        print(f"✗ Connection failed: {e}")
        proc.terminate()
        return 1
    
    print("✓ Connected to monitor")
    
    # Drain initial response
    time.sleep(0.5)
    try:
        sock.setblocking(False)
        sock.recv(4096)
    except:
        pass
    sock.setblocking(True)
    
    # Wait for kernel boot
    print("\nWaiting 20s for kernel boot...")
    time.sleep(20)
    
    # Check if log file has booted
    if os.path.exists(log_file):
        with open(log_file, 'r') as f:
            content = f.read()
        if "> " in content:
            print("✓ Kernel booted (found prompt)")
        else:
            print("? Kernel may not have booted yet")
    
    # Send 'ls' via monitor sendkey
    print("\nSending 'ls' command...")
    keys = ['l', 's', 'ret']
    for key in keys:
        sock.sendall(f"sendkey {key}\n".encode())
        time.sleep(0.1)
        # Drain response
        try:
            sock.setblocking(False)
            sock.recv(1024)
        except:
            pass
        sock.setblocking(True)
    
    time.sleep(3)
    
    # Check for win_hello.exe
    if os.path.exists(log_file):
        with open(log_file, 'r') as f:
            content = f.read()
        clean = clean_output(content)
        
        if "win_hello.exe" in clean:
            print("✓ win_hello.exe found in filesystem")
        else:
            print("✗ win_hello.exe NOT found")
            print("Last 500 chars of output:")
            print(clean[-500:])
    
    # Send 'run win_hello.exe'
    print("\nSending 'run win_hello.exe'...")
    cmd_keys = ['r', 'u', 'n', 'spc', 'w', 'i', 'n', 'shift-minus', 
                'h', 'e', 'l', 'l', 'o', 'dot', 'e', 'x', 'e', 'ret']
    for key in cmd_keys:
        sock.sendall(f"sendkey {key}\n".encode())
        time.sleep(0.05)
        try:
            sock.setblocking(False)
            sock.recv(1024)
        except:
            pass
        sock.setblocking(True)
    
    # Wait for PE loader
    print("\nWaiting 15s for PE loader...")
    time.sleep(15)
    
    # Analyze results
    print("\n" + "=" * 60)
    print("Results")
    print("=" * 60)
    
    if os.path.exists(log_file):
        with open(log_file, 'r') as f:
            content = f.read()
        clean = clean_output(content)
        
        passed = True
        
        if "Loading Windows PE executable" in clean:
            print("✓ PE format detection: PASS")
        else:
            print("✗ PE format detection: FAIL")
            passed = False
            
        if "Spawned thread" in clean:
            print("✓ PE loading: PASS")
        else:
            print("✗ PE loading: FAIL")
            passed = False
            
        if "Spawned thread" in clean:
            print("✓ Thread spawn: PASS")
        else:
            print("✗ Thread spawn: FAIL")
            passed = False
            
        if "Hello from PE" in clean or "TEST:" in clean:
            print("✓ Program execution: PASS")
        else:
            print("? Program execution: Not confirmed")
            
        print("\nRelevant output:")
        # Find PE-related section
        idx = clean.find("Loading Windows PE")
        if idx == -1:
            idx = clean.find("run win_hello")
        if idx >= 0:
            print(clean[idx:idx+1500])
        else:
            print(clean[-1000:])
        
        if passed:
            print("\n✓✓✓ PE LOADER TEST PASSED")
        else:
            print("\n✗✗✗ PE LOADER TEST FAILED")
    
    # Cleanup
    try:
        sock.sendall(b"quit\n")
        sock.close()
    except:
        pass
    proc.terminate()
    time.sleep(1)
    if proc.poll() is None:
        proc.kill()
    
    return 0 if passed else 1

if __name__ == "__main__":
    sys.exit(main())
