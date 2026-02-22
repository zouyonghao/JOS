#!/usr/bin/env python3
"""Test to run plasrv.exe on JOS"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from expect import ExpectSession

kernel = sys.argv[1] if len(sys.argv) > 1 else "build/BB.bin"

def test_plasrv():
    qemu = ExpectSession(kernel)
    
    print("Waiting for boot...")
    if not qemu.wait_for("> ", timeout=30):
        print("FAILED: Kernel didn't boot")
        qemu.close()
        sys.exit(1)
    
    print("Kernel booted! Running 'run plasrv.exe'...")
    qemu.send_keys("run plasrv.exe\n")
    
    # Wait for output
    qemu.wait_for("> ", timeout=15)
    
    output = qemu.get_output()
    print("=" * 70)
    print("OUTPUT:")
    print("=" * 70)
    print(output)
    print("=" * 70)
    
    qemu.close()

if __name__ == "__main__":
    test_plasrv()
