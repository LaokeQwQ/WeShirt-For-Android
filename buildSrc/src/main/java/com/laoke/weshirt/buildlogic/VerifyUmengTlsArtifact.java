package com.laoke.weshirt.buildlogic;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Validate actual dependency bytes at execution time, after all configurations are complete. */
@DisableCachingByDefault(because = "The security gate must verify the resolved SDK on every requested build")
public abstract class VerifyUmengTlsArtifact extends DefaultTask {
    public VerifyUmengTlsArtifact() {
        getOutputs().upToDateWhen(task -> false);
    }

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getSdkArtifacts();

    @Input
    public abstract ListProperty<String> getCoordinates();

    @Input
    public String getExpectedSha256() {
        return UmengArtifactVerifier.SHA256;
    }

    @OutputFile
    public abstract RegularFileProperty getVerificationMarker();

    @TaskAction
    public void verifyArtifact() {
        UmengArtifactVerifier.verifyAndWriteMarker(getSdkArtifacts().getFiles(), getCoordinates().get(),
                getVerificationMarker().get().getAsFile().toPath());
    }
}
