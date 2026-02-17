import java.io.*;
import java.util.*;

/**
 * Lightweight Java bytecode to LLVM IR translator for the JOS bare-metal kernel.
 * Replaces GraalVM native-image for kernel compilation.
 *
 * Usage: java JavaToLLVM Kernel.class output.ll
 *
 * Supports only the subset of bytecodes used by Kernel.java:
 * - Static methods and fields
 * - Primitive types (int, long, char) and String
 * - Basic control flow (if, for, while, goto)
 * - invokestatic, invokevirtual (String only)
 * - Native method declarations
 */
public class JavaToLLVM {

    // ===== Constant pool tag constants =====
    static final int CP_UTF8 = 1, CP_INT = 3, CP_FLOAT = 4, CP_LONG = 5,
                     CP_DOUBLE = 6, CP_CLASS = 7, CP_STRING = 8,
                     CP_FIELDREF = 9, CP_METHODREF = 10,
                     CP_INTERFACE_METHODREF = 11, CP_NAMEANDTYPE = 12,
                     CP_METHOD_HANDLE = 15, CP_METHOD_TYPE = 16,
                     CP_INVOKE_DYNAMIC = 18;

    // ===== Parsed class data =====
    static Object[] cp;
    static int cpCount;
    static String className;
    static List<FieldInfo> fields = new ArrayList<>();
    static List<MethodInfo> methods = new ArrayList<>();

    // ===== LLVM IR generation state =====
    static StringBuilder out = new StringBuilder();
    static int ssaCounter;
    static LinkedHashMap<String, Integer> stringConstants = new LinkedHashMap<>();
    static int stringIndex = 0;

    // ===== Symbolic operand stack =====
    static Deque<String> stack = new ArrayDeque<>();
    static Deque<Character> stackTypes = new ArrayDeque<>();

    // ===== Per-block stack tracking for phi node generation =====
    static Map<Integer, List<String>> blockExitStacks;
    static Map<Integer, List<Character>> blockExitStackTypes;
    static Map<Integer, Set<Integer>> predecessorMap;
    static int currentBlockStart;

    // ===== Inner types =====
    static class FieldInfo {
        String name, descriptor;
        int accessFlags;
    }

