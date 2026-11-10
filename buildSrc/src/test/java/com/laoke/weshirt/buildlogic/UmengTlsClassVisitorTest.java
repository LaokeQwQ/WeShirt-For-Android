package com.laoke.weshirt.buildlogic;

import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationContext;
import com.laoke.weshirt.platform.push.StrictPlatformTls;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.JarURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class UmengTlsClassVisitorTest {
    private static final Map<String, Integer> COUNTS = Map.of("c", 7, "d", 1, "e", 8, "f", 3);

    @After
    public void clearSink() {
        StrictPlatformTls.certificateFailure = null;
        StrictPlatformTls.result = null;
        StrictPlatformTls.lastArguments = null;
        StrictPlatformTls.lastMethod = null;
    }

    @Test
    public void actualPinnedSdkHasExactlyNineteenCompletelyReplacedTlsBodies() throws Exception {
        URL resource = resource("c");
        assertEquals("jar", resource.getProtocol());
        Path jar = Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI());
        UmengArtifactVerifier.verifyPinnedJar(jar);
        int count = 0;
        for (var entry : COUNTS.entrySet()) {
            byte[] input = upstream(entry.getKey());
            ClassNode before = read(input), after = read(transform(input));
            assertEquals(before.name, after.name);
            assertEquals(before.superName, after.superName);
            assertEquals(before.access, after.access);
            assertEquals(before.interfaces, after.interfaces);
            assertEquals(before.fields.size(), after.fields.size());
            assertEquals(before.methods.size(), after.methods.size());
            for (int index = 0; index < before.fields.size(); index++) {
                assertEquals(before.fields.get(index).name, after.fields.get(index).name);
                assertEquals(before.fields.get(index).desc, after.fields.get(index).desc);
                assertEquals(before.fields.get(index).access, after.fields.get(index).access);
                assertEquals(before.fields.get(index).value, after.fields.get(index).value);
            }
            int selected = 0;
            for (int index = 0; index < before.methods.size(); index++) {
                MethodNode old = before.methods.get(index), patched = after.methods.get(index);
                assertEquals(old.name, patched.name); assertEquals(old.desc, patched.desc);
                assertEquals(old.access, patched.access); assertEquals(old.signature, patched.signature);
                assertEquals(old.exceptions, patched.exceptions);
                if (tlsMethod(old)) {
                    assertDelegateOnly(patched);
                    selected++;
                } else {
                    assertArrayEquals("Unrelated method changed: " + old.name, methodBytes(old), methodBytes(patched));
                }
            }
            assertEquals(entry.getValue().intValue(), selected);
            count += selected;
        }
        assertEquals(19, count);
    }

    @Test
    public void allNineteenRuntimeCallsForwardActualArgumentsAndReturnValues() throws Exception {
        int count = 0;
        for (String suffix : COUNTS.keySet()) {
            Class<?> type = new PatchedLoader().define(transform(upstream(suffix)));
            Object receiver = instance(type, suffix);
            for (MethodNode source : read(upstream(suffix)).methods) {
                if (!tlsMethod(source)) continue;
                Class<?>[] parameters = parameterTypes(source.desc);
                Object[] arguments = arguments(parameters);
                Object expected = result(Type.getReturnType(source.desc));
                StrictPlatformTls.result = expected;
                Method method = type.getDeclaredMethod(source.name, parameters);
                method.setAccessible(true);
                assertEquals(expected, method.invoke(receiver, arguments));
                assertEquals(source.name.equals("verify") ? "verifyHostname" : source.name, StrictPlatformTls.lastMethod);
                assertEquals(arguments.length, StrictPlatformTls.lastArguments.length);
                for (int index = 0; index < arguments.length; index++) {
                    if (parameters[index].isPrimitive()) assertEquals(arguments[index], StrictPlatformTls.lastArguments[index]);
                    else assertSame(arguments[index], StrictPlatformTls.lastArguments[index]);
                }
                count++;
            }
        }
        assertEquals(19, count);
    }

    @Test
    public void upstreamCatchBlocksCannotSwallowStrictCertificateFailures() throws Exception {
        CertificateException failure = new CertificateException("fixture certificate rejected");
        StrictPlatformTls.certificateFailure = failure;
        int count = 0;
        for (String suffix : List.of("c", "f")) {
            Class<?> type = new PatchedLoader().define(transform(upstream(suffix)));
            Object receiver = instance(type, suffix);
            for (MethodNode source : read(upstream(suffix)).methods) {
                if (!source.name.startsWith("check")) continue;
                Class<?>[] parameters = parameterTypes(source.desc);
                Method method = type.getDeclaredMethod(source.name, parameters);
                method.setAccessible(true);
                InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                        () -> method.invoke(receiver, arguments(parameters)));
                assertSame(failure, thrown.getCause());
                count++;
            }
        }
        assertEquals(8, count);
    }

    @Test
    public void selectionIsExactAndOtherClassesRemainByteForByteUnchanged() throws Exception {
        UmengTlsVisitorFactory factory = new UmengTlsVisitorFactory() {
            @Override public Property<UmengTlsParameters> getParameters() { return null; }
            @Override public InstrumentationContext getInstrumentationContext() { return null; }
        };
        for (String suffix : COUNTS.keySet()) assertTrue(factory.isInstrumentable(classData("com.umeng.ut.a.b." + suffix)));
        for (String name : List.of("com.umeng.ut.a.b.c$Other", "com.umeng.ut.a.b.cc",
                "other.com.umeng.ut.a.b.c", "com.laoke.weshirt.platform.push.StrictPlatformTls")) {
            assertFalse(factory.isInstrumentable(classData(name)));
        }
        ClassNode unrelated = read(upstream("c"));
        unrelated.name = "com/umeng/ut/a/b/c$Other";
        byte[] input = bytes(unrelated);
        assertArrayEquals(input, transform(input));
    }

    @Test
    public void changedHierarchyMissingMethodsNewOverloadsAndInvalidAccessFailClosed() throws Exception {
        ClassNode hierarchy = read(upstream("c")); hierarchy.superName = "java/lang/Object";
        assertThrows(IllegalStateException.class, () -> transform(bytes(hierarchy)));
        ClassNode missing = read(upstream("e")); missing.methods.removeIf(method -> method.name.equals("createSocket") && method.desc.equals("()Ljava/net/Socket;"));
        assertThrows(IllegalStateException.class, () -> transform(bytes(missing)));
        for (int access : new int[] { Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, Opcodes.ACC_PROTECTED }) {
            ClassNode changed = read(upstream("d"));
            changed.methods.stream().filter(method -> method.name.equals("verify")).findFirst().orElseThrow().access = access;
            assertThrows(IllegalStateException.class, () -> transform(bytes(changed)));
        }
        ClassNode extra = read(upstream("f"));
        MethodNode overload = new MethodNode(Opcodes.ACC_PUBLIC, "checkServerTrusted", "(Ljava/lang/String;)V", null, null);
        overload.instructions.add(new InsnNode(Opcodes.RETURN)); overload.maxLocals = 2;
        extra.methods.add(overload);
        assertThrows(IllegalStateException.class, () -> transform(bytes(extra)));
    }

    @Test
    public void aTamperedSdkCannotPassTheFingerprintGate() throws Exception {
        URL resource = resource("c");
        byte[] original = Files.readAllBytes(Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI()));
        original[original.length - 1] ^= 1;
        Path altered = Files.createTempFile("umdid-fingerprint-fixture", ".jar");
        try {
            Files.write(altered, original);
            assertThrows(GradleException.class, () -> UmengArtifactVerifier.verifyPinnedJar(altered));
            assertThrows(GradleException.class, () -> UmengArtifactVerifier.verifyArtifacts(java.util.Set.of()));
        } finally {
            Files.deleteIfExists(altered);
        }
    }

    private static void assertDelegateOnly(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) instructions.add(instruction);
        Type[] arguments = Type.getArgumentTypes(method.desc);
        assertEquals(arguments.length + 2, instructions.size());
        int local = 1;
        for (int index = 0; index < arguments.length; index++) {
            assertTrue(instructions.get(index) instanceof VarInsnNode);
            VarInsnNode load = (VarInsnNode) instructions.get(index);
            assertEquals(arguments[index].getOpcode(Opcodes.ILOAD), load.getOpcode());
            assertEquals(local, load.var);
            local += arguments[index].getSize();
        }
        assertTrue(instructions.get(arguments.length) instanceof MethodInsnNode);
        MethodInsnNode call = (MethodInsnNode) instructions.get(arguments.length);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("com/laoke/weshirt/platform/push/StrictPlatformTls", call.owner);
        assertEquals(method.name.equals("verify") ? "verifyHostname" : method.name, call.name);
        assertEquals(method.desc, call.desc); assertFalse(call.itf);
        assertEquals(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN), instructions.get(instructions.size() - 1).getOpcode());
        assertTrue(method.tryCatchBlocks.isEmpty());
        assertTrue(method.localVariables == null || method.localVariables.isEmpty());
    }

    private static boolean tlsMethod(MethodNode method) {
        return List.of("checkClientTrusted", "checkServerTrusted", "getAcceptedIssuers", "verify",
                "createSocket", "getDefaultCipherSuites", "getSupportedCipherSuites").contains(method.name);
    }

    private static URL resource(String suffix) {
        URL resource = UmengTlsClassVisitorTest.class.getClassLoader().getResource("com/umeng/ut/a/b/" + suffix + ".class");
        assertNotNull("The exact upstream UMdid runtime fixture is required", resource);
        return resource;
    }

    private static byte[] upstream(String suffix) throws Exception {
        try (InputStream input = resource(suffix).openStream()) { return input.readAllBytes(); }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode(); new ClassReader(bytes).accept(result, 0); return result;
    }

    private static byte[] transform(byte[] bytes) {
        ClassReader input = new ClassReader(bytes);
        ClassWriter output = new ClassWriter(input, 0);
        input.accept(new UmengTlsClassVisitor(output), 0);
        return output.toByteArray();
    }

    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }

    private static byte[] methodBytes(MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Unchanged", null, "java/lang/Object", null);
        method.accept(writer); writer.visitEnd(); return writer.toByteArray();
    }

    private static Object instance(Class<?> type, String suffix) throws Exception {
        Constructor<?> constructor = suffix.equals("d") || suffix.equals("e") ? type.getDeclaredConstructor(String.class) : type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.getParameterCount() == 0 ? constructor.newInstance() : constructor.newInstance("cached.invalid.example");
    }

    private static Class<?>[] parameterTypes(String descriptor) throws ClassNotFoundException {
        Type[] types = Type.getArgumentTypes(descriptor); Class<?>[] result = new Class<?>[types.length];
        for (int index = 0; index < types.length; index++) {
            Type type = types[index];
            result[index] = type.getSort() == Type.INT ? int.class : type.getSort() == Type.BOOLEAN ? boolean.class
                    : Class.forName(type.getSort() == Type.ARRAY ? type.getDescriptor().replace('/', '.') : type.getClassName());
        }
        return result;
    }

    private static Object[] arguments(Class<?>[] types) throws Exception {
        Object[] values = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            values[index] = type == String.class ? "actual.requested.example" : type == int.class ? 443 + index
                    : type == boolean.class ? Boolean.TRUE : type == Socket.class ? new Socket()
                    : type == InetAddress.class ? InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 })
                    : type == X509Certificate[].class ? new X509Certificate[0]
                    : type == javax.net.ssl.SSLEngine.class ? SSLContext.getDefault().createSSLEngine()
                    : type == javax.net.ssl.SSLSession.class ? SSLContext.getDefault().createSSLEngine().getSession()
                    : null;
            assertNotNull("Uncovered argument type " + type, values[index]);
        }
        return values;
    }

    private static Object result(Type type) {
        if (type.getSort() == Type.VOID) return null;
        if (type.getSort() == Type.BOOLEAN) return Boolean.TRUE;
        if (type.getDescriptor().equals("Ljava/net/Socket;")) return new Socket();
        if (type.getDescriptor().equals("[Ljava/lang/String;")) return new String[] { "fixture_cipher" };
        if (type.getDescriptor().equals("[Ljava/security/cert/X509Certificate;")) return new X509Certificate[0];
        throw new AssertionError("Uncovered return type " + type);
    }

    private static ClassData classData(String name) {
        return new ClassData() {
            @Override public String getClassName() { return name; }
            @Override public List<String> getClassAnnotations() { return List.of(); }
            @Override public List<String> getInterfaces() { return List.of(); }
            @Override public List<String> getSuperClasses() { return List.of(); }
        };
    }

    private static final class PatchedLoader extends ClassLoader {
        PatchedLoader() { super(UmengTlsClassVisitorTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
