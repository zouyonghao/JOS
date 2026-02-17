import re

with open('Kernel.java', 'r') as f:
    content = f.read()

# Remove writeString debug statements in initFilesystem
content = re.sub(r'writeString\("  Reading sector.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Retry read.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Testing sector 0 read.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Sector 0 first byte: 0x.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Now reading the actual superblock.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Got:.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Hex:.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Buffer bytes:.*?\\n"\);\s*', '', content, flags=re.DOTALL)
content = re.sub(r'writeString\("  Magic read:.*?\\n"\);\s*', '', content, flags=re.DOTALL)
content = re.sub(r'writeString\("  ATA: read sector.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Data:.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Reading file table.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Entry 0 bytes:.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("    Name len:.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  SBF bytes.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  SBF magic OK.*?\\n"\);\s*', '', content)
content = re.sub(r'writeString\("  Buffer at offset 8:.*?\\n"\);\s*', '', content)
content = re.sub(r'int k = 8;.*?writeString\("\\n"\);\s*', '', content, flags=re.DOTALL)
content = re.sub(r'writeString\("  Manual read at offset 8:.*?\\n"\);\s*', '', content)
content = re.sub(r'int b0 =.*?writeString\("\\n"\);\s*', '', content, flags=re.DOTALL)

with open('Kernel.java', 'w') as f:
    f.write(content)
print("Cleaned up debug output!")
