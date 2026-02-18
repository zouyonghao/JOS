#!/usr/bin/env python3
"""
Main test runner for JOS kernel.
Runs all tests and reports results.
"""

import sys
import os
import argparse
import subprocess
import time

# Ensure we can import from the test directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_boot import BootTest
from test_memory import MemoryTest
from test_command import CommandTest
from test_pe_loader import QEMUPETest


class Colors:
    """ANSI color codes for terminal output."""
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    BOLD = '\033[1m'
    END = '\033[0m'
    
    @classmethod
    def disable(cls):
        """Disable colors (for non-terminal output)."""
        cls.GREEN = ''
        cls.RED = ''
        cls.YELLOW = ''
        cls.BLUE = ''
        cls.BOLD = ''
        cls.END = ''


class TestResult:
    """Result of a single test."""
    
    def __init__(self, name, passed, message, output="", duration=0):
        self.name = name
        self.passed = passed
        self.message = message
        self.output = output
        self.duration = duration


class TestRunner:
    """Main test runner for JOS kernel tests."""
    
    def __init__(self, kernel_path="build/BB.bin", timeout=30, verbose=False, 
                 no_color=False, skip_build=False):
        """
        Initialize test runner.
        
        Args:
            kernel_path: Path to the kernel binary
            timeout: Maximum time for each test (seconds)
            verbose: Enable verbose output
            no_color: Disable colored output
            skip_build: Skip building the kernel
        """
        self.kernel_path = kernel_path
        self.timeout = timeout
        self.verbose = verbose
        self.skip_build = skip_build
        
        if no_color or not sys.stdout.isatty():
            Colors.disable()
            
        self.results = []
        
    def log(self, message, color=None):
        """Log a message with optional color."""
        if color:
            print(f"{color}{message}{Colors.END}")
        else:
            print(message)
            
    def build_kernel(self):
        """Build the kernel using make."""
        if self.skip_build:
            self.log("Skipping build (using existing kernel)", Colors.YELLOW)
            return True
            
        self.log("Building kernel...", Colors.BLUE)
        start = time.time()
        
        try:
            # Run make clean && make disk (to include filesystem)
            result = subprocess.run(
                ["make", "clean"],
                capture_output=True,
                text=True,
                cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            )
            
            result = subprocess.run(
                ["make", "disk"],
                capture_output=True,
                text=True,
                cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
            )
            
            duration = time.time() - start
            
            if result.returncode != 0:
                self.log(f"Build failed in {duration:.1f}s", Colors.RED)
                if self.verbose:
                    print("STDOUT:", result.stdout)
                    print("STDERR:", result.stderr)
                return False
                
            self.log(f"Build succeeded in {duration:.1f}s", Colors.GREEN)
            return True
            
        except Exception as e:
            self.log(f"Build error: {e}", Colors.RED)
            return False
            
    def run_test(self, name, test_class):
        """
        Run a single test.
        
        Args:
            name: Test name for reporting
            test_class: Test class to instantiate and run
            
        Returns:
            TestResult object
        """
        self.log(f"\nRunning: {name}...", Colors.BOLD)
        start = time.time()
        
        try:
            test = test_class(self.kernel_path, self.timeout)
            passed, message, output = test.run()
            duration = time.time() - start
            
            result = TestResult(name, passed, message, output, duration)
            
            if passed:
                self.log(f"  PASS ({duration:.1f}s): {message}", Colors.GREEN)
            else:
                self.log(f"  FAIL ({duration:.1f}s): {message}", Colors.RED)
                
            if self.verbose and output:
                print(f"  Output:\n{output[:2000]}")
                
            return result
            
        except Exception as e:
            duration = time.time() - start
            result = TestResult(name, False, f"Exception: {e}", "", duration)
            self.log(f"  ERROR ({duration:.1f}s): {e}", Colors.RED)
            return result
            
    def run_all_tests(self):
        """
        Run all tests.
        
        Returns:
            True if all tests passed, False otherwise
        """
        self.log("=" * 60, Colors.BOLD)
        self.log("JOS Kernel Test Harness", Colors.BOLD)
        self.log("=" * 60, Colors.BOLD)
        
        # Check kernel exists
        if not os.path.exists(self.kernel_path):
            if not self.build_kernel():
                self.log("\nCannot continue without kernel binary", Colors.RED)
                return False
        elif not self.skip_build:
            if not self.build_kernel():
                self.log("\nBuild failed, trying with existing kernel", Colors.YELLOW)
                
        # Run tests with small delay between them to ensure cleanup
        import time
        self.results.append(self.run_test("boot", BootTest))
        time.sleep(1)
        self.results.append(self.run_test("memory", MemoryTest))
        time.sleep(1)
        self.results.append(self.run_test("command", CommandTest))
        time.sleep(1)
        self.results.append(self.run_test("pe", QEMUPETest))
        
        # Print summary
        return self.print_summary()
        
    def print_summary(self):
        """
        Print test summary.
        
        Returns:
            True if all tests passed, False otherwise
        """
        self.log("\n" + "=" * 60, Colors.BOLD)
        self.log("Test Summary", Colors.BOLD)
        self.log("=" * 60, Colors.BOLD)
        
        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if not r.passed)
        total = len(self.results)
        total_time = sum(r.duration for r in self.results)
        
        for result in self.results:
            status = f"{Colors.GREEN}PASS{Colors.END}" if result.passed else f"{Colors.RED}FAIL{Colors.END}"
            self.log(f"  [{status}] {result.name:20s} ({result.duration:.1f}s) - {result.message}")
            
        self.log("-" * 60)
        
        if failed == 0:
            self.log(f"Result: {Colors.GREEN}{passed}/{total} tests passed{Colors.END} (total: {total_time:.1f}s)")
            return True
        else:
            self.log(f"Result: {Colors.RED}{failed}/{total} tests failed{Colors.END} (total: {total_time:.1f}s)")
            return False


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description="JOS Kernel Test Harness",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 test/run_tests.py              # Run all tests
  python3 test/run_tests.py -v           # Verbose output
  python3 test/run_tests.py --no-build   # Use existing kernel
  python3 test/run_tests.py -k build/BB.bin  # Custom kernel path
        """
    )
    
    parser.add_argument("--kernel", "-k", default="build/BB.bin",
                        help="Path to kernel binary (default: build/BB.bin)")
    parser.add_argument("--timeout", "-t", type=int, default=30,
                        help="Timeout per test in seconds (default: 30)")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Verbose output")
    parser.add_argument("--no-color", action="store_true",
                        help="Disable colored output")
    parser.add_argument("--no-build", action="store_true",
                        help="Skip building the kernel")
    parser.add_argument("--test", "-T", choices=["boot", "memory", "command", "pe", "all"],
                        default="all", help="Run specific test (default: all)")
                        
    args = parser.parse_args()
    
    runner = TestRunner(
        kernel_path=args.kernel,
        timeout=args.timeout,
        verbose=args.verbose,
        no_color=args.no_color,
        skip_build=args.no_build
    )
    
    # Run specific test or all tests
    if args.test == "all":
        success = runner.run_all_tests()
    else:
        # Run single test
        test_map = {
            "boot": BootTest,
            "memory": MemoryTest,
            "command": CommandTest,
            "pe": QEMUPETest
        }
        test_class = test_map.get(args.test)
        if test_class:
            result = runner.run_test(args.test, test_class)
            success = result.passed
        else:
            print(f"Unknown test: {args.test}")
            success = False
        
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
