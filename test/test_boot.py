#!/usr/bin/env python3
"""
Boot verification test for JOS kernel.
Tests that the kernel boots successfully and shows the command prompt.
"""

import sys
import os

# Add test directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from expect import ExpectSession
import subprocess
import time


class BootTest:
    """Test kernel boot and basic functionality."""
    
    def __init__(self, kernel_path="build/BB.bin", timeout=30):
        """
        Initialize boot test.
        
        Args:
            kernel_path: Path to the kernel binary
            timeout: Maximum time to wait for boot (seconds)
        """
        self.kernel_path = kernel_path
        self.timeout = timeout
        self.session = None
        self.process = None
        
    def _cleanup_monitor(self):
        """Remove old monitor socket."""
        import os
        try:
            os.unlink("/tmp/qemu-test-monitor")
        except FileNotFoundError:
            pass
        
    def _start_qemu(self):
        """Start QEMU with the kernel."""
        self._cleanup_monitor()
        
        cmd = [
            "qemu-system-x86_64",
            "-accel", "tcg",
            "-nographic",
            "-device", "isa-debug-exit,iobase=0xf4,iosize=0x04",
            "-no-reboot",
            "-drive", f"format=raw,file={self.kernel_path}",
            "-monitor", "unix:/tmp/qemu-test-monitor,server,nowait"
        ]
        
        self.process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0
        )
        
        self.session = ExpectSession(self.process, timeout=self.timeout)
        # Boot test doesn't need monitor, but connect for consistency
        time.sleep(4)
        try:
            self.session.connect_monitor()
        except:
            pass  # Boot test works without monitor
        
    def run(self):
        """
        Run the boot test.
        
        Returns:
            Tuple of (passed: bool, message: str, output: str)
        """
        output_lines = []
        
        try:
            self._start_qemu()
            
            # Wait for kernel boot message
            try:
                idx, matched = self.session.expect("JOS Kernel", timeout=10)
                output_lines.append(f"Found: {matched}")
            except TimeoutError:
                return (False, "Timeout waiting for 'JOS Kernel' boot message", 
                        self.session.get_buffer())
                        
            # Wait for prompt
            try:
                idx, matched = self.session.expect("> ", timeout=self.timeout - 10)
                output_lines.append(f"Found prompt: {matched}")
            except TimeoutError:
                return (False, "Timeout waiting for command prompt", 
                        self.session.get_buffer())
                
            # Give a moment for any remaining boot output
            time.sleep(0.5)
            remaining = self.session.drain(timeout=0.5)
            if remaining:
                output_lines.append(f"Additional output: {repr(remaining[:200])}")
                
            return (True, "Kernel booted successfully", "\n".join(output_lines))
            
        except RuntimeError as e:
            return (False, f"QEMU exited unexpectedly: {e}", 
                    self.session.get_buffer() if self.session else "")
        except Exception as e:
            return (False, f"Error during test: {e}", 
                    self.session.get_buffer() if self.session else "")
        finally:
            if self.session:
                self.session.close()
                
    def cleanup(self):
        """Clean up resources."""
        if self.session:
            self.session.close()
            self.session = None


def run_test(kernel_path="build/BB.bin", timeout=30):
    """
    Convenience function to run the boot test.
    
    Args:
        kernel_path: Path to the kernel binary
        timeout: Maximum time to wait for boot
        
    Returns:
        Tuple of (passed: bool, message: str)
    """
    test = BootTest(kernel_path, timeout)
    passed, message, output = test.run()
    test.cleanup()
    return passed, message, output


if __name__ == "__main__":
    # Allow running standalone
    import argparse
    
    parser = argparse.ArgumentParser(description="JOS Boot Test")
    parser.add_argument("--kernel", "-k", default="build/BB.bin",
                        help="Path to kernel binary")
    parser.add_argument("--timeout", "-t", type=int, default=30,
                        help="Timeout in seconds")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Verbose output")
    
    args = parser.parse_args()
    
    print(f"Running boot test with kernel: {args.kernel}")
    passed, message, output = run_test(args.kernel, args.timeout)
    
    if args.verbose:
        print("\n--- Output ---")
        print(output)
        print("--- End Output ---\n")
        
    if passed:
        print(f"PASS: {message}")
        sys.exit(0)
    else:
        print(f"FAIL: {message}")
        if output:
            print(f"Captured output:\n{output[:500]}")
        sys.exit(1)
