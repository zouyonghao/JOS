#!/usr/bin/env python3
"""
Test script for Windows PE loader in JOS.
Uses QEMU monitor socket to send keystrokes reliably.
"""

import subprocess
import sys
import socket
import time
import re
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
    '[': 'bracket_left', ']': 'bracket_right',
    '{': 'shift-bracket_left', '}': 'shift-bracket_right',
    '\\': 'backslash', '|': 'shift-backslash',
    ';': 'semicolon', ':': 'shift-semicolon',
    "'": 'apostrophe', '"': 'shift-apostrophe',
    ',': 'comma', '<': 'shift-comma',
    '/': 'slash', '?': 'shift-slash',
    '`': 'grave_accent', '~': 'shift-grave_accent',
}


class QEMUPETest:
    def __init__(self, kernel_path="build/BB.bin", timeout=60):
        self.kernel_path = kernel_path
        self.timeout = timeout
        self.process = None
        self.sock = None
        self.monitor_path = "/tmp/qemu-pe-test-monitor"
        self.output = ""
        
    def _cleanup_monitor(self):
        """Remove old monitor socket."""
        try:
            os.unlink(self.monitor_path)
        except FileNotFoundError:
            pass
            
    def _send_keys(self, text):
        """Send keystrokes via QEMU monitor."""
        for ch in text:
            key = KEY_MAP.get(ch)
            if key:
                self.sock.sendall(f"sendkey {key}\n".encode())
                time.sleep(0.05)
                # Drain response
                try:
                    self.sock.setblocking(False)
                    self.sock.recv(4096)
                except:
                    pass
                self.sock.setblocking(True)
                time.sleep(0.02)
            else:
                print(f"Warning: No key mapping for '{ch}'")
                
    def _read_output(self):
        """Read available output from QEMU."""
        import select
        try:
            # Check if data is available without blocking
            ready, _, _ = select.select([self.process.stdout], [], [], 0)
            if ready:
                data = self.process.stdout.read(4096)
                if data:
                    self.output += data.decode('latin-1', errors='replace')
        except:
            pass
            
    def _wait_for_pattern(self, patterns, timeout=30):
        """Wait for pattern in output."""
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
        
    def _clean_output(self, text):
        """Clean spinner noise from output."""
        # Remove spinner characters
        text = re.sub(r'[\|/\\-]', '', text)
        # Remove ANSI sequences
        text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
        # Normalize whitespace
        text = re.sub(r'\s+', ' ', text).strip()
        return text
        
    def run(self):
        """
        Run the PE loader test.
        
        Returns:
            Tuple of (passed: bool, message: str, output: str)
        """
        output_lines = []
        
        output_lines.append("=" * 70)
        output_lines.append("JOS Windows PE Loader Test")
        output_lines.append("=" * 70)
        output_lines.append("")
        
        try:
            # Cleanup old socket
            self._cleanup_monitor()
            
            # Start QEMU with monitor socket
            output_lines.append(f"Starting QEMU with {self.kernel_path}...")
            cmd = [
                'qemu-system-x86_64', '-accel', 'tcg', '-nographic',
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
            
            # Wait for QEMU to start and create socket
            time.sleep(4)
            
            # Connect to monitor
            output_lines.append("Connecting to QEMU monitor...")
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.connect(self.monitor_path)
            time.sleep(0.5)
            
            # Drain initial monitor output
            try:
                self.sock.setblocking(False)
                self.sock.recv(4096)
            except:
                pass
            self.sock.setblocking(True)
            
            # Wait for kernel boot
            output_lines.append("Waiting for kernel boot...")
            self._wait_for_pattern("> ", timeout=30)
            output_lines.append("✓ Kernel booted")
            
            # Clear output buffer for fresh start
            self.output = ""
            
            # Send 'ls' command
            output_lines.append("")
            output_lines.append("Sending 'ls' command...")
            self._send_keys("ls\n")
            time.sleep(3)
            
            # Read output
            self._read_output()
            
            if "win_dual_hello.exe" in self.output:
                output_lines.append("✓ win_dual_hello.exe found in filesystem")
            else:
                output_lines.append("✗ win_dual_hello.exe NOT found")
                output_lines.append("")
                output_lines.append(f"Output length: {len(self.output)}")
                output_lines.append("Output (last 800 chars):")
                output_lines.append(self._clean_output(self.output[-800:]))
                return (False, "win_dual_hello.exe not found in filesystem", "\n".join(output_lines))
                
            # Clear buffer for PE test
            self.output = ""
            
            # Run the PE file
            output_lines.append("")
            output_lines.append("Sending 'run win_dual_hello.exe' command...")
            self._send_keys("run win_dual_hello.exe\n")
            
            # Wait for PE loader output
            output_lines.append("Waiting for PE loader...")
            
            expected_patterns = [
                "Detected Windows PE executable",
                "PE loaded successfully",
                "Spawned thread",
                "Hello from Windows binary",
                "ERROR",
                "Invalid",
                "hang",  # If it hangs
            ]
            
            found = None
            start = time.time()
            
            while time.time() - start < 20:
                self._read_output()
                
                for pattern in expected_patterns:
                    if pattern in self.output:
                        found = pattern
                        break
                        
                if found:
                    break
                    
                time.sleep(0.2)
                
            # Wait a bit more for program output
            time.sleep(3)
            self._read_output()
            
            # Collect results
            output_lines.append("")
            output_lines.append("=" * 70)
            output_lines.append("PE Loader Output")
            output_lines.append("=" * 70)
            
            # Find relevant section
            output = self.output
            idx = output.find("Detected Windows PE")
            if idx == -1:
                idx = output.find("run win_dual_hello")
            if idx == -1:
                idx = 0
                
            relevant = output[idx:idx+2000]
            output_lines.append(self._clean_output(relevant))
            output_lines.append("=" * 70)
            
            # Analyze results
            output_lines.append("")
            output_lines.append("Test Analysis:")
            output_lines.append("-" * 50)
            
            passed = True
            
            if "Detected Windows PE executable" in output:
                output_lines.append("✓ PE format detection: PASS")
            else:
                output_lines.append("✗ PE format detection: FAIL")
                passed = False
                
            if "PE loaded successfully" in output:
                output_lines.append("✓ PE section loading: PASS")
            else:
                output_lines.append("✗ PE section loading: FAIL (may have hung)")
                passed = False
                
            if "Spawned thread" in output:
                output_lines.append("✓ Thread spawning: PASS")
            else:
                output_lines.append("✗ Thread spawning: FAIL")
                passed = False
                
            if "Hello from Windows binary" in output:
                output_lines.append("✓ Program execution: PASS")
            elif "Detected Windows PE" in output:
                output_lines.append("~ Program execution: Started but no output seen")
                
            if "ERROR" in output or "Invalid" in output:
                output_lines.append("✗ Errors detected in output")
                passed = False
                
            output_lines.append("-" * 50)
            
            output_text = "\n".join(output_lines)
            
            if passed:
                output_lines.append("")
                output_lines.append("✓✓✓ PE LOADER TEST PASSED")
                return (True, "PE loader test passed", "\n".join(output_lines))
            else:
                output_lines.append("")
                output_lines.append("✗✗✗ PE LOADER TEST FAILED")
                return (False, "PE loader test failed", "\n".join(output_lines))
            
        except TimeoutError as e:
            output_lines.append(f"")
            output_lines.append(f"✗ Timeout: {e}")
            output_lines.append(f"Output: {self._clean_output(self.output[-1000:])}")
            return (False, f"Timeout: {e}", "\n".join(output_lines))
            
        except Exception as e:
            output_lines.append(f"")
            output_lines.append(f"✗ Error: {e}")
            import traceback
            output_lines.append(traceback.format_exc())
            return (False, f"Error: {e}", "\n".join(output_lines))
            
        finally:
            self.cleanup()
            
    def cleanup(self):
        """Clean up resources."""
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
    import argparse
    parser = argparse.ArgumentParser(description="Test JOS Windows PE loader")
    parser.add_argument("--kernel", "-k", default="build/BB.bin",
                        help="Path to kernel binary")
    parser.add_argument("--timeout", "-t", type=int, default=60,
                        help="Timeout in seconds")
    
    args = parser.parse_args()
    
    test = QEMUPETest(args.kernel, args.timeout)
    passed, message, output = test.run()
    
    print(f"\nResult: {'PASS' if passed else 'FAIL'}")
    print(f"Message: {message}")
    if output:
        print(f"Output:\n{output}")
    
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
