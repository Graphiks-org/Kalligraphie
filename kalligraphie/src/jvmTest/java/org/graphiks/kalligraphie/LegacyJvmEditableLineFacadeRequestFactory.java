package org.graphiks.kalligraphie;

import java.util.List;
import org.graphiks.kalligraphie.api.BaseDirection;
import org.graphiks.kalligraphie.api.CancellationToken;
import org.graphiks.kalligraphie.api.EditableLineMaterialization;
import org.graphiks.kalligraphie.api.FontInstance;
import org.graphiks.kalligraphie.api.LineVerticalMetrics;
import org.graphiks.kalligraphie.api.OpenTypeFeature;
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy;
import org.graphiks.kalligraphie.api.ShapingResourceProfile;
import org.graphiks.kalligraphie.api.TextSnapshot;
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile;

final class LegacyJvmEditableLineFacadeRequestFactory {
    private LegacyJvmEditableLineFacadeRequestFactory() {}

    static JvmEditableLineFacadeRequest create(
            TextSnapshot snapshot,
            FontInstance font,
            BaseDirection baseDirection,
            String language,
            ShapingFeaturePolicy featurePolicy,
            List<OpenTypeFeature> features,
            LineVerticalMetrics verticalMetrics,
            EditableLineMaterialization materialization,
            Integer emptyLineBidiLevel,
            CancellationToken cancellationToken,
            UnicodeAnalysisProfile unicodeAnalysisProfile,
            ShapingResourceProfile shapingResourceProfile) {
        return new JvmEditableLineFacadeRequest(
                snapshot,
                font,
                baseDirection,
                language,
                featurePolicy,
                features,
                verticalMetrics,
                materialization,
                emptyLineBidiLevel,
                cancellationToken,
                unicodeAnalysisProfile,
                shapingResourceProfile);
    }
}
