#!/usr/bin/env python3
"""
Command test for JOS kernel.
Tests that the kernel is ready to accept commands (prompt appears).

NOTE: The kernel uses PS/2 keyboard for input, not serial. Commands cannot
be sent via the serial console. This test verifies the kernel reaches the
command-ready state with the prompt displayed.
"""

import sys
import os

# Add test directory to path for imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from expect import ExpectSession
import subprocess
import time


class CommandTest:
    """Test kernel command prompt readiness."""
    
    def __init__(self, kernel_path="build/BB.bin", timeout=30):
        """
        Initialize command test.
        
        Args:
            kernel_path: Path to the kernel binary
            timeout: Maximum time to wait for test (seconds)
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
        # Wait for QEMU to start and create monitor socket
        time.sleep(4)
        self.session.connect_monitor()
        
    def run(self):
        """
        Run the command test.
        
        Returns:
            Tuple of (passed: bool, message: str, output: str)
        """
        output_lines = []
        
        try:
            self._start_qemu()
            
            # Wait for kernel boot
            try:
                idx, matched = self.session.expect("JOS Kernel", timeout=15)
                output_lines.append(f"Boot: {matched}")
            except TimeoutError:
                return (False, "Timeout waiting for kernel boot",
                        self.session.get_buffer())
            
            # Check for help message (shown at boot)
            try:
                idx, matched = self.session.expect(
                    "Type 'help' for available commands",
                    timeout=5
                )
                output_lines.append(f"Help message: {matched}")
            except TimeoutError:
                output_lines.append("Help message not found (may be OK)")
                        
            # Wait for prompt
            try:
                self.session.expect("> ", timeout=10)
                output_lines.append("Command prompt ready")
            except TimeoutError:
                return (False, "Timeout waiting for command prompt",
                        self.session.get_buffer())
                
            return (True, "Kernel ready for commands", "\n".join(output_lines))
            
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
    Convenience function to run the command test.
    
    Args:
        kernel_path: Path to the kernel binary
        timeout: Maximum time to wait for test
        
    Returns:
        Tuple of (passed: bool, message: str, output: str)
    """
    test = CommandTest(kernel_path, timeout)
    result = test.run()
    test.cleanup()
    return result


if __name__ == "__main__":
    # Allow running standalone
    import argparse
    
    parser = argparse.ArgumentParser(
        description="JOS Command Prompt Test",
        epilog="""
NOTE: This test only verifies the kernel shows the command prompt.
The kernel uses PS/2 keyboard input, not serial, so commands cannot
be sent via the serial console in automated testing.
        """
    )
    parser.add_argument("--kernel", "-k", default="build/BB.bin",
                        help="Path to kernel binary")
    parser.add_argument("--timeout", "-t", type=int, default=30,
                        help="Timeout in seconds")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Verbose output")
    
    args = parser.parse_args()
    
    print(f"Running command test with kernel: {args.kernel}")
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
