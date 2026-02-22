#!/usr/bin/env python3
"""Validate kernel binary size against bootloader's max load capacity."""
import sys, os

SECTORS = 1 + 65 + 128  # boot + stage1 + stage2
MAX_SIZE = SECTORS * 512  # 99,328 bytes
WARN_THRESHOLD = 0.85

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "build/BB.bin"
    size = os.path.getsize(path)
    # Kernel code ends before 1MB filesystem region; find effective size
    effective = min(size, 0x100000)  # cap at 1MB (filesystem starts there)
    pct = effective / MAX_SIZE * 100
    headroom = MAX_SIZE - effective
    print(f"Kernel size: {effective} / {MAX_SIZE} bytes ({pct:.1f}%), headroom: {headroom} bytes")
    if effective > MAX_SIZE:
        print(f"ERROR: Kernel exceeds bootloader max by {effective - MAX_SIZE} bytes!")
        print(f"  Increase stage2 sector count in bootloader.asm or reduce kernel size.")
        sys.exit(1)
    if pct >= WARN_THRESHOLD * 100:
        print(f"WARNING: Kernel is above {WARN_THRESHOLD*100:.0f}% capacity!")
    return 0

if __name__ == "__main__":
    main()
