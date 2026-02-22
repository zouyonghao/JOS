#!/usr/bin/env python3
"""Quick test to run plasrv.exe on JOS"""
import subprocess, socket, time, sys, os, re, select

kernel = sys.argv[1] if len(sys.argv) > 1 else "build/BB.bin"
monitor_path = "/tmp/qemu-plasrv-test-monitor"

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

try: os.unlink(monitor_path)
except: pass

cmd = [
    'qemu-system-x86_64', '-nographic',
    '-device', 'isa-debug-exit,iobase=0xf4,iosize=0x04',
    '-no-reboot',
    '-drive', f'format=raw,file={kernel}',
    '-monitor', f'unix:{monitor_path},server,nowait'
]

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

def read_output():
    global output
    try:
        ready, _, _ = select.select([proc.stdout], [], [], 0)
        if ready:
            data = proc.stdout.read(65536)
            if data:
                output += data.decode('latin-1', errors='replace')
    except: pass

def wait_for(pattern, timeout=30):
    start = time.time()
    while time.time() - start < timeout:
        read_output()
        if pattern in output:
            return True
        if proc.poll() is not None:
            read_output()
            print(f"QEMU exited with code {proc.returncode}")
            return False
        time.sleep(0.1)
    return False

def send_keys(text):
    for ch in text:
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

def clean(text):
    text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
    # Remove spinner chars
    text = re.sub(r'[|/\\]', '', text)
    return text

print("Waiting for boot...")
if not wait_for("> ", timeout=30):
    print("FAILED: Kernel didn't boot")
    print("Output:", clean(output[-500:]))
    proc.kill()
    sys.exit(1)

print("Kernel booted! Sending 'run plasrv.exe'...")
output = ""
send_keys("run plasrv.exe\n")

# Wait for output
time.sleep(15)
read_output()

cleaned = clean(output)

print("=" * 70)
print("OUTPUT after 'run plasrv.exe':")
print("=" * 70)
lines = cleaned.split('\n')
for line in lines:
    while len(line) > 120:
        print(line[:120])
        line = line[120:]
    print(line)
print("=" * 70)
print(f"Raw output length: {len(output)}")

proc.kill()
proc.wait()
try: os.unlink(monitor_path)
except: pass
