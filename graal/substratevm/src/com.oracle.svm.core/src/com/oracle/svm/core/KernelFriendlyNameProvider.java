package com.oracle.svm.core;

import java.lang.reflect.Member;
import java.util.function.BooleanSupplier;

import com.oracle.svm.core.feature.AutomaticallyRegisteredImageSingleton;

import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

/**
 * Kernel-friendly implementation for unique method names that generates predictable,
 * human-readable function names using the pattern Package_Class_methodName.
 * This makes it easier to write C runtime stubs for Java native methods.
 */
@AutomaticallyRegisteredImageSingleton(value = UniqueShortNameProvider.class, onlyWith = KernelFriendlyNameProvider.UseKernelFriendly.class)
public class KernelFriendlyNameProvider implements UniqueShortNameProvider {
    
    @Override
    public String uniqueShortName(ClassLoader loader, ResolvedJavaType declaringClass, String methodName, Signature methodSignature, boolean isConstructor) {
        // Debug: Print what we're receiving
        String result = generateKernelFriendlyName(declaringClass, methodName, methodSignature, isConstructor);
        System.out.println("KernelFriendlyNameProvider called with methodName: " + methodName + " -> " + result);
        return result;
    }

    @Override
    public String uniqueShortName(Member m) {
        String className = m.getDeclaringClass().getName();
        String methodName = m.getName();
        boolean isConstructor = m instanceof java.lang.reflect.Constructor;
        
        // For reflection-based calls, create a simple signature identifier
        String signatureId = createSimpleSignature(m);
        
        return generateKernelFriendlyName(className, methodName, signatureId, isConstructor);
    }

    @Override
    public String uniqueShortLoaderName(ClassLoader classLoader) {
        return "";  // No loader prefix for kernel-friendly names
    }
    
    private String generateKernelFriendlyName(ResolvedJavaType declaringClass, String methodName, Signature methodSignature, boolean isConstructor) {
        String className = declaringClass.toJavaName();
        String signatureId = createSimpleSignature(methodSignature);
        return generateKernelFriendlyName(className, methodName, signatureId, isConstructor);
    }
    
    private String generateKernelFriendlyName(String className, String methodName, String signatureId, boolean isConstructor) {
        // Convert package.Class and inner classes to Package_Class_InnerClass
        String mangledClassName = className.replace('.', '_').replace('$', '_');
        
        // Handle collision markers (e.g., method%1, <init>%2) by preserving them
        String cleanMethodName = methodName;
        String collisionSuffix = "";
        if (methodName.contains("%")) {
            int percentIndex = methodName.indexOf('%');
            cleanMethodName = methodName.substring(0, percentIndex);
            collisionSuffix = "_" + methodName.substring(percentIndex + 1); // Convert %1 to _1
            System.out.println("Collision detected: " + methodName + " -> clean=" + cleanMethodName + " suffix=" + collisionSuffix);
        }
        
        if (isConstructor) {
            // Handle both "init" and "<init>" constructor names
            if (cleanMethodName.equals("<init>")) {
                cleanMethodName = "init";
            }
            String result = mangledClassName + "_" + cleanMethodName + signatureId + collisionSuffix;
            System.out.println("Constructor result: " + result);
            return result;
        } else {
            String result = mangledClassName + "_" + cleanMethodName + signatureId + collisionSuffix;
            System.out.println("Method result: " + result);
            return result;
        }
    }
    
    private String createSimpleSignature(Signature methodSignature) {
        StringBuilder sb = new StringBuilder();
        
        // Add parameter types
        int paramCount = methodSignature.getParameterCount(false);
        if (paramCount == 0) {
            sb.append("_V"); // V for void parameters (no params)
        } else {
            sb.append("_");
            for (int i = 0; i < paramCount; i++) {
                String paramType = methodSignature.getParameterType(i, null).toJavaName();
                String simpleName = getReadableTypeName(paramType);
                sb.append(simpleName);
                if (i < paramCount - 1) {
                    sb.append("_");
                }
            }
        }
        
        // Add return type for additional uniqueness
        String returnType = methodSignature.getReturnType(null).toJavaName();
        if (!"void".equals(returnType)) {
            sb.append("_ret").append(getReadableTypeName(returnType));
        }
        
        return sb.toString();
    }
    
    private String createSimpleSignature(Member m) {
        StringBuilder sb = new StringBuilder();
        
        if (m instanceof java.lang.reflect.Method) {
            java.lang.reflect.Method method = (java.lang.reflect.Method) m;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0) {
                sb.append("_V"); // V for void parameters
            } else {
                sb.append("_");
                for (int i = 0; i < params.length; i++) {
                    String simpleName = getReadableTypeName(params[i].getName());
                    sb.append(simpleName);
                    if (i < params.length - 1) {
                        sb.append("_");
                    }
                }
            }
            
            // Add return type
            Class<?> returnType = method.getReturnType();
            if (!returnType.equals(void.class)) {
                sb.append("_ret").append(getReadableTypeName(returnType.getName()));
            }
            
        } else if (m instanceof java.lang.reflect.Constructor) {
            java.lang.reflect.Constructor<?> constructor = (java.lang.reflect.Constructor<?>) m;
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 0) {
                sb.append("_V"); // V for void parameters
            } else {
                sb.append("_");
                for (int i = 0; i < params.length; i++) {
                    String simpleName = getReadableTypeName(params[i].getName());
                    sb.append(simpleName);
                    if (i < params.length - 1) {
                        sb.append("_");
                    }
                }
            }
        } else {
            sb.append("_V");
        }
        
        return sb.toString();
    }
    
    private String getReadableTypeName(String fullTypeName) {
        // Convert types to readable names without hashes
        String typeName = fullTypeName.replace('$', '_'); // Handle inner classes
        
        // Handle array types first
        if (typeName.startsWith("[")) {
            return "ArrayOf" + getReadableTypeName(typeName.substring(1));
        }
        
        // Map primitive types to readable names
        switch (typeName) {
            case "boolean": return "Bool";
            case "byte": return "Byte"; 
            case "char": return "Char";
            case "short": return "Short";
            case "int": return "Int";
            case "long": return "Long";
            case "float": return "Float";
            case "double": return "Double";
            case "void": return "Void";
            default: 
                // For object types, remove package and use class name
                int lastDot = typeName.lastIndexOf('.');
                if (lastDot >= 0) {
                    typeName = typeName.substring(lastDot + 1);
                }
                return typeName;
        }
    }

    public static class UseKernelFriendly implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            // Enable when system property is set
            return Boolean.getBoolean("svm.kernelFriendlyNames");
        }
    }
}