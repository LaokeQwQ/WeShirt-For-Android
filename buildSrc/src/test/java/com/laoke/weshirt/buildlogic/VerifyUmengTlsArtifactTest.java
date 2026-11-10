package com.laoke.weshirt.buildlogic;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Proxy;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class VerifyUmengTlsArtifactTest {
    private static final List<String> COORDINATES = List.of("com.umeng.umsdk:umdid:1.1.0");

    @Test
    public void registrationRemainsLazyAndMarkerCarriesTheVerificationTaskDependency() throws Exception {
        Project project = ProjectBuilder.builder().build();
        File sdk = upstreamJar().toFile();
        AtomicInteger metadataReads = new AtomicInteger();
        ModuleComponentIdentifier component = proxy(ModuleComponentIdentifier.class,
                Map.of("getGroup", "com.umeng.umsdk", "getModule", "umdid", "getVersion", "1.1.0"));
        ComponentArtifactIdentifier id = proxy(ComponentArtifactIdentifier.class, Map.of("getComponentIdentifier", component));
        ResolvedArtifactResult artifact = proxy(ResolvedArtifactResult.class, Map.of("getId", id, "getFile", sdk));
        ArtifactCollection artifacts = (ArtifactCollection) Proxy.newProxyInstance(ArtifactCollection.class.getClassLoader(),
                new Class<?>[] { ArtifactCollection.class }, (receiver, method, arguments) -> {
                    if (method.getName().equals("getArtifactFiles")) return project.files(sdk);
                    if (method.getName().equals("getArtifacts")) { metadataReads.incrementAndGet(); return Set.of(artifact); }
                    throw new UnsupportedOperationException(method.getName());
                });
        Provider<List<String>> coordinates = PushTlsPlugin.coordinates(project, artifacts);
        TaskProvider<VerifyUmengTlsArtifact> verification = PushTlsPlugin.registerVerification(project, "debug", artifacts, coordinates);
        UmengTlsParameters parameters = project.getObjects().newInstance(UmengTlsParameters.class);
        PushTlsPlugin.configureParameters(parameters, verification, artifacts, coordinates);
        assertEquals(0, metadataReads.get());
        VerifyUmengTlsArtifact task = verification.get();
        assertEquals(0, metadataReads.get());
        assertTrue(project.files(parameters.getVerificationMarker()).getBuildDependencies().getDependencies(null).contains(task));
        assertEquals("Dependency inspection must not resolve coordinates", 0, metadataReads.get());
        task.verifyArtifact();
        assertEquals(1, metadataReads.get());
        assertEquals(UmengArtifactVerifier.SHA256 + "\n", Files.readString(parameters.getVerificationMarker().get().getAsFile().toPath()));
        assertEquals(Set.of(sdk), parameters.getReviewedSdkFiles().getFiles());
        assertEquals(COORDINATES, parameters.getCoordinates().get());
    }

    @Test
    public void changedSdkOrCoordinatesCannotReuseAnOlderMarkerAndFailedCheckRemovesIt() throws Exception {
        Path directory = Files.createTempDirectory("weshirt-tls-gate-fixture");
        Path marker = directory.resolve("verified.sha256"), altered = directory.resolve("altered.jar");
        Set<File> reviewed = Set.of(upstreamJar().toFile());
        try {
            UmengArtifactVerifier.verifyAndWriteMarker(reviewed, COORDINATES, marker);
            assertEquals(65, Files.size(marker));
            UmengArtifactVerifier.verifyMarkerAndInputs(reviewed, COORDINATES, marker);
            byte[] changed = Files.readAllBytes(upstreamJar()); changed[changed.length - 1] ^= 1;
            Files.write(altered, changed);
            Set<File> replacement = Set.of(altered.toFile());
            assertThrows(GradleException.class, () -> UmengArtifactVerifier.verifyMarkerAndInputs(replacement, COORDINATES, marker));
            assertThrows(GradleException.class, () -> UmengArtifactVerifier.verifyAndWriteMarker(replacement, COORDINATES, marker));
            assertFalse(Files.exists(marker));
            UmengArtifactVerifier.verifyAndWriteMarker(reviewed, COORDINATES, marker);
            assertThrows(GradleException.class, () -> UmengArtifactVerifier.verifyAndWriteMarker(reviewed,
                    List.of("com.umeng.umsdk:umdid:1.1.1"), marker));
            assertFalse(Files.exists(marker));
            assertThrows(GradleException.class, () -> UmengArtifactVerifier.verifyMarkerAndInputs(reviewed, COORDINATES, marker));
        } finally {
            Files.deleteIfExists(marker); Files.deleteIfExists(altered); Files.deleteIfExists(directory);
        }
    }

    private static Path upstreamJar() throws Exception {
        var resource = VerifyUmengTlsArtifactTest.class.getClassLoader().getResource("com/umeng/ut/a/b/c.class");
        assertNotNull(resource);
        return Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (receiver, method, arguments) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(receiver);
            if (method.getName().equals("equals")) return receiver == arguments[0];
            if (method.getName().equals("toString")) return type.getSimpleName();
            if (values.containsKey(method.getName())) return values.get(method.getName());
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
