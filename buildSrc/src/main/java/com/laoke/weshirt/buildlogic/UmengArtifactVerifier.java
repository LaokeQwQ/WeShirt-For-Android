package com.laoke.weshirt.buildlogic;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.List;
import java.io.File;

/** No network operations or substitutions: inspect the artifact Gradle actually resolved. */
final class UmengArtifactVerifier {
    static final String SHA256 = "cb85080c598f1dd0ddf21f443edc74cb259c534e4e49f8178c0199a380b273ee";

    private UmengArtifactVerifier() { }

    static boolean isUmeng(ModuleComponentIdentifier component) {
        return "com.umeng.umsdk".equals(component.getGroup()) && "umdid".equals(component.getModule());
    }

    static void verifyArtifacts(Set<ResolvedArtifactResult> artifacts) {
        verifyInputs(artifacts.stream().map(ResolvedArtifactResult::getFile).collect(java.util.stream.Collectors.toSet()),
                artifacts.stream().map(UmengArtifactVerifier::coordinate).toList());
    }

    static String coordinate(ResolvedArtifactResult artifact) {
        if (!(artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier)) {
            throw new GradleException("Push TLS repair rejects an unreviewed UMdid artifact substitution.");
        }
        ModuleComponentIdentifier component = (ModuleComponentIdentifier) artifact.getId().getComponentIdentifier();
        return component.getGroup() + ":" + component.getModule() + ":" + component.getVersion();
    }

    static void verifyInputs(Set<File> files, List<String> coordinates) {
        if (files.size() != 1 || !coordinates.equals(List.of("com.umeng.umsdk:umdid:1.1.0"))) {
            throw new GradleException("Push TLS repair requires exactly com.umeng.umsdk:umdid:1.1.0.");
        }
        File file = files.iterator().next();
        if (!file.getName().endsWith(".jar")) {
            throw new GradleException("UMdid version or artifact format changed; review its TLS code before building.");
        }
        verifyPinnedJar(file.toPath());
    }

    static void verifyAndWriteMarker(Set<File> files, List<String> coordinates, Path marker) {
        try {
            // A failed verification cannot leave an older successful marker behind.
            Files.deleteIfExists(marker);
            verifyInputs(files, coordinates);
            Files.createDirectories(marker.toAbsolutePath().getParent());
            Files.writeString(marker, SHA256 + "\n", StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new GradleException("Cannot write the UMdid verification marker; refusing to build.", error);
        }
    }

    static void verifyMarkerAndInputs(Set<File> files, List<String> coordinates, Path marker) {
        try {
            if (!Files.isRegularFile(marker) || Files.size(marker) != 65
                    || !(SHA256 + "\n").equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                throw new GradleException("UMdid fingerprint verification must complete before instrumentation.");
            }
            // Also check the transform's actual byte inputs. A stale marker
            // cannot authorize different dependency bytes or coordinates.
            verifyInputs(files, coordinates);
        } catch (IOException error) {
            throw new GradleException("Cannot read the UMdid verification marker; refusing instrumentation.", error);
        }
    }

    static void verifyPinnedJar(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[16384];
            int count;
            while ((count = input.read(chunk)) != -1) digest.update(chunk, 0, count);
            if (!SHA256.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new GradleException("UMdid SHA-256 changed; refusing to apply a repair to unreviewed SDK bytes.");
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new GradleException("Cannot verify the UMdid TLS artifact; refusing to build.", error);
        }
    }
}
