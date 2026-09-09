package org.graphiks.kalligraphie.layout;

import java.util.List;
import org.graphiks.kalligraphie.api.FontInstanceKey;
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact;
import org.graphiks.kalligraphie.api.OpenTypeFeature;
import org.graphiks.kalligraphie.api.OpenTypeScript;
import org.graphiks.kalligraphie.api.ShapedGlyph;
import org.graphiks.kalligraphie.api.ShapedGlyphRun;
import org.graphiks.kalligraphie.api.ShaperCluster;
import org.graphiks.kalligraphie.api.ShapingBackendIdentity;
import org.graphiks.kalligraphie.api.ShapingDirection;
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy;
import org.graphiks.kalligraphie.api.TextRange;

public final class LegacyShapedGlyphRunConsumer {
    private LegacyShapedGlyphRunConsumer() {}

    public static ShapedGlyphRun create(
            TextRange range,
            FontInstanceKey fontInstanceKey,
            ShapingBackendIdentity backendIdentity,
            ShapingDirection direction,
            OpenTypeScript script,
            String language,
            int bidiLevel,
            boolean bot,
            boolean eot,
            ShapingFeaturePolicy featurePolicy,
            List<OpenTypeFeature> features,
            List<TextRange> graphemeClusters,
            List<ShapedGlyph> glyphs,
            List<ShaperCluster> clusters,
            List<GdefLigatureCaretFact> ligatureCaretFacts) {
        return new ShapedGlyphRun(
                range,
                fontInstanceKey,
                backendIdentity,
                direction,
                script,
                language,
                bidiLevel,
                bot,
                eot,
                featurePolicy,
                features,
                graphemeClusters,
                glyphs,
                clusters,
                ligatureCaretFacts);
    }
}