    static class MethodInfo {
        String name, descriptor;
        int accessFlags;
        byte[] code;
        int maxStack, maxLocals;
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java JavaToLLVM <input.class> <output.ll>");
            System.exit(1);
        }
        parseClassFile(args[0]);
        generateLLVM();
        try (FileWriter fw = new FileWriter(args[1])) {
            fw.write(out.toString());
        }
        System.out.println("Generated " + args[1] + " (" + out.length() + " chars, "
            + methods.size() + " methods, " + stringConstants.size() + " string constants)");
    }

    // =========================================================================
    // Class File Parsing
    // =========================================================================

    static void parseClassFile(String path) throws Exception {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path)))) {
            int magic = dis.readInt();
            if (magic != 0xCAFEBABE) throw new RuntimeException("Not a class file");
            dis.readUnsignedShort(); // minor version
            dis.readUnsignedShort(); // major version

            readConstantPool(dis);

            dis.readUnsignedShort(); // access flags
            int thisClass = dis.readUnsignedShort();
            className = resolveClassName(thisClass);
            dis.readUnsignedShort(); // super class
            int ifaceCount = dis.readUnsignedShort();
            for (int i = 0; i < ifaceCount; i++) dis.readUnsignedShort();

            readFields(dis);
            readMethods(dis);
        }
    }

    static void readConstantPool(DataInputStream dis) throws Exception {
        cpCount = dis.readUnsignedShort();
        cp = new Object[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = dis.readUnsignedByte();
            switch (tag) {
                case CP_UTF8:
                    cp[i] = dis.readUTF();
                    break;
                case CP_INT:
                    cp[i] = dis.readInt();
                    break;
                case CP_FLOAT:
                    dis.readFloat(); // skip
                    cp[i] = 0;
                    break;
                case CP_LONG:
                    cp[i] = dis.readLong();
                    i++; // longs take two slots
                    break;
                case CP_DOUBLE:
                    dis.readDouble(); // skip
                    cp[i] = 0.0;
                    i++; // doubles take two slots
                    break;
                case CP_CLASS:
                case CP_STRING:
                case CP_METHOD_TYPE:
                    cp[i] = new int[]{tag, dis.readUnsignedShort()};
                    break;
                case CP_FIELDREF:
                case CP_METHODREF:
                case CP_INTERFACE_METHODREF:
                case CP_NAMEANDTYPE:
                case CP_INVOKE_DYNAMIC:
                    cp[i] = new int[]{tag, dis.readUnsignedShort(), dis.readUnsignedShort()};
                    break;
                case CP_METHOD_HANDLE:
                    cp[i] = new int[]{tag, dis.readUnsignedByte(), dis.readUnsignedShort()};
                    break;
                default:
                    throw new RuntimeException("Unknown CP tag: " + tag + " at index " + i);
            }
        }
    }

    static void readFields(DataInputStream dis) throws Exception {
        int count = dis.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            FieldInfo f = new FieldInfo();
            f.accessFlags = dis.readUnsignedShort();
            f.name = cpUtf8(dis.readUnsignedShort());
            f.descriptor = cpUtf8(dis.readUnsignedShort());
            int attrCount = dis.readUnsignedShort();
            for (int j = 0; j < attrCount; j++) {
                dis.readUnsignedShort();
                int len = dis.readInt();
                dis.skipBytes(len);
            }
            fields.add(f);
        }
    }

    static void readMethods(DataInputStream dis) throws Exception {
        int count = dis.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            MethodInfo m = new MethodInfo();
            m.accessFlags = dis.readUnsignedShort();
            m.name = cpUtf8(dis.readUnsignedShort());
            m.descriptor = cpUtf8(dis.readUnsignedShort());
            int attrCount = dis.readUnsignedShort();
            for (int j = 0; j < attrCount; j++) {
                String attrName = cpUtf8(dis.readUnsignedShort());
                int attrLen = dis.readInt();
                if (attrName.equals("Code")) {
                    m.maxStack = dis.readUnsignedShort();
                    m.maxLocals = dis.readUnsignedShort();
                    int codeLen = dis.readInt();
                    m.code = new byte[codeLen];
                    dis.readFully(m.code);
                    int excCount = dis.readUnsignedShort();
                    for (int k = 0; k < excCount; k++) {
                        dis.readUnsignedShort(); dis.readUnsignedShort();
                        dis.readUnsignedShort(); dis.readUnsignedShort();
                    }
                    int codeAttrCount = dis.readUnsignedShort();
                    for (int k = 0; k < codeAttrCount; k++) {
                        dis.readUnsignedShort();
                        int len = dis.readInt();
                        dis.skipBytes(len);
                    }
                } else {
                    dis.skipBytes(attrLen);
                }
            }
            methods.add(m);
        }
    }

    // =========================================================================
    // Constant pool accessors
    // =========================================================================

    static String cpUtf8(int index) {
        return (String) cp[index];
    }

    static String resolveClassName(int classIndex) {
        int[] entry = (int[]) cp[classIndex];
        return cpUtf8(entry[1]).replace('/', '_');
    }

    static String resolveClassNameRaw(int classIndex) {
        int[] entry = (int[]) cp[classIndex];
        return cpUtf8(entry[1]);
    }

    /** Resolve a Fieldref or Methodref to [className, name, descriptor] */
    static String[] resolveRef(int refIndex) {
        int[] ref = (int[]) cp[refIndex];
        String cls = resolveClassNameRaw(ref[1]);
        int[] nat = (int[]) cp[ref[2]];
        String name = cpUtf8(nat[1]);
        String desc = cpUtf8(nat[2]);
        return new String[]{cls, name, desc};
    }

    // =========================================================================
    // LLVM IR Generation
    // =========================================================================

    static void generateLLVM() {
        // First pass: collect all string constants from all methods
        collectStringConstants();

        emitModuleHeader();
        emitStringConstants();
        emitStaticFields();
        emit("");

        // Emit native method declarations
        for (MethodInfo m : methods) {
            if ((m.accessFlags & 0x0100) != 0) { // ACC_NATIVE
                String mangledName = mangleName(className, m.name, m.descriptor);
                String retType = returnTypeFromDescriptor(m.descriptor);
                List<String[]> params = parseParams(m.descriptor);
                registerExtern(retType, mangledName, params);
            }
        }

        // Emit functions
        for (MethodInfo m : methods) {
            if (m.name.equals("<init>")) continue; // skip constructor
            if ((m.accessFlags & 0x0100) != 0) continue; // skip native methods (ACC_NATIVE)
            if (m.code == null) continue;
            emitFunction(m);
            emit("");
        }

        emitExternDeclarations();
    }

    static void emitModuleHeader() {
        emit("; ModuleID = '" + className + "'");
        emit("source_filename = \"" + className + ".java\"");
        emit("target triple = \"x86_64-unknown-linux-gnu\"");
        emit("");
    }

    static void emitStringConstants() {
        if (stringConstants.isEmpty()) return;
        emit("; ====== String Constants ======");
        for (Map.Entry<String, Integer> e : stringConstants.entrySet()) {
            String value = e.getKey();
            int idx = e.getValue();
            byte[] bytes;
            try { bytes = value.getBytes("UTF-8"); } catch (Exception ex) { bytes = value.getBytes(); }
            int len = bytes.length + 1; // +1 for null terminator
            emit("@str." + idx + " = private unnamed_addr constant [" + len + " x i8] c\""
                + escapeLLVMString(bytes) + "\\00\"");
        }
        emit("");
    }

    static void emitStaticFields() {
        emit("; ====== Static Fields ======");
        for (FieldInfo f : fields) {
            if ((f.accessFlags & 0x0008) == 0) continue; // only static
            if ((f.accessFlags & 0x0010) != 0) continue; // skip final constants (inlined by javac)
            String llType = descriptorToLLVMType(f.descriptor);
            String globalName = "@" + className + "_" + f.name;
            emit(globalName + " = dso_local global " + llType + " " + defaultValue(llType));
        }
    }

    static Set<String> externFunctions = new LinkedHashSet<>();

    static void emitExternDeclarations() {
        emit("; ====== External Declarations ======");
        for (String decl : externFunctions) {
            emit(decl);
        }
    }

    // =========================================================================
    // Function emission
    // =========================================================================

    static void emitFunction(MethodInfo m) {
        ssaCounter = 0;
        stack.clear();
        stackTypes.clear();

        String mangledName = mangleName(className, m.name, m.descriptor);
        String retType = returnTypeFromDescriptor(m.descriptor);
        List<String[]> params = parseParams(m.descriptor);

        // Build function signature
        StringBuilder sig = new StringBuilder();
        sig.append("define dso_local ").append(retType).append(" @").append(mangledName).append("(");
        int paramSlot = 0;
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(params.get(i)[0]).append(" %arg.").append(paramSlot);
            paramSlot += params.get(i)[0].equals("i64") ? 2 : 1;
        }
        sig.append(") {");
        emit(sig.toString());

        // Entry block: allocate locals and store params
        emit("entry:");
        char[] localTypes = inferLocalTypes(m);
        for (int i = 0; i < m.maxLocals; i++) {
            String llType = localTypeToLLVM(localTypes[i]);
            emit("  %local." + i + " = alloca " + llType);
        }

        // Store parameters into locals
        paramSlot = 0;
        for (String[] param : params) {
            String llType = param[0];
            String localType = localTypeToLLVM(localTypes[paramSlot]);
            if (llType.equals(localType)) {
                emit("  store " + llType + " %arg." + paramSlot + ", " + llType + "* %local." + paramSlot);
            } else {
                // Type mismatch — cast appropriately
                String tmp = nextSSA();
                if (llType.equals("i1") && localType.equals("i32")) {
                    // Boolean to int: zero extend
                    emit("  " + tmp + " = zext i1 %arg." + paramSlot + " to i32");
                } else if (llType.equals("i32") && localType.equals("i1")) {
                    // Int to boolean: truncate
                    emit("  " + tmp + " = trunc i32 %arg." + paramSlot + " to i1");
                } else {
                    emit("  " + tmp + " = bitcast " + llType + " %arg." + paramSlot + " to " + localType);
                }
                emit("  store " + localType + " " + tmp + ", " + localType + "* %local." + paramSlot);
            }
            paramSlot += llType.equals("i64") ? 2 : 1;
        }

        // If this is startKernel, insert clinit call
        if (m.name.equals("startKernel")) {
            // Check if clinit exists
            for (MethodInfo mm : methods) {
                if (mm.name.equals("<clinit>") && mm.code != null) {
                    emit("  call void @" + className + "_clinit_V()");
                    break;
                }
            }
        }

        emit("  br label %bb_0");

        // Translate bytecodes
        translateMethod(m, localTypes, retType);

        emit("}");
    }

    // =========================================================================
    // Bytecode translation
    // =========================================================================

    static boolean lastWasTerminator;

    static void translateMethod(MethodInfo m, char[] localTypes, String retType) {
        byte[] code = m.code;
        Set<Integer> blockStarts = discoverBasicBlocks(code);
        predecessorMap = buildPredecessorMap(code, blockStarts);
        blockExitStacks = new HashMap<>();
        blockExitStackTypes = new HashMap<>();
        currentBlockStart = -1;
        lastWasTerminator = true; // entry br is a terminator

        int pc = 0;
        while (pc < code.length) {
            if (blockStarts.contains(pc)) {
                // Save exit stack of the block we just finished
                if (currentBlockStart >= 0) {
                    blockExitStacks.put(currentBlockStart, snapshotStack());
                    blockExitStackTypes.put(currentBlockStart, snapshotStackTypes());
                }

                // Insert fall-through branch if previous block didn't end with terminator
                if (!lastWasTerminator) {
                    emit("  br label %bb_" + pc);
                }
                emit("bb_" + pc + ":");
                lastWasTerminator = false;
                currentBlockStart = pc;

                // Compute entry stack for this block
                Set<Integer> preds = predecessorMap.get(pc);
                if (preds != null && preds.size() > 1) {
                    // Merge block: emit phi nodes
                    emitPhiNodes(pc);
                } else if (preds != null && preds.size() == 1) {
                    // Single predecessor: restore its exit stack
                    int pred = preds.iterator().next();
                    if (blockExitStacks.containsKey(pred)) {
                        restoreStack(blockExitStacks.get(pred), blockExitStackTypes.get(pred));
                    } else {
                        stack.clear();
                        stackTypes.clear();
                    }
                } else {
                    // Entry block or unreachable
                    stack.clear();
                    stackTypes.clear();
                }
            }

            int op = code[pc] & 0xFF;
            switch (op) {
                // ===== Constants =====
                case 0x02: // iconst_m1
                    push("-1", 'i'); pc++; break;
                case 0x03: // iconst_0
                    push("0", 'i'); pc++; break;
                case 0x04: // iconst_1
                    push("1", 'i'); pc++; break;
                case 0x05: // iconst_2
                    push("2", 'i'); pc++; break;
                case 0x06: // iconst_3
                    push("3", 'i'); pc++; break;
                case 0x07: // iconst_4
                    push("4", 'i'); pc++; break;
                case 0x08: // iconst_5
                    push("5", 'i'); pc++; break;
                case 0x09: // lconst_0
                    push("0", 'l'); pc++; break;
                case 0x0A: // lconst_1
                    push("1", 'l'); pc++; break;
                case 0x10: // bipush
                    push(String.valueOf(code[pc + 1]), 'i'); pc += 2; break;
                case 0x11: // sipush
                    push(String.valueOf((short)((code[pc+1] & 0xFF) << 8 | (code[pc+2] & 0xFF))), 'i');
                    pc += 3; break;
                case 0x12: // ldc
                    translateLdc(code[pc + 1] & 0xFF);
                    pc += 2; break;
                case 0x13: // ldc_w
                    translateLdc(((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
                    pc += 3; break;
                case 0x14: // ldc2_w
                    translateLdc2w(((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
                    pc += 3; break;

                // ===== Int loads =====
                case 0x15: // iload
                    translateIload(code[pc + 1] & 0xFF, localTypes);
                    pc += 2; break;
                case 0x1A: // iload_0
                    translateIload(0, localTypes); pc++; break;
                case 0x1B: // iload_1
                    translateIload(1, localTypes); pc++; break;
                case 0x1C: // iload_2
                    translateIload(2, localTypes); pc++; break;
                case 0x1D: // iload_3
                    translateIload(3, localTypes); pc++; break;

                // ===== Long loads =====
                case 0x16: // lload
                    translateLload(code[pc + 1] & 0xFF); pc += 2; break;
                case 0x1E: // lload_0
                    translateLload(0); pc++; break;
                case 0x1F: // lload_1
                    translateLload(1); pc++; break;
                case 0x20: // lload_2
                    translateLload(2); pc++; break;
                case 0x21: // lload_3
                    translateLload(3); pc++; break;

                // ===== Reference loads =====
                case 0x19: // aload
                    translateAload(code[pc + 1] & 0xFF); pc += 2; break;
                case 0x2A: // aload_0
                    translateAload(0); pc++; break;
                case 0x2B: // aload_1
                    translateAload(1); pc++; break;
                case 0x2C: // aload_2
                    translateAload(2); pc++; break;
                case 0x2D: // aload_3
                    translateAload(3); pc++; break;

                // ===== Int stores =====
                case 0x36: // istore
                    translateIstore(code[pc + 1] & 0xFF, localTypes);
                    pc += 2; break;
                case 0x3B: // istore_0
                    translateIstore(0, localTypes); pc++; break;
                case 0x3C: // istore_1
                    translateIstore(1, localTypes); pc++; break;
                case 0x3D: // istore_2
                    translateIstore(2, localTypes); pc++; break;
                case 0x3E: // istore_3
                    translateIstore(3, localTypes); pc++; break;

                // ===== Long stores =====
                case 0x37: // lstore
                    translateLstore(code[pc + 1] & 0xFF); pc += 2; break;
                case 0x3F: // lstore_0
                    translateLstore(0); pc++; break;
                case 0x40: // lstore_1
                    translateLstore(1); pc++; break;
                case 0x41: // lstore_2
                    translateLstore(2); pc++; break;
                case 0x42: // lstore_3
                    translateLstore(3); pc++; break;

                // ===== Reference stores =====
                case 0x3A: // astore
                    translateAstore(code[pc + 1] & 0xFF); pc += 2; break;
                case 0x4B: // astore_0
                    translateAstore(0); pc++; break;
                case 0x4C: // astore_1
                    translateAstore(1); pc++; break;
                case 0x4D: // astore_2
                    translateAstore(2); pc++; break;
                case 0x4E: // astore_3
                    translateAstore(3); pc++; break;

                // ===== Arithmetic =====
                case 0x60: // iadd
                    translateBinOp("add", "i32"); pc++; break;
                case 0x61: // ladd
                    translateBinOp("add", "i64"); pc++; break;
                case 0x64: // isub
                    translateBinOp("sub", "i32"); pc++; break;
                case 0x65: // lsub
                    translateBinOp("sub", "i64"); pc++; break;
                case 0x6C: // idiv
                    translateBinOp("sdiv", "i32"); pc++; break;
                case 0x6D: // ldiv
                    translateBinOp("sdiv", "i64"); pc++; break;
                case 0x70: // irem
                    translateBinOp("srem", "i32"); pc++; break;
                case 0x71: // lrem
                    translateBinOp("srem", "i64"); pc++; break;
                case 0x68: // imul
                    translateBinOp("mul", "i32"); pc++; break;
                case 0x69: // lmul
                    translateBinOp("mul", "i64"); pc++; break;
                case 0x7E: // iand
                    translateBinOp("and", "i32"); pc++; break;
                case 0x7F: // land
                    translateBinOp("and", "i64"); pc++; break;
                case 0x80: // ior
                    translateBinOp("or", "i32"); pc++; break;
                case 0x81: // lor
                    translateBinOp("or", "i64"); pc++; break;
                case 0x82: // ixor
                    translateBinOp("xor", "i32"); pc++; break;
                case 0x83: // lxor
                    translateBinOp("xor", "i64"); pc++; break;
                case 0x78: // ishl
                    translateBinOp("shl", "i32"); pc++; break;
                case 0x79: // lshl
                    translateBinOp("shl", "i64"); pc++; break;
                case 0x7A: // ishr
                    translateBinOp("ashr", "i32"); pc++; break;
                case 0x7B: // lshr
                    translateBinOp("ashr", "i64"); pc++; break;
                case 0x7C: // iushr
                    translateBinOp("lshr", "i32"); pc++; break;
                case 0x7D: // lushr
                    translateBinOp("lshr", "i64"); pc++; break;

                // ===== Type conversions =====
                case 0x85: // i2l
                {
                    String val = pop();
                    String r = nextSSA();
                    emit("  " + r + " = sext i32 " + val + " to i64");
                    push(r, 'l');
                    pc++; break;
                }
                case 0x88: // l2i
                {
                    String val = pop();
                    String r = nextSSA();
                    emit("  " + r + " = trunc i64 " + val + " to i32");
                    push(r, 'i');
                    pc++; break;
                }
                case 0x91: // i2b
                {
                    String val = pop();
                    String r1 = nextSSA();
                    String r2 = nextSSA();
                    emit("  " + r1 + " = trunc i32 " + val + " to i8");
                    emit("  " + r2 + " = sext i8 " + r1 + " to i32");
                    push(r2, 'i');
                    pc++; break;
                }
                case 0x92: // i2c
                {
                    String val = pop();
                    String r = nextSSA();
                    emit("  " + r + " = and i32 " + val + ", 65535");
                    push(r, 'i');
                    pc++; break;
                }
                case 0x93: // i2s
                {
                    String val = pop();
                    String r1 = nextSSA();
                    String r2 = nextSSA();
                    emit("  " + r1 + " = trunc i32 " + val + " to i16");
                    emit("  " + r2 + " = sext i16 " + r1 + " to i32");
                    push(r2, 'i');
                    pc++; break;
                }

                // ===== iinc =====
                case 0x84: // iinc
                {
                    int idx = code[pc + 1] & 0xFF;
                    int incr = code[pc + 2]; // signed byte
                    String llType = localTypeToLLVM(localTypes[idx]);
                    String tmp = nextSSA();
                    emit("  " + tmp + " = load " + llType + ", " + llType + "* %local." + idx);
                    String r = nextSSA();
                    emit("  " + r + " = add " + llType + " " + tmp + ", " + incr);
                    emit("  store " + llType + " " + r + ", " + llType + "* %local." + idx);
                    pc += 3; break;
                }

                // ===== Comparisons / Branches =====
                case 0x99: // ifeq
                    translateIfZero("eq", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0x9A: // ifne
                    translateIfZero("ne", pc, readS2(code, pc + 1)); pc += 3; break;
                

                case 0x9B: // iflt
                    translateIfZero("slt", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0x9C: // ifge
                    translateIfZero("sge", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0x9D: // ifgt
                    translateIfZero("sgt", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0x9E: // ifle
                    translateIfZero("sle", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0x9F: // if_icmpeq
                    translateIfIcmp("eq", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0xA0: // if_icmpne
                    translateIfIcmp("ne", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0xA1: // if_icmplt
                    translateIfIcmp("slt", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0xA2: // if_icmpge
                    translateIfIcmp("sge", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0xA3: // if_icmpgt
                    translateIfIcmp("sgt", pc, readS2(code, pc + 1)); pc += 3; break;
                case 0xA4: // if_icmple
                    translateIfIcmp("sle", pc, readS2(code, pc + 1)); pc += 3; break;

                // ===== Long comparison =====
                case 0x94: // lcmp
                {
                    String b = pop();
                    String a = pop();
                    String cmpEq = nextSSA();
                    String cmpLt = nextSSA();
                    String sel1 = nextSSA();
                    String sel2 = nextSSA();
                    emit("  " + cmpEq + " = icmp eq i64 " + a + ", " + b);
                    emit("  " + cmpLt + " = icmp slt i64 " + a + ", " + b);
                    emit("  " + sel1 + " = select i1 " + cmpLt + ", i32 -1, i32 1");
                    emit("  " + sel2 + " = select i1 " + cmpEq + ", i32 0, i32 " + sel1);
                    push(sel2, 'i');
                    pc++; break;
                }
                
                // Special case: icmp eq followed by istore to boolean
                // We handle this by tracking the comparison for boolean use
                
                // ===== Boolean comparison shortcuts =====
                // These avoid the lcmp pattern when result is used as boolean
                case 0x95: // fcmpl  (simplified - just push 0/1)
                case 0x96: // fcmpg
                {
                    pop(); pop();  // Discard operands
                    push("0", 'i');  // Just say equal for now
                    pc++; break;
                }
                case 0x97: // dcmpl
                case 0x98: // dcmpg
                {
                    pop(); pop();  // Discard operands  
                    push("0", 'i');  // Just say equal for now
                    pc++; break;
                }

                // ===== Null checks =====
                case 0xC6: // ifnull
                {
                    String val = pop();
                    int target = pc + readS2(code, pc + 1);
                    String cmp = nextSSA();
                    emit("  " + cmp + " = icmp eq i8* " + val + ", null");
                    emit("  br i1 " + cmp + ", label %bb_" + target + ", label %bb_" + (pc + 3));
                    pc += 3; break;
                }
                case 0xC7: // ifnonnull
                {
                    String val = pop();
                    int target = pc + readS2(code, pc + 1);
                    String cmp = nextSSA();
                    emit("  " + cmp + " = icmp ne i8* " + val + ", null");
                    emit("  br i1 " + cmp + ", label %bb_" + target + ", label %bb_" + (pc + 3));
                    pc += 3; break;
                }

                // ===== goto =====
                case 0xA7: // goto
                {
                    int target = pc + readS2(code, pc + 1);
                    emit("  br label %bb_" + target);
                    pc += 3; break;
                }

                // ===== Returns =====
                case 0xB1: // return (void)
                    emit("  ret void");
                    pc++; break;
                case 0xAC: // ireturn
                {
                    String val = pop();
                    if (retType.equals("i1")) {
                        // Boolean return - truncate i32 to i1
                        String tmp = nextSSA();
                        emit("  " + tmp + " = trunc i32 " + val + " to i1");
                        emit("  ret i1 " + tmp);
                    } else {
                        emit("  ret i32 " + val);
                    }
                    pc++; break;
                }
                case 0xAD: // lreturn
                {
                    String val = pop();
                    emit("  ret i64 " + val);
                    pc++; break;
                }
                case 0xB0: // areturn
                {
                    String val = pop();
                    emit("  ret i8* " + val);
                    pc++; break;
                }

                // ===== Field access =====
                case 0xB2: // getstatic
                {
                    int idx = readU2(code, pc + 1);
                    String[] ref = resolveRef(idx);
                    String fieldClass = ref[0].replace('/', '_');
                    String fieldName = ref[1];
                    String fieldDesc = ref[2];
                    String llType = descriptorToLLVMType(fieldDesc);
                    String globalName = "@" + fieldClass + "_" + fieldName;
                    String r = nextSSA();
                    emit("  " + r + " = load " + llType + ", " + llType + "* " + globalName);
                    push(r, llvmTypeChar(fieldDesc));
                    pc += 3; break;
                }
                case 0xB3: // putstatic
                {
                    int idx = readU2(code, pc + 1);
                    String[] ref = resolveRef(idx);
                    String fieldClass = ref[0].replace('/', '_');
                    String fieldName = ref[1];
                    String fieldDesc = ref[2];
                    String llType = descriptorToLLVMType(fieldDesc);
                    String globalName = "@" + fieldClass + "_" + fieldName;
                    String val = pop();
                    emit("  store " + llType + " " + val + ", " + llType + "* " + globalName);
                    pc += 3; break;
                }

                // ===== Method calls =====
                case 0xB6: // invokevirtual
                {
                    int idx = readU2(code, pc + 1);
                    translateInvokeVirtual(idx);
                    pc += 3; break;
                }
                case 0xB7: // invokespecial
                {
                    int idx = readU2(code, pc + 1);
                    String[] ref = resolveRef(idx);
                    // Skip Object.<init> calls
                    if (ref[1].equals("<init>")) {
                        pop(); // pop 'this'
                    }
                    pc += 3; break;
                }
                case 0xB8: // invokestatic
                {
                    int idx = readU2(code, pc + 1);
                    translateInvokeStatic(idx);
                    pc += 3; break;
                }

                // ===== Stack manipulation =====
                case 0x57: // pop
                    pop(); pc++; break;
                case 0x59: // dup
                {
                    String val = stack.peek();
                    char type = stackTypes.peek();
                    push(val, type);
                    pc++; break;
                }

                default:
                    throw new RuntimeException("Unsupported opcode 0x"
                        + Integer.toHexString(op) + " at pc=" + pc
                        + " in method " + m.name);
            }

            // Track if this instruction was a terminator
            if (isTerminator(op)) {
                lastWasTerminator = true;
            }
        }
    }

    static boolean isTerminator(int op) {
        switch (op) {
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: // ifXX
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: // if_icmpXX
            case 0xC6: case 0xC7: // ifnull, ifnonnull
            case 0xA7: // goto
            case 0xAC: case 0xAD: case 0xAE: case 0xAF: case 0xB0: case 0xB1: // returns
                return true;
            default:
                return false;
        }
    }

    // =========================================================================
    // Helper methods for bytecode translation
    // =========================================================================

    static void translateIload(int idx, char[] localTypes) {
        String llType = localTypeToLLVM(localTypes[idx]);
        String r = nextSSA();
        emit("  " + r + " = load " + llType + ", " + llType + "* %local." + idx);
        // If loading boolean (i1), zext to i32 for Java stack
        if (llType.equals("i1")) {
            String ext = nextSSA();
            emit("  " + ext + " = zext i1 " + r + " to i32");
            push(ext, 'i');
        } else {
            push(r, 'i');
        }
    }

    static void translateLload(int idx) {
        String r = nextSSA();
        emit("  " + r + " = load i64, i64* %local." + idx);
        push(r, 'l');
    }

    static void translateAload(int idx) {
        String r = nextSSA();
        emit("  " + r + " = load i8*, i8** %local." + idx);
        push(r, 'p');
    }

    static void translateIstore(int idx, char[] localTypes) {
        String val = pop();
        String llType = localTypeToLLVM(localTypes[idx]);
        // If storing to boolean (i1) but value is i32, convert it
        if (llType.equals("i1")) {
            String cmp = nextSSA();
            emit("  " + cmp + " = icmp ne i32 " + val + ", 0");
            val = cmp;
        }
        emit("  store " + llType + " " + val + ", " + llType + "* %local." + idx);
    }

    static void translateLstore(int idx) {
        String val = pop();
        emit("  store i64 " + val + ", i64* %local." + idx);
    }

    static void translateAstore(int idx) {
        String val = pop();
        emit("  store i8* " + val + ", i8** %local." + idx);
    }

    static void translateBinOp(String op, String type) {
        String b = pop();
        String a = pop();
        // For 64-bit shifts, the shift amount (b) is int but needs to be i64 in LLVM
        if (type.equals("i64") && (op.equals("shl") || op.equals("ashr") || op.equals("lshr"))) {
            String bExt = nextSSA();
            emit("  " + bExt + " = zext i32 " + b + " to i64");
            b = bExt;
        }
        String r = nextSSA();
        emit("  " + r + " = " + op + " " + type + " " + a + ", " + b);
        push(r, type.equals("i64") ? 'l' : 'i');
    }

    static void translateIfIcmp(String cond, int pc, int offset) {
        char typeB = stackTypes.pop();
        String b = stack.pop();
        char typeA = stackTypes.pop();
        String a = stack.pop();
        int target = pc + offset;
        int fallthrough = pc + 3;
        String cmp = nextSSA();
        // Use i64 if either operand is long, otherwise i32
        if (typeA == 'l' || typeB == 'l') {
            emit("  " + cmp + " = icmp " + cond + " i64 " + a + ", " + b);
        } else {
            emit("  " + cmp + " = icmp " + cond + " i32 " + a + ", " + b);
        }
        emit("  br i1 " + cmp + ", label %bb_" + target + ", label %bb_" + fallthrough);
    }

    static void translateIfZero(String cond, int pc, int offset) {
        char type = stackTypes.pop();
        String a = stack.pop();
        int target = pc + offset;
        int fallthrough = pc + 3;
        String cmp = nextSSA();
        if (type == 'l') {
            // Long comparison - use i64
            emit("  " + cmp + " = icmp " + cond + " i64 " + a + ", 0");
        } else {
            // Int comparison - use i32
            emit("  " + cmp + " = icmp " + cond + " i32 " + a + ", 0");
        }
        emit("  br i1 " + cmp + ", label %bb_" + target + ", label %bb_" + fallthrough);
    }

    static void translateLdc(int cpIdx) {
        Object entry = cp[cpIdx];
        if (entry instanceof Integer) {
            push(String.valueOf((int) entry), 'i');
        } else if (entry instanceof int[]) {
            int[] arr = (int[]) entry;
            if (arr[0] == CP_STRING) {
                String strValue = cpUtf8(arr[1]);
                int idx = getStringConstantIndex(strValue);
                byte[] bytes;
                try { bytes = strValue.getBytes("UTF-8"); } catch (Exception e) { bytes = strValue.getBytes(); }
                int len = bytes.length + 1;
                String r = nextSSA();
                emit("  " + r + " = getelementptr inbounds [" + len + " x i8], ["
                    + len + " x i8]* @str." + idx + ", i64 0, i64 0");
                push(r, 'p');
            }
        }
    }

    static void translateLdc2w(int cpIdx) {
        Object entry = cp[cpIdx];
        if (entry instanceof Long) {
            push(String.valueOf((long) entry), 'l');
        }
    }

    static void translateInvokeStatic(int cpIdx) {
        String[] ref = resolveRef(cpIdx);
        String cls = ref[0].replace('/', '_');
        String methodName = ref[1];
        String desc = ref[2];

        String mangledName;
        if (methodName.equals("<clinit>")) {
            mangledName = cls + "_clinit_V";
        } else {
            mangledName = mangleName(cls, methodName, desc);
        }

        List<String[]> params = parseParams(desc);
        String retType = returnTypeFromDescriptor(desc);

        // Pop arguments in reverse order
        String[] argValues = new String[params.size()];
        String[] argTypes = new String[params.size()];
        for (int i = params.size() - 1; i >= 0; i--) {
            argValues[i] = pop();
            argTypes[i] = params.get(i)[0];
        }

        // Build call
        StringBuilder call = new StringBuilder();
        if (!retType.equals("void")) {
            String r = nextSSA();
            call.append("  ").append(r).append(" = call ").append(retType)
                .append(" @").append(mangledName).append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) call.append(", ");
                call.append(argTypes[i]).append(" ").append(argValues[i]);
            }
            call.append(")");
            emit(call.toString());
            // If return type is boolean (i1), zero-extend to i32 for Java compatibility
            if (retType.equals("i1")) {
                String ext = nextSSA();
                emit("  " + ext + " = zext i1 " + r + " to i32");
                push(ext, 'i');
            } else {
                push(r, retType.equals("i64") ? 'l' : retType.equals("i8*") ? 'p' : 'i');
            }
        } else {
            call.append("  call void @").append(mangledName).append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) call.append(", ");
                call.append(argTypes[i]).append(" ").append(argValues[i]);
            }
            call.append(")");
            emit(call.toString());
        }

        // Register as extern if not in our class
        if (!cls.equals(className)) {
            registerExtern(retType, mangledName, params);
        }
    }

    static void translateInvokeVirtual(int cpIdx) {
        String[] ref = resolveRef(cpIdx);
        String cls = ref[0];
        String methodName = ref[1];
        String desc = ref[2];

        if (cls.equals("java/lang/String")) {
            List<String[]> params = parseParams(desc);
            // Pop args (excluding receiver)
            String[] argValues = new String[params.size()];
            String[] argTypes = new String[params.size()];
            for (int i = params.size() - 1; i >= 0; i--) {
                argValues[i] = pop();
                argTypes[i] = params.get(i)[0];
            }
            String receiver = pop(); // the String (i8*)

            if (methodName.equals("length") && desc.equals("()I")) {
                String r = nextSSA();
                emit("  " + r + " = call i32 @java_lang_String_length_Int(i8* " + receiver + ")");
                push(r, 'i');
                registerExtern("i32", "java_lang_String_length_Int",
                    Collections.singletonList(new String[]{"i8*", "str"}));
            } else if (methodName.equals("charAt") && desc.equals("(I)C")) {
                String r = nextSSA();
                emit("  " + r + " = call i32 @java_lang_String_charAt_Int_retChar(i8* "
                    + receiver + ", i32 " + argValues[0] + ")");
                push(r, 'i');
                List<String[]> extParams = new ArrayList<>();
                extParams.add(new String[]{"i8*", "str"});
                extParams.add(new String[]{"i32", "index"});
                registerExtern("i32", "java_lang_String_charAt_Int_retChar", extParams);
            } else {
                throw new RuntimeException("Unsupported invokevirtual: " + cls + "." + methodName + desc);
            }
        } else {
            throw new RuntimeException("Unsupported invokevirtual on class: " + cls);
        }
    }

    static void registerExtern(String retType, String name, List<String[]> params) {
        StringBuilder decl = new StringBuilder();
        decl.append("declare dso_local ").append(retType).append(" @").append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) decl.append(", ");
            decl.append(params.get(i)[0]);
        }
        decl.append(")");
        externFunctions.add(decl.toString());
    }

    // =========================================================================
    // Basic block discovery
    // =========================================================================

    static Set<Integer> discoverBasicBlocks(byte[] code) {
        Set<Integer> starts = new TreeSet<>();
        starts.add(0);

        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xFF;
            switch (op) {
                // Conditional branches
                case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: // ifXX
                case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: // if_icmpXX
                case 0xC6: case 0xC7: // ifnull, ifnonnull
                {
                    int offset = readS2(code, pc + 1);
                    starts.add(pc + offset);
                    starts.add(pc + 3);
                    pc += 3; break;
                }
                // goto
                case 0xA7:
                {
                    int offset = readS2(code, pc + 1);
                    starts.add(pc + offset);
                    if (pc + 3 < code.length) starts.add(pc + 3);
                    pc += 3; break;
                }
                // return instructions
                case 0xAC: case 0xAD: case 0xAE: case 0xAF: case 0xB0: case 0xB1:
                    if (pc + 1 < code.length) starts.add(pc + 1);
                    pc++; break;
                default:
                    pc += opcodeLength(op, code, pc);
                    break;
            }
        }
        return starts;
    }

    // =========================================================================
    // Predecessor map construction (for phi node generation)
    // =========================================================================

    static Map<Integer, Set<Integer>> buildPredecessorMap(byte[] code, Set<Integer> blockStarts) {
        Map<Integer, Set<Integer>> preds = new HashMap<>();
        for (int start : blockStarts) {
            preds.put(start, new LinkedHashSet<>());
        }

        Integer[] sortedStarts = blockStarts.toArray(new Integer[0]);
        Arrays.sort(sortedStarts);

        for (int bi = 0; bi < sortedStarts.length; bi++) {
            int blockStart = sortedStarts[bi];
            int blockEnd = (bi + 1 < sortedStarts.length) ? sortedStarts[bi + 1] : code.length;

            // Find the last instruction in this block
            int lastPC = blockStart;
            int tmpPC = blockStart;
            while (tmpPC < blockEnd) {
                lastPC = tmpPC;
                tmpPC += opcodeLength(code[tmpPC] & 0xFF, code, tmpPC);
            }

            int lastOp = code[lastPC] & 0xFF;

            if (isConditionalBranch(lastOp)) {
                int target = lastPC + readS2(code, lastPC + 1);
                int fallthrough = lastPC + 3;
                if (preds.containsKey(target)) preds.get(target).add(blockStart);
                if (preds.containsKey(fallthrough)) preds.get(fallthrough).add(blockStart);
            } else if (lastOp == 0xA7) { // goto
                int target = lastPC + readS2(code, lastPC + 1);
                if (preds.containsKey(target)) preds.get(target).add(blockStart);
            } else if (isTerminator(lastOp)) {
                // return/athrow: no successors
            } else {
                // Fall-through to next block
                if (bi + 1 < sortedStarts.length) {
                    preds.get(sortedStarts[bi + 1]).add(blockStart);
                }
            }
        }
        return preds;
    }

    static boolean isConditionalBranch(int op) {
        switch (op) {
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: // ifXX
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: // if_icmpXX
            case 0xC6: case 0xC7: // ifnull, ifnonnull
                return true;
            default:
                return false;
        }
    }

    // =========================================================================
    // String constant collection (first pass)
    // =========================================================================

    static void collectStringConstants() {
        for (MethodInfo m : methods) {
            if (m.code == null) continue;
            byte[] code = m.code;
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                if (op == 0x12) { // ldc
                    int idx = code[pc + 1] & 0xFF;
                    Object entry = cp[idx];
                    if (entry instanceof int[]) {
                        int[] arr = (int[]) entry;
                        if (arr[0] == CP_STRING) {
                            getStringConstantIndex(cpUtf8(arr[1]));
                        }
                    }
                } else if (op == 0x13) { // ldc_w
                    int idx = ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
                    Object entry = cp[idx];
                    if (entry instanceof int[]) {
                        int[] arr = (int[]) entry;
                        if (arr[0] == CP_STRING) {
                            getStringConstantIndex(cpUtf8(arr[1]));
                        }
                    }
                }
                pc += opcodeLength(op, code, pc);
            }
        }
    }

    static int getStringConstantIndex(String value) {
        Integer idx = stringConstants.get(value);
        if (idx != null) return idx;
        int newIdx = stringIndex++;
        stringConstants.put(value, newIdx);
        return newIdx;
    }

    // =========================================================================
    // Operand stack
    // =========================================================================

    static void push(String ssa, char type) {
        stack.push(ssa);
        stackTypes.push(type);
    }

    static String pop() {
        stackTypes.pop();
        return stack.pop();
    }

    static String nextSSA() {
        return "%" + (ssaCounter++);
    }

    // =========================================================================
    // Per-block stack save/restore and phi node emission
    // =========================================================================

    static List<String> snapshotStack() {
        return new ArrayList<>(stack); // top-first order
    }

    static List<Character> snapshotStackTypes() {
        return new ArrayList<>(stackTypes);
    }

    static void restoreStack(List<String> values, List<Character> types) {
        stack.clear();
        stackTypes.clear();
        // Push bottom-to-top (reverse of saved top-first order)
        for (int i = values.size() - 1; i >= 0; i--) {
            stack.push(values.get(i));
            stackTypes.push(types.get(i));
        }
    }

    static String typeCharToLLVM(char type) {
        switch (type) {
            case 'l': return "i64";
            case 'p': return "i8*";
            default:  return "i32";
        }
    }

    /** Emit phi nodes at a merge block and set up the operand stack. */
    static void emitPhiNodes(int blockPC) {
        Set<Integer> preds = predecessorMap.get(blockPC);
        if (preds == null || preds.size() < 2) return;

        // Collect exit stacks from processed predecessors only
        List<Integer> availablePreds = new ArrayList<>();
        for (int pred : preds) {
            if (blockExitStacks.containsKey(pred)) {
                availablePreds.add(pred);
            }
        }
        if (availablePreds.size() < 2) {
            // Not enough predecessors processed — use single predecessor path
            if (availablePreds.size() == 1) {
                restoreStack(blockExitStacks.get(availablePreds.get(0)),
                             blockExitStackTypes.get(availablePreds.get(0)));
            } else {
                stack.clear();
                stackTypes.clear();
            }
            return;
        }

        int depth = blockExitStacks.get(availablePreds.get(0)).size();
        // Verify consistent stack depth
        for (int pred : availablePreds) {
            if (blockExitStacks.get(pred).size() != depth) {
                // Inconsistent stack depth — clear and skip
                stack.clear();
                stackTypes.clear();
                return;
            }
        }

        stack.clear();
        stackTypes.clear();

        if (depth == 0) return; // Empty stacks — no phi needed

        // Process stack slots from bottom (index depth-1) to top (index 0)
        // so that push order yields correct final stack
        for (int slot = depth - 1; slot >= 0; slot--) {
            String firstValue = blockExitStacks.get(availablePreds.get(0)).get(slot);
            char firstType = blockExitStackTypes.get(availablePreds.get(0)).get(slot);

            // Check if all predecessors have the same value for this slot
            boolean allSame = true;
            for (int pi = 1; pi < availablePreds.size(); pi++) {
                if (!firstValue.equals(blockExitStacks.get(availablePreds.get(pi)).get(slot))) {
                    allSame = false;
                    break;
                }
            }

            if (allSame) {
                push(firstValue, firstType);
            } else {
                // Emit phi node
                String llType = typeCharToLLVM(firstType);
                String phiSSA = nextSSA();
                StringBuilder phiInst = new StringBuilder();
                phiInst.append("  ").append(phiSSA).append(" = phi ").append(llType).append(" ");
                for (int pi = 0; pi < availablePreds.size(); pi++) {
                    if (pi > 0) phiInst.append(", ");
                    String val = blockExitStacks.get(availablePreds.get(pi)).get(slot);
                    int predBlock = availablePreds.get(pi);
                    phiInst.append("[ ").append(val).append(", %bb_").append(predBlock).append(" ]");
                }
                emit(phiInst.toString());
                push(phiSSA, firstType);
            }
        }
    }

    // =========================================================================
    // Type mapping utilities
    // =========================================================================

    static String descriptorToLLVMType(String desc) {
        switch (desc.charAt(0)) {
            case 'I': return "i32";
            case 'J': return "i64";
            case 'C': return "i32"; // char is i32 in LLVM
            case 'B': return "i8";
            case 'S': return "i16";
            case 'Z': return "i1";
            case 'F': return "float";
            case 'D': return "double";
            case 'L': return "i8*"; // object reference
            case '[': return "i8*"; // array reference
            case 'V': return "void";
            default: return "i32";
        }
    }

    static char llvmTypeChar(String desc) {
        switch (desc.charAt(0)) {
            case 'J': return 'l';
            case 'L': case '[': return 'p';
            default: return 'i';
        }
    }

    static String returnTypeFromDescriptor(String desc) {
        int idx = desc.indexOf(')') + 1;
        return descriptorToLLVMType(desc.substring(idx));
    }

    static List<String[]> parseParams(String desc) {
        List<String[]> params = new ArrayList<>();
        int i = 1; // skip '('
        while (desc.charAt(i) != ')') {
            String llType;
            switch (desc.charAt(i)) {
                case 'I': llType = "i32"; i++; break;
                case 'J': llType = "i64"; i++; break;
                case 'C': llType = "i32"; i++; break;
                case 'B': llType = "i8"; i++; break;
                case 'S': llType = "i16"; i++; break;
                case 'Z': llType = "i1"; i++; break;
                case 'F': llType = "float"; i++; break;
                case 'D': llType = "double"; i++; break;
                case 'L':
                    llType = "i8*";
                    i = desc.indexOf(';', i) + 1;
                    break;
                case '[':
                    llType = "i8*";
                    while (desc.charAt(i) == '[') i++;
                    if (desc.charAt(i) == 'L') i = desc.indexOf(';', i) + 1; else i++;
                    break;
                default:
                    throw new RuntimeException("Unknown param type: " + desc.charAt(i));
            }
            params.add(new String[]{llType, "p" + params.size()});
        }
        return params;
    }

    static char[] inferLocalTypes(MethodInfo m) {
        char[] types = new char[m.maxLocals];
        Arrays.fill(types, 'i'); // default: int

        // From parameters
        int slot = 0;
        if ((m.accessFlags & 0x0008) == 0) { // not static -> slot 0 is 'this'
            types[slot++] = 'p';
        }
        String desc = m.descriptor;
        int i = 1;
        while (desc.charAt(i) != ')') {
            switch (desc.charAt(i)) {
                case 'I': case 'C': case 'B': case 'S': case 'Z':
                    types[slot++] = 'i'; i++; break;
                case 'J':
                    types[slot] = 'l'; slot += 2; i++; break;
                case 'F':
                    types[slot++] = 'i'; i++; break; // treat float as i32 for now
                case 'D':
                    types[slot] = 'l'; slot += 2; i++; break;
                case 'L':
                    types[slot++] = 'p'; i = desc.indexOf(';', i) + 1; break;
                case '[':
                    types[slot++] = 'p';
                    while (desc.charAt(i) == '[') i++;
                    if (desc.charAt(i) == 'L') i = desc.indexOf(';', i) + 1; else i++;
                    break;
            }
        }

        // Scan bytecodes for store types
        if (m.code != null) {
            byte[] code = m.code;
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                switch (op) {
                    case 0x36: types[code[pc+1] & 0xFF] = 'i'; break; // istore
                    case 0x37: types[code[pc+1] & 0xFF] = 'l'; break; // lstore
                    case 0x3A: types[code[pc+1] & 0xFF] = 'p'; break; // astore
                    case 0x3B: case 0x3C: case 0x3D: case 0x3E: // istore_0..3
                        types[op - 0x3B] = 'i'; break;
                    case 0x3F: case 0x40: case 0x41: case 0x42: // lstore_0..3
                        types[op - 0x3F] = 'l'; break;
                    case 0x4B: case 0x4C: case 0x4D: case 0x4E: // astore_0..3
                        types[op - 0x4B] = 'p'; break;
                }
                pc += opcodeLength(op, code, pc);
            }
        }
        return types;
    }

    static String localTypeToLLVM(char type) {
        switch (type) {
            case 'l': return "i64";
            case 'p': return "i8*";
            default: return "i32";
        }
    }

    static String defaultValue(String llType) {
        if (llType.equals("i8*")) return "null";
        return "0";
    }

    // =========================================================================
    // Name mangling
    // =========================================================================

    static String mangleName(String cls, String methodName, String desc) {
        if (methodName.equals("<clinit>")) return cls + "_clinit_V";

        StringBuilder sb = new StringBuilder();
        sb.append(cls).append('_').append(methodName);

        int i = 1; // skip '('
        boolean hasParams = false;
        while (desc.charAt(i) != ')') {
            sb.append('_');
            hasParams = true;
            switch (desc.charAt(i)) {
                case 'I': sb.append("Int"); i++; break;
                case 'J': sb.append("Long"); i++; break;
                case 'C': sb.append("Char"); i++; break;
                case 'B': sb.append("Byte"); i++; break;
                case 'S': sb.append("Short"); i++; break;
                case 'Z': sb.append("Boolean"); i++; break;
                case 'F': sb.append("Float"); i++; break;
                case 'D': sb.append("Double"); i++; break;
                case 'L': {
                    int semi = desc.indexOf(';', i);
                    String fqn = desc.substring(i + 1, semi);
                    String simple = fqn.substring(fqn.lastIndexOf('/') + 1);
                    sb.append(simple);
                    i = semi + 1;
                    break;
                }
                case '[': {
                    sb.append("Array");
                    while (desc.charAt(i) == '[') i++;
                    if (desc.charAt(i) == 'L') i = desc.indexOf(';', i) + 1; else i++;
                    break;
                }
            }
        }
        if (!hasParams) {
            sb.append("_V");
        }
        return sb.toString();
    }

    // =========================================================================
    // LLVM string escaping
    // =========================================================================

    static String escapeLLVMString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127 && c != '"' && c != '\\') {
                sb.append((char) c);
            } else {
                sb.append(String.format("\\%02X", c));
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // Bytecode reading utilities
    // =========================================================================

    static int readU2(byte[] code, int offset) {
        return ((code[offset] & 0xFF) << 8) | (code[offset + 1] & 0xFF);
    }

    static int readS2(byte[] code, int offset) {
        return (short)(((code[offset] & 0xFF) << 8) | (code[offset + 1] & 0xFF));
    }

    static int opcodeLength(int op, byte[] code, int pc) {
        switch (op) {
            // No operands (1 byte)
            case 0x00: // nop
            case 0x01: // aconst_null
            case 0x02: case 0x03: case 0x04: case 0x05: // iconst_m1..iconst_2
            case 0x06: case 0x07: case 0x08: // iconst_3..iconst_5
            case 0x09: case 0x0A: // lconst_0, lconst_1
            case 0x0B: case 0x0C: case 0x0D: case 0x0E: case 0x0F: // fconst, dconst
            case 0x1A: case 0x1B: case 0x1C: case 0x1D: // iload_0..3
            case 0x1E: case 0x1F: case 0x20: case 0x21: // lload_0..3
            case 0x22: case 0x23: case 0x24: case 0x25: // fload_0..3
            case 0x26: case 0x27: case 0x28: case 0x29: // dload_0..3
            case 0x2A: case 0x2B: case 0x2C: case 0x2D: // aload_0..3
            case 0x2E: case 0x2F: case 0x30: case 0x31: // iaload..saload
            case 0x32: case 0x33: case 0x34: case 0x35: // ?aload
            case 0x3B: case 0x3C: case 0x3D: case 0x3E: // istore_0..3
            case 0x3F: case 0x40: case 0x41: case 0x42: // lstore_0..3
            case 0x43: case 0x44: case 0x45: case 0x46: // fstore_0..3
            case 0x47: case 0x48: case 0x49: case 0x4A: // dstore_0..3
            case 0x4B: case 0x4C: case 0x4D: case 0x4E: // astore_0..3
            case 0x4F: case 0x50: case 0x51: case 0x52: // iastore..sastore
            case 0x53: case 0x54: case 0x55: case 0x56: // ?astore
            case 0x57: case 0x58: case 0x59: case 0x5A: // pop, pop2, dup, dup_x1
            case 0x5B: case 0x5C: case 0x5D: case 0x5E: case 0x5F: // dup_x2..swap
            case 0x60: case 0x61: case 0x62: case 0x63: // iadd..dsub
            case 0x64: case 0x65: case 0x66: case 0x67: // isub..dsub
            case 0x68: case 0x69: case 0x6A: case 0x6B: // imul..dmul
            case 0x6C: case 0x6D: case 0x6E: case 0x6F: // idiv..ddiv
            case 0x70: case 0x71: case 0x72: case 0x73: // irem..drem
            case 0x74: case 0x75: case 0x76: case 0x77: // ineg..dneg
            case 0x78: case 0x79: case 0x7A: case 0x7B: // ishl..lushr
            case 0x7C: case 0x7D: case 0x7E: case 0x7F: // iushr..land
            case 0x80: case 0x81: case 0x82: case 0x83: // ior..lxor
            case 0x85: case 0x86: case 0x87: case 0x88: // i2l..l2i
            case 0x89: case 0x8A: case 0x8B: case 0x8C: // l2f..f2l
            case 0x8D: case 0x8E: case 0x8F: case 0x90: // f2d..d2f
            case 0x91: case 0x92: case 0x93: // i2b, i2c, i2s
            case 0x94: case 0x95: case 0x96: case 0x97: case 0x98: // lcmp..dcmpg
            case 0xAC: case 0xAD: case 0xAE: case 0xAF: case 0xB0: case 0xB1: // returns
            case 0xBE: // arraylength
            case 0xBF: // athrow
            case 0xC2: case 0xC3: // monitorenter, monitorexit
                return 1;

            // 1 operand byte (2 bytes total)
            case 0x10: // bipush
            case 0x12: // ldc
            case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: // iload..aload
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: // istore..astore
            case 0xA9: // ret
            case 0xBC: // newarray
                return 2;

            // 2 operand bytes (3 bytes total)
            case 0x11: // sipush
            case 0x13: // ldc_w
            case 0x14: // ldc2_w
            case 0x84: // iinc (index, const)
            case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E: // ifXX
            case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4: // if_icmpXX
            case 0xA5: case 0xA6: // if_acmpXX
            case 0xA7: // goto
            case 0xA8: // jsr
            case 0xB2: case 0xB3: case 0xB4: case 0xB5: // getstatic..putfield
            case 0xB6: case 0xB7: case 0xB8: // invokevirtual..invokestatic
            case 0xBB: // new
            case 0xBD: // anewarray
            case 0xC0: // checkcast
            case 0xC1: // instanceof
            case 0xC6: case 0xC7: // ifnull, ifnonnull
                return 3;

            // Special lengths
            case 0xB9: return 5; // invokeinterface
            case 0xBA: return 5; // invokedynamic
            case 0xC5: return 4; // multianewarray
            case 0xC8: return 5; // goto_w
            case 0xC9: return 5; // jsr_w

            // Wide prefix
            case 0xC4: {
                int wideOp = code[pc + 1] & 0xFF;
                if (wideOp == 0x84) return 6; // wide iinc
                return 4; // wide load/store
            }

            // Tableswitch / lookupswitch - variable length
            case 0xAA: { // tableswitch
                int padded = (pc + 4) & ~3;
                int defaultOffset = padded;
                int low = readInt(code, padded + 4);
                int high = readInt(code, padded + 8);
                return (padded - pc) + 12 + (high - low + 1) * 4;
            }
            case 0xAB: { // lookupswitch
                int padded = (pc + 4) & ~3;
                int npairs = readInt(code, padded + 4);
                return (padded - pc) + 8 + npairs * 8;
            }

            default:
                throw new RuntimeException("Unknown opcode length for 0x" + Integer.toHexString(op));
        }
    }

    static int readInt(byte[] code, int offset) {
        return ((code[offset] & 0xFF) << 24) | ((code[offset+1] & 0xFF) << 16)
             | ((code[offset+2] & 0xFF) << 8) | (code[offset+3] & 0xFF);
    }

    static void emit(String line) {
        out.append(line).append('\n');
    }
}
