#!/usr/bin/env python3
"""
mksbf.py - Convert ELF binary to SBF (Simple Binary Format)

SBF Format:
  Offset 0-3:   "SBF\0" magic (4 bytes)
  Offset 4-7:   Entry point offset (uint32, little-endian)
  Offset 8-11:  Code size (uint32, little-endian)
  Offset 12-15: Data size (uint32, little-endian)
  Offset 16+:   Code and data sections

Usage: mksbf input.elf output.sbf
"""

import sys
import struct

SBF_MAGIC = b"SBF\x00"
SBF_HEADER_SIZE = 16


def read_elf(elf_path):
    """
    Read an ELF file and extract the loadable segments.
    Returns a tuple of (entry_offset, flat_image) where flat_image preserves
    the virtual address layout so RIP-relative addressing works correctly.
    """
    with open(elf_path, "rb") as f:
        elf_data = f.read()

    # ELF header parsing
    if elf_data[:4] != b"\x7fELF":
        raise ValueError("Not a valid ELF file")

    # Check if 64-bit
    is_64bit = elf_data[4] == 2
    if not is_64bit:
        raise ValueError("Only 64-bit ELF files are supported")

    # Check little-endian
    is_little_endian = elf_data[5] == 1
    if not is_little_endian:
        raise ValueError("Only little-endian ELF files are supported")

    # Parse ELF64 header
    # e_entry: entry point virtual address (offset 0x18, 8 bytes)
    entry_point = struct.unpack("<Q", elf_data[0x18:0x20])[0]

    # e_phoff: program header offset (offset 0x20, 8 bytes)
    phoff = struct.unpack("<Q", elf_data[0x20:0x28])[0]

    # e_phentsize: program header entry size (offset 0x36, 2 bytes)
    phentsize = struct.unpack("<H", elf_data[0x36:0x38])[0]

    # e_phnum: number of program headers (offset 0x38, 2 bytes)
    phnum = struct.unpack("<H", elf_data[0x38:0x3A])[0]

    # Read all LOAD segments with their virtual addresses
    segments = []

    for i in range(phnum):
        ph_start = phoff + i * phentsize

        # p_type: segment type (offset 0, 4 bytes)
        p_type = struct.unpack("<I", elf_data[ph_start:ph_start+4])[0]

        # PT_LOAD = 1
        if p_type == 1:
            # p_offset: offset in file (offset 8, 8 bytes)
            p_offset = struct.unpack("<Q", elf_data[ph_start+8:ph_start+16])[0]
            # p_vaddr: virtual address (offset 16, 8 bytes)
            p_vaddr = struct.unpack("<Q", elf_data[ph_start+16:ph_start+24])[0]
            # p_filesz: size in file (offset 32, 8 bytes)
            p_filesz = struct.unpack("<Q", elf_data[ph_start+32:ph_start+40])[0]
            # p_flags: flags (offset 4, 4 bytes)
            p_flags = struct.unpack("<I", elf_data[ph_start+4:ph_start+8])[0]

            if p_filesz > 0:
                segment_data = elf_data[p_offset:p_offset + p_filesz]
                segments.append((p_vaddr, p_flags, segment_data))

    if not segments:
        raise ValueError("No loadable segments found")

    # Find the executable segment as the base
    exec_segments = [s for s in segments if s[1] & 1]  # PF_X = 1
    if not exec_segments:
        raise ValueError("No executable segment found")

    base_addr = exec_segments[0][0]

    # Build a flat image preserving virtual address layout
    # Only include segments at or after the code base address
    load_segments = [s for s in segments if s[0] >= base_addr]
    if not load_segments:
        raise ValueError("No segments to load")

    max_addr = max(s[0] + len(s[2]) for s in load_segments)
    image_size = max_addr - base_addr

    flat_image = bytearray(image_size)
    for vaddr, flags, data in load_segments:
        offset = vaddr - base_addr
        flat_image[offset:offset + len(data)] = data

    entry_offset = entry_point - base_addr

    return entry_offset, bytes(flat_image)


def create_sbf(entry_offset, flat_image, output_path):
    """
    Create an SBF file from a flat memory image.
    The image preserves the ELF virtual address layout so RIP-relative
    addressing works regardless of the load address.
    """
    code_size = len(flat_image)
    data_size = 0  # Everything is in the flat image

    # Build SBF header
    header = SBF_MAGIC
    header += struct.pack("<I", entry_offset)  # Entry point offset
    header += struct.pack("<I", code_size)     # Image size
    header += struct.pack("<I", data_size)     # Data size (0)

    # Write SBF file
    with open(output_path, "wb") as f:
        f.write(header)
        f.write(flat_image)

    print(f"Created {output_path}:")
    print(f"  Entry point offset: {entry_offset}")
    print(f"  Image size: {code_size} bytes")
    print(f"  Total size: {SBF_HEADER_SIZE + code_size} bytes")


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} input.elf output.sbf")
        sys.exit(1)

    elf_path = sys.argv[1]
    output_path = sys.argv[2]

    try:
        entry_offset, flat_image = read_elf(elf_path)
        create_sbf(entry_offset, flat_image, output_path)
    except Exception as e:
        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
