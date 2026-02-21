#!/usr/bin/env python3
"""
Test new PE programs: win_memtest, win_printf
Runs each program in QEMU and checks for expected output.
"""

import subprocess
import sys
import socket
import time
import re
import os

KEY_MAP = {
    'a': 'a', 'b': 'b', 'c': 'c', 'd': 'd', 'e': 'e',
    'f': 'f', 'g': 'g', 'h': 'h', 'i': 'i', 'j': 'j',
    'k': 'k', 'l': 'l', 'm': 'm', 'n': 'n', 'o': 'o',
    'p': 'p', 'q': 'q', 'r': 'r', 's': 's', 't': 't',
    'u': 'u', 'v': 'v', 'w': 'w', 'x': 'x', 'y': 'y', 'z': 'z',
    '0': '0', '1': '1', '2': '2', '3': '3', '4': '4',
    '5': '5', '6': '6', '7': '7', '8': '8', '9': '9',
    ' ': 'spc', '.': 'dot', '\n': 'ret', '-': 'minus',
    '_': 'shift-minus',
}

def send_keys(sock, text):
    for ch in text:
        key = KEY_MAP.get(ch)
        if key:
            sock.sendall(f"sendkey {key}\n".encode())
            time.sleep(0.05)
            try:
                sock.setblocking(False)
                sock.recv(4096)
            except:
                pass
            sock.setblocking(True)
            time.sleep(0.02)

def read_output(proc, prev=""):
    import select
    output = prev
    try:
        ready, _, _ = select.select([proc.stdout], [], [], 0)
        if ready:
            data = proc.stdout.read(4096)
            if data:
                output += data.decode('latin-1', errors='replace')
    except:
        pass
    return output

def wait_for(proc, pattern, output="", timeout=30):
    start = time.time()
    while time.time() - start < timeout:
        output = read_output(proc, output)
        if pattern in output:
            return output
        if proc.poll() is not None:
            raise RuntimeError(f"QEMU exited")
        time.sleep(0.1)
    raise TimeoutError(f"Timeout waiting for: {pattern}\nOutput tail: {output[-500:]}")

def run_pe_test(kernel_path, program_name, expected_patterns, timeout=20):
    """Run a PE program and check for expected output patterns."""
    monitor_path = f"/tmp/qemu-pe-test-{program_name}"
    try:
        os.unlink(monitor_path)
    except:
        pass

    print(f"  Testing {program_name}...")

    cmd = [
        'qemu-system-x86_64', '-accel', 'tcg', '-nographic',
        '-device', 'isa-debug-exit,iobase=0xf4,iosize=0x04',
        '-no-reboot',
        '-drive', f'format=raw,file={kernel_path}',
        '-monitor', f'unix:{monitor_path},server,nowait'
    ]

    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)

    try:
        time.sleep(4)

        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(monitor_path)
        time.sleep(0.5)
        try:
            sock.setblocking(False)
            sock.recv(4096)
        except:
            pass
        sock.setblocking(True)

        # Wait for prompt
        output = wait_for(proc, "> ", timeout=30)
        output = ""  # Reset

        # Run the program
        send_keys(sock, f"run {program_name}\n")

        # Wait for all expected patterns
        all_found = True
        for pattern in expected_patterns:
            try:
                output = wait_for(proc, pattern, output, timeout=timeout)
                print(f"    FOUND: {pattern}")
            except TimeoutError:
                print(f"    MISSING: {pattern}")
                # Read whatever we have
                output = read_output(proc, output)
                all_found = False
                break

        # Extra time
        time.sleep(2)
        output = read_output(proc, output)

        if not all_found:
            # Clean and show output
            clean = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', output)
            print(f"    Output: {clean[:1000]}")

        return all_found, output

    except Exception as e:
        print(f"    ERROR: {e}")
        return False, str(e)
    finally:
        try:
            sock.sendall(b"quit\n")
            sock.close()
        except:
            pass
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except:
            proc.kill()
            proc.wait()
        try:
            os.unlink(monitor_path)
        except:
            pass


def main():
    kernel_path = sys.argv[1] if len(sys.argv) > 1 else "build/BB.bin"

    print("=" * 60)
    print("JOS Extended PE Test Suite")
    print("=" * 60)

    results = {}

    # Test 1: win_memtest.exe
    ok, _ = run_pe_test(kernel_path, "win_memtest.exe", [
        "HeapAlloc test start",
        "HeapAlloc OK",
        "JOS!",
        "HeapFree OK",
        "MEMTEST PASS",
    ])
    results["win_memtest"] = ok

    # Test 2: win_printf.exe (msvcrt)
    ok, _ = run_pe_test(kernel_path, "win_printf.exe", [
        "printf test start",
        "Hello from JOS PE printf",
        "Number: 42",
        "PRINTF PASS",
    ])
    results["win_printf"] = ok

    # Test 3: win_threads.exe (CreateThread)
    ok, _ = run_pe_test(kernel_path, "win_threads.exe", [
        "CreateThread test start",
        "Hello from thread!",
        "THREAD PASS",
    ], timeout=30)
    results["win_threads"] = ok

    # Test 4: win_fileio.exe (CreateFileA/ReadFile)
    ok, _ = run_pe_test(kernel_path, "win_fileio.exe", [
        "File I/O test start",
        "Opened hello.sbf",
        "FILEIO PASS",
    ])
    results["win_fileio"] = ok

    # Summary
    print()
    print("=" * 60)
    print("Results:")
    all_pass = True
    for name, ok in results.items():
        status = "PASS" if ok else "FAIL"
        print(f"  [{status}] {name}")
        if not ok:
            all_pass = False

    print(f"\n{'ALL TESTS PASSED' if all_pass else 'SOME TESTS FAILED'}")
    sys.exit(0 if all_pass else 1)

if __name__ == "__main__":
    main()
