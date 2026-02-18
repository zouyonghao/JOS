#!/usr/bin/env python3
"""
Verify PE loader setup without running QEMU interactively.
This checks that all components are in place.
"""

import struct
import os
import sys

def check_file(path, desc):
    """Check if a file exists."""
    if os.path.exists(path):
        size = os.path.getsize(path)
        print(f"✓ {desc}: {path} ({size} bytes)")
        return True
    else:
        print(f"✗ {desc}: {path} NOT FOUND")
        return False

def check_filesystem():
    """Check embedded filesystem."""
    print("\n=== Checking Embedded Filesystem ===")
    
    with open('build/BB.bin', 'rb') as f:
        # Check superblock at 1MB + 512
        f.seek(1024*1024 + 512)
        data = f.read(16)
        
        magic = data[0:4]
        version = data[4]
        num_files = struct.unpack('<H', data[8:10])[0]
        
        if magic != b'SFRO':
            print(f"✗ Invalid filesystem magic: {magic}")
            return False
            
        print(f"✓ Filesystem magic: {magic.decode()}")
        print(f"✓ Version: {version}")
        print(f"✓ Number of files: {num_files}")
        
        # Read file entries
        f.seek(1024*1024 + 1024)
        data = f.read(256)
        
        files = []
        for i in range(num_files):
            name = data[i*64:i*64+48].split(b'\x00')[0].decode()
            start = struct.unpack('<I', data[i*64+48:i*64+52])[0]
            size = struct.unpack('<I', data[i*64+52:i*64+56])[0]
            files.append((name, start, size))
            print(f"  [{i}] {name} (sector {start}, {size} bytes)")
            
        return files

def check_pe_file():
    """Check win_hello.exe PE format."""
    print("\n=== Checking win_hello.exe PE Format ===")
    
    with open('win_hello.exe', 'rb') as f:
        data = f.read()
        
    # Check DOS header
    if data[0:2] != b'MZ':
        print("✗ Invalid DOS magic")
        return False
    print("✓ DOS magic: MZ")
    
    # Get PE offset
    pe_offset = struct.unpack('<I', data[0x3C:0x40])[0]
    print(f"✓ PE header offset: {pe_offset}")
    
    # Check PE signature
    if data[pe_offset:pe_offset+4] != b'PE\x00\x00':
        print("✗ Invalid PE signature")
        return False
    print("✓ PE signature: PE\\0\\0")
    
    # Check machine type
    machine = struct.unpack('<H', data[pe_offset+4:pe_offset+6])[0]
    if machine != 0x8664:
        print(f"✗ Invalid machine type: 0x{machine:04X} (expected 0x8664 for x64)")
        return False
    print(f"✓ Machine type: 0x{machine:04X} (x64)")
    
    # Check PE32+ magic
    opt_header_offset = pe_offset + 24
    opt_magic = struct.unpack('<H', data[opt_header_offset:opt_header_offset+2])[0]
    if opt_magic != 0x20B:
        print(f"✗ Not PE32+ format: 0x{opt_magic:03X}")
        return False
    print(f"✓ Optional header magic: 0x{opt_magic:03X} (PE32+)")
    
    # Get entry point
    entry_point = struct.unpack('<I', data[opt_header_offset+16:opt_header_offset+20])[0]
    print(f"✓ Entry point RVA: 0x{entry_point:08X}")
    
    # Get image base
    image_base = struct.unpack('<Q', data[opt_header_offset+24:opt_header_offset+32])[0]
    print(f"✓ Image base: 0x{image_base:016X}")
    
    # Get subsystem
    subsystem = struct.unpack('<H', data[opt_header_offset+68:opt_header_offset+70])[0]
    subsys_name = {1: "Native", 2: "GUI", 3: "Console"}.get(subsystem, f"Unknown({subsystem})")
    print(f"✓ Subsystem: {subsystem} ({subsys_name})")
    
    # Number of sections
    num_sections = struct.unpack('<H', data[pe_offset+6:pe_offset+8])[0]
    print(f"✓ Number of sections: {num_sections}")
    
    return True

def main():
    print("=" * 60)
    print("JOS PE Loader Setup Verification")
    print("=" * 60)
    
    all_ok = True
    
    # Check files
    print("\n=== Checking Files ===")
    all_ok &= check_file("build/BB.bin", "Kernel binary")
    all_ok &= check_file("build/disk.img", "Disk image")
    all_ok &= check_file("win_hello.exe", "Windows PE test file")
    
    if not all_ok:
        print("\n✗✗✗ Some files are missing!")
        return 1
        
    # Check filesystem
    files = check_filesystem()
    if not files:
        print("\n✗✗✗ Filesystem check failed!")
        return 1
        
    # Check for win_hello.exe
    win_hello_found = any(f[0] == "win_hello.exe" for f in files)
    if win_hello_found:
        print("\n✓ win_hello.exe is in the filesystem")
    else:
        print("\n✗ win_hello.exe is NOT in the filesystem!")
        print("  Run: make disk")
        return 1
        
    # Check PE format
    if not check_pe_file():
        print("\n✗✗✗ PE format check failed!")
        return 1
        
    # Print summary
    print("\n" + "=" * 60)
    print("Summary")
    print("=" * 60)
    print("✓ All files present")
    print("✓ Filesystem properly embedded")
    print("✓ win_hello.exe is a valid x64 PE executable")
    print()
    print("To test the PE loader manually:")
    print("  1. Run: make qemu-disk")
    print("  2. At the JOS prompt, type: run win_hello.exe")
    print("  3. You should see 'Detected Windows PE executable' followed by")
    print("     'Hello from Windows PE!' output")
    print()
    print("✓✓✓ PE loader setup verification PASSED")
    
    return 0

if __name__ == "__main__":
    sys.exit(main())
