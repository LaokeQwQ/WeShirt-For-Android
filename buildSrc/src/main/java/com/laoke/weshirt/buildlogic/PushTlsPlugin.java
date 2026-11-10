package com.laoke.weshirt.buildlogic;

import com.android.build.api.instrumentation.FramesComputationMode;
import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import kotlin.Unit;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.List;

/** A narrowly pinned build-time repair for the reviewed UMdid TLS implementation. */
public final class PushTlsPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // Keep configuration caching disabled until the ArtifactCollection
        // providers below have also been validated with configuration-cache reuse.
        if (project.getGradle().getStartParameter().isConfigurationCacheRequested()) {
            throw new GradleException("Push TLS verification requires --no-configuration-cache; "
                    + "cached configuration must not bypass the pinned SDK fingerprint check.");
        }
        project.getPluginManager().withPlugin("com.android.application", ignored -> {
            ApplicationAndroidComponentsExtension android = project.getExtensions()
                    .getByType(ApplicationAndroidComponentsExtension.class);
            android.onVariants(android.selector().all(), (Action<ApplicationVariant>) variant -> {
                ArtifactCollection artifacts = variant.getRuntimeConfiguration()
                        .getIncoming().artifactView(view -> view.componentFilter(component ->
                                component instanceof ModuleComponentIdentifier
                                        && UmengArtifactVerifier.isUmeng((ModuleComponentIdentifier) component)))
                        .getArtifacts();
                // Only create providers here. Resolving runtimeClasspath during
                // onVariants freezes configurations before Kotlin finishes setup.
                Provider<List<String>> coordinates = coordinates(project, artifacts);
                TaskProvider<VerifyUmengTlsArtifact> verification = registerVerification(
                        project, variant.getName(), artifacts, coordinates);
                variant.getLifecycleTasks().registerPreBuild(verification);
                variant.getInstrumentation().transformClassesWith(UmengTlsVisitorFactory.class,
                        InstrumentationScope.ALL, parameters -> {
                            configureParameters(parameters, verification, artifacts, coordinates);
                            return Unit.INSTANCE;
                        });
                variant.getInstrumentation().setAsmFramesComputationMode(
                        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES);
            });
        });
    }

    static Provider<List<String>> coordinates(Project project, ArtifactCollection artifacts) {
        return project.provider(() -> artifacts.getArtifacts().stream()
                .map(UmengArtifactVerifier::coordinate).sorted().toList());
    }

    static TaskProvider<VerifyUmengTlsArtifact> registerVerification(Project project, String variant,
            ArtifactCollection artifacts, Provider<List<String>> coordinates) {
        String suffix = Character.toUpperCase(variant.charAt(0)) + variant.substring(1);
        return project.getTasks().register("verify" + suffix + "PushTlsArtifact", VerifyUmengTlsArtifact.class, task -> {
            task.setGroup("verification");
            task.setDescription("Verify the reviewed UMdid TLS artifact before bytecode instrumentation.");
            task.getSdkArtifacts().from(artifacts.getArtifactFiles());
            task.getCoordinates().set(coordinates);
            task.getVerificationMarker().set(project.getLayout().getBuildDirectory()
                    .file("intermediates/push-tls/" + variant + "/verified-sdk.sha256"));
        });
    }

    static void configureParameters(UmengTlsParameters parameters, TaskProvider<VerifyUmengTlsArtifact> verification,
            ArtifactCollection artifacts, Provider<List<String>> coordinates) {
        // @InputFile retains the producer dependency even when AGP can reuse a
        // cached transform. Raw bytes and coordinates are also transform inputs.
        parameters.getVerificationMarker().set(verification.flatMap(VerifyUmengTlsArtifact::getVerificationMarker));
        parameters.getReviewedSdkFiles().from(artifacts.getArtifactFiles());
        parameters.getCoordinates().set(coordinates);
    }
}
