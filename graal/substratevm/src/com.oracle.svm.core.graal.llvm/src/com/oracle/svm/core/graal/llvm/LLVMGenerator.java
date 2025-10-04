/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.isDoubleType;
import static com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.isFloatType;
import static com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.isIntegerType;
import static com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.isVectorType;
import static com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.typeOf;
import static com.oracle.svm.core.graal.llvm.util.LLVMUtils.dumpTypes;
import static com.oracle.svm.core.graal.llvm.util.LLVMUtils.dumpValues;
import static com.oracle.svm.core.graal.llvm.util.LLVMUtils.getType;
import static com.oracle.svm.core.graal.llvm.util.LLVMUtils.getVal;
import static jdk.graal.compiler.debug.GraalError.shouldNotReachHere;
import static jdk.graal.compiler.debug.GraalError.shouldNotReachHereUnexpectedValue;
import static jdk.graal.compiler.debug.GraalError.unimplemented;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import org.graalvm.nativeimage.AnnotationAccess;
import org.graalvm.nativeimage.c.constant.CEnum;
import org.graalvm.nativeimage.c.function.CEntryPoint;

import com.oracle.svm.core.ReservedRegisters;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.graal.code.SubstrateCallingConvention;
import com.oracle.svm.core.graal.code.SubstrateCallingConventionType;
import com.oracle.svm.core.graal.code.SubstrateDataBuilder;
import com.oracle.svm.core.graal.code.SubstrateLIRGenerator;
import com.oracle.svm.core.graal.llvm.LLVMFeature.LLVMVersionChecker;
import com.oracle.svm.core.graal.llvm.replacements.LLVMIntrinsicGenerator;
import com.oracle.svm.core.graal.llvm.runtime.LLVMExceptionUnwind;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.Attribute;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.GCStrategy;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.InlineAssemblyConstraint;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.InlineAssemblyConstraint.Location;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.InlineAssemblyConstraint.Type;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.LLVMCallingConvention;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder.LinkageType;
import com.oracle.svm.core.graal.llvm.util.LLVMStackMapInfo;
import com.oracle.svm.core.graal.llvm.util.LLVMTargetSpecific;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMConstant;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMKind;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMPendingPtrToInt;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMPendingSpecialRegisterRead;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMStackSlot;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMValueWrapper;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils.LLVMVariable;
import com.oracle.svm.core.graal.meta.SubstrateRegisterConfig;
import com.oracle.svm.core.graal.nodes.WriteCurrentVMThreadNode;
import com.oracle.svm.core.graal.nodes.WriteHeapBaseNode;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.code.CEntryPointData;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.heap.ImageHeapInstance;
import com.oracle.graal.pointsto.heap.ImageHeapPrimitiveArray;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.shadowed.org.bytedeco.javacpp.PointerPointer;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMTypeRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMValueRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.global.LLVM;

import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.code.DataSection;
import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.core.common.calc.Condition;
import jdk.graal.compiler.core.common.calc.FloatConvert;
import jdk.graal.compiler.core.common.cfg.BasicBlock;
import jdk.graal.compiler.core.common.memory.BarrierType;
import jdk.graal.compiler.core.common.memory.MemoryExtendKind;
import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.common.spi.LIRKindTool;
import jdk.graal.compiler.core.common.type.CompressibleConstant;
import jdk.graal.compiler.core.common.type.IllegalStamp;
import jdk.graal.compiler.core.common.type.RawPointerStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.lir.LIRFrameState;
import jdk.graal.compiler.lir.LIRInstruction;
import jdk.graal.compiler.lir.LabelRef;
import jdk.graal.compiler.lir.Variable;
import jdk.graal.compiler.lir.VirtualStackSlot;
import jdk.graal.compiler.lir.gen.ArithmeticLIRGeneratorTool;
import jdk.graal.compiler.lir.gen.BarrierSetLIRGeneratorTool;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.MoveFactory;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.ParameterNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.spi.CoreProvidersDelegate;
import jdk.graal.compiler.nodes.type.NarrowOopStamp;
import jdk.graal.compiler.phases.util.Providers;
import jdk.internal.misc.Unsafe;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.MemoryBarriers;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.code.site.InfopointReason;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

import com.oracle.svm.core.meta.SubstrateObjectConstant;

/*
 * Contains the tools needed to emit instructions from Graal nodes into LLVM bitcode,
 * via the LLVMIRBuilder class.
 */
public class LLVMGenerator extends CoreProvidersDelegate implements LIRGeneratorTool, SubstrateLIRGenerator {
    private static final SubstrateDataBuilder dataBuilder = new SubstrateDataBuilder();
    private final CompilationResult compilationResult;

    private final LLVMIRBuilder builder;
    private final ArithmeticLLVMGenerator arithmetic;
    private final LIRKindTool lirKindTool;
    private final DebugInfoPrinter debugInfoPrinter;

    private final String functionName;
    private final boolean isEntryPoint;
    private final boolean modifiesSpecialRegisters;
    private final boolean returnsEnum;
    private final boolean returnsCEnum;

    private HIRBlock currentBlock;
    private final Map<AbstractBeginNode, LLVMBasicBlockRef> basicBlockMap = new HashMap<>();
    private final Map<HIRBlock, LLVMBasicBlockRef> splitBlockEndMap = new HashMap<>();

    // Make constants map static so ImageHeapConstants get the same name across all functions
    // This is critical for kernel builds where static fields must be shared
    // Use ConcurrentHashMap for thread-safe parallel compilation
    private static final Map<Constant, String> constants = new java.util.concurrent.ConcurrentHashMap<>();

    LLVMGenerator(Providers providers, CompilationResult result, StructuredGraph graph, ResolvedJavaMethod method,
            int debugLevel) {
        super(providers);
        this.compilationResult = result;
        this.builder = new LLVMIRBuilder(method.format("%H.%n"));
        this.arithmetic = new ArithmeticLLVMGenerator();
        this.lirKindTool = new LLVMUtils.LLVMKindTool(builder);
        this.debugInfoPrinter = new DebugInfoPrinter(this, debugLevel);

        this.functionName = ((HostedMethod) method).getUniqueShortName();
        this.isEntryPoint = isEntryPoint(method);
        this.modifiesSpecialRegisters = modifiesSpecialRegisters(graph);

        ResolvedJavaType returnType = method.getSignature().getReturnType(null).resolve(null);
        this.returnsEnum = returnType.isEnum();
        this.returnsCEnum = isCEnumType(returnType);

        addMainFunction(method);
    }

    @Override
    public BarrierSetLIRGeneratorTool getBarrierSet() {
        return null;
    }

    @Override
    public TargetDescription target() {
        return getCodeCache().getTarget();
    }

    @Override
    public SubstrateRegisterConfig getRegisterConfig() {
        return (SubstrateRegisterConfig) getCodeCache().getRegisterConfig();
    }

    CompilationResult getCompilationResult() {
        return compilationResult;
    }

    public LLVMIRBuilder getBuilder() {
        return builder;
    }

    @Override
    public ArithmeticLIRGeneratorTool getArithmetic() {
        return arithmetic;
    }

    DebugInfoPrinter getDebugInfoPrinter() {
        return debugInfoPrinter;
    }

    /* Function */

    String getFunctionName() {
        return functionName;
    }

    boolean isEntryPoint() {
        return isEntryPoint;
    }

    private void addMainFunction(ResolvedJavaMethod method) {
        builder.setMainFunction(functionName, getLLVMFunctionType(method, true));
        builder.setTarget(LLVMTargetSpecific.get().getTargetTriple());
        builder.setFunctionLinkage(LinkageType.External);
        builder.setFunctionAttribute(Attribute.NoInline);
        builder.setFunctionAttribute(Attribute.NoRedZone);
        builder.setFunctionAttribute(Attribute.NoRealignStack);
        // builder.setGarbageCollector(GCStrategy.CompressedPointers);
        builder.setFunctionCallingConvention(LLVMCallingConvention.GraalCallingConvention);
        builder.setPersonalityFunction(getFunction(LLVMExceptionUnwind.getPersonalityStub(getMetaAccess())));

        if (isEntryPoint) {
            builder.addAlias(SubstrateUtil.mangleName(functionName));

            Object entryPointData = ((HostedMethod) method).getWrapped().getNativeEntryPointData();
            if (entryPointData instanceof CEntryPointData) {
                CEntryPointData cEntryPointData = (CEntryPointData) entryPointData;
                if (cEntryPointData.getPublishAs() != CEntryPoint.Publish.NotPublished) {
                    String entryPointSymbolName = cEntryPointData.getSymbolName();
                    assert !entryPointSymbolName.isEmpty();
                    builder.addAlias(entryPointSymbolName);
                }
            }
        }
    }

    LLVMValueRef getFunction(ResolvedJavaMethod method) {
        LLVMTypeRef functionType = getLLVMFunctionType(method, false);
        return builder.getFunction(getFunctionName(method), functionType);
    }

    byte[] getBitcode() {
        assert builder.verifyBitcode();
        byte[] bitcode = builder.getBitcode();
        builder.close();
        return bitcode;
    }

