#!/usr/bin/env python3
"""
Embed filesystem into kernel disk image at 1MB offset (sector 2048).
Usage: python3 embed_fs.py <kernel_disk> <filesystem_img>
"""

import sys

def embed_filesystem(kernel_path, fs_path):
    # Read kernel
    with open(kernel_path, 'rb') as f:
        kernel = f.read()
    
    # Read filesystem
    with open(fs_path, 'rb') as f:
        fs_data = f.read()
    
    # Create output: kernel + padding to 1MB + filesystem
    sector_size = 512
    fs_offset_sectors = 2048  # 1MB = 2048 sectors
    fs_offset_bytes = fs_offset_sectors * sector_size
    
    # Pad kernel to 1MB
    if len(kernel) < fs_offset_bytes:
        kernel = kernel + b'\x00' * (fs_offset_bytes - len(kernel))
    
    # Combine
    output = kernel + fs_data
    
    # Write output
    with open(kernel_path, 'wb') as f:
        f.write(output)
    
    print(f"Embedded {len(fs_data)} bytes of filesystem at offset {fs_offset_bytes} (1MB)")
    print(f"Total disk size: {len(output)} bytes ({len(output)//sector_size} sectors)")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python3 embed_fs.py <kernel_disk> <filesystem_img>")
        sys.exit(1)
    
    embed_filesystem(sys.argv[1], sys.argv[2])
