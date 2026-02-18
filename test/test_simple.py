#!/usr/bin/env python3
import subprocess
import socket
import time
import os
import re

os.system('rm -f /tmp/qemu-pe-test-monitor')

cmd = [
    'qemu-system-x86_64', '-nographic',
    '-device', 'isa-debug-exit,iobase=0xf4,iosize=0x04',
    '-no-reboot',
    '-drive', 'format=raw,file=./build/BB.bin',
    '-monitor', 'unix:/tmp/qemu-pe-test-monitor,server,nowait'
]

proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
time.sleep(6)

sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.connect('/tmp/qemu-pe-test-monitor')

time.sleep(3)

def clean(text):
    text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
    text = re.sub(r'[|/\\-]', '', text)
    return text

def send_cmd(cmd_str):
    for k in cmd_str:
        if k == ' ':
            sock.sendall(b'sendkey spc\n')
        elif k == '.':
            sock.sendall(b'sendkey dot\n')
        else:
            sock.sendall(f'sendkey {k}\n'.encode())
        time.sleep(0.1)
    sock.sendall(b'sendkey ret\n')

# Run hello.sbf and capture output
print('=== Running hello.sbf ===')
send_cmd('run hello.sbf')
time.sleep(8)

# Get output
data = proc.stdout.read1(16384).decode('latin-1', errors='replace')
clean_data = clean(data)

# Print relevant lines
print("=== Output ===")
for line in clean_data.split('\n'):
    line = line.strip()
    if line and any(x in line for x in ['SCHED', 'SPAWN', 'TERM', 'Spawned', 'Hello', 'state', 'count=']):
        print(line)

print("\n=== Last 300 chars ===")
print(clean_data[-300:])

# Try to run again
print("\n=== Sending second run command ===")
send_cmd('run hello.sbf')
time.sleep(5)

try:
    data = proc.stdout.read1(8192).decode('latin-1', errors='replace')
    clean_data = clean(data)
    print("=== Second output ===")
    for line in clean_data.split('\n'):
        line = line.strip()
        if line and any(x in line for x in ['SCHED', 'SPAWN', 'TERM', 'Spawned', 'Hello', 'state', 'count=']):
            print(line)
except:
    print("Failed to read output")

sock.sendall(b'quit\n')
sock.close()
proc.terminate()