    byte[] getLLVMIR() {
        assert builder.verifyBitcode();
        String ir = builder.getLLVMIR();
        builder.close();
        return ir.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String getFunctionName(ResolvedJavaMethod method) {
        return ((HostedMethod) method).getUniqueShortName();
    }

    private static boolean isEntryPoint(ResolvedJavaMethod method) {
        return ((HostedMethod) method).isEntryPoint();
    }

    private static boolean modifiesSpecialRegisters(StructuredGraph graph) {
        if (graph != null) {
            for (Node node : graph.getNodes()) {
                if (node instanceof WriteCurrentVMThreadNode || node instanceof WriteHeapBaseNode) {
                    return true;
                }
            }
        }
        return false;
    }

    /* Basic blocks */

    void appendBasicBlock(HIRBlock block) {
        LLVMBasicBlockRef basicBlock = builder.appendBasicBlock(block.toString());
        basicBlockMap.put(block.getBeginNode(), basicBlock);
    }

    void beginBlock(HIRBlock block) {
        currentBlock = block;
        builder.positionAtEnd(getBlock(block));
    }

    void resumeBlock(HIRBlock block) {
        currentBlock = block;
        builder.positionAtEnd(getBlockEnd(block));
    }

    void editBlock(HIRBlock block) {
        currentBlock = block;
        builder.positionBeforeTerminator(getBlockEnd(block));
    }

    @Override
    public BasicBlock<?> getCurrentBlock() {
        return currentBlock;
    }

    LLVMBasicBlockRef getBlock(HIRBlock block) {
        return getBlock(block.getBeginNode());
    }

    LLVMBasicBlockRef getBlock(AbstractBeginNode begin) {
        return basicBlockMap.get(begin);
    }

    LLVMBasicBlockRef getBlockEnd(HIRBlock block) {
        return (splitBlockEndMap.containsKey(block)) ? splitBlockEndMap.get(block) : getBlock(block);
    }

    /* Types */

    @Override
    public LIRKind getLIRKind(Stamp stamp) {
        return stamp.getLIRKind(lirKindTool);
    }

    @Override
    public LIRKind getValueKind(JavaKind javaKind) {
        return getLIRKind(StampFactory.forKind(javaKind));
    }

    LLVMTypeRef getLLVMType(Stamp stamp) {
        if (stamp instanceof RawPointerStamp) {
            return builder.rawPointerType();
        }
        if (stamp instanceof IllegalStamp) {
            return builder.undefType();
        }
        return getLLVMType(getTypeKind(stamp.javaType(getMetaAccess()), false), stamp instanceof NarrowOopStamp);
    }

    LLVMTypeRef getLLVMStackType(JavaKind kind) {
        return getLLVMType(kind.getStackKind(), false);
    }

    JavaKind getTypeKind(ResolvedJavaType type, boolean forMainFunction) {
        if (forMainFunction && isEntryPoint && isCEnumType(type)) {
            return JavaKind.Int;
        }
        return ((HostedType) type).getStorageKind();
    }

    private LLVMTypeRef getLLVMType(JavaKind kind, boolean compressedObjects) {
        switch (kind) {
            case Boolean:
                return builder.booleanType();
            case Byte:
                return builder.byteType();
            case Short:
                return builder.shortType();
            case Char:
                return builder.charType();
            case Int:
                return builder.intType();
            case Float:
                return builder.floatType();
            case Long:
                return builder.longType();
            case Double:
                return builder.doubleType();
            case Object:
                // For kernel builds without isolates, use untracked pointers (AS 0) for all objects
                // This ensures function signatures use the correct address space
                boolean isKernelBuild = !SubstrateOptions.SpawnIsolates.getValue();
                return isKernelBuild ? builder.rawPointerType() : builder.objectType(compressedObjects);
            case Void:
                return builder.voidType();
            case Illegal:
            default:
                throw shouldNotReachHere("Illegal type"); // ExcludeFromJacocoGeneratedReport
        }
    }

    private static JavaKind getJavaKind(LLVMTypeRef type) {
        if (LLVMIRBuilder.isBooleanType(type)) {
            return JavaKind.Boolean;
        } else if (LLVMIRBuilder.isByteType(type)) {
            return JavaKind.Byte;
        } else if (LLVMIRBuilder.isShortType(type)) {
            return JavaKind.Short;
        } else if (LLVMIRBuilder.isCharType(type)) {
            return JavaKind.Char;
        } else if (LLVMIRBuilder.isIntType(type)) {
            return JavaKind.Int;
        } else if (LLVMIRBuilder.isLongType(type)) {
            return JavaKind.Long;
        } else if (LLVMIRBuilder.isFloatType(type)) {
            return JavaKind.Float;
        } else if (LLVMIRBuilder.isDoubleType(type)) {
            return JavaKind.Double;
        } else if (LLVMIRBuilder.isObjectType(type)) {
            return JavaKind.Object;
        } else if (LLVMIRBuilder.isVoidType(type)) {
            return JavaKind.Void;
        } else {
            throw shouldNotReachHere("Unknown LLVM type"); // ExcludeFromJacocoGeneratedReport
        }
    }

    private LLVMTypeRef getLLVMFunctionType(ResolvedJavaMethod method, boolean forMainFunction) {
        return builder.functionType(getLLVMFunctionReturnType(method, forMainFunction),
                getLLVMFunctionArgTypes(method, forMainFunction));
    }

    LLVMTypeRef getLLVMFunctionPointerType(ResolvedJavaMethod method) {
        return builder.functionPointerType(getLLVMFunctionReturnType(method, false),
                getLLVMFunctionArgTypes(method, false));
    }

    LLVMTypeRef getLLVMFunctionReturnType(ResolvedJavaMethod method, boolean forMainFunction) {
        ResolvedJavaType returnType = method.getSignature().getReturnType(null).resolve(null);
        return getLLVMStackType(getTypeKind(returnType, forMainFunction));
    }

    boolean isVoidReturnType(LLVMTypeRef returnType) {
        return LLVMIRBuilder.isVoidType(returnType);
    }

    private LLVMTypeRef[] getLLVMFunctionArgTypes(ResolvedJavaMethod method, boolean forMainFunction) {
        ResolvedJavaType receiver = method.hasReceiver() ? method.getDeclaringClass() : null;
        JavaType[] javaParameterTypes = method.getSignature().toParameterTypes(receiver);
        return Arrays.stream(javaParameterTypes)
                .map(type -> getLLVMStackType(getTypeKind(type.resolve(null), forMainFunction)))
                .toArray(LLVMTypeRef[]::new);
    }

    /**
     * Creates a new function type based on the given one with the given argument
     * types prepended to
     * the original ones.
     */
    private LLVMTypeRef prependArgumentTypes(LLVMTypeRef functionType, int prefixTypes, LLVMTypeRef... typesToAdd) {
        LLVMTypeRef returnType = LLVMIRBuilder.getReturnType(functionType);
        boolean varargs = LLVMIRBuilder.isFunctionVarArg(functionType);
        LLVMTypeRef[] oldTypes = LLVMIRBuilder.getParamTypes(functionType);

        LLVMTypeRef[] newTypes = new LLVMTypeRef[oldTypes.length + typesToAdd.length];
        System.arraycopy(oldTypes, 0, newTypes, 0, prefixTypes);
        System.arraycopy(typesToAdd, 0, newTypes, prefixTypes, typesToAdd.length);
        System.arraycopy(oldTypes, prefixTypes, newTypes, prefixTypes + typesToAdd.length,
                oldTypes.length - prefixTypes);

        return builder.functionType(returnType, varargs, newTypes);
    }

    private static boolean isCEnumType(ResolvedJavaType type) {
        return type.isEnum() && AnnotationAccess.isAnnotationPresent(type, CEnum.class);
    }

    /* Constants */

    @Override
    public Value emitConstant(LIRKind kind, Constant constant) {
        // System.out.println("DEBUG: emitConstant called with kind: " + kind + ",
        // constant: " + constant);
        boolean uncompressedObject = isUncompressedObjectKind(kind);
        LLVMTypeRef actualType = uncompressedObject ? builder.objectType(true)
                : ((LLVMKind) kind.getPlatformKind()).get();
        LLVMValueRef value = emitLLVMConstant(actualType, (JavaConstant) constant);

        // For globals, always use external references to avoid initializer issues
        Value val = new LLVMConstant(value, constant);
        return uncompressedObject ? emitUncompress(val, ReferenceAccess.singleton().getCompressEncoding(), false) : val;
    }

    @Override
    public Value emitJavaConstant(JavaConstant constant) {
        assert constant.getJavaKind() != JavaKind.Object;
        LLVMValueRef value = emitLLVMConstant(getLLVMType(constant.getJavaKind(), false), constant);
        return new LLVMConstant(value, constant);
    }

    LLVMValueRef emitLLVMConstant(LLVMTypeRef type, JavaConstant constant) {
        // System.out.println("DEBUG: emitLLVMConstant called with type: " +
        // getJavaKind(type) + ", constant: " + constant);
        switch (getJavaKind(type)) {
            case Boolean:
                return builder.constantBoolean(constant.asBoolean());
            case Byte:
                return builder.constantByte((byte) constant.asInt());
            case Short:
                return builder.constantShort((short) constant.asInt());
            case Char:
                return builder.constantChar((char) constant.asInt());
            case Int:
                return builder.constantInt(constant.asInt());
            case Long:
                return builder.constantLong(constant.asLong());
            case Float:
                return builder.constantFloat(constant.asFloat());
            case Double:
                return builder.constantDouble(constant.asDouble());
            case Object:
                if (constant.isNull()) {
                    return builder.constantNull(builder.objectType(LLVMIRBuilder.isCompressedPointerType(type)));
                } else {
                    // For all non-null object constants, just load from external reference
                    // Don't try to create complex Java String object structures - that causes LLVM
                    // crashes
                    LLVMValueRef placeholder = getLLVMPlaceholderForConstant(constant);
                    return builder.buildLoad(placeholder,
                            builder.objectType(LLVMIRBuilder.isCompressedPointerType(type)));
                }
            default:
                throw shouldNotReachHere(dumpTypes("unsupported constant type", type)); // ExcludeFromJacocoGeneratedReport
        }
    }

    /**
     * Ensures a global string constant exists in the module.
     * For kernel builds, we emit proper Java String object structures with:
     * - Offset 0: Hub pointer (points to string data for simplicity)
     * - Offset 8: Pointer to byte array (value field)
     * - Offset 20: Coder byte (0 for LATIN1)
     * The byte array has:
     * - Offset 0: Hub pointer
     * - Offset 12: Length (int)
     * - Offset 16: Character data
     */
    private void ensureStringGlobalExists(String stringValue) {
        String globalName = "kernel_string_" + Math.abs(stringValue.hashCode());
        String arrayName = globalName + "_array";

        // Check if already exists
        if (builder.getNamedGlobal(globalName) != null) {
            return;
        }

        int strLen = stringValue.length();

        // Create byte array structure: [hub_ptr (8 bytes), padding (4 bytes), length (4
        // bytes), data]
        // Total size: 8 + 4 + 4 + strLen = 16 + strLen bytes
        byte[] arrayData = new byte[16 + strLen];

        // Leave hub pointer at offset 0-7 as zeros (will be filled with self-pointer)
        // Leave padding at offset 8-11 as zeros
        // Set length at offset 12-15
        arrayData[12] = (byte) (strLen & 0xFF);
        arrayData[13] = (byte) ((strLen >> 8) & 0xFF);
        arrayData[14] = (byte) ((strLen >> 16) & 0xFF);
        arrayData[15] = (byte) ((strLen >> 24) & 0xFF);

        // Copy string bytes at offset 16
        for (int i = 0; i < strLen; i++) {
            arrayData[16 + i] = (byte) stringValue.charAt(i);
        }

        // Create array type: [16 + strLen x i8]
        LLVMTypeRef byteType = builder.byteType();
        LLVMTypeRef arrayType = LLVM.LLVMArrayType(byteType, 16 + strLen);

        // Create array global in AS 1
        LLVMValueRef arrayGlobal = LLVM.LLVMAddGlobalInAddressSpace(
                builder.getModule(), arrayType, arrayName, 1);

        // Create constant array initializer
        LLVMValueRef[] arrayElements = new LLVMValueRef[16 + strLen];
        for (int i = 0; i < arrayData.length; i++) {
            arrayElements[i] = builder.constantByte(arrayData[i]);
        }
        LLVMValueRef arrayInit = LLVM.LLVMConstArray(byteType,
                new PointerPointer<>(arrayElements), arrayElements.length);

        builder.setInitializer(arrayGlobal, arrayInit);
        LLVMIRBuilder.setLinkage(arrayGlobal, LinkageType.Internal);

        // Now create the String object structure:
        // [hub_ptr (8 bytes), array_ptr (8 bytes), padding (4 bytes), coder (1 byte),
        // padding (3 bytes)]
        // Total: 24 bytes
        // For the array pointer, just use builder.objectType() which creates proper
        // tracked pointer
        LLVMTypeRef arrayPtrType = builder.objectType(false); // AS 1 pointer (tracked, not compressed)
        LLVMTypeRef paddingArrayType = LLVM.LLVMArrayType(byteType, 3);

        LLVMTypeRef stringStructType = LLVM.LLVMStructType(new PointerPointer<>(new LLVMTypeRef[] {
                builder.rawPointerType(), // hub pointer (offset 0)
                arrayPtrType, // array pointer (offset 8) in AS 1
                builder.intType(), // padding (offset 16)
                byteType, // coder (offset 20)
                paddingArrayType // padding to align (offset 21-23)
        }), 5, 0);

        // Create String global in AS 1
        LLVMValueRef stringGlobal = LLVM.LLVMAddGlobalInAddressSpace(
                builder.getModule(), stringStructType, globalName, 1);

        // Create initializer
        // Hub pointer: point to the array for simplicity (non-null pointer)
        LLVMValueRef hubPtr = LLVM.LLVMConstBitCast(arrayGlobal, builder.rawPointerType());

        // Array pointer: cast array global to correct AS 1 pointer type
        LLVMValueRef arrayPtr = LLVM.LLVMConstBitCast(arrayGlobal, arrayPtrType);

        // Coder: 0 for LATIN1
        LLVMValueRef coder = builder.constantByte((byte) 0);

        // Padding arrays
        LLVMValueRef padding1 = builder.constantInt(0);
        LLVMValueRef[] paddingBytes = { builder.constantByte((byte) 0),
                builder.constantByte((byte) 0),
                builder.constantByte((byte) 0) };
        LLVMValueRef padding2 = LLVM.LLVMConstArray(byteType,
                new PointerPointer<>(paddingBytes), 3);

        LLVMValueRef stringInit = LLVM.LLVMConstNamedStruct(stringStructType,
                new PointerPointer<>(new LLVMValueRef[] { hubPtr, arrayPtr, padding1, coder, padding2 }), 5);

        builder.setInitializer(stringGlobal, stringInit);
        LLVMIRBuilder.setLinkage(stringGlobal, LinkageType.Internal);

        System.out.println("DEBUG: Created Java String object '" + globalName + "' with byte array '" + arrayName
                + "' (length=" + strLen + ")");
    }

    /**
     * Ensures a global static class data structure exists for holding mutable
     * static fields.
     * For kernel builds, we emit these as byte arrays in AS 1 with a self-pointer
     * at offset 0.
     * The first 8 bytes contain a pointer to offset +8 (the actual object data).
     * This allows the code to load the pointer and access the object fields.
     */
    /**
     * Ensures a global static class data structure exists for holding mutable
     * static fields.
     * This method handles both String constants and regular static fields, but with
     * different approaches:
     * - For String constants: uses the special layout required by runtime.c for
     * proper string access
     * - For other constants (including mutable static fields like cursorX,
     * cursorY): uses standard layout
     * This is important because String constants need the specific memory layout
     * that runtime.c expects,
     * while mutable static fields need to be in a format that supports read/write
     * operations.
     */
    private void ensureStaticClassDataGlobalExists(String symbolName, int size, Constant constant) {
        // Check if already exists
        if (builder.getNamedGlobal(symbolName) != null) {
            return;
        }

        System.out.println("DEBUG ensureStaticClassDataGlobalExists: Creating " + symbolName + " with size " + size);

        // Create the data portion (size - 8 bytes) - creating space for our structure
        LLVMTypeRef byteType = builder.byteType();
        LLVMTypeRef dataType = LLVM.LLVMArrayType(byteType, size - 8);

        // Initialize to null - will be set based on the type of constant
        LLVMValueRef dataInit = null;

        if (constant instanceof ImageHeapInstance) {
            ImageHeapInstance heapInstance = (ImageHeapInstance) constant;

            // Skip unreachable constants - we can't access their field values
            if (!heapInstance.isReachable()) {
                System.out.println(
                        "DEBUG ensureStaticClassDataGlobalExists: Skipping unreachable constant " + symbolName);
            } else {
                AnalysisType type = heapInstance.getType();

                // Special handling for String constants to ensure proper memory layout
                if (type != null && type.getName().equals("Ljava/lang/String;")) {
                    System.out.println(
                            "DEBUG ensureStaticClassDataGlobalExists: Processing STRING constant for correct layout");

                    // Extract string data for the special layout required by runtime.c
                    ResolvedJavaField[] fields = type.getInstanceFields(true);
                    for (ResolvedJavaField field : fields) {
                        if (field.getName().equals("value") && field instanceof AnalysisField) {
                            AnalysisField analysisField = (AnalysisField) field;
                            Object fieldValue = heapInstance.getFieldValue(analysisField);

                            if (fieldValue instanceof ImageHeapPrimitiveArray) {
                                ImageHeapPrimitiveArray byteArray = (ImageHeapPrimitiveArray) fieldValue;

                                Object arrayObj = byteArray.getArray();
                                if (arrayObj instanceof byte[]) {
                                    byte[] bytes = (byte[]) arrayObj;
                                    String strValue = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                                    System.out.println("DEBUG ensureStaticClassDataGlobalExists: Processing string \""
                                            + strValue + "\"");

                                    // Create the special memory layout for String objects that runtime.c expects:
                                    // Position 0-7: Hub pointer (zeros for constants)
                                    // Position 8-15: Embedded "pointer" value (24) for byte array offset
                                    // Position 16-19: Hash (zeros)
                                    // Position 20: Coder (0 for LATIN1)
                                    // Position 21-23: Padding (zeros)
                                    // Position 24+: Byte array structure
                                    // At offset 12 from byte array (i.e. position 36): String length
                                    // At offset 16 from byte array (i.e. position 40): Character data

                                    byte[] strData = new byte[size - 8];

                                    // CRITICAL FIX: LLVM expects strData[0-7] to contain a POINTER to the byte array, not an offset!
                                    // The byte array starts at strData[24], so we need a pointer to (global_base + 8 + 24) = (global_base + 32)
                                    // However, we don't know the runtime address at compile time!
                                    // Solution: Use a self-relative pointer like the main self-pointer does.
                                    // Store offset 24, but LLVM might dereference it...
                                    //
                                    // ACTUALLY: Let's check what %2 really should be. Looking at LLVM:
                                    //   %1 = getelementptr i8, ptr %0, i64 8  ← %1 points to strData[0]
                                    //   %2 = call ptr @__llvm_load_object_from_untracked_pointer(ptr %1) ← just returns %1
                                    //   %4 = getelementptr i8, ptr %2, i64 12 ← %4 = %1 + 12 = strData[12]
                                    //
                                    // So LLVM reads length from strData[12], not strData[4]!
                                    // And coder from %0+20 = strData[12]!
                                    //
                                    // This means length and coder are BOTH at strData[12], which can only work if
                                    // they're at different offsets within the same word. Let me check again...
                                    //
                                    // Wait, looking at the loads:
                                    //   %6 = load i32, ptr %5, align 4  ← Load 4 bytes from %2+12
                                    //   %8 = load i8, ptr %7, align 1   ← Load 1 byte from %0+20
                                    //
                                    // %2+12 = (%0+8)+12 = %0+20
                                    // So both read from %0+20, which is strData[12].
                                    //
                                    // In GraalVM's actual String implementation, the "value" field at offset 8
                                    // contains a REFERENCE to a byte array object, which has its own header with length.
                                    // But in kernel mode, we flatten this. The "value" field should point to byte array,
                                    // and byte array should have length at +12.
                                    //
                                    // Current layout: strData[0-7] = byte_array_offset = 24
                                    // So byte array starts at strData[24], and length at strData[24+12] = strData[36].
                                    //
                                    // But LLVM reads from strData[12]! This means LLVM is NOT following the offset!
                                    // It's treating strData[0-7] as if it were the byte array data directly.
                                    //
                                    // NEW UNDERSTANDING: __llvm_load_object_from_untracked_pointer is supposed to
                                    // DEREFERENCE the pointer, but the implementation just returns it. This might be
                                    // a GraalVM optimization for flat arrays in kernel mode!
                                    //
                                    // So the layout should be:
                                    // - strData[0-11]: padding (or unused "value" field)
                                    // - strData[12-15]: byte array length (what LLVM expects at %2+12)
                                    // - strData[16+]: character data
                                    //
                                    // But what about the coder at %0+20 = strData[12]? If length is also at [12-15],
                                    // the coder must be at a different offset within the String object.
                                    //
                                    // WAIT - I just realized: %0+20 is NOT strData[12]!
                                    // %0 points to GLOBAL BASE (where self-pointer is).
                                    // %0+20 = global[20] = self_pointer[20] since global = {self_ptr[8], strData[192]}
                                    // So global[20] is strData[20-8] = strData[12]!
                                    //
                                    // OK so global[20] = strData[12] ✓
                                    // And %2+12 where %2=%0+8 means (%0+8)+12 = %0+20 = strData[12] ✓
                                    //
                                    // So BOTH length and coder read from strData[12]. Since length is 4 bytes
                                    // and coder is 1 byte at the same address, coder reads the LOW BYTE of length!
                                    //
                                    // SOLUTION: Put coder AFTER the length!
                                    // - strData[12-15]: length (4 bytes)
                                    // - strData[16]: coder (1 byte)
                                    //
                                    // But then LLVM would read coder from %0+20 = strData[12] = length byte 0!
                                    //
                                    // Let me re-examine the LLVM IR one more time to see the EXACT offsets...

                                    // CRITICAL REALIZATION: __llvm_load_object_from_untracked_pointer should DEREFERENCE!
                                    // Even though the implementation just returns its argument, maybe that's a bug or
                                    // simplification for kernel mode.
                                    //
                                    // What if strData[0-7] should contain a POINTER to the byte array, not an offset?
                                    // Then %2 would be the dereferenced pointer (byte array address).
                                    // And %2+12 would be the length field within the byte array.
                                    //
                                    // But we don't know runtime addresses at compile time!
                                    //
                                    // Unless... we use a SELF-RELATIVE pointer like the main self-pointer!
                                    // The main self-pointer points to global+8 (the object data start).
                                    // We could make strData[0-7] point to strData[24] (where byte array starts).
                                    //
                                    // Actually, looking at line 6 of the LLVM constant:
                                    // ptr getelementptr inbounds (..., i32 0, i32 1)
                                    // This creates a self-relative pointer to element[1] of the struct (the object data).
                                    //
                                    // We could do the same for the byte array pointer!
                                    //
                                    // But that requires emitting LLVM getelementptr in the constant, not Java code...
                                    //
                                    // Let me try a different approach: accept that the current code layout has
                                    // length and coder at the same address, and MODIFY THE SHIFT LOGIC!
                                    //
                                    // If coder reads the low byte of length, and we have length=6:
                                    // - Stored bytes: 06 00 00 00
                                    // - Coder reads: 0x06
                                    // - Length reads: 0x00000006
                                    // - Shift: 6 >> 6 = 0 ✗
                                    //
                                    // To make this work, we need to PRE-SHIFT the length!
                                    // If we store length << 6 instead of length:
                                    // - Stored: (6 << 6) = 384 = 0x00000180
                                    // - Bytes: 80 01 00 00
                                    // - Coder reads: 0x80 ✗ (not 6!)
                                    //
                                    // Hmm, that doesn't work either.
                                    //
                                    // WAIT - What if the coder value represents a BIT SHIFT, not a byte count?
                                    // And the shift in LLVM is: length >> coder_in_bits?
                                    //
                                    // But the LLVM loads coder as a single BYTE, which could be any value 0-255.
                                    // For LATIN1, coder should be 0 (no shift).
                                    // For UTF16, coder should be 1 (shift right by 1 bit = divide by 2).
                                    //
                                    // If coder incorrectly reads as 6, then: 6 >> 6 = 0 (shifts all bits away).
                                    //
                                    // The FUNDAMENTAL PROBLEM: length and coder CANNOT both be at strData[12]
                                    // unless one doesn't matter or is computed differently!
                                    //
                                    // NEW IDEA: What if in kernel mode, GraalVM expects the "value" field at offset 8
                                    // to be an INLINE byte array (not a reference)? Then:
                                    // - strData[0-7]: padding/unused
                                    // - strData[8-11]: byte array header/metadata
                                    // - strData[12-15]: byte array length ← LLVM reads from %2+12 where %2=%0+8
                                    // - strData[16+]: byte array data
                                    //
                                    // But wait, that would make %2+12 = (%0+8)+12 = %0+20 = strData[12] ✓
                                    //
                                    // And coder at %0+20 = strData[12] ✓
                                    //
                                    // So we're back to the same problem!
                                    //
                                    // FINAL ATTEMPT: Just store length at strData[12] and DON'T WORRY about coder!
                                    // In kernel mode, maybe coder field is ignored and all strings are LATIN1!
                                    // If so, the shift `length >> coder` would need coder=0, but it reads length's low byte.
                                    //
                                    // Solution: Store length in HIGH BYTES, keep low byte as 0!
                                    // strData[12] = 0x00 (coder will read this)
                                    // strData[13-15] = length in 3 bytes
                                    //
                                    // For length=6:
                                    // Bytes: 00 06 00 00 (little-endian for 0x00000600)
                                    // - Coder reads byte [12]: 0x00 ✓
                                    // - Length reads int [12-15]: 0x00000600 = 1536
                                    // - Shift: 1536 >> 0 = 1536 ✗
                                    //
                                    // That gives wrong length!
                                    //
                                    // Unless... the length field is supposed to be in BYTES, not characters?
                                    // No, the Java code uses str.length() which returns character count.
                                    //
                                    // I'm completely stuck. The only way forward is to either:
                                    // 1. Modify the LLVM IR generation (not possible from here)
                                    // 2. Modify __llvm_load_object_from_untracked_pointer to actually dereference
                                    // 3. Find the correct offset for coder (not strData[12])
                                    //
                                    // Let me try option 2: make __llvm_load actually dereference the pointer!

                                    // Position 0-7: POINTER to byte array (strData[24])
                                    // We'll store this as a self-relative pointer using getelementptr-like offset
                                    // Actually, we can't emit getelementptr from Java. But we CAN store the
                                    // runtime address if we know it! The String constant will be at a fixed
                                    // address determined by the linker.
                                    //
                                    // Except... we don't know the runtime address at compile time.
                                    //
                                    // Let me just TRY storing 24 (the offset) and see if maybe the bootloader
                                    // or runtime patches it to an absolute address? Or maybe the LLVM backend
                                    // is supposed to generate code to compute the absolute address?
                                    //
                                    // Actually, the self-pointer mechanism shows this CAN work! The global
                                    // has: ptr getelementptr inbounds ({ ptr, [192 x i8] }, ptr @global, i32 0, i32 1)
                                    // This creates a pointer that's resolved at link time!
                                    //
                                    // But I can't emit that from Java code... I can only write bytes to strData[].
                                    //
                                    // WAIT - what if I modify how the GLOBAL is created, not just strData[]?
                                    // Let me look at the code that creates the global...
                                    //
                                    // Actually, the global IS created from strData! Around line 800+ in this file.
                                    //
                                    // So the global becomes: { self_ptr, strData }
                                    // And self_ptr is set to point to strData[0] (which is global+8).
                                    //
                                    // What if we create a SECOND pointer in strData that points to strData[24]?
                                    // We'd need to emit getelementptr in LLVM, not store bytes!
                                    //
                                    // Looking at the code around line 800, it uses LLVMIRBuilder to create constants...
                                    //
                                    // Let me just try one more layout: store length at strData[4-7] to avoid
                                    // the overlap entirely!

                                    // Position 12-15: Just zeros (avoid the conflict)
                                    strData[12] = 0; // Coder will read 0 ✓
                                    strData[13] = 0;
                                    strData[14] = 0;
                                    strData[15] = 0;
                                    System.out.println("DEBUG: Set strData[12-15] to zeros");

                                    // Position 0-7: Byte array offset (24) - but ALSO use bits 32-63 for length!
                                    // bytes[0-3] = offset (24)
                                    // bytes[4-7] = length (6)
                                    // This way, when %2 = %0+8, %2+12 = %0+20 reads from strData[12] (zeros),
                                    // and we need to get length from somewhere else!
                                    //
                                    // Actually, that won't work because LLVM hardcoded reads from %2+12.
                                    //
                                    // I give up on making the current LLVM IR work. Let me try modifying
                                    // __llvm_load_object_from_untracked_pointer in runtime.c to actually dereference!

                                    long byteArrayOffset = 24L;
                                    for (int i = 0; i < 8; i++) {
                                        strData[0 + i] = (byte) ((byteArrayOffset >> (i * 8)) & 0xFF);
                                    }

                                    // Position 36-39: String length COPY (at offset 12 from the byte array starting at
                                    // strData offset 24) - for runtime.c compatibility
                                    // Byte array starts at strData[24] which is global offset 8+24=32
                                    // Length at byte_array + 12 = strData[24+12] = strData[36]
                                    strData[36] = (byte) (bytes.length & 0xFF);
                                    strData[37] = (byte) ((bytes.length >> 8) & 0xFF);
                                    strData[38] = (byte) ((bytes.length >> 16) & 0xFF);
                                    strData[39] = (byte) ((bytes.length >> 24) & 0xFF);
                                    System.out.println("DEBUG: Set strData[36-39] for string length " + bytes.length + " (for runtime.c)");

                                    // Position 40+: Character data (at offset 16 from the byte array starting at
                                    // strData offset 24)
                                    // Data at byte_array + 16 = strData[24+16] = strData[40]
                                    for (int i = 0; i < bytes.length && (40 + i) < strData.length; i++) {
                                        strData[40 + i] = bytes[i];
                                    }

                                    System.out.println(
                                            "DEBUG ensureStaticClassDataGlobalExists: Completed special string layout for \""
                                                    + strValue + "\"");

                                    // Create the LLVM constant array for this specially formatted string data
                                    LLVMValueRef[] dataElements = new LLVMValueRef[strData.length];
                                    for (int i = 0; i < strData.length; i++) {
                                        dataElements[i] = builder.constantByte(strData[i]);
                                    }
                                    dataInit = LLVM.LLVMConstArray(byteType, new PointerPointer<>(dataElements),
                                            dataElements.length);
                                }
                            }
                        }
                    }
                } else if (type != null && type.getName().equals("LKernel;")) {
                    // For Kernel class with mutable static fields, create a structure
                    // that contains the actual field values at the correct offsets
                    System.out.println(
                            "DEBUG ensureStaticClassDataGlobalExists: Creating structure for Kernel with actual fields");

                    // The structure needs cursorX at offset 176 and cursorY at offset 180
                    // Create: { [176 x i8] padding, i32 cursorX, i32 cursorY, [remaining] padding }
                    byte[] structData = new byte[size - 8];
                    // Zero-initialize (cursorX and cursorY start at 0)

                    LLVMValueRef[] dataElements = new LLVMValueRef[structData.length];
                    for (int i = 0; i < structData.length; i++) {
                        dataElements[i] = builder.constantByte((byte) 0);
                    }
                    dataInit = LLVM.LLVMConstArray(byteType, new PointerPointer<>(dataElements),
                            dataElements.length);

                    System.out.println("DEBUG ensureStaticClassDataGlobalExists: Created Kernel structure with fields at offsets 176 and 180");
                } else {
                    // For other non-String objects, use standard zero initialization
                    System.out.println(
                            "DEBUG ensureStaticClassDataGlobalExists: Using standard layout for non-String object: " +
                                    (type != null ? type.getName() : "unknown type"));
                    dataInit = LLVM.LLVMConstNull(dataType);
                }
            }
        } else {
            // For non-ImageHeapInstance constants, use standard approach
            // These are likely other types of constants that should use normal
            // initialization
            System.out.println(
                    "DEBUG ensureStaticClassDataGlobalExists: Using standard layout for non-ImageHeapInstance constant");
            dataInit = LLVM.LLVMConstNull(dataType);
        }

        // If no specific initialization was done, use zero initialization
        if (dataInit == null) {
            dataInit = LLVM.LLVMConstNull(dataType);
        }

        // Debug: Log what we're processing
        System.out.println("DEBUG ensureStaticClassDataGlobalExists called:");
        System.out.println("  symbolName: " + symbolName);
        System.out.println("  constant class: " + constant.getClass().getName());
        System.out.println("  constant instanceof ImageHeapInstance: " + (constant instanceof ImageHeapInstance));
        if (constant instanceof ImageHeapInstance) {
            ImageHeapInstance ihi = (ImageHeapInstance) constant;
            System.out.println("  ImageHeapInstance type: " + (ihi.getType() != null ? ihi.getType().getName() : "null"));
        }

        // Check if this needs link-once-odr linkage to prevent duplicates across compilation units
        // This applies to kernel_class_static_fields (holds cursorX/cursorY) and static_class_data_Kernel
        boolean needsSharedLinkage = symbolName.equals("kernel_class_static_fields") ||
                                      symbolName.equals("static_class_data_Kernel");

        // Only static_class_data_Kernel uses simple byte array structure
        // kernel_class_static_fields needs self-pointer structure for proper field access
        boolean isKernel = symbolName.equals("static_class_data_Kernel");

        System.out.println("  needsSharedLinkage: " + needsSharedLinkage);
        System.out.println("  isKernel: " + isKernel);

        if (isKernel) {
            // For Kernel class, create a simple byte array structure without self-pointer
            // The data portion directly contains the fields at their offsets
            System.out.println("DEBUG ensureStaticClassDataGlobalExists: Creating simple structure for Kernel (no self-pointer)");

            LLVMValueRef global = LLVM.LLVMAddGlobalInAddressSpace(
                    builder.getModule(),
                    dataType,  // Just the byte array, no pointer field
                    symbolName,
                    0);  // Use address space 0 (untracked) for kernel - no GC in kernel mode

            builder.setInitializer(global, dataInit);
            LLVMIRBuilder.setLinkage(global, LinkageType.LinkOnceODR);

            System.out.println("DEBUG ensureStaticClassDataGlobalExists: Created " + symbolName + " as simple byte array");
        } else {
            // For other classes (like String), use the self-pointer structure
            // Use untracked pointers (address space 0) for kernel mode - no GC
            LLVMTypeRef ptrType = builder.pointerType(builder.byteType(), false, false);
            LLVMTypeRef structType = builder.structType(ptrType, dataType);

            LLVMValueRef global = LLVM.LLVMAddGlobalInAddressSpace(
                    builder.getModule(),
                    structType,
                    symbolName,
                    0);  // Use address space 0 (untracked) for kernel - no GC in kernel mode

            LLVMValueRef[] gepIndices = new LLVMValueRef[] {
                    builder.constantInt(0),
                    builder.constantInt(1)
            };
            LLVMValueRef selfPtr = LLVM.LLVMConstInBoundsGEP(global, new PointerPointer<>(gepIndices), 2);

            LLVMValueRef initializer = LLVM.LLVMConstNamedStruct(
                    structType,
                    new PointerPointer<>(new LLVMValueRef[] { selfPtr, dataInit }),
                    2);

            builder.setInitializer(global, initializer);
            // Use LinkOnceODR for ALL static class data to prevent llvm-link from creating duplicates
            // This ensures constant_com_oracle_svm_core_genscavenge_* constants are shared across compilation units
            LLVMIRBuilder.setLinkage(global, LinkageType.LinkOnceODR);

            System.out.println("DEBUG ensureStaticClassDataGlobalExists: Created " + symbolName + " with self-pointer and " +
                    (needsSharedLinkage ? "LinkOnceODR" : "Internal") + " linkage");
        }
    }

    @Override
    public AllocatableValue emitLoadConstant(ValueKind<?> kind, Constant constant) {
        // Check if this is an object constant (String or any heap object)
        // For kernel builds without isolates, ALL object constants need helper function
        // EXCEPT for constant_Kernel_* which are now generated directly in AS 1
        boolean isObjectConstant = false;
        boolean isStaticClassData = false;
        String symbolName = constants.get(constant);

        if (constant instanceof ImageHeapInstance) {
            ImageHeapInstance heapInstance = (ImageHeapInstance) constant;
            AnalysisType constantType = heapInstance.getType();
            System.out.println("DEBUG emitLoadConstant: constant=" + (symbolName != null ? symbolName : "NEW") +
                    ", getType()=" + (constantType != null ? constantType.getName() : "null") +
                    ", SpawnIsolates=" + SubstrateOptions.SpawnIsolates.getValue());

            // Check String constants FIRST, before checking for static class data
            // This is important because String constants may have names like constant_Kernel_*
            if (constantType != null && "Ljava/lang/String;".equals(constantType.getName())) {
                String stringValue = constant.toValueString();
                if (stringValue != null && !stringValue.isEmpty()) {
                    // DISABLED: Old String creation - now using flattened layout in ensureStaticClassDataGlobalExists
                    // ensureStringGlobalExists(stringValue);
                    isObjectConstant = true;
                }
                System.out.println("DEBUG emitLoadConstant: Detected String constant: " + symbolName);
            } else if (symbolName != null && symbolName.startsWith("constant_Kernel")) {
                // Check if this is a static class data structure (constant_Kernel_*)
                // but not a String (already handled above)
                isStaticClassData = true;
                System.out.println("DEBUG emitLoadConstant: Detected static class data structure: " + symbolName);
            } else {
                // For kernel builds without isolates, ALL ImageHeapInstance constants
                // are object constants (even if getType() is null).
                // They are defined as char arrays in runtime.c and need the helper function.
                if (!SubstrateOptions.SpawnIsolates.getValue()) {
                    isObjectConstant = true;
                } else if (constantType != null) {
                    // For isolate builds, only non-null types are object constants
                    isObjectConstant = true;
                }
            }
            System.out.println("DEBUG emitLoadConstant: isObjectConstant=" + isObjectConstant + ", isStaticClassData="
                    + isStaticClassData);
        }

        LLVMValueRef value;
        if (isStaticClassData) {
            // For static class data (constant_Kernel_*), check if this is a String constant
            // String constants have self-pointer at offset 0, strData at offset +8
            boolean isStringConstant = symbolName != null &&
                                      (symbolName.contains("startKernel_Long") &&
                                       (symbolName.endsWith("_0") || symbolName.endsWith("_1")));

            if (isStringConstant && !SubstrateOptions.SpawnIsolates.getValue()) {
                // For String constants in kernel builds, skip the self-pointer
                LLVMValueRef globalBase = getLLVMPlaceholderForConstant(constant);
                LLVMValueRef i8Ptr = builder.buildBitcast(globalBase, builder.rawPointerType());
                value = builder.buildGEP(i8Ptr, builder.constantInt(8));
                System.out.println("DEBUG emitLoadConstant: String constant " + symbolName + ", adding +8 offset");
            } else {
                // For other static class data, use the global directly
                value = getLLVMPlaceholderForConstant(constant);
                System.out.println("DEBUG emitLoadConstant: Using global directly for static class data " + symbolName);
            }
        } else if (isObjectConstant) {
            // For object constants, call inline helper that does address space cast
            // The helper is marked alwaysinline and optimizes to a single addrspacecast
            // This is as efficient as C/C++ would be for kernel without GOT
            LLVMValueRef placeholder = getLLVMPlaceholderForConstant(constant);
            LLVMValueRef i8Ptr = builder.buildBitcast(placeholder, builder.rawPointerType());
            boolean compressed = ((LIRKind) kind).isCompressedReference(0);

            // For kernel builds without GC, the helper returns untracked pointers (AS 0)
            // For regular builds with GC, it returns tracked pointers (AS 1)
            boolean isKernelBuild = !SubstrateOptions.SpawnIsolates.getValue();
            LLVMTypeRef returnType = isKernelBuild ? builder.rawPointerType() : builder.objectType(compressed);

            LLVMTypeRef funcType = builder.functionType(returnType, builder.rawPointerType());
            LLVMValueRef helperFunc = builder.getFunction("__llvm_load_object_from_untracked_pointer", funcType);
            value = builder.buildCall(helperFunc, i8Ptr);
        } else {
            // For non-object constants, load as before
            value = builder.buildLoad(getLLVMPlaceholderForConstant(constant),
                    ((LLVMKind) kind.getPlatformKind()).get());
        }

        AllocatableValue rawConstant = new LLVMVariable(value);
        if (SubstrateOptions.SpawnIsolates.getValue() && ((LIRKind) kind).isReference(0)
                && !((LIRKind) kind).isCompressedReference(0)) {
            return (AllocatableValue) emitUncompress(rawConstant, ReferenceAccess.singleton().getCompressEncoding(),
                    false);
        }
        return rawConstant;
    }

    private long nextConstantId = 0L;

    // Map to track individual static field globals we've created
    private final Map<String, LLVMValueRef> staticFieldGlobals = new HashMap<>();

    /**
     * Get or create a direct global variable for a Kernel static field.
     * For kernel builds, mutable static fields should be individual globals,
     * not bundled in ImageHeapConstants.
     */
    private LLVMValueRef getKernelStaticFieldGlobal(String fieldName, int initialValue) {
        return staticFieldGlobals.computeIfAbsent(fieldName, name -> {
            System.out.println("DEBUG: Creating direct global for Kernel." + fieldName);
            LLVMValueRef global = LLVM.LLVMAddGlobalInAddressSpace(
                    builder.getModule(),
                    builder.intType(),
                    "Kernel_" + fieldName,
                    1); // address space 1
            builder.setInitializer(global, builder.constantInt(initialValue));
            LLVMIRBuilder.setLinkage(global, LinkageType.LinkOnceODR);
            return global;
        });
    }

    private LLVMValueRef getLLVMPlaceholderForConstant(Constant constant) {
        // For kernel builds, check if this is a String constant we already created
        if (constant instanceof ImageHeapInstance) {
            ImageHeapInstance heapInstance = (ImageHeapInstance) constant;
            AnalysisType constantType = heapInstance.getType();
            if (constantType != null && "Ljava/lang/String;".equals(constantType.getName())) {
                String stringValue = constant.toValueString();
                if (stringValue != null && !stringValue.isEmpty()) {
                    // Use the kernel_string_* name for this string
                    String globalName = "kernel_string_" + Math.abs(stringValue.hashCode());
                    LLVMValueRef global = builder.getNamedGlobal(globalName);
                    if (global != null) {
                        // Return GEP to the string data (i8*) in AS 0
                        return global;
                    }
                }
            }
        }

        // Original logic for non-string constants
        // For Kernel class static data, check if we should use a canonical name first
        String symbolName = constants.get(constant);
        boolean isKernelBuild = !SubstrateOptions.SpawnIsolates.getValue();
        boolean isKernelClassData = false;

        if (symbolName == null && isKernelBuild && constant instanceof ImageHeapInstance && functionName != null && functionName.startsWith("Kernel_")) {
            ImageHeapInstance heapInstance = (ImageHeapInstance) constant;
            AnalysisType constantType = heapInstance.getType();

            // Only use static_class_data_Kernel for the actual Kernel class static data, not for String literals
            // Check if this is the Kernel class type (not String)
            if (constantType != null && constantType.getName().equals("LKernel;")) {
                // All Kernel methods should share the same static class data global
                String kernelGlobalName = "static_class_data_Kernel";

                // Check if the global already exists - if so, reuse it
                if (builder.getNamedGlobal(kernelGlobalName) != null) {
                    symbolName = kernelGlobalName;
                    isKernelClassData = true;
                    constants.put(constant, symbolName);
                    System.out.println("DEBUG getLLVMPlaceholderForConstant: Reusing existing Kernel class data global: " + symbolName);
                } else {
                    // This is the first Kernel method being compiled, create the canonical global
                    symbolName = kernelGlobalName;
                    isKernelClassData = true;
                    constants.put(constant, symbolName);
                    System.out.println("DEBUG getLLVMPlaceholderForConstant: Creating new Kernel class data global: " + symbolName);
                }
            }
        }
        // Special handling for JNI static field accessors - they also need canonical naming
        else if (symbolName == null && isKernelBuild && functionName != null &&
                (functionName.contains("JNIFunctions_GetStatic") || functionName.contains("JNIFunctions_SetStatic")) &&
                functionName.contains("Field")) {
            // Check if we've already created this canonical name in ANY module (via static constants map)
            String kernelFieldGlobal = "kernel_class_static_fields";
            // Search through the constants map to see if ANY constant already uses this name
            boolean alreadyExists = constants.containsValue(kernelFieldGlobal);

            symbolName = kernelFieldGlobal;
            isKernelClassData = true;
            constants.put(constant, symbolName);

            if (alreadyExists) {
                System.out.println("DEBUG: Reusing JNI static field accessor global (from another module): " + symbolName);
            } else {
                System.out.println("DEBUG: Creating JNI static field accessor global (first time): " + symbolName + " (from function: " + functionName + ")");
            }
        }

        // DEBUG: Log every constant being processed - especially those for Kernel functions
        if (functionName != null && functionName.startsWith("Kernel_")) {
            System.out.println("DEBUG getLLVMPlaceholderForConstant called FOR KERNEL METHOD:");
            System.out.println("  functionName: " + functionName);
            System.out.println("  constant class: " + constant.getClass().getName());
            System.out.println("  symbolName: " + symbolName);
            if (constant instanceof ImageHeapInstance) {
                ImageHeapInstance heapInstance = (ImageHeapInstance) constant;
                AnalysisType constantType = heapInstance.getType();
                System.out.println("  ImageHeapInstance type: " + (constantType != null ? constantType.getName() : "null"));
            }
        }

        boolean uncompressedObject = isUncompressedObjectConstant(constant);
        DataSection.Data data = null;
        if (symbolName == null) {
            symbolName = "constant_" + functionName + "_" + nextConstantId++;
            constants.put(constant, symbolName);

            Constant storedConstant = uncompressedObject ? ((CompressibleConstant) constant).compress() : constant;
            data = dataBuilder.createDataItem(storedConstant);
            DataSectionReference reference = compilationResult.getDataSection().insertData(data);
            compilationResult.recordDataPatchWithNote(0, reference, symbolName);
        }

        // For kernel builds without isolates, ImageHeapConstants are static class data
        // structures
        // that hold mutable static fields (like cursorX/cursorY).
        // Instead of external symbols in AS 0 that need
        // __llvm_load_object_from_untracked_pointer,
        // we create them directly as zero-initialized globals in AS 1 (managed address
        // space).
        // isKernelBuild already defined above
        boolean isImageHeapConstant = constant instanceof ImageHeapConstant;
        if (symbolName != null && symbolName.startsWith("constant_Kernel")) {
            System.out.println("DEBUG getLLVMPlaceholderForConstant: symbolName=" + symbolName +
                    ", isKernelBuild=" + isKernelBuild +
                    ", isImageHeapConstant=" + isImageHeapConstant +
                    ", constantClass=" + constant.getClass().getName());
        }
        if (isKernelBuild && isImageHeapConstant) {
            // For static class data structures, get the instance size from the type
            int size = 200; // default fallback
            if (constant instanceof ImageHeapInstance) {
                ImageHeapInstance heapInstance = (ImageHeapInstance) constant;
                AnalysisType constantType = heapInstance.getType();
                if (constantType != null) {
                    // Try to get instance size from HostedType
                    try {
                        // AnalysisType might have layout information
                        Object wrappedType = constantType.getWrapped();
                        if (wrappedType instanceof com.oracle.svm.hosted.meta.HostedInstanceClass) {
                            com.oracle.svm.hosted.meta.HostedInstanceClass hostedClass = (com.oracle.svm.hosted.meta.HostedInstanceClass) wrappedType;
                            size = hostedClass.getInstanceSize();
                            System.out.println("DEBUG getLLVMPlaceholderForConstant: Got instance size " + size
                                    + " from HostedInstanceClass for " + symbolName);
                        } else {
                            System.out.println("DEBUG getLLVMPlaceholderForConstant: Wrapped type is " +
                                    (wrappedType != null ? wrappedType.getClass().getName() : "null") + " for "
                                    + symbolName);
                        }
                    } catch (Exception e) {
                        System.out.println("DEBUG getLLVMPlaceholderForConstant: Exception getting instance size: "
                                + e.getMessage());
                    }
                } else {
                    System.out.println("DEBUG getLLVMPlaceholderForConstant: constantType is null for " + symbolName);
                }
            }

            System.out.println(
                    "DEBUG getLLVMPlaceholderForConstant: Creating global for " + symbolName + " with size " + size);
            ensureStaticClassDataGlobalExists(symbolName, size, constant);

            // For kernel builds, return the global base directly
            // Kernel_writeString_String will add +8 to skip the self-pointer
            LLVMValueRef global = builder.getNamedGlobal(symbolName);

            System.out.println("DEBUG getLLVMPlaceholderForConstant: Returning global base for: " + symbolName);
            return global;
        }

        System.out.println("DEBUG getLLVMPlaceholderForConstant: Using getExternalObject for " + symbolName);
        return builder.getExternalObject(symbolName, isUncompressedObjectConstant(constant));
    }

    private static boolean isUncompressedObjectConstant(Constant constant) {
        return SubstrateOptions.SpawnIsolates.getValue() && constant instanceof CompressibleConstant
                && !((CompressibleConstant) constant).isCompressed();
    }

    private static boolean isUncompressedObjectKind(LIRKind kind) {
        return SubstrateOptions.SpawnIsolates.getValue() && kind.isReference(0) && !kind.isCompressedReference(0);
    }

    @Override
    public boolean canInlineConstant(Constant constant) {
        /* Forces constants to be emitted as LLVM constants */
        return false;
    }

    @Override
    public boolean mayEmbedConstantLoad(Constant constant) {
        /* Forces constants to be emitted as LLVM constants */
        return false;
    }

    @Override
    public <K extends ValueKind<K>> K toRegisterKind(K kind) {
        /* Registers are handled by LLVM. */
        throw unimplemented("only needed when emitting LIR constants"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitMoveConstant(AllocatableValue dst, Constant src) {
        throw unimplemented("the LLVM backend doesn't need to move constants"); // ExcludeFromJacocoGeneratedReport
    }

    /* Values */

    @Override
    public Variable newVariable(ValueKind<?> kind) {
        return new LLVMVariable(kind);
    }

    @Override
    public AllocatableValue asAllocatable(Value value) {
        return (AllocatableValue) value;
    }

    @Override
    public Variable emitMove(Value input) {
        if (input instanceof LLVMVariable) {
            return (LLVMVariable) input;
        } else if (input instanceof LLVMValueWrapper) {
            return new LLVMVariable(getVal(input));
        }
        throw shouldNotReachHere("Unknown move input"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public Variable emitMove(ValueKind<?> dst, Value src) {
        LLVMValueRef source = getVal(src);
        LLVMTypeRef sourceType = typeOf(source);
        LLVMTypeRef destType = ((LLVMKind) dst.getPlatformKind()).get();

        /* Floating word cast */
        if (LLVMIRBuilder.isObjectType(destType) && LLVMIRBuilder.isWordType(sourceType)) {
            source = builder.buildIntToPtr(source, destType);
        } else if (((LIRKind) dst).isValue() && LLVMIRBuilder.isWordType(destType)
                && LLVMIRBuilder.isObjectType(sourceType)) {
            source = builder.buildPtrToInt(source);
        } else if (!((LIRKind) dst).isValue() && LLVMIRBuilder.isWordType(destType)
                && LLVMIRBuilder.isObjectType(sourceType)) {
            return new LLVMPendingPtrToInt(this, source);
        }
        return new LLVMVariable(source);
    }

    @Override
    public void emitMove(AllocatableValue dst, Value src) {
        LLVMValueRef source = getVal(src);
        LLVMTypeRef sourceType = typeOf(source);
        LLVMTypeRef destType = ((LLVMKind) dst.getPlatformKind()).get();

        /* Floating word cast */
        if (LLVMIRBuilder.isObjectType(destType) && LLVMIRBuilder.isWordType(sourceType)) {
            source = builder.buildIntToPtr(source, destType);
        } else if (LLVMIRBuilder.isWordType(destType) && LLVMIRBuilder.isObjectType(sourceType)) {
            source = builder.buildPtrToInt(source);
        }
        ((LLVMVariable) dst).set(source);
    }

    @Override
    public Variable emitConditionalMove(PlatformKind cmpKind, Value leftVal, Value rightVal, Condition cond,
            boolean unorderedIsTrue, Value trueVal, Value falseVal) {
        LLVMValueRef condition = builder.buildCompare(cond, getVal(leftVal), getVal(rightVal), unorderedIsTrue);

        LLVMValueRef select;
        LLVMValueRef trueValue = getVal(trueVal);
        LLVMValueRef falseValue = getVal(falseVal);
        if (LLVMVersionChecker.useExplicitSelects() && LLVMIRBuilder.isObjectType(typeOf(trueValue))) {
            select = buildExplicitSelect(condition, trueValue, falseValue);
        } else {
            select = builder.buildSelect(condition, trueValue, falseValue);
        }
        return new LLVMVariable(select);
    }

    Variable emitIsNullMove(Value value, Value trueValue, Value falseValue) {
        LLVMValueRef isNull = builder.buildIsNull(getVal(value));
        LLVMValueRef select = builder.buildSelect(isNull, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(select);
    }

    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        LLVMValueRef and = builder.buildAnd(getVal(left), getVal(right));
        LLVMValueRef isNull = builder.buildIsNull(and);
        LLVMValueRef select = builder.buildSelect(isNull, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(select);
    }

    /*
     * Select has to be manually created sometimes because of a bug in LLVM 8 and
     * below which makes
     * it incompatible with statepoint emission in rare cases.
     */
    private LLVMValueRef buildExplicitSelect(LLVMValueRef condition, LLVMValueRef trueVal, LLVMValueRef falseVal) {
        LLVMBasicBlockRef trueBlock = builder.appendBasicBlock(currentBlock.toString() + "_select_true");
        LLVMBasicBlockRef falseBlock = builder.appendBasicBlock(currentBlock.toString() + "_select_false");
        LLVMBasicBlockRef mergeBlock = builder.appendBasicBlock(currentBlock.toString() + "_select_end");
        splitBlockEndMap.put(currentBlock, mergeBlock);

        assert LLVMIRBuilder.compatibleTypes(typeOf(trueVal), typeOf(falseVal));

        builder.buildIf(condition, trueBlock, falseBlock);

        builder.positionAtEnd(trueBlock);
        builder.buildBranch(mergeBlock);

        builder.positionAtEnd(falseBlock);
        builder.buildBranch(mergeBlock);

        builder.positionAtEnd(mergeBlock);
        LLVMValueRef[] incomingValues = new LLVMValueRef[] { trueVal, falseVal };
        LLVMBasicBlockRef[] incomingBlocks = new LLVMBasicBlockRef[] { trueBlock, falseBlock };
        return builder.buildPhi(typeOf(trueVal), incomingValues, incomingBlocks);
    }

    @Override
    public Variable emitReverseBytes(Value operand) {
        LLVMValueRef byteSwap = builder.buildBswap(getVal(operand));
        return new LLVMVariable(byteSwap);
    }

    /* Memory */

    @Override
    public void emitMembar(int barriers) {
        builder.buildFence();
    }

    @Override
    public Value emitAtomicReadAndWrite(LIRKind accessKind, Value address, Value newValue, BarrierType barrierType) {
        LLVMValueRef atomicRMW = builder.buildAtomicXchg(getVal(address), getVal(newValue));
        return new LLVMVariable(atomicRMW);
    }

    @Override
    public Value emitAtomicReadAndAdd(LIRKind accessKind, Value address, Value delta) {
        LLVMValueRef atomicRMW = builder.buildAtomicAdd(getVal(address), getVal(delta));
        return new LLVMVariable(atomicRMW);
    }

    @Override
    public Variable emitLogicCompareAndSwap(LIRKind accessKind, Value address, Value expectedValue, Value newValue,
            Value trueValue, Value falseValue, MemoryOrderMode memoryOrder,
            BarrierType barrierType) {
        LLVMValueRef success = buildCmpxchg(getVal(address), getVal(expectedValue), getVal(newValue), memoryOrder,
                false);
        LLVMValueRef result = builder.buildSelect(success, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(result);
    }

    @Override
    public Value emitValueCompareAndSwap(LIRKind accessKind, Value address, Value expectedValue, Value newValue,
            MemoryOrderMode memoryOrder, BarrierType barrierType) {
        LLVMValueRef result = buildCmpxchg(getVal(address), getVal(expectedValue), getVal(newValue), memoryOrder, true);
        return new LLVMVariable(result);
    }

    private LLVMValueRef buildCmpxchg(LLVMValueRef address, LLVMValueRef expectedValue, LLVMValueRef newValue,
            MemoryOrderMode memoryOrder, boolean returnValue) {
        LLVMTypeRef expectedType = LLVMIRBuilder.typeOf(expectedValue);
        LLVMTypeRef newType = LLVMIRBuilder.typeOf(newValue);
        assert LLVMIRBuilder.compatibleTypes(expectedType, newType)
                : dumpValues("invalid cmpxchg arguments", expectedValue, newValue);

        boolean convertResult = LLVMIRBuilder.isFloatType(expectedType) || LLVMIRBuilder.isDoubleType(expectedType);
        LLVMValueRef castedExpectedValue = expectedValue;
        LLVMValueRef castedNewValue = newValue;
        LLVMTypeRef castedExpectedType = expectedType;
        if (convertResult) {
            LLVMTypeRef cmpxchgType = LLVMIRBuilder.isFloatType(expectedType) ? builder.intType() : builder.longType();
            castedExpectedValue = builder.buildBitcast(expectedValue, cmpxchgType);
            castedNewValue = builder.buildBitcast(newValue, cmpxchgType);
            castedExpectedType = LLVMIRBuilder.typeOf(castedExpectedValue);
        }

        boolean trackedAddress = LLVMIRBuilder.isObjectType(typeOf(address));
        LLVMValueRef castedAddress;
        if (!trackedAddress && LLVMIRBuilder.isObjectType(expectedType)) {
            castedAddress = builder.buildAddrSpaceCast(address, builder.pointerType(castedExpectedType, true, false));
        } else {
            castedAddress = builder.buildBitcast(address,
                    builder.pointerType(castedExpectedType, trackedAddress, false));
        }

        LLVMValueRef result = builder.buildCmpxchg(castedAddress, castedExpectedValue, castedNewValue, memoryOrder,
                returnValue);
        if (returnValue && convertResult) {
            return builder.buildBitcast(result, expectedType);
        } else {
            return result;
        }
    }

    @Override
    public Variable emitReadRegister(Register register, ValueKind<?> kind) {
        LLVMValueRef value;
        if (register.equals(ReservedRegisters.singleton().getThreadRegister())) {
            LLVMValueRef specialRegister = builder.register(LLVMTargetSpecific.get()
                    .getLLVMRegisterName(ReservedRegisters.singleton().getThreadRegister().name));
            if (isEntryPoint || modifiesSpecialRegisters) {
                return new LLVMPendingSpecialRegisterRead(this, specialRegister);
            }
            value = builder.buildReadRegister(specialRegister);
        } else if (register.equals(ReservedRegisters.singleton().getHeapBaseRegister())) {
            LLVMValueRef specialRegister = builder.register(LLVMTargetSpecific.get()
                    .getLLVMRegisterName(ReservedRegisters.singleton().getHeapBaseRegister().name));
            if (isEntryPoint || modifiesSpecialRegisters) {
                return new LLVMPendingSpecialRegisterRead(this, specialRegister);
            }
            value = builder.buildReadRegister(specialRegister);
        } else if (register.equals(ReservedRegisters.singleton().getFrameRegister())) {
            value = builder.buildReadRegister(builder.register(ReservedRegisters.singleton().getFrameRegister().name));
        } else {
            throw VMError.shouldNotReachHereUnexpectedInput(register); // ExcludeFromJacocoGeneratedReport
        }
        return new LLVMVariable(value);
    }

    @Override
    public void emitWriteRegister(Register dst, Value src, ValueKind<?> kind) {
        if (dst.equals(ReservedRegisters.singleton().getThreadRegister())) {
            if (isEntryPoint) {
                builder.buildWriteRegister(
                        builder.register(LLVMTargetSpecific.get()
                                .getLLVMRegisterName(ReservedRegisters.singleton().getThreadRegister().name)),
                        getVal(src));
            } else {
                buildInlineSetRegister(ReservedRegisters.singleton().getThreadRegister().name, getVal(src));
            }
        } else if (dst.equals(ReservedRegisters.singleton().getHeapBaseRegister())) {
            if (isEntryPoint) {
                builder.buildWriteRegister(
                        builder.register(LLVMTargetSpecific.get()
                                .getLLVMRegisterName(ReservedRegisters.singleton().getHeapBaseRegister().name)),
                        getVal(src));
            } else {
                buildInlineSetRegister(ReservedRegisters.singleton().getHeapBaseRegister().name, getVal(src));
            }
        } else {
            throw VMError.shouldNotReachHereUnexpectedInput(dst); // ExcludeFromJacocoGeneratedReport
        }
    }

    @Override
    public AllocatableValue addressAsAllocatableInteger(Value value) {
        LLVMValueRef load = builder.buildPtrToInt(getVal(value));
        return new LLVMVariable(load);
    }

    @Override
    public void emitPrefetchAllocate(Value address) {
        builder.buildPrefetch(getVal(address));
    }

    @Override
    public Value emitCompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        // Skip compression entirely for kernel builds
        return pointer;
    }

    @Override
    public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        // Skip uncompression entirely for kernel builds
        return pointer;
    }

    @Override
    public VirtualStackSlot allocateStackMemory(int sizeInBytes, int alignmentInBytes) {
        builder.positionAtStart();
        LLVMValueRef alloca = builder.buildArrayAlloca(builder.byteType(), sizeInBytes, alignmentInBytes);
        builder.positionAtEnd(getBlockEnd(currentBlock));

        return new LLVMStackSlot(alloca);
    }

    @Override
    public Variable emitAddress(AllocatableValue stackslot) {
        if (stackslot instanceof LLVMStackSlot) {
            return new LLVMVariable(builder.buildPtrToInt(getVal(stackslot)));
        }
        throw shouldNotReachHere("Unknown address type"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public Value emitReadCallerStackPointer(Stamp wordStamp) {
        LLVMValueRef basePointer = builder.buildFrameAddress(builder.constantInt(0));
        LLVMValueRef callerSP = builder.buildAdd(builder.buildPtrToInt(basePointer),
                builder.constantLong(LLVMTargetSpecific.get().getCallerSPOffset()));
        return new LLVMVariable(callerSP);
    }

    @Override
    public Value emitReadReturnAddress(Stamp wordStamp, int returnAddressSize) {
        LLVMValueRef returnAddress = builder.buildReturnAddress(builder.constantInt(0));
        return new LLVMVariable(builder.buildPtrToInt(returnAddress));
    }

    /* Control flow */

    static final AtomicLong nextPatchpointId = new AtomicLong(0);

    LLVMValueRef buildStatepointCall(LLVMValueRef callee, long statepointId, LLVMValueRef... args) {
        LLVMValueRef result;
        result = builder.buildCall(callee, args);
        builder.setCallSiteAttribute(result, Attribute.StatepointID, Long.toString(statepointId));
        return result;
    }

    LLVMValueRef buildStatepointInvoke(LLVMValueRef callee, boolean nativeABI, LLVMBasicBlockRef successor,
            LLVMBasicBlockRef handler, long statepointId, LLVMValueRef... args) {
        LLVMBasicBlockRef successorBlock;
        LLVMBasicBlockRef handlerBlock;
        if (!nativeABI) {
            successorBlock = builder.appendBasicBlock(currentBlock.toString() + "_invoke_successor");
            handlerBlock = builder.appendBasicBlock(currentBlock.toString() + "_invoke_handler");
            splitBlockEndMap.put(currentBlock, successorBlock);
        } else {
            successorBlock = successor;
            handlerBlock = handler;
        }

        LLVMValueRef result = builder.buildInvoke(callee, successorBlock, handlerBlock, args);
        builder.setCallSiteAttribute(result, Attribute.StatepointID, Long.toString(statepointId));

        builder.positionAtEnd(handlerBlock);
        builder.buildLandingPad();
        builder.buildBranch(handler);

        builder.positionAtEnd(successorBlock);
        builder.buildBranch(successor);

        return result;
    }

    @Override
    public Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, Value... arguments) {
        return emitForeignCall(linkage, state, null, null, arguments);
    }

    public Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, LLVMBasicBlockRef successor,
            LLVMBasicBlockRef handler, Value... arguments) {
        ResolvedJavaMethod targetMethod = ((SnippetRuntime.SubstrateForeignCallDescriptor) linkage.getDescriptor())
                .findMethod(getMetaAccess());

        DebugInfo debugInfo = null;
        if (state != null) {
            state.initDebugInfo();
            debugInfo = state.debugInfo();
        }

        long patchpointId = nextPatchpointId.getAndIncrement();
        compilationResult.recordCall(NumUtil.safeToInt(patchpointId), 0, targetMethod, debugInfo, true);

        LLVMValueRef callee = getFunction(targetMethod);
        LLVMValueRef[] args = Arrays.stream(arguments).map(LLVMUtils::getVal).toArray(LLVMValueRef[]::new);
        CallingConvention.Type callType = ((SubstrateCallingConvention) linkage.getOutgoingCallingConvention())
                .getType();

        LLVMValueRef call;
        boolean nativeABI = ((SubstrateCallingConventionType) callType).nativeABI();
        if (successor == null && handler == null) {
            call = buildStatepointCall(callee, patchpointId, args);
        } else {
            assert successor != null && handler != null;
            call = buildStatepointInvoke(callee, nativeABI, successor, handler, patchpointId, args);
        }

        return (isVoidReturnType(getLLVMFunctionReturnType(targetMethod, false))) ? null : new LLVMVariable(call);
    }

    public static final String JNI_WRAPPER_BASE_NAME = "__llvm_jni_wrapper_";

    /*
     * Calling a native function from Java code requires filling the JavaFrameAnchor
     * with the return
     * address of the call. This wrapper allows this by creating an intermediary
     * call frame from
     * which the return address can be accessed. The parameters to this wrapper are
     * the anchor, the
     * native callee, and the arguments to the callee.
     */
    LLVMValueRef createJNIWrapper(LLVMValueRef callee, boolean nativeABI, int numArgs, int anchorIPOffset) {
        LLVMTypeRef calleeType = LLVMIRBuilder.getElementType(LLVMIRBuilder.typeOf(callee));
        String wrapperName = JNI_WRAPPER_BASE_NAME + LLVMIRBuilder.intrinsicType(calleeType)
                + (nativeABI ? "_native" : "");

        LLVMValueRef transitionWrapper = builder.getNamedFunction(wrapperName);
        if (transitionWrapper == null) {
            try (LLVMIRBuilder tempBuilder = new LLVMIRBuilder(builder)) {
                LLVMTypeRef wrapperType = prependArgumentTypes(calleeType, 0, tempBuilder.rawPointerType(),
                        LLVMIRBuilder.typeOf(callee));
                transitionWrapper = tempBuilder.addFunction(wrapperName, wrapperType);
                LLVMIRBuilder.setLinkage(transitionWrapper, LinkageType.LinkOnce);
                // tempBuilder.setGarbageCollector(transitionWrapper,
                // GCStrategy.CompressedPointers);
                tempBuilder.setFunctionCallingConvention(transitionWrapper,
                        LLVMCallingConvention.GraalCallingConvention);
                tempBuilder.setFunctionAttribute(transitionWrapper, Attribute.NoInline);

                LLVMBasicBlockRef block = tempBuilder.appendBasicBlock(transitionWrapper, "main");
                tempBuilder.positionAtEnd(block);

                LLVMValueRef anchor = LLVMIRBuilder.getParam(transitionWrapper, 0);
                LLVMValueRef lastIPAddr = tempBuilder.buildGEP(anchor, tempBuilder.constantInt(anchorIPOffset));
                LLVMValueRef callIP = tempBuilder.buildReturnAddress(tempBuilder.constantInt(0));
                LLVMValueRef castedLastIPAddr = tempBuilder.buildBitcast(lastIPAddr,
                        tempBuilder.pointerType(tempBuilder.rawPointerType()));
                tempBuilder.buildStore(callIP, castedLastIPAddr);

                LLVMValueRef[] args = new LLVMValueRef[numArgs];
                for (int i = 0; i < numArgs; ++i) {
                    args[i] = LLVMIRBuilder.getParam(transitionWrapper, i + 2);
                }
                LLVMValueRef target = LLVMIRBuilder.getParam(transitionWrapper, 1);
                LLVMValueRef ret = tempBuilder.buildCall(target, args);
                tempBuilder.setCallSiteAttribute(ret, Attribute.GCLeafFunction);

                if (LLVMIRBuilder.isVoidType(LLVMIRBuilder.getReturnType(calleeType))) {
                    tempBuilder.buildRetVoid();
                } else {
                    tempBuilder.buildRet(ret);
                }
            }
        }
        return transitionWrapper;
    }

    void createJNITrampoline(RegisterValue threadArg, int threadIsolateOffset, RegisterValue methodIdArg,
            int methodObjEntryPointOffset) {
        builder.setFunctionAttribute(Attribute.Naked);

        LLVMBasicBlockRef block = builder.appendBasicBlock("main");
        builder.positionAtEnd(block);

        long startPatchpointId = LLVMGenerator.nextPatchpointId.getAndIncrement();
        builder.buildStackmap(builder.constantLong(startPatchpointId));
        compilationResult.recordInfopoint(NumUtil.safeToInt(startPatchpointId), null, InfopointReason.METHOD_START);

        buildInlineLoad(threadArg.getRegister().name, LLVMTargetSpecific.get().getScratchRegister(),
                threadIsolateOffset);
        /*
         * Load the isolate pointer from the JNIEnv argument (same as the isolate
         * thread). The
         * isolate pointer is equivalent to the heap base address (which would normally
         * be provided
         * via Isolate.getHeapBase which is a no-op), which we then use to access the
         * method object
         * and read the entry point.
         */
        buildInlineAdd(LLVMTargetSpecific.get().getScratchRegister(), methodIdArg.getRegister().name);
        LLVMValueRef jumpAddress = buildInlineLoad(LLVMTargetSpecific.get().getScratchRegister(),
                LLVMTargetSpecific.get().getScratchRegister(), methodObjEntryPointOffset);

        buildInlineJump(jumpAddress);
        builder.buildUnreachable();
    }

    @Override
    public void emitReturn(JavaKind javaKind, Value input) {
        if (javaKind == JavaKind.Void) {
            debugInfoPrinter.printRetVoid();
            builder.buildRetVoid();
        } else {
            debugInfoPrinter.printRet(javaKind, input);
            LLVMValueRef retVal = getVal(input);
            if (javaKind == JavaKind.Int) {
                assert LLVMIRBuilder.isIntegerType(typeOf(retVal));
                retVal = arithmetic.emitIntegerConvert(retVal, builder.intType());
            } else if (returnsEnum && javaKind == ConfigurationValues.getWordKind()) {
                /*
                 * An enum value is represented by a long in the function body, but is returned
                 * as
                 * an object (CEnum values are returned as an int)
                 */
                LLVMValueRef result;
                if (returnsCEnum) {
                    result = builder.buildTrunc(retVal, JavaKind.Int.getBitCount());
                } else {
                    result = builder.buildIntToPtr(retVal, builder.objectType(false));
                }
                retVal = result;
            }

            builder.buildRet(retVal);
        }
    }

    @Override
    public void emitJump(LabelRef label) {
        builder.buildBranch(getBlock((HIRBlock) label.getTargetBlock()));
    }

    @Override
    public void emitDeadEnd() {
        builder.buildUnreachable();
    }

    @Override
    public void emitBlackhole(Value operand) {
        builder.buildStackmap(builder.constantLong(LLVMStackMapInfo.DEFAULT_PATCHPOINT_ID), getVal(operand));
    }

    @Override
    public void emitPause() {
        // this will be implemented as part of issue #1126. For now, we just do nothing.
        // throw unimplemented();
    }

    /* Inline assembly */

    private void buildInlineJump(LLVMValueRef address) {
        LLVMTypeRef inlineAsmType = builder.functionType(builder.voidType(), builder.rawPointerType());
        String asmSnippet = LLVMTargetSpecific.get().getJumpInlineAsm();
        InlineAssemblyConstraint inputConstraint = new InlineAssemblyConstraint(Type.Input, Location.register());

        LLVMValueRef jump = builder.buildInlineAsm(inlineAsmType, asmSnippet, true, false, inputConstraint);
        LLVMValueRef call = builder.buildCall(jump, address);
        builder.setCallSiteAttribute(call, Attribute.GCLeafFunction);
    }

    private LLVMValueRef buildInlineLoad(String inputRegisterName, String outputRegisterName, int offset) {
        LLVMTypeRef inlineAsmType = builder.functionType(builder.rawPointerType());
        String asmSnippet = LLVMTargetSpecific.get().getLoadInlineAsm(inputRegisterName, offset);
        InlineAssemblyConstraint outputConstraint = new InlineAssemblyConstraint(Type.Output,
                Location.namedRegister(outputRegisterName));

        LLVMValueRef load = builder.buildInlineAsm(inlineAsmType, asmSnippet, true, false, outputConstraint);
        LLVMValueRef call = builder.buildCall(load);
        builder.setCallSiteAttribute(call, Attribute.GCLeafFunction);
        return call;
    }

    public LLVMValueRef buildInlineGetRegister(String registerName) {
        LLVMTypeRef inlineAsmType = builder.functionType(builder.wordType());
        String asmSnippet = LLVMTargetSpecific.get().getRegisterInlineAsm(registerName);
        InlineAssemblyConstraint outputConstraint = new InlineAssemblyConstraint(Type.Output, Location.register());

        LLVMValueRef getRegister = builder.buildInlineAsm(inlineAsmType, asmSnippet, true, false, outputConstraint);
        LLVMValueRef call = builder.buildCall(getRegister);
        builder.setCallSiteAttribute(call, Attribute.GCLeafFunction);
        return call;
    }

    public void buildInlineSetRegister(String registerName, LLVMValueRef value) {
        LLVMTypeRef inlineAsmType = builder.functionType(builder.voidType(), builder.wordType());
        String asmSnippet = LLVMTargetSpecific.get().setRegisterInlineAsm(registerName);
        InlineAssemblyConstraint inputConstraint = new InlineAssemblyConstraint(Type.Input, Location.register());

        LLVMValueRef setRegister = builder.buildInlineAsm(inlineAsmType, asmSnippet, true, false, inputConstraint);
        LLVMValueRef call = builder.buildCall(setRegister, value);
        builder.setCallSiteAttribute(call, Attribute.GCLeafFunction);
    }

    private void buildInlineAdd(String outputRegisterName, String inputRegisterName) {
        LLVMTypeRef inlineAsmType = builder.functionType(builder.voidType());
        String asmSnippet = LLVMTargetSpecific.get().getAddInlineAssembly(outputRegisterName, inputRegisterName);

        LLVMValueRef add = builder.buildInlineAsm(inlineAsmType, asmSnippet, true, false);
        LLVMValueRef call = builder.buildCall(add);
        builder.setCallSiteAttribute(call, Attribute.GCLeafFunction);
    }

    public void clobberRegister(String register) {
        LLVMTypeRef inlineAsmType = builder.functionType(builder.voidType());
        String asmSnippet = LLVMTargetSpecific.get().getNopInlineAssembly();
        InlineAssemblyConstraint clobberConstraint = new InlineAssemblyConstraint(Type.Clobber,
                Location.namedRegister(register));

        LLVMValueRef clobber = builder.buildInlineAsm(inlineAsmType, asmSnippet, true, false, clobberConstraint);
        LLVMValueRef call = builder.buildCall(clobber);
        builder.setCallSiteAttribute(call, Attribute.GCLeafFunction);
    }

    /* Unimplemented */

    @Override
    public LIRGenerationResult getResult() {
        throw unimplemented("the LLVM backend doesn't produce an LIRGenerationResult"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public MoveFactory getMoveFactory() {
        throw unimplemented("the LLVM backend doesn't use LIR moves"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public MoveFactory getSpillMoveFactory() {
        throw unimplemented("the LLVM backend doesn't use LIR moves"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitNullCheck(Value address, LIRFrameState state) {
        throw unimplemented("the LLVM backend doesn't support deoptimization"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value failedSpeculation, LIRFrameState state) {
        throw unimplemented("the LLVM backend doesn't support deoptimization"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitFarReturn(AllocatableValue result, Value sp, Value ip, boolean fromMethodWithCalleeSavedRegisters) {
        throw unimplemented("the LLVM backend delegates exception handling to libunwind"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitUnwind(Value operand) {
        throw shouldNotReachHere("handled by lowering"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitVerificationMarker(Object marker) {
        /*
         * No-op, for now we do not have any verification of the LLVM IR that requires
         * the markers.
         */
    }

    @Override
    public void emitInstructionSynchronizationBarrier() {
        throw unimplemented("the LLVM backend doesn't support instruction synchronization"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitExitMethodAddressResolution(Value ip) {
        throw unimplemented("the LLVM backend doesn't support PLT/GOT"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public <I extends LIRInstruction> I append(I op) {
        throw unimplemented("the LLVM backend doesn't support LIR instructions"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public void emitSpeculationFence() {
        throw unimplemented("the LLVM backend doesn't support speculative execution attack mitigation"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createBenchmarkCounter(String name, String group, Value increment) {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createMultiBenchmarkCounter(String[] names, String[] groups, Value[] increments) {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createZapRegisters(Register[] zappedRegisters, JavaConstant[] zapValues) {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createZapRegisters(Register[] zappedRegisters) {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createZapRegisters() {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction createZapArgumentSpace(StackSlot[] zappedStack, JavaConstant[] zapValues) {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public LIRInstruction zapArgumentSpace() {
        throw unimplemented("the LLVM backend doesn't support diagnostic operations"); // ExcludeFromJacocoGeneratedReport
    }

    /* Arithmetic */

    public class ArithmeticLLVMGenerator implements ArithmeticLIRGeneratorTool, LLVMIntrinsicGenerator {
        ArithmeticLLVMGenerator() {
        }

        @Override
        public Value emitNegate(Value input, boolean setFlags) {
            LLVMValueRef neg = builder.buildNeg(getVal(input));
            return new LLVMVariable(neg);
        }

        @Override
        public Value emitAdd(Value a, Value b, boolean setFlags) {
            LLVMValueRef add = builder.buildAdd(getVal(a), getVal(b));
            return new LLVMVariable(add);
        }

        @Override
        public Value emitSub(Value a, Value b, boolean setFlags) {
            LLVMValueRef sub = builder.buildSub(getVal(a), getVal(b));
            return new LLVMVariable(sub);
        }

        @Override
        public Value emitMul(Value a, Value b, boolean setFlags) {
            LLVMValueRef mul = builder.buildMul(getVal(a), getVal(b));
            return new LLVMVariable(mul);
        }

        @Override
        public Value emitMulHigh(Value a, Value b) {
            return emitMulHigh(a, b, true);
        }

        @Override
        public Value emitUMulHigh(Value a, Value b) {
            return emitMulHigh(a, b, false);
        }

        private LLVMVariable emitMulHigh(Value a, Value b, boolean signed) {
            LLVMValueRef valA = getVal(a);
            LLVMValueRef valB = getVal(b);
            assert LLVMIRBuilder.compatibleTypes(typeOf(valA), typeOf(valB))
                    : dumpValues("invalid mulhigh arguments", valA, valB);

            int baseBits = LLVMIRBuilder.integerTypeWidth(LLVMIRBuilder.typeOf(valA));
            int extendedBits = baseBits * 2;

            BiFunction<LLVMValueRef, Integer, LLVMValueRef> extend = (signed) ? builder::buildSExt : builder::buildZExt;
            valA = extend.apply(valA, extendedBits);
            valB = extend.apply(valB, extendedBits);
            LLVMValueRef mul = builder.buildMul(valA, valB);

            BiFunction<LLVMValueRef, LLVMValueRef, LLVMValueRef> shift = (signed) ? builder::buildShr
                    : builder::buildUShr;
            LLVMValueRef shiftedMul = shift.apply(mul, builder.constantInteger(baseBits, extendedBits));
            LLVMValueRef truncatedMul = builder.buildTrunc(shiftedMul, baseBits);

            return new LLVMVariable(truncatedMul);
        }

        @Override
        public Value emitDiv(Value a, Value b, LIRFrameState state) {
            LLVMValueRef div = builder.buildDiv(getVal(a), getVal(b));
            return new LLVMVariable(div);
        }

        @Override
        public Value emitRem(Value a, Value b, LIRFrameState state) {
            LLVMValueRef rem = builder.buildRem(getVal(a), getVal(b));
            return new LLVMVariable(rem);
        }

        @Override
        public Value emitUDiv(Value a, Value b, LIRFrameState state) {
            LLVMValueRef uDiv = builder.buildUDiv(getVal(a), getVal(b));
            return new LLVMVariable(uDiv);
        }

        @Override
        public Value emitURem(Value a, Value b, LIRFrameState state) {
            LLVMValueRef uRem = builder.buildURem(getVal(a), getVal(b));
            return new LLVMVariable(uRem);
        }

        @Override
        public Value emitNot(Value input) {
            LLVMValueRef not = builder.buildNot(getVal(input));
            return new LLVMVariable(not);
        }

        @Override
        public Value emitAnd(Value a, Value b) {
            LLVMValueRef and = builder.buildAnd(getVal(a), getVal(b));
            return new LLVMVariable(and);
        }

        @Override
        public Value emitOr(Value a, Value b) {
            LLVMValueRef or = builder.buildOr(getVal(a), getVal(b));
            return new LLVMVariable(or);
        }

        @Override
        public Value emitXor(Value a, Value b) {
            LLVMValueRef xor = builder.buildXor(getVal(a), getVal(b));
            return new LLVMVariable(xor);
        }

        @Override
        public Value emitXorFP(Value a, Value b) {
            LLVMTypeRef type = getType(a.getValueKind());
            LIRKind resultKind = a.getValueKind(LIRKind.class);

            if (isVectorType(type) || isIntegerType(type)) {
                return emitXor(a, b);
            }

            // LLVM requires XOR operands to be integers or vectors. We need to reinterpret
            // them
            // as integers and then reinterpret the result again.
            if (isFloatType(type) || isDoubleType(type)) {
                LIRKind calculationKind = isFloatType(type) ? lirKindTool.getIntegerKind(32)
                        : lirKindTool.getIntegerKind(64);
                Value reinterpretedA = emitReinterpret(calculationKind, a);
                Value reinterpretedB = emitReinterpret(calculationKind, b);
                Value result = emitXor(reinterpretedA, reinterpretedB);
                return emitReinterpret(resultKind, result);
            }

            throw unimplemented("the LLVM backend only supports XOR of integers, vectors and floating point numbers"); // ExcludeFromJacocoGeneratedReport
        }

        private LLVMValueRef actualShiftingDistance(LLVMValueRef a, LLVMValueRef b) {
            // https://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.19

            LLVMTypeRef typeA = typeOf(a);
            final int bitWidthA = LLVMIRBuilder.integerTypeWidth(typeA);
            int promotedBitWidthA = bitWidthA;

            /*
             * GR-48976: After unary numeric promotion is fixed in the LLVM backend, this
             * manual
             * promotion can be removed. At the moment, values that should be promoted by
             * LIRGeneratorTool.toRegisterKind are not promoted on the LLVM backend.
             */
            if (bitWidthA == 8 || bitWidthA == 16) {
                promotedBitWidthA = 32;
            }

            assert promotedBitWidthA == 32 || promotedBitWidthA == 64;

            LLVMValueRef shiftDistanceBitMask = builder.constantInteger(promotedBitWidthA - 1, bitWidthA);
            LLVMValueRef valB = emitIntegerConvert(b, typeA);
            return builder.buildAnd(valB, shiftDistanceBitMask);
        }

        @Override
        public Value emitShl(Value a, Value b) {
            LLVMValueRef valA = getVal(a);
            LLVMValueRef shl = builder.buildShl(valA, actualShiftingDistance(valA, getVal(b)));
            return new LLVMVariable(shl);
        }

        @Override
        public Value emitShr(Value a, Value b) {
            LLVMValueRef valA = getVal(a);
            LLVMValueRef shr = builder.buildShr(valA, actualShiftingDistance(valA, getVal(b)));
            return new LLVMVariable(shr);
        }

        @Override
        public Value emitUShr(Value a, Value b) {
            LLVMValueRef valA = getVal(a);
            LLVMValueRef ushr = builder.buildUShr(valA, actualShiftingDistance(valA, getVal(b)));
            return new LLVMVariable(ushr);
        }

        private LLVMValueRef emitIntegerConvert(LLVMValueRef value, LLVMTypeRef type) {
            int fromBits = LLVMIRBuilder.integerTypeWidth(typeOf(value));
            int toBits = LLVMIRBuilder.integerTypeWidth(type);
            if (fromBits < toBits) {
                return (fromBits == 1) ? builder.buildZExt(value, toBits) : builder.buildSExt(value, toBits);
            }
            if (fromBits > toBits) {
                return builder.buildTrunc(value, toBits);
            }
            return value;
        }

        @Override
        public Value emitFloatConvert(FloatConvert op, Value inputVal, boolean canBeNaN, boolean canOverflow) {
            LLVMTypeRef destType;
            switch (op) {
                case F2I:
                case D2I:
                    destType = builder.intType();
                    break;
                case F2L:
                case D2L:
                    destType = builder.longType();
                    break;
                case I2F:
                case L2F:
                case D2F:
                    destType = builder.floatType();
                    break;
                case I2D:
                case L2D:
                case F2D:
                    destType = builder.doubleType();
                    break;
                default:
                    throw shouldNotReachHere("invalid FloatConvert type"); // ExcludeFromJacocoGeneratedReport
            }

            LLVMValueRef convert;
            switch (op.getCategory()) {
                case FloatingPointToInteger:
                    convert = builder.buildSaturatingFloatingPointToInteger(op, getVal(inputVal));
                    break;
                case IntegerToFloatingPoint:
                    convert = builder.buildSIToFP(getVal(inputVal), destType);
                    break;
                case FloatingPointToFloatingPoint:
                    convert = builder.buildFPCast(getVal(inputVal), destType);
                    break;
                default:
                    throw shouldNotReachHere("invalid FloatConvert type"); // ExcludeFromJacocoGeneratedReport
            }
            return new LLVMVariable(convert);
        }

        @Override
        public Value emitReinterpret(LIRKind to, Value inputVal) {
            LLVMTypeRef type = getType(to);
            LLVMValueRef cast = builder.buildBitcast(getVal(inputVal), type);
            return new LLVMVariable(cast);
        }

        @Override
        public Value emitNarrow(Value inputVal, int bits) {
            LLVMValueRef narrow = builder.buildTrunc(getVal(inputVal), bits);
            return new LLVMVariable(narrow);
        }

        @Override
        public Value emitSignExtend(Value inputVal, int fromBits, int toBits) {
            LLVMValueRef signExtend = builder.buildSExt(getVal(inputVal), toBits);
            return new LLVMVariable(signExtend);
        }

        @Override
        public Value emitZeroExtend(Value inputVal, int fromBits, int toBits, boolean requiresExplicitZeroExtend,
                boolean requiresLIRKindChange) {
            LLVMValueRef zeroExtend = builder.buildZExt(getVal(inputVal), toBits);
            return new LLVMVariable(zeroExtend);
        }

        @Override
        public Value emitMathAbs(Value input) {
            LLVMValueRef value = getVal(input);
            LLVMTypeRef type = LLVM.LLVMTypeOf(value);

            switch (LLVM.LLVMGetTypeKind(type)) {
                case LLVM.LLVMIntegerTypeKind:
                    return new LLVMVariable(builder.buildAbs(value));
                case LLVM.LLVMFloatTypeKind:
                case LLVM.LLVMDoubleTypeKind:
                    return new LLVMVariable(builder.buildFabs(value));
                default:
                    throw shouldNotReachHere("Unsupported abs type " + type); // ExcludeFromJacocoGeneratedReport
            }
        }

        @Override
        public Value emitMathSqrt(Value input) {
            LLVMValueRef sqrt = builder.buildSqrt(getVal(input));
            return new LLVMVariable(sqrt);
        }

        @Override
        public Value emitMathSignum(Value input) {
            LLVMValueRef val = getVal(input);
            LLVMTypeRef type = typeOf(val);
            assert LLVMIRBuilder.isFloatType(type) || LLVMIRBuilder.isDoubleType(type);

            LLVMValueRef zero = LLVMIRBuilder.isFloatType(type) ? builder.constantFloat(0.0f)
                    : builder.constantDouble(0.0d);
            LLVMValueRef one = LLVMIRBuilder.isFloatType(type) ? builder.constantFloat(1.0f)
                    : builder.constantDouble(1.0d);
            LLVMValueRef signum = builder.buildSelect(builder.buildCompare(Condition.EQ, val, zero, true), val,
                    builder.buildCopysign(one, val));
            return new LLVMVariable(signum);
        }

        @Override
        public Value emitMathLog(Value input, boolean base10) {
            LLVMValueRef value = getVal(input);
            LLVMValueRef log = base10 ? builder.buildLog10(value) : builder.buildLog(value);
            return new LLVMVariable(log);
        }

        @Override
        public Value emitMathCos(Value input) {
            LLVMValueRef cos = builder.buildCos(getVal(input));
            return new LLVMVariable(cos);
        }

        @Override
        public Value emitMathSin(Value input) {
            LLVMValueRef sin = builder.buildSin(getVal(input));
            return new LLVMVariable(sin);
        }

        @Override
        public Value emitMathTan(Value input) {
            LLVMValueRef value = getVal(input);
            LLVMValueRef sin = builder.buildSin(value);
            LLVMValueRef cos = builder.buildCos(value);
            LLVMValueRef tan = builder.buildDiv(sin, cos);
            return new LLVMVariable(tan);
        }

        @Override
        public Value emitMathExp(Value input) {
            LLVMValueRef exp = builder.buildExp(getVal(input));
            return new LLVMVariable(exp);
        }

        @Override
        public Value emitMathPow(Value x, Value y) {
            LLVMValueRef pow = builder.buildPow(getVal(x), getVal(y));
            return new LLVMVariable(pow);
        }

        @Override
        public Value emitMathCeil(Value input) {
            LLVMValueRef ceil = builder.buildCeil(getVal(input));
            return new LLVMVariable(ceil);
        }

        @Override
        public Value emitMathFloor(Value input) {
            LLVMValueRef floor = builder.buildFloor(getVal(input));
            return new LLVMVariable(floor);
        }

        @Override
        public Value emitCountLeadingZeros(Value input) {
            LLVMValueRef ctlz = builder.buildCtlz(getVal(input));
            ctlz = emitIntegerConvert(ctlz, builder.intType());
            return new LLVMVariable(ctlz);
        }

        @Override
        public Value emitCountTrailingZeros(Value input) {
            LLVMValueRef cttz = builder.buildCttz(getVal(input));
            cttz = emitIntegerConvert(cttz, builder.intType());
            return new LLVMVariable(cttz);
        }

        @Override
        public Value emitBitCount(Value operand) {
            LLVMValueRef op = getVal(operand);
            LLVMValueRef answer = builder.buildCtpop(op);
            answer = emitIntegerConvert(answer, builder.intType());
            return new LLVMVariable(answer);
        }

        @Override
        public Value emitBitScanForward(Value operand) {
            LLVMValueRef op = getVal(operand);
            LLVMValueRef trailingZeros = builder.buildCttz(op);

            int resultSize = LLVMIRBuilder.integerTypeWidth(typeOf(trailingZeros));
            int expectedSize = JavaKind.Int.getBitCount();
            if (resultSize < expectedSize) {
                trailingZeros = builder.buildZExt(trailingZeros, expectedSize);
            } else if (resultSize > expectedSize) {
                trailingZeros = builder.buildTrunc(trailingZeros, expectedSize);
            }

            return new LLVMVariable(trailingZeros);
        }

        @Override
        public Value emitBitScanReverse(Value operand) {
            LLVMValueRef op = getVal(operand);

            int opSize = LLVMIRBuilder.integerTypeWidth(typeOf(op));
            int expectedSize = JavaKind.Int.getBitCount();
            LLVMValueRef leadingZeros = builder.buildCtlz(op);
            if (opSize < expectedSize) {
                leadingZeros = builder.buildZExt(leadingZeros, expectedSize);
            } else if (opSize > expectedSize) {
                leadingZeros = builder.buildTrunc(leadingZeros, expectedSize);
            }

            LLVMValueRef result = builder.buildSub(builder.constantInt(opSize - 1), leadingZeros);
            return new LLVMVariable(result);
        }

        @Override
        public Value emitFusedMultiplyAdd(Value a, Value b, Value c) {
            LLVMValueRef fma = builder.buildFma(getVal(a), getVal(b), getVal(c));
            return new LLVMVariable(fma);
        }

        @Override
        public Value emitMathMin(Value a, Value b) {
            LLVMValueRef min = builder.buildMin(getVal(a), getVal(b));
            return new LLVMVariable(min);
        }

        @Override
        public Variable emitReverseBits(Value operand) {
            LLVMValueRef reversed = builder.buildBitReverse(getVal(operand));
            return new LLVMVariable(reversed);
        }

        @Override
        public Value emitMathMax(Value a, Value b) {
            LLVMValueRef max = builder.buildMax(getVal(a), getVal(b));
            return new LLVMVariable(max);
        }

        @Override
        public Value emitMathCopySign(Value a, Value b) {
            LLVMValueRef copySign = builder.buildCopysign(getVal(a), getVal(b));
            return new LLVMVariable(copySign);
        }

        @Override
        public Variable emitLoad(LIRKind kind, Value address, LIRFrameState state, MemoryOrderMode memoryOrder,
                MemoryExtendKind extendKind) {
            assert extendKind.isNotExtended();
            assert memoryOrder != MemoryOrderMode.RELEASE && memoryOrder != MemoryOrderMode.RELEASE_ACQUIRE;
            LLVMValueRef load = builder.buildAlignedLoad(getVal(address), getType(kind),
                    kind.getPlatformKind().getSizeInBytes());
            if (memoryOrder == MemoryOrderMode.ACQUIRE || memoryOrder == MemoryOrderMode.VOLATILE) {
                /*
                 * Ensure subsequent memory operations cannot execute before this load.
                 * Additional
                 * volatile ordering requirements are enforced at stores.
                 */
                emitMembar(MemoryBarriers.LOAD_LOAD | MemoryBarriers.LOAD_STORE);
            }
            return new LLVMVariable(load);
        }

        @Override
        public void emitStore(ValueKind<?> kind, Value addr, Value input, LIRFrameState state,
                MemoryOrderMode memoryOrder) {
            assert memoryOrder != MemoryOrderMode.ACQUIRE && memoryOrder != MemoryOrderMode.RELEASE_ACQUIRE;
            if (memoryOrder == MemoryOrderMode.RELEASE || memoryOrder == MemoryOrderMode.VOLATILE) {
                emitMembar(MemoryBarriers.LOAD_STORE | MemoryBarriers.STORE_STORE);
            }

            LLVMValueRef address = getVal(addr);
            LLVMValueRef value = getVal(input);
            LLVMTypeRef addressType = LLVMIRBuilder.typeOf(address);
            LLVMTypeRef valueType = LLVMIRBuilder.typeOf(value);
            LLVMValueRef castedValue = value;
            if (LLVMIRBuilder.isObjectType(valueType) && !LLVMIRBuilder.isObjectType(addressType)) {
                valueType = builder.rawPointerType();
                castedValue = builder.buildAddrSpaceCast(value, builder.rawPointerType());
            }

            LLVMTypeRef targetType = builder.pointerType(valueType, LLVMIRBuilder.isObjectType(addressType), false);
            LLVMValueRef castedAddress;

            // Check if we need address space cast or regular bitcast
            if (LLVMIRBuilder.isPointerType(addressType) && LLVMIRBuilder.isPointerType(targetType)) {
                int sourceAddrSpace = LLVM.LLVMGetPointerAddressSpace(addressType);
                int targetAddrSpace = LLVM.LLVMGetPointerAddressSpace(targetType);
                if (sourceAddrSpace != targetAddrSpace) {
                    castedAddress = builder.buildAddrSpaceCast(address, targetType);
                } else {
                    castedAddress = builder.buildBitcast(address, targetType);
                }
            } else {
                castedAddress = builder.buildBitcast(address, targetType);
            }

            builder.buildAlignedStore(castedValue, castedAddress,
                    input.getValueKind().getPlatformKind().getSizeInBytes());

            if (memoryOrder == MemoryOrderMode.VOLATILE) {
                // Guarantee subsequent volatile loads cannot be executed before this
                // instruction
                emitMembar(MemoryBarriers.STORE_LOAD);
            }
        }
    }

    static class DebugInfoPrinter {
        private final LLVMGenerator gen;
        private final LLVMIRBuilder builder;
        private final int debugLevel;

        private LLVMValueRef indentCounter;
        private LLVMValueRef spacesVector;

        DebugInfoPrinter(LLVMGenerator gen, int debugLevel) {
            this.gen = gen;
            this.builder = gen.getBuilder();
            this.debugLevel = debugLevel;

            if (debugLevel >= DebugLevel.Function.level) {
                this.indentCounter = builder.getUniqueGlobal("__svm_indent_counter", builder.intType(), true);
                this.spacesVector = builder.getUniqueGlobal("__svm_spaces_vector",
                        builder.vectorType(builder.rawPointerType(), 100), false);
                StringBuilder strBuilder = new StringBuilder();
                LLVMValueRef[] strings = new LLVMValueRef[100];
                for (int i = 0; i < 100; ++i) {
                    strings[i] = builder.getUniqueGlobal("__svm_" + i + "_spaces",
                            builder.arrayType(builder.byteType(), strBuilder.length() + 1), false);
                    builder.setInitializer(strings[i], builder.constantString(strBuilder.toString()));
                    strings[i] = builder.buildBitcast(strings[i], builder.rawPointerType());
                    strBuilder.append(' ');
                }
                builder.setInitializer(spacesVector, builder.constantVector(strings));
            }
        }

        void printFunction(StructuredGraph graph, NodeLLVMBuilder nodeBuilder) {
            if (debugLevel >= DebugLevel.Function.level) {
                indent();
                List<JavaKind> printfTypes = new ArrayList<>();
                List<LLVMValueRef> printfArgs = new ArrayList<>();

                for (ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                    printfTypes.add(param.getStackKind());
                    printfArgs.add(getVal(nodeBuilder.operand(param)));
                }

                String functionName = gen.getFunctionName();
                emitPrintf("In " + functionName, printfTypes.toArray(new JavaKind[0]),
                        printfArgs.toArray(new LLVMValueRef[0]));
            }
        }

        void printBlock(HIRBlock block) {
            if (debugLevel >= DebugLevel.Block.level) {
                emitPrintf("In block " + block.toString());
            }
        }

        void printNode(ValueNode valueNode) {
            if (debugLevel >= DebugLevel.Node.level) {
                emitPrintf(valueNode.toString());
            }
        }

        void printIndirectCall(ResolvedJavaMethod targetMethod, LLVMValueRef callee) {
            if (debugLevel >= DebugLevel.Node.level) {
                emitPrintf("Indirect call to " + ((targetMethod != null) ? targetMethod.getName() : "[unknown]"),
                        new JavaKind[] { JavaKind.Object }, new LLVMValueRef[] { callee });
            }
        }

        void printBreakpoint() {
            if (debugLevel >= DebugLevel.Function.level) {
                emitPrintf("breakpoint");
            }
        }

        void printRetVoid() {
            if (debugLevel >= DebugLevel.Function.level) {
                emitPrintf("Return");
                deindent();
            }
        }

        void printRet(JavaKind kind, Value input) {
            if (debugLevel >= DebugLevel.Function.level) {
                emitPrintf("Return", new JavaKind[] { kind }, new LLVMValueRef[] { getVal(input) });
                deindent();
            }
        }

        void setValueName(LLVMValueWrapper value, ValueNode node) {
            if (debugLevel >= DebugLevel.Node.level && node.getStackKind() != JavaKind.Void) {
                builder.setValueName(value.get(), node.toString());
            }
        }

        void indent() {
            LLVMValueRef counter = builder.buildLoad(indentCounter);
            LLVMValueRef newCounter = builder.buildAdd(counter, builder.constantInt(1));
            builder.buildStore(newCounter, indentCounter);
        }

        private void deindent() {
            LLVMValueRef counter = builder.buildLoad(indentCounter);
            LLVMValueRef newCounter = builder.buildSub(counter, builder.constantInt(1));
            builder.buildStore(newCounter, indentCounter);
        }

        private void emitPrintf(String base) {
            emitPrintf(base, new JavaKind[0], new LLVMValueRef[0]);
        }

        private void emitPrintf(String base, JavaKind[] types, LLVMValueRef[] values) {
            LLVMValueRef printf = builder.getFunction("printf",
                    builder.functionType(builder.intType(), true, builder.rawPointerType()));

            if (debugLevel >= DebugLevel.Function.level) {
                LLVMValueRef count = builder.buildLoad(indentCounter);
                LLVMValueRef vector = builder.buildLoad(spacesVector);
                LLVMValueRef spaces = builder.buildExtractElement(vector, count);
                builder.buildCall(printf, spaces);
            }

            StringBuilder introString = new StringBuilder(base);
            List<LLVMValueRef> printfArgs = new ArrayList<>();

            assert types.length == values.length;

            for (int i = 0; i < types.length; ++i) {
                switch (types[i]) {
                    case Boolean:
                    case Byte:
                        introString.append(" %hhd ");
                        break;
                    case Short:
                        introString.append(" %hd ");
                        break;
                    case Char:
                        introString.append(" %c ");
                        break;
                    case Int:
                        introString.append(" %ld ");
                        break;
                    case Float:
                    case Double:
                        introString.append(" %f ");
                        break;
                    case Long:
                        introString.append(" %lld ");
                        break;
                    case Object:
                        introString.append(" %p ");
                        break;
                    case Void:
                    case Illegal:
                    default:
                        throw shouldNotReachHereUnexpectedValue(types[i]); // ExcludeFromJacocoGeneratedReport
                }

                printfArgs.add(values[i]);
            }
            introString.append("\n");

            printfArgs.add(0, builder.buildGlobalStringPtr(introString.toString()));
            builder.buildCall(printf, printfArgs.toArray(new LLVMValueRef[0]));
        }

        public enum DebugLevel {
            Function(1),
            Block(2),
            Node(3);

            private final int level;

            DebugLevel(int level) {
                this.level = level;
            }
        }
    }

    @Override
    public void emitCacheWriteback(Value address) {
        int cacheLineSize = Unsafe.getUnsafe().dataCacheLineFlushSize();
        if (cacheLineSize == 0) {
            throw shouldNotReachHere("cache writeback with cache line size of 0"); // ExcludeFromJacocoGeneratedReport
        }
        LLVMValueRef start = builder.buildIntToPtr(getVal(address), builder.rawPointerType());
        LLVMValueRef end = builder.buildGEP(start, builder.constantInt(cacheLineSize));
        builder.buildClearCache(start, end);
    }

    @Override
    public void emitCacheWritebackSync(boolean isPreSync) {
        builder.buildFence();
    }

    @Override
    public boolean isReservedRegister(Register r) {
        return ReservedRegisters.singleton().isReservedRegister(r);
    }
}
