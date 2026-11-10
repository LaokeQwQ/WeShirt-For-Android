package com.laoke.weshirt.buildlogic;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import org.objectweb.asm.ClassVisitor;

/** AGP supplies the abstract managed properties; selection is exact, never a package prefix. */
public abstract class UmengTlsVisitorFactory implements AsmClassVisitorFactory<UmengTlsParameters> {
    @Override
    public boolean isInstrumentable(ClassData classData) {
        return UmengTlsClassVisitor.isTarget(classData.getClassName());
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        UmengTlsParameters parameters = getParameters().get();
        UmengArtifactVerifier.verifyMarkerAndInputs(parameters.getReviewedSdkFiles().getFiles(),
                parameters.getCoordinates().get(), parameters.getVerificationMarker().get().getAsFile().toPath());
        return new UmengTlsClassVisitor(nextClassVisitor);
    }
}
