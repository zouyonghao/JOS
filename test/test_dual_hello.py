#!/usr/bin/env python3
"""
Test script for Windows PE with kernel32.dll imports (win_dual_hello.exe).
Tests that the binary runs on both Windows and JOS.

This is the primary Windows PE test for JOS - it verifies:
1. PE format detection
2. Import table parsing
3. kernel32.dll function emulation (GetStdHandle, WriteFile, ExitProcess)
4. Program execution and output
"""

import subprocess
import sys
import socket
import time
import os

# Key mapping for QEMU sendkey command
KEY_MAP = {
    'a': 'a', 'b': 'b', 'c': 'c', 'd': 'd', 'e': 'e',
    'f': 'f', 'g': 'g', 'h': 'h', 'i': 'i', 'j': 'j',
    'k': 'k', 'l': 'l', 'm': 'm', 'n': 'n', 'o': 'o',
    'p': 'p', 'q': 'q', 'r': 'r', 's': 's', 't': 't',
    'u': 'u', 'v': 'v', 'w': 'w', 'x': 'x', 'y': 'y', 'z': 'z',
    '0': '0', '1': '1', '2': '2', '3': '3', '4': '4',
    '5': '5', '6': '6', '7': '7', '8': '8', '9': '9',
    ' ': 'spc', '.': 'dot', '\n': 'ret', '-': 'minus',
    '_': 'shift-minus', '=': 'equal', '+': 'shift-equal',
}


class QEMUTest:
    def __init__(self, kernel_path="build/BB.bin", timeout=60):
        self.kernel_path = kernel_path
        self.timeout = timeout
        self.process = None
        self.sock = None
        self.monitor_path = "/tmp/qemu-dual-test-monitor"
        self.output = ""
        
    def _cleanup_monitor(self):
        try:
            os.unlink(self.monitor_path)
        except FileNotFoundError:
            pass
            
    def _send_keys(self, text):
        for ch in text:
            key = KEY_MAP.get(ch)
            if key:
                self.sock.sendall(f"sendkey {key}\n".encode())
                time.sleep(0.05)
                try:
                    self.sock.setblocking(False)
                    self.sock.recv(4096)
                except:
                    pass
                self.sock.setblocking(True)
                time.sleep(0.02)
                
    def _read_output(self):
        import select
        try:
            ready, _, _ = select.select([self.process.stdout], [], [], 0)
            if ready:
                data = self.process.stdout.read(4096)
                if data:
                    self.output += data.decode('latin-1', errors='replace')
        except:
            pass
            
    def _wait_for_pattern(self, patterns, timeout=30):
        if isinstance(patterns, str):
            patterns = [patterns]
        start = time.time()
        while time.time() - start < timeout:
            self._read_output()
            for pattern in patterns:
                if pattern in self.output:
                    return pattern
            if self.process.poll() is not None:
                raise RuntimeError(f"QEMU exited with code {self.process.returncode}")
            time.sleep(0.1)
        raise TimeoutError(f"Timeout waiting for: {patterns}")

    def run(self):
        print("=" * 70)
        print("JOS Windows PE Dual-Mode Test")
        print("Testing win_dual_hello.exe (kernel32.dll imports)")
        print("=" * 70)
        
        try:
            self._cleanup_monitor()
            
            print(f"Starting QEMU with {self.kernel_path}...")
            cmd = [
                'qemu-system-x86_64', '-nographic',
                '-device', 'isa-debug-exit,iobase=0xf4,iosize=0x04',
                '-no-reboot',
                '-drive', f'format=raw,file={self.kernel_path}',
                '-monitor', f'unix:{self.monitor_path},server,nowait'
            ]
            
            self.process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0
            )
            
            time.sleep(4)
            
            print("Connecting to QEMU monitor...")
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.connect(self.monitor_path)
            time.sleep(0.5)
            
            try:
                self.sock.setblocking(False)
                self.sock.recv(4096)
            except:
                pass
            self.sock.setblocking(True)
            
            print("Waiting for kernel boot...")
            self._wait_for_pattern("> ", timeout=30)
            print("✓ Kernel booted")
            
            self.output = ""
            
            # List files
            print("\nSending 'ls' command...")
            self._send_keys("ls\n")
            time.sleep(2)
            self._read_output()
            
            if "win_dual_hello.exe" in self.output:
                print("✓ win_dual_hello.exe found in filesystem")
            else:
                print("✗ win_dual_hello.exe NOT found")
                return False
            
            # Run the binary
            print("\nRunning win_dual_hello.exe...")
            self.output = ""
            self._send_keys("run win_dual_hello.exe\n")
            
            # Wait for output
            time.sleep(5)
            self._read_output()
            
            print("\nOutput received:")
            print("-" * 40)
            print(self.output)
            print("-" * 40)
            
            # Check for expected output
            passed = True
            messages = []
            
            if "Detected Windows PE executable" in self.output:
                messages.append("✓ PE format detected")
            else:
                messages.append("✗ PE format not detected")
                passed = False
                
            if "Processing import table" in self.output:
                messages.append("✓ Import table processing")
            else:
                messages.append("✗ Import table not processed")
                passed = False
                
            if "kernel32.dll" in self.output or "kernel32" in self.output.lower():
                messages.append("✓ kernel32.dll recognized")
            else:
                messages.append("✗ kernel32.dll not recognized")
                passed = False
                
            if "GetStdHandle" in self.output:
                messages.append("✓ GetStdHandle resolved")
            else:
                messages.append("~ GetStdHandle not shown (may still work)")
                
            if "WriteFile" in self.output:
                messages.append("✓ WriteFile resolved")
            else:
                messages.append("~ WriteFile not shown (may still work)")
                
            if "Hello from Windows binary" in self.output:
                messages.append("✓ Program output correct")
            else:
                messages.append("✗ Expected output not found")
                passed = False
                
            if "runs on both Windows and JOS" in self.output:
                messages.append("✓ Full message received")
            else:
                messages.append("~ Partial message")
            
            print("\nTest Results:")
            for msg in messages:
                print(f"  {msg}")
            
            if passed:
                print("\n✓✓✓ TEST PASSED")
            else:
                print("\n✗✗✗ TEST FAILED")
            
            return passed
            
        except Exception as e:
            print(f"Error: {e}")
            import traceback
            traceback.print_exc()
            return False
            
        finally:
            self.cleanup()
            
    def cleanup(self):
        if self.sock:
            try:
                self.sock.sendall(b"quit\n")
                self.sock.close()
            except:
                pass
            self.sock = None
            
        if self.process:
            try:
                if self.process.poll() is None:
                    self.process.terminate()
                    time.sleep(1)
                if self.process.poll() is None:
                    self.process.kill()
                    self.process.wait()
            except:
                pass
            self.process = None
            
        self._cleanup_monitor()


def main():
    test = QEMUTest()
    passed = test.run()
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
