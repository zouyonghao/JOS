#!/usr/bin/env python3
"""Quick debug: run a single PE program and dump all output."""
import subprocess, sys, socket, time, os, select, re

program = sys.argv[1] if len(sys.argv) > 1 else "win_printf.exe"
kernel = sys.argv[2] if len(sys.argv) > 2 else "build/BB.bin"
monitor_path = "/tmp/qemu-debug-pe"

KEY_MAP = {c: c for c in 'abcdefghijklmnopqrstuvwxyz0123456789'}
KEY_MAP.update({' ': 'spc', '.': 'dot', '\n': 'ret', '-': 'minus', '_': 'shift-minus'})

try: os.unlink(monitor_path)
except: pass

cmd = ['qemu-system-x86_64', '-accel', 'tcg', '-nographic',
       '-device', 'isa-debug-exit,iobase=0xf4,iosize=0x04', '-no-reboot',
       '-drive', f'format=raw,file={kernel}',
       '-monitor', f'unix:{monitor_path},server,nowait']
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
time.sleep(4)

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.connect(monitor_path)
time.sleep(0.5)
try:
    sock.setblocking(False)
    sock.recv(4096)
except: pass
sock.setblocking(True)

output = ""
# Wait for prompt
start = time.time()
while time.time() - start < 30:
    try:
        ready, _, _ = select.select([proc.stdout], [], [], 0)
        if ready:
            data = proc.stdout.read(4096)
            if data: output += data.decode('latin-1', errors='replace')
    except: pass
    if "> " in output: break
    time.sleep(0.1)

output = ""  # Reset
# Send command
for ch in f"run {program}\n":
    key = KEY_MAP.get(ch)
    if key:
        sock.sendall(f"sendkey {key}\n".encode())
        time.sleep(0.05)
        try:
            sock.setblocking(False)
            sock.recv(4096)
        except: pass
        sock.setblocking(True)
        time.sleep(0.02)

# Collect output for 15 seconds
start = time.time()
while time.time() - start < 15:
    try:
        ready, _, _ = select.select([proc.stdout], [], [], 0)
        if ready:
            data = proc.stdout.read(4096)
            if data: output += data.decode('latin-1', errors='replace')
    except: pass
    time.sleep(0.1)

# Clean spinner chars
clean = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', output)
# Keep printable + newlines
clean2 = ''.join(c for c in clean if c.isprintable() or c in '\n\r\t')
print("=== RAW OUTPUT ===")
print(clean2)
print("=== END ===")

sock.sendall(b"quit\n"); sock.close()
proc.terminate(); proc.wait()
try: os.unlink(monitor_path)
except: pass
