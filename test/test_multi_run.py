#!/usr/bin/env python3
"""Test running hello.sbf multiple times"""
import subprocess
import socket
import time
import os
import sys
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

try:
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.connect('/tmp/qemu-pe-test-monitor')
except Exception as e:
    print('Failed to connect:', e)
    proc.terminate()
    sys.exit(1)

time.sleep(3)

def clean_output(text):
    text = re.sub(r'[|/\\-]', '', text)
    text = re.sub(r'\x1b\[[0-9;?]*[a-zA-Z]', '', text)
    return text

all_output = ''

def run_program(name):
    global all_output
    for k in name:
        if k == ' ':
            sock.sendall(b'sendkey spc\n')
        elif k == '.':
            sock.sendall(b'sendkey dot\n')
        else:
            sock.sendall(f'sendkey {k}\n'.encode())
        time.sleep(0.05)
    sock.sendall(b'sendkey ret\n')
    time.sleep(5)
    data = proc.stdout.read1(16384).decode('latin-1', errors='replace')
    all_output += data

print('=== First run ===')
run_program('run hello.sbf')
clean = clean_output(all_output)
for line in clean.split('\n'):
    if any(x in line for x in ['[SCHED]', '[SPAWN]', '[TERM]', 'Hello', 'Spawned', 'ERROR']):
        print(line)

print('\n=== Second run ===')
run_program('run hello.sbf')
clean = clean_output(all_output)
for line in clean.split('\n'):
    if any(x in line for x in ['[SCHED]', '[SPAWN]', '[TERM]', 'Hello', 'Spawned', 'ERROR']):
        print(line)

print('\n=== Third run ===')
run_program('run hello.sbf')
clean = clean_output(all_output)
for line in clean.split('\n'):
    if any(x in line for x in ['[SCHED]', '[SPAWN]', '[TERM]', 'Hello', 'Spawned', 'ERROR']):
        print(line)

sock.sendall(b'quit\n')
sock.close()
proc.terminate()
print('\n=== Done ===')
