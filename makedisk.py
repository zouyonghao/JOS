#!/usr/bin/env python3
"""
Create a disk image with SFROFS filesystem for JOS.
Usage: python3 makedisk.py <disk_image> <file1> [file2] ...
"""

import sys
import os
import struct

SECTOR_SIZE = 512
SUPERBLOCK_SECTOR = 1
FILE_TABLE_SECTOR = 2
DATA_START_SECTOR = 4  # Sectors 1-3 reserved for superblock + file table

def pad_to_sector(data):
    """Pad data to sector boundary."""
    remainder = len(data) % SECTOR_SIZE
    if remainder:
        data += b'\x00' * (SECTOR_SIZE - remainder)
    return data

def create_superblock(num_files, data_start):
    """Create SFROFS superblock."""
    magic = b'SFRO'
    version = 1
    # Structure: magic(4) + version(1) + padding(3) + num_files(2) + data_start(4) + reserved(498)
    superblock = magic
    superblock += struct.pack('<B', version)
    superblock += b'\x00' * 3  # Padding
    superblock += struct.pack('<H', num_files)
    superblock += struct.pack('<I', data_start)
    superblock += b'\x00' * (SECTOR_SIZE - len(superblock))  # Pad to sector
    return superblock

def create_file_entry(filename, start_sector, size):
    """Create a 64-byte file entry."""
    # Filename: 48 bytes (null-terminated)
    name_bytes = filename.encode('utf-8')[:47]
    name_padded = name_bytes + b'\x00' * (48 - len(name_bytes))
    
    # Entry: name(48) + start_sector(4) + size(4) + reserved(8)
    entry = name_padded
    entry += struct.pack('<I', start_sector)
    entry += struct.pack('<I', size)
    entry += b'\x00' * 8
    return entry

def create_disk_image(output_path, files):
    """Create a disk image with the given files."""
    num_files = len(files)
    
    # Read all files
    file_data = []
    total_data_size = 0
    for filepath in files:
        with open(filepath, 'rb') as f:
            data = f.read()
        filename = os.path.basename(filepath)
        file_data.append((filename, data))
        total_data_size += len(data)
        print(f"Adding: {filename} ({len(data)} bytes)")
    
    # Calculate layout
    data_start = DATA_START_SECTOR
    
    # Create superblock
    superblock = create_superblock(num_files, data_start)
    
    # Create file table
    file_table = b''
    current_sector = data_start
    for filename, data in file_data:
        size = len(data)
        entry = create_file_entry(filename, current_sector, size)
        file_table += entry
        # Calculate next sector
        sectors_needed = (size + SECTOR_SIZE - 1) // SECTOR_SIZE
        current_sector += sectors_needed
    
    # Pad file table to sector boundary
    file_table = pad_to_sector(file_table)
    
    # Create data section
    data_section = b''
    for filename, data in file_data:
        data_section += pad_to_sector(data)
    
    # Combine all parts
    # Sector 0: Empty (boot sector reserved)
    # Sector 1: Superblock
    # Sector 2+: File table
    # Sector DATA_START_SECTOR+: Data
    boot_sector = b'\x00' * SECTOR_SIZE
    
    disk_image = boot_sector + superblock + file_table
    
    # Pad to data_start sector
    while len(disk_image) < data_start * SECTOR_SIZE:
        disk_image += b'\x00' * SECTOR_SIZE
    
    disk_image += data_section
    
    # Write disk image
    with open(output_path, 'wb') as f:
        f.write(disk_image)
    
    total_size = len(disk_image)
    print(f"\nCreated: {output_path}")
    print(f"Total size: {total_size} bytes ({total_size // SECTOR_SIZE} sectors)")
    print(f"Files: {num_files}")
    print(f"Data starts at sector: {data_start}")

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage: python3 makedisk.py <disk_image> <file1> [file2] ...")
        sys.exit(1)
    
    output_path = sys.argv[1]
    input_files = sys.argv[2:]
    
    create_disk_image(output_path, input_files)
