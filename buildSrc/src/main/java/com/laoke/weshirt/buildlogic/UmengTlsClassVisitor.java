package com.laoke.weshirt.buildlogic;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Replace only the reviewed 19 TLS methods. Constructors, fields, annotations,
 * exception declarations and unrelated methods keep their original structure.
 * Unexpected SDK shapes stop the build rather than silently leaving a TLS bypass.
 */
final class UmengTlsClassVisitor extends ClassVisitor {
    static final String HELPER = "com/laoke/weshirt/platform/push/StrictPlatformTls";
    private static final String PREFIX = "com/umeng/ut/a/b/";
    private static final String BASIC = "([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V";
    private static final String SOCKET = "([Ljava/security/cert/X509Certificate;Ljava/lang/String;Ljava/net/Socket;)V";
    private static final String ENGINE = "([Ljava/security/cert/X509Certificate;Ljava/lang/String;Ljavax/net/ssl/SSLEngine;)V";
    private static final Map<String, Target> TARGETS = targets();

    private record Target(String parent, Set<String> interfaces, Map<String, String> methods) { }

    private Target target;
    private String className;
    private final Set<String> replaced = new HashSet<>();

    UmengTlsClassVisitor(ClassVisitor next) {
        super(Opcodes.ASM9, next);
    }

    static boolean isTarget(String dottedName) {
        return dottedName != null && TARGETS.containsKey(dottedName.replace('.', '/'));
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        className = name;
        target = TARGETS.get(name);
        if (target != null && (!target.parent.equals(superName)
                || !target.interfaces.equals(new HashSet<>(Arrays.asList(interfaces)))
                || (access & Opcodes.ACC_INTERFACE) != 0)) {
            throw changed("class hierarchy");
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (target == null) return super.visitMethod(access, name, descriptor, signature, exceptions);
        String key = name + descriptor;
        String delegate = target.methods.get(key);
        if (delegate == null) {
            // A newly added overload of a security-sensitive method also needs review.
            if (target.methods.keySet().stream().anyMatch(method -> method.startsWith(name + "("))) {
                throw changed("unreviewed overload " + name + descriptor);
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
                || (access & Opcodes.ACC_PUBLIC) == 0 || !replaced.add(key)) {
            throw changed("method access " + name + descriptor);
        }
        MethodVisitor destination = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                instructions.clear();
                tryCatchBlocks.clear();
                if (localVariables != null) localVariables.clear();
                if (visibleLocalVariableAnnotations != null) visibleLocalVariableAnnotations.clear();
                if (invisibleLocalVariableAnnotations != null) invisibleLocalVariableAnnotations.clear();
                if (attrs != null) attrs.removeIf(attribute -> attribute.isCodeAttribute());
                int local = 1;
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                    local += argument.getSize();
                }
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, delegate, descriptor, false));
                Type result = Type.getReturnType(descriptor);
                instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
                maxStack = Math.max(local - 1, result.getSize());
                maxLocals = local;
                if (destination != null) accept(destination);
            }
        };
    }

    @Override
    public void visitEnd() {
        if (target != null && !replaced.equals(target.methods.keySet())) throw changed("missing TLS methods");
        super.visitEnd();
    }

    private IllegalStateException changed(String reason) {
        return new IllegalStateException("UMdid TLS class shape changed (" + className + "): " + reason
                + "; review the SDK before building.");
    }

    private static Map<String, Target> targets() {
        Map<String, Target> result = new LinkedHashMap<>();
        Map<String, String> extended = trustMethods(true);
        Map<String, String> basic = trustMethods(false);
        result.put(PREFIX + "c", new Target("javax/net/ssl/X509ExtendedTrustManager", Set.of(), extended));
        result.put(PREFIX + "f", new Target("java/lang/Object", Set.of("javax/net/ssl/X509TrustManager"), basic));
        result.put(PREFIX + "d", new Target("java/lang/Object", Set.of("javax/net/ssl/HostnameVerifier"),
                Map.of("verify(Ljava/lang/String;Ljavax/net/ssl/SSLSession;)Z", "verifyHostname")));
        Map<String, String> sockets = new LinkedHashMap<>();
        for (String descriptor : new String[] {
                "()Ljava/net/Socket;", "(Ljava/lang/String;I)Ljava/net/Socket;",
                "(Ljava/lang/String;ILjava/net/InetAddress;I)Ljava/net/Socket;",
                "(Ljava/net/InetAddress;I)Ljava/net/Socket;",
                "(Ljava/net/InetAddress;ILjava/net/InetAddress;I)Ljava/net/Socket;",
                "(Ljava/net/Socket;Ljava/lang/String;IZ)Ljava/net/Socket;"
        }) sockets.put("createSocket" + descriptor, "createSocket");
        sockets.put("getDefaultCipherSuites()[Ljava/lang/String;", "getDefaultCipherSuites");
        sockets.put("getSupportedCipherSuites()[Ljava/lang/String;", "getSupportedCipherSuites");
        result.put(PREFIX + "e", new Target("javax/net/ssl/SSLSocketFactory", Set.of(), sockets));
        return Map.copyOf(result);
    }

    private static Map<String, String> trustMethods(boolean extended) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String method : new String[] { "checkClientTrusted", "checkServerTrusted" }) {
            result.put(method + BASIC, method);
            if (extended) {
                result.put(method + SOCKET, method);
                result.put(method + ENGINE, method);
            }
        }
        result.put("getAcceptedIssuers()[Ljava/security/cert/X509Certificate;", "getAcceptedIssuers");
        return result;
    }
}
