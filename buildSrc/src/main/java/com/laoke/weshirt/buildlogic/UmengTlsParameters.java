package com.laoke.weshirt.buildlogic;

import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/** Explicit producer-backed inputs for both project and dependency ASM transforms. */
public interface UmengTlsParameters extends InstrumentationParameters {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    RegularFileProperty getVerificationMarker();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    ConfigurableFileCollection getReviewedSdkFiles();

    @Input
    ListProperty<String> getCoordinates();
}
