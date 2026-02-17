#!/usr/bin/env python3
"""
Memory test for JOS kernel.
Tests virtual memory functionality by running the 'vmtest' command.
"""

import sys
import os

# Add test directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from expect import ExpectSession
import subprocess
import time


class MemoryTest:
    """Test virtual memory functionality."""
    
    def __init__(self, kernel_path="build/BB.bin", timeout=30):
        """
        Initialize memory test.
        
        Args:
            kernel_path: Path to the kernel binary
            timeout: Maximum time to wait for test (seconds)
        """
        self.kernel_path = kernel_path
        self.timeout = timeout
        self.session = None
        self.process = None
        
    def _start_qemu(self):
        """Start QEMU with the kernel."""
        # Note: -nographic implies -serial stdio, so we don't need both
        cmd = [
            "qemu-system-x86_64",
            "-nographic",
            "-device", "isa-debug-exit,iobase=0xf4,iosize=0x04",
            "-no-reboot",
            "-drive", f"format=raw,file={self.kernel_path}"
        ]
        
        self.process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT
        )
        
        self.session = ExpectSession(self.process, timeout=self.timeout)
        
    def run(self):
        """
        Run the memory test.
        
        Returns:
            Tuple of (passed: bool, message: str, output: str)
        """
        output_lines = []
        
        try:
            self._start_qemu()
            
            # Wait for kernel boot message
            try:
                idx, matched = self.session.expect("JavaOS Kernel", timeout=10)
                output_lines.append(f"Boot message: {matched}")
            except TimeoutError:
                return (False, "Timeout waiting for 'JavaOS Kernel' boot message",
                        self.session.get_buffer())
                        
            # Wait for prompt
            try:
                idx, matched = self.session.expect("> ", timeout=15)
                output_lines.append(f"Prompt ready")
            except TimeoutError:
                return (False, "Timeout waiting for command prompt",
                        self.session.get_buffer())
                
            # Send vmtest command
            self.session.sendline("vmtest")
            output_lines.append("Sent: vmtest")
            
            # Wait for PASS or FAIL message
            try:
                # The kernel runs vmtest automatically at boot, but we run it again
                # Look for the specific pass message
                idx, matched = self.session.expect(
                    ["PASS: Virtual memory works!", "FAIL:"],
                    timeout=10
                )
                
                if idx == 0:
                    output_lines.append(f"Result: {matched}")
                    # Wait for prompt to return
                    try:
                        self.session.expect("> ", timeout=5)
                        output_lines.append("Command completed, prompt returned")
                    except TimeoutError:
                        pass  # Prompt might not show if we're at end of output
                    return (True, "Virtual memory test passed", "\n".join(output_lines))
                else:
                    # Found FAIL
                    # Try to get more context
                    fail_context = self.session.drain(timeout=0.5)
                    output_lines.append(f"Result: FAIL")
                    output_lines.append(f"Context: {fail_context[:200]}")
                    return (False, f"Virtual memory test failed: {matched}",
                            "\n".join(output_lines))
                            
            except TimeoutError:
                # Check if we got any output about memory
                buf = self.session.get_buffer()
                if "Virtual memory" in buf or "VM Test" in buf:
                    output_lines.append(f"Partial output: {buf[-500:]}")
                    if "PASS" in buf:
                        return (True, "Virtual memory test passed (found in buffer)",
                                "\n".join(output_lines))
                    elif "FAIL" in buf:
                        return (False, "Virtual memory test failed (found in buffer)",
                                "\n".join(output_lines))
                return (False, "Timeout waiting for vmtest result",
                        self.session.get_buffer())
                        
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
    Convenience function to run the memory test.
    
    Args:
        kernel_path: Path to the kernel binary
        timeout: Maximum time to wait for test
        
    Returns:
        Tuple of (passed: bool, message: str, output: str)
    """
    test = MemoryTest(kernel_path, timeout)
    result = test.run()
    test.cleanup()
    return result


if __name__ == "__main__":
    # Allow running standalone
    import argparse
    
    parser = argparse.ArgumentParser(description="JOS Memory Test")
    parser.add_argument("--kernel", "-k", default="build/BB.bin",
                        help="Path to kernel binary")
    parser.add_argument("--timeout", "-t", type=int, default=30,
                        help="Timeout in seconds")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Verbose output")
    
    args = parser.parse_args()
    
    print(f"Running memory test with kernel: {args.kernel}")
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
            print(f"Captured output:\n{output[:1000]}")
        sys.exit(1)
