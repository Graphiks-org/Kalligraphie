# Catalogue d'attentes e2e des polices — Plan d'implémentation (phases 1-2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Faire de `:kalligraphie:e2e` le catalogue de référence des technologies de polices que
Kalligraphie doit consommer — modèle typé, statuts à cliquet, scènes/sondes générées, corpus
acquis et vérifié hors ligne — en migrant les 16 scènes golden existantes sans changer une seule
empreinte.

**Architecture:** Le modèle vit dans `commonMain` de `:kalligraphie:e2e` (`expectation catalog`),
les artefacts exécutables (rendus de scènes, sondes de comportement) vivent dans `jvmTest` et sont
reliés au modèle par l'id de l'entrée. Le matériel golden existant (`GoldenScene`,
`GoldenManifest`, `GoldenVerifier`, `updateE2eGolden`) est réutilisé tel quel : la migration ne
touche pas au format du manifeste, seulement à la façon dont les scènes sont produites. Le corpus
de polices est décrit par `scripts/fonts/corpus.json` et vérifié hors ligne par un lint Python
(fontTools) qui croise les tables réellement présentes avec les revendications exportées du modèle
Kotlin.

**Tech Stack:** Kotlin Multiplatform (JVM/Android/iOS, style `explicitApi()`), Gradle avec
conventions `ygdrasil.conventions.kmp-library`, tâches `Test` filtrées par méthode (idiome
existant `updateE2eGolden`), Python 3 + fontTools 4.65.0 (version épinglée par les `PROVENANCE.md`),
`unittest` de la stdlib, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-23-e2e-font-catalog-design.md`

**Périmètre de ce plan:** phases 1 et 2 du spec (§9) uniquement — le dispositif du catalogue et
l'infrastructure du corpus. Les phases 3 à 5 (remplissage des axes par de nouvelles polices
réelles, écritures, pages de docs narratives) feront l'objet de plans séparés : leurs tâches
dépendent de propriétés de polices que seule l'acquisition révèle (tables réellement portées,
axes présents, codepoints disponibles).

## Refinements du spec appliqués par ce plan

Le spec est un document de vision ; le plan précise trois points, sans changer l'intention :

1. `CatalogStatus.ExpectedRejection` porte aussi un `stage: CatalogStage` (en plus du `code`), pour
   que la sonde sache à quelle étape du pipeline l'échec est attendu.
2. `CatalogStatus.NotYet.noRealFontKnown: Boolean` du spec devient
   `unpinnedReason: UnpinnedReason?` (`NO_REAL_FONT_KNOWN` | `CORPUS_NOT_ACQUIRED`) : le spec
   n'avait prévu que le premier cas ; le second (police réelle existante mais pas encore acquise)
   doit être distinguable sans mentir sur le premier.
3. `CatalogEntry` porte `family: GoldenSceneFamily?` et `frame: SceneFramePolicy?`, invariants par
   statut : ces deux champs sont ce qui rend la génération des scènes mécanique.
4. Le spec prévoyait une tâche Gradle `generateCatalogMatrix`. Le plan la fond dans
   `updateE2eGolden` sous la forme d'un runner `Test` filtré par méthode, l'idiome déjà utilisé pour
   le manifeste golden : une seule commande régénère les deux artefacts, et aucune nouvelle
   mécanique Gradle n'entre dans le dépôt.
5. `CatalogEntry` porte `tables: Set<String>`, absent du spec : c'est ce qui permet au lint de la
   phase 2 de croiser les revendications du catalogue avec les tables réelles des polices. Une
   entrée `Supported` doit en revendiquer au moins une (`supported-without-tables`).

**Conventions de rédaction.** Plusieurs valeurs de ce plan ne peuvent pas être écrites de mémoire :
elles s'observent. Les marqueurs `<hash>`, `<code observé>`, `<codepoint relevé>` signalent
exactement ces cas, et chaque occurrence est accompagnée de la commande ou de la procédure qui
donne la vraie valeur. Aucune ne se devine.

## Global Constraints

- **Commits** : Conventional Commits, types et scopes de `.github/contributing-policy.toml`.
  Scope `e2e` pour tout le travail du module ; scope `ci` pour les workflows ; scope `docs` pour
  la documentation. Branche : `feat/` (courante : `feat/e2e-catalog`).
- **CHANGELOG.md** : la politique PR l'exige — chaque PR coche « `CHANGELOG.md` has been updated »
  ou « No changelog update needed » avec raison. Les tâches 8 et 13 mettent à jour le CHANGELOG.
- **`explicitApi()`** est actif dans `:kalligraphie:e2e` : toute déclaration publique de
  `commonMain` porte un modificateur `public` explicite et une KDoc (style du module existant :
  KDoc sur chaque déclaration publique).
- **Langue du code et des KDoc** : anglais (le dépôt est en anglais ; seules les pages `docs/` de
  Mintlify/MkDocs sont bilingues).
- **Aucune nouvelle dépendance Gradle** dans ces phases. Aucun accès réseau dans les tests.
- **Python** : fontTools 4.65.0 exactement (version des audits `PROVENANCE.md`), `unittest` de la
  stdlib, exécution via `uv run --with fonttools==4.65.0`.
- **Les 16 empreintes golden existantes ne changent pas** à l'issue des tâches 1 à 4 : la
  migration est une refonte de la production des scènes, pas une re-bénédiction d'images.
- **Aucun sous-échantillonnage** des polices du corpus (police complète commitée).
- **Commandes de test** :
  `./gradlew :kalligraphie:e2e:jvmTest` (module seul),
  `./gradlew check` (tout),
  `./gradlew :kalligraphie:e2e:updateE2eGolden` (régénère manifeste **et** matrice de doc après
  la tâche 8).
- **Les répertoires de fixtures existants** (`test-fixtures/fonts/<clé>/`) ne sont pas renommés :
  les clés de corpus sont dérivées de ces noms de répertoire et deviennent stables.

## Review Focus

Cinq modes d'échec que le spec implique sans qu'un test de tâche ne les couvre spontanément. Chacun
est épinglé par un test dans la tâche propriétaire indiquée.

1. **Une scène auto-dimensionnée dont le rendu naturel est entièrement vide** produirait une boîte
   d'encre nulle, donc une scène de taille nulle ou un padding absurde. Attendu : refus typé
   `e2e.blank-scene`, jamais une scène dégénérée. — test dans la tâche 4.
2. **Une scène migrée dont le rendu naturel ne fait plus la taille épinglée** (le refactor des 16
   rendus peut décaler une largeur d'un pixel). Attendu : refus `e2e.scene-bounds-invalid` avant
   toute comparaison d'empreinte, jamais une empreinte faussement « régénérée ». — test dans la
   tâche 4.
3. **Un id dupliqué entre deux fichiers d'axes** (8 fichiers, ids écrits à la main). Attendu :
   violation d'audit `duplicate-id` listant les deux axes. — test dans la tâche 2.
4. **Une matrice de doc non régénérée après un changement de statut** — exactement la dérive
   « 17 scènes annoncées pour 16 réelles » que le spec veut éliminer. Attendu : test de fraîcheur
   qui compare le fichier committé au rendu du modèle, dans les deux langues. — test dans la
   tâche 8.
5. **Une URL, un SHA-256 ou une licence transcrits de travers** dans `corpus.json` (saisie manuelle
   depuis 19 `PROVENANCE.md`). Attendu : `--check` re-hashe le fichier committé et compare, et
   toute licence hors allowlist bloque. — tests dans la tâche 10.

---

# Milestone A — Le dispositif du catalogue

Livrable : les 16 scènes golden existantes sont déclarées dans le catalogue, produites par un
matérialiseur commun, et un test de cliquet interdit les entrées orphelines. Les empreintes du
manifeste sont inchangées.

## Task 1: Types du modèle

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogAxis.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogStage.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogStatus.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CorpusKey.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneFramePolicy.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogEntry.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogStatusTest.kt`

**Interfaces:**
- Consumes: rien (première tâche).
- Produces: `CatalogAxis` (enum 8 valeurs), `CatalogStage` (enum 7 valeurs), `CatalogStatus`
  (sealed : `Supported(sinceCommit)`, `ExpectedRejection(stage, code)`,
  `NotYet(trackingIssue, currentBehavior?, unpinnedReason?)`, `OutOfScope(rationale)`),
  `PinnedBehavior` (sealed : `RejectedAt(stage, diagnostic)`, `SucceededWith(stage, observation)`),
  `UnpinnedReason` (enum : `NO_REAL_FONT_KNOWN`, `CORPUS_NOT_ACQUIRED`), `CorpusKey(value)`,
  `SceneFramePolicy` (sealed : `AutoSized(padding)`, `Pinned(width, height)`),
  `CatalogEntry(id, axis, technology, font?, status, tags, family?, frame?)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// CatalogStatusTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogStatusTest {
    @Test
    fun aNotYetEntryWithAPinnedBehaviorCarriesNoUnpinnedReason() {
        val status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 variation",
            currentBehavior = PinnedBehavior.RejectedAt(CatalogStage.METRICS, "font.variation.varc-unsupported"),
            unpinnedReason = null,
        )
        assertNull(status.unpinnedReason)
        assertNotNull(status.currentBehavior)
    }

    @Test
    fun aNotYetEntryWithoutAProbeMustStateWhy() {
        assertFailsWith<IllegalArgumentException> {
            CatalogStatus.NotYet(trackingIssue = "spec:§5 metrics", currentBehavior = null, unpinnedReason = null)
        }
    }

    @Test
    fun aNotYetEntryWithAProbeMustNotStateAnUnpinnedReason() {
        assertFailsWith<IllegalArgumentException> {
            CatalogStatus.NotYet(
                trackingIssue = "spec:§5 metrics",
                currentBehavior = PinnedBehavior.SucceededWith(CatalogStage.METRICS, "STAT decodes as Success(null)"),
                unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
            )
        }
    }

    @Test
    fun aSupportedStatusRequiresACommitHash() {
        assertFailsWith<IllegalArgumentException> { CatalogStatus.Supported("") }
    }

    @Test
    fun anOutOfScopeStatusRequiresARationale() {
        assertFailsWith<IllegalArgumentException> { CatalogStatus.OutOfScope("   ") }
    }

    @Test
    fun anAutoSizedFrameRequiresANonNegativePadding() {
        assertFailsWith<IllegalArgumentException> { SceneFramePolicy.AutoSized(padding = -1) }
    }

    @Test
    fun aPinnedFrameRequiresPositiveDimensions() {
        assertFailsWith<IllegalArgumentException> { SceneFramePolicy.Pinned(width = 0, height = 10) }
    }

    @Test
    fun aCorpusKeyMustNotBeBlank() {
        assertFailsWith<IllegalArgumentException> { CorpusKey(" ") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogStatusTest*'`
Expected: FAIL — compilation error, `Unresolved reference: catalog` / `CatalogStatus`.

- [ ] **Step 3: Write the model types**

```kotlin
// CatalogAxis.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Dimension of the font-technology surface a catalog entry belongs to. */
public enum class CatalogAxis {
    /** SFNT containers and collections. */
    CONTAINER,
    /** Outline formats and encoding schemes. */
    OUTLINE,
    /** Horizontal and vertical metrics, including variations. */
    METRICS,
    /** Variable-font machinery: axes, deltas, instance resolution. */
    VARIATION,
    /** Colour glyph technologies. */
    COLOR,
    /** Embedded bitmap strikes. */
    BITMAP,
    /** Writing systems and their shaping requirements. */
    SCRIPT,
    /** Behaviour on hostile or malformed input. */
    ROBUSTNESS,
}
```

```kotlin
// CatalogStage.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Pipeline stage an entry's probe observes. */
public enum class CatalogStage {
    /** SFNT container decoding. */
    DECODE,
    /** Face resolution against access requirements. */
    FACE_RESOLUTION,
    /** Instance creation and variation application. */
    INSTANTIATION,
    /** Horizontal or vertical metric resolution. */
    METRICS,
    /** Shaping through the platform shaper. */
    SHAPING,
    /** Glyph representation materialization (outline, paint, bitmap). */
    GLYPH_REPRESENTATION,
    /** Rasterization into a canonical image. */
    RASTERIZATION,
}
```

```kotlin
// CatalogStatus.kt
package org.graphiks.kalligraphie.e2e.catalog

/** What Kalligraphie is expected to do with the technology of one catalog entry. */
public sealed interface CatalogStatus {
    /** Supported and verified by a golden scene. */
    public data class Supported(
        /** Short commit hash that introduced the support. */
        public val sinceCommit: String,
    ) : CatalogStatus {
        init {
            require(sinceCommit.isNotBlank()) { "A Supported entry must record the commit that introduced it." }
        }
    }

    /** Refused on purpose with exactly [code] at [stage]; the refusal must never disappear. */
    public data class ExpectedRejection(
        /** Stage the refusal is observed at. */
        public val stage: CatalogStage,
        /** Stable diagnostic identity, e.g. `font.variation.varc-unsupported`. */
        public val code: String,
    ) : CatalogStatus {
        init {
            require(code.isNotBlank()) { "An ExpectedRejection entry must record its diagnostic code." }
        }
    }

    /**
     * Not supported today. Exactly one of [currentBehavior] and [unpinnedReason] is set:
     * a probe pins today's behaviour when a font is available, otherwise the entry states why
     * nothing can be observed yet.
     */
    public data class NotYet(
        /** Tracking anchor: a GitHub issue (`#95`) or a spec section (`spec:§5 metrics`). */
        public val trackingIssue: String,
        /** Today's observable behaviour, asserted by a probe. */
        public val currentBehavior: PinnedBehavior?,
        /** Why no probe runs yet. */
        public val unpinnedReason: UnpinnedReason?,
    ) : CatalogStatus {
        init {
            require(trackingIssue.isNotBlank()) { "A NotYet entry must reference what tracks it." }
            require((currentBehavior == null) != (unpinnedReason == null)) {
                "A NotYet entry must pin a behaviour or state why it cannot, never both and never neither."
            }
        }
    }

    /** Deliberately outside the supported surface, with a recorded rationale. */
    public data class OutOfScope(
        /** Why this technology will not be supported. */
        public val rationale: String,
    ) : CatalogStatus {
        init {
            require(rationale.isNotBlank()) { "An OutOfScope entry must record its rationale." }
        }
    }
}

/** The behaviour a probe asserts today for a [CatalogStatus.NotYet] entry. */
public sealed interface PinnedBehavior {
    /** The stage fails with exactly [diagnostic]. */
    public data class RejectedAt(
        /** Stage the failure is observed at. */
        public val stage: CatalogStage,
        /** Diagnostic identity observed today. */
        public val diagnostic: String,
    ) : PinnedBehavior

    /** The stage succeeds; [observation] names the degradation the probe asserts. */
    public data class SucceededWith(
        /** Stage the observation is made at. */
        public val stage: CatalogStage,
        /** Human-readable description of the degraded behaviour. */
        public val observation: String,
    ) : PinnedBehavior
}

/** Why a [CatalogStatus.NotYet] entry has no probe yet. */
public enum class UnpinnedReason {
    /** No real font carrying the technology is publicly known; coverage stays synthetic. */
    NO_REAL_FONT_KNOWN,
    /** A real font exists but is not in the corpus yet. */
    CORPUS_NOT_ACQUIRED,
}
```

```kotlin
// CorpusKey.kt
package org.graphiks.kalligraphie.e2e.catalog

/**
 * Stable identifier of one font family of `test-fixtures/fonts/`, shared with
 * `scripts/fonts/corpus.json`.
 */
public data class CorpusKey(
    /** Directory name under `test-fixtures/fonts/`. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A corpus key must not be blank." }
        require(value.none { char -> char.isWhitespace() }) { "A corpus key must not contain whitespace." }
    }
}
```

```kotlin
// SceneFramePolicy.kt
package org.graphiks.kalligraphie.e2e.catalog

/** How a supported entry's golden frame is decided. */
public sealed interface SceneFramePolicy {
    /** The ink box measured at render time, plus [padding] on every side. */
    public data class AutoSized(
        /** Margin kept around the measured ink box, in pixels. */
        public val padding: Int = 1,
    ) : SceneFramePolicy {
        init {
            require(padding >= 0) { "A padding must not be negative." }
        }
    }

    /** A hand-pinned frame, preserved verbatim from the pre-catalog registry. */
    public data class Pinned(
        /** Frame width in pixels. */
        public val width: Int,
        /** Frame height in pixels. */
        public val height: Int,
    ) : SceneFramePolicy {
        init {
            require(width > 0 && height > 0) { "A pinned frame must be positive." }
        }
    }
}
```

```kotlin
// CatalogEntry.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** One declared expectation about one font technology. */
public data class CatalogEntry(
    /** Stable identifier, unique across every axis, lower-case dotted form. */
    public val id: String,
    /** Dimension this entry belongs to. */
    public val axis: CatalogAxis,
    /** Short human description of the technology, e.g. "COLR v0 + CPAL v0". */
    public val technology: String,
    /** Corpus family carrying the technology, or null when none applies yet. */
    public val font: CorpusKey?,
    /** Expected behaviour. */
    public val status: CatalogStatus,
    /** Free-form labels for filtering and reporting. */
    public val tags: Set<String> = emptySet(),
    /** SFNT tables this entry's technology consumes, e.g. `setOf("COLR", "CPAL")`. */
    public val tables: Set<String> = emptySet(),
    /** Manifest route of the generated scene; set exactly for [CatalogStatus.Supported]. */
    public val family: GoldenSceneFamily? = null,
    /** Frame policy of the generated scene; set exactly for [CatalogStatus.Supported]. */
    public val frame: SceneFramePolicy? = null,
) {
    init {
        require(id.isNotBlank()) { "A catalog entry id must not be blank." }
        require(id.matches(ID_PATTERN)) { "A catalog entry id must be lower-case dotted form: $id" }
        require(technology.isNotBlank()) { "A catalog entry must describe its technology: $id" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogStatusTest*'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog
git commit -m "feat(e2e): add the expectation catalog model types"
```

## Task 2: Audit du modèle, agrégat et déclarations des axes

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogAuditor.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CorpusKeys.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ExpectationCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ContainerCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/OutlineCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/MetricsCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/VariationCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ColorCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/BitmapCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ScriptCatalog.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/RobustnessCatalog.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogAuditorTest.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ExpectationCatalogTest.kt`

**Interfaces:**
- Consumes: tout de la tâche 1.
- Produces: `CatalogViolation(entryId?, rule, detail)`, `CatalogAuditor.audit(entries): List<CatalogViolation>`,
  `CorpusKeys` (constantes par famille), `ExpectationCatalog.entries: List<CatalogEntry>` et
  `ExpectationCatalog.byAxis: Map<CatalogAxis, List<CatalogEntry>>`.

**Note d'exécution** : les 16 entrées `Supported` de cette tâche déclarent les scènes migrées. Le
champ `sinceCommit` se relève sans deviner, pour chaque id de scène :

```bash
git log -S'<scene id>' --format=%h -- kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv | tail -1
```

- [ ] **Step 1: Write the failing auditor test**

```kotlin
// CatalogAuditorTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

class CatalogAuditorTest {
    @Test
    fun aSupportedEntryNeedsAFontAFamilyAndAFrame() {
        val violations = CatalogAuditor.audit(
            listOf(
                entry(id = "outline.glyf", status = CatalogStatus.Supported("abc1234")),
            ),
        )
        assertEquals(setOf("supported-missing-font", "supported-missing-family", "supported-missing-frame"), violations.map { it.rule }.toSet())
    }

    @Test
    fun aSupportedEntryWithEveryFieldIsClean() {
        val violations = CatalogAuditor.audit(listOf(supportedEntry("outline.glyf")))
        assertTrue(violations.isEmpty(), violations.toString())
    }

    @Test
    fun aSupportedEntryMustClaimAtLeastOneTable() {
        val violations = CatalogAuditor.audit(
            listOf(
                entry(
                    id = "outline.glyf",
                    status = CatalogStatus.Supported("abc1234"),
                    font = CorpusKey("liberation"),
                    family = GoldenSceneFamily.GLYPH_OUTLINE,
                    frame = SceneFramePolicy.Pinned(10, 10),
                ),
            ),
        )
        assertEquals(setOf("supported-without-tables"), violations.map { it.rule }.toSet())
    }

    @Test
    fun anExpectedRejectionMustNotCarryAFrame() {
        val violations = CatalogAuditor.audit(
            listOf(
                entry(
                    id = "robustness.truncated",
                    status = CatalogStatus.ExpectedRejection(CatalogStage.DECODE, "font.sfnt.truncated"),
                    font = CorpusKey("liberation"),
                    family = GoldenSceneFamily.GLYPH_OUTLINE,
                ),
            ),
        )
        assertEquals(setOf("non-scene-carries-scene-fields"), violations.map { it.rule }.toSet())
    }

    @Test
    fun aNotYetEntryPinningABehaviourNeedsAFont() {
        val violations = CatalogAuditor.audit(
            listOf(
                entry(
                    id = "metrics.vvar",
                    status = CatalogStatus.NotYet(
                        trackingIssue = "spec:§5 metrics",
                        currentBehavior = PinnedBehavior.RejectedAt(CatalogStage.METRICS, "font.variation.vvar-missing"),
                        unpinnedReason = null,
                    ),
                ),
            ),
        )
        assertEquals(setOf("pinned-behaviour-missing-font"), violations.map { it.rule }.toSet())
    }

    @Test
    fun duplicateIdsAcrossAxesAreReportedOncePerExtraEntry() {
        val violations = CatalogAuditor.audit(
            listOf(
                supportedEntry("color.colr-v0", CatalogAxis.COLOR),
                supportedEntry("color.colr-v0", CatalogAxis.BITMAP),
            ),
        )
        assertEquals(listOf("duplicate-id"), violations.map { it.rule })
    }

    @Test
    fun everyAxisMustBeCovered() {
        val violations = CatalogAuditor.audit(listOf(supportedEntry("outline.glyf")))
        assertTrue(
            violations.any { violation -> violation.rule == "axis-uncovered" && violation.detail.contains("BITMAP") },
            violations.toString(),
        )
    }

    private fun supportedEntry(id: String, axis: CatalogAxis = CatalogAxis.OUTLINE) = entry(
        id = id,
        axis = axis,
        status = CatalogStatus.Supported("abc1234"),
        font = CorpusKey("liberation"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.Pinned(10, 10),
        tables = setOf("cmap"),
    )

    private fun entry(
        id: String,
        axis: CatalogAxis = CatalogAxis.OUTLINE,
        status: CatalogStatus,
        font: CorpusKey? = null,
        family: GoldenSceneFamily? = null,
        frame: SceneFramePolicy? = null,
        tables: Set<String> = emptySet(),
    ) = CatalogEntry(
        id = id,
        axis = axis,
        technology = "test technology",
        font = font,
        status = status,
        family = family,
        frame = frame,
        tables = tables,
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogAuditorTest*'`
Expected: FAIL — `Unresolved reference: CatalogAuditor`.

- [ ] **Step 3: Write the auditor**

```kotlin
// CatalogAuditor.kt
package org.graphiks.kalligraphie.e2e.catalog

/** One rule violation found in a catalog. */
public data class CatalogViolation(
    /** Id of the offending entry, or null for a catalog-wide violation. */
    public val entryId: String?,
    /** Stable rule name. */
    public val rule: String,
    /** Human-readable detail. */
    public val detail: String,
)

/**
 * Structural audit of an [ExpectationCatalog]: the rules a reviewer would otherwise have to hold
 * in their head across eight axis files. Returns every violation instead of failing on the first,
 * so one run reports the whole state.
 */
public object CatalogAuditor {
    /** Audits [entries], returning violations in entry order followed by catalog-wide rules. */
    public fun audit(entries: List<CatalogEntry>): List<CatalogViolation> {
        val violations = ArrayList<CatalogViolation>()
        val seen = LinkedHashMap<String, CatalogEntry>()
        for (entry in entries) {
            val previous = seen.put(entry.id, entry)
            if (previous != null) {
                violations.add(
                    CatalogViolation(
                        entry.id,
                        "duplicate-id",
                        "${entry.id} is declared twice (axes ${previous.axis} and ${entry.axis})",
                    ),
                )
                continue
            }
            violations.addAll(auditEntry(entry))
        }
        for (axis in CatalogAxis.entries) {
            if (entries.none { entry -> entry.axis == axis }) {
                violations.add(CatalogViolation(null, "axis-uncovered", "axis $axis has no catalog entry"))
            }
        }
        return violations
    }

    private fun auditEntry(entry: CatalogEntry): List<CatalogViolation> {
        val violations = ArrayList<CatalogViolation>()
        when (entry.status) {
            is CatalogStatus.Supported -> {
                if (entry.font == null) violations.add(entry.violation("supported-missing-font", "a supported entry must name its corpus font"))
                if (entry.family == null) violations.add(entry.violation("supported-missing-family", "a supported entry must name its scene family"))
                if (entry.frame == null) violations.add(entry.violation("supported-missing-frame", "a supported entry must name its frame policy"))
                if (entry.tables.isEmpty()) violations.add(entry.violation("supported-without-tables", "a supported entry must claim the tables it exercises"))
            }

            is CatalogStatus.ExpectedRejection, is CatalogStatus.OutOfScope -> {
                if (entry.family != null || entry.frame != null) {
                    violations.add(entry.violation("non-scene-carries-scene-fields", "${entry.id} declares scene fields but produces no scene"))
                }
            }

            is CatalogStatus.NotYet -> {
                if (entry.family != null || entry.frame != null) {
                    violations.add(entry.violation("non-scene-carries-scene-fields", "${entry.id} declares scene fields but produces no scene"))
                }
                if (entry.status.currentBehavior != null && entry.font == null) {
                    violations.add(entry.violation("pinned-behaviour-missing-font", "${entry.id} pins an observable behaviour without a font to observe it on"))
                }
            }
        }
        return violations
    }

    private fun CatalogEntry.violation(rule: String, detail: String) = CatalogViolation(id, rule, detail)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogAuditorTest*'`
Expected: PASS (7 tests).

- [ ] **Step 5: Write the corpus keys and the axis declarations**

`CorpusKeys.kt` déclare une constante par famille de `test-fixtures/fonts/` (19 familles) :

```kotlin
// CorpusKeys.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Canonical corpus keys, one per directory of `test-fixtures/fonts/`. */
public object CorpusKeys {
    /** Liberation Sans, static TrueType. */
    public val LIBERATION: CorpusKey = CorpusKey("liberation")
    /** Amiri Regular, static TrueType Arabic. */
    public val AMIRI: CorpusKey = CorpusKey("amiri")
    /** DejaVu Sans. */
    public val DEJAVU: CorpusKey = CorpusKey("dejavu")
    /** Noto Sans Devanagari. */
    public val NOTO_DEVANAGARI: CorpusKey = CorpusKey("noto-devanagari")
    /** Noto Sans JP vertical fixture. */
    public val NOTO_SANS_JP: CorpusKey = CorpusKey("noto-sans-jp")
    /** Bungee Color, COLR v0. */
    public val BUNGEE_COLOR: CorpusKey = CorpusKey("bungee-color")
    /** Emoji Two COLR v0. */
    public val EMOJI_TWO_COLR_V0: CorpusKey = CorpusKey("emoji-two-colr-v0")
    /** Skia COLR v1 test glyphs. */
    public val SKIA_COLR_V1: CorpusKey = CorpusKey("skia-colr-v1")
    /** Synthetic variable COLR v1. */
    public val KALLIGRAPHIE_VAR_COLR: CorpusKey = CorpusKey("kalligraphie-var-colr")
    /** Synthetic VVAR fixture. */
    public val KALLIGRAPHIE_VAR_VVAR: CorpusKey = CorpusKey("kalligraphie-var-vvar")
    /** Skia CBDT/CBLC strikes. */
    public val SKIA_CBDT: CorpusKey = CorpusKey("skia-cbdt")
    /** Skia EBDT format 1 strike. */
    public val SKIA_EBDT_FORMAT1: CorpusKey = CorpusKey("skia-ebdt-format1")
    /** Skia sbix strikes. */
    public val SKIA_SBIX: CorpusKey = CorpusKey("skia-sbix")
    /** Twemoji SVG-in-OpenType subset. */
    public val TWEMOJI_SVGINOT_GLYPH5: CorpusKey = CorpusKey("twemoji-svginot-glyph5")
    /** Liberation Sans converted to CFF 1. */
    public val CFF_LIBERATION: CorpusKey = CorpusKey("cff-liberation")
    /** Liberation Sans converted to CFF 2. */
    public val CFF2_LIBERATION: CorpusKey = CorpusKey("cff2-liberation")
    /** Synthetic variable CFF 2. */
    public val CFF2_VARIABLE: CorpusKey = CorpusKey("cff2-variable")
    /** Minimal GDEF/GPOS caret fixture. */
    public val GDEF_KERN: CorpusKey = CorpusKey("gdef-kern")
    /** Liberation + Amiri TrueType collection. */
    public val LIBERATION_AMIRI_COLLECTION: CorpusKey = CorpusKey("liberation-amiri-collection")
}
```

`ScriptCatalog.kt` — les 11 entrées migrées de l'axe SCRIPT (`sinceCommit` relevé par la commande
de la note d'exécution), par exemple :

```kotlin
// ScriptCatalog.kt (extrait — les 11 entrées suivent le même gabarit)
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Writing-system expectations: one entry per script the end-to-end catalog renders. */
public object ScriptCatalog {
    /** Declared script expectations. */
    public val entries: List<CatalogEntry> = listOf(
        line(
            id = "script.latin.composed-line",
            technology = "Latin composed line through the paragraph facade",
            font = CorpusKeys.LIBERATION,
            scene = "line.latin.48",
            sinceCommit = "<hash>",
            tags = setOf("scripts:latin"),
        ),
        // … idem pour grec, cyrillique, arabe, devanagari, mixte
        sheet(
            id = "script.latin.outline-sheet",
            technology = "Latin outline alphabet sheet",
            font = CorpusKeys.LIBERATION,
            scene = "sheet.outline.liberation-latin.32",
            sinceCommit = "<hash>",
            tags = setOf("scripts:latin"),
        ),
        // … idem pour les quatre autres feuilles
    )

    private fun line(id: String, technology: String, font: CorpusKey, scene: String, sinceCommit: String, tags: Set<String>) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.SCRIPT,
            technology = technology,
            font = font,
            status = CatalogStatus.Supported(sinceCommit),
            tags = tags,
            tables = setOf("cmap", "GSUB", "GPOS"),
            family = GoldenSceneFamily.COMPOSED_LINE,
            frame = SceneFramePolicy.Pinned(width = PINNED_FRAMES.getValue(scene).first, height = PINNED_FRAMES.getValue(scene).second),
        )

    private fun sheet(id: String, technology: String, font: CorpusKey, scene: String, sinceCommit: String, tags: Set<String>) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.SCRIPT,
            technology = technology,
            font = font,
            status = CatalogStatus.Supported(sinceCommit),
            tags = tags,
            tables = setOf("cmap", "glyf", "loca", "hmtx"),
            family = GoldenSceneFamily.ALPHABET_SHEET,
            frame = SceneFramePolicy.Pinned(width = PINNED_FRAMES.getValue(scene).first, height = PINNED_FRAMES.getValue(scene).second),
        )

    private val PINNED_FRAMES: Map<String, Pair<Int, Int>> = mapOf(
        "line.latin.48" to (250 to 49),
        "line.greek.48" to (267 to 51),
        "line.cyrillic.48" to (299 to 49),
        "line.arabic.48" to (182 to 64),
        "line.devanagari.48" to (156 to 52),
        "line.mixed.48" to (1235 to 62),
        "sheet.outline.liberation-latin.32" to (576 to 140),
        "sheet.outline.liberation-greek.32" to (464 to 140),
        "sheet.outline.liberation-cyrillic.32" to (560 to 160),
        "sheet.outline.amiri-arabic.32" to (736 to 162),
        "sheet.outline.noto-devanagari.32" to (624 to 156),
    )
}
```

Les cadres épinglés sont recopiés du manifeste committé
(`kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv`) — c'est la même valeur qui y est
enregistrée, et la tâche 4 vérifie qu'elle n'a pas bougé.

`OutlineCatalog.kt`, `BitmapCatalog.kt` et `ColorCatalog.kt` reçoivent la même structure pour leurs
scènes migrées :

- `OutlineCatalog` : `outline.glyf-simple-composite` → scène `glyph.outline.liberation-sans.A.64`,
  `Pinned(43, 45)`, famille `GLYPH_OUTLINE`, font `LIBERATION`, tables
  `cmap, glyf, head, hhea, hmtx, loca, maxp, post, OS/2`.
- `BitmapCatalog` : `bitmap.ebdt-format1` → scène `glyph.bitmap.skia-ebdt-format1.u1F600.16`,
  `Pinned(13, 13)`, famille `GLYPH_BITMAP`, font `SKIA_EBDT_FORMAT1`, tables `EBLC, EBDT`.
- `ColorCatalog` : `color.colr-v0-single-glyph` → scène `glyph.paint.emoji-two-colr-v0.u1F600.64`,
  `Pinned(71, 72)`, tables `COLR, CPAL` ; `color.colr-v0-alphabet-sheet` → scène
  `sheet.paint.bungee-color-latin.48`, `Pinned(656, 90)`, font `BUNGEE_COLOR`, tables `COLR, CPAL` ;
  `color.colr-v0-emoji-sheet` → `sheet.paint.emoji-two-colr-v0.64`, `Pinned(1200, 76)`, font
  `EMOJI_TWO_COLR_V0`, tables `COLR, CPAL`. Famille `GLYPH_PAINT` pour la première,
  `ALPHABET_SHEET` pour les deux feuilles.

`ContainerCatalog.kt`, `MetricsCatalog.kt`, `VariationCatalog.kt` et `RobustnessCatalog.kt` sont
créés avec leurs entrées documentées (aucune scène) :

```kotlin
// VariationCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Variable-font expectations, including the technologies deliberately not supported yet. */
public object VariationCatalog {
    /** Declared variation expectations. */
    public val entries: List<CatalogEntry> = listOf(
        documented(
            id = "variation.avar-v2",
            technology = "avar version 2 segment maps",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "variation.cvar",
            technology = "cvar CVT variations",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "variation.varc",
            technology = "VARC variable composite glyphs",
            unpinnedReason = UnpinnedReason.NO_REAL_FONT_KNOWN,
        ),
        documented(
            id = "variation.stat",
            technology = "STAT style attributes",
            unpinnedReason = UnpinnedReason.CORPUS_NOT_ACQUIRED,
        ),
    )

    private fun documented(id: String, technology: String, unpinnedReason: UnpinnedReason) = CatalogEntry(
        id = id,
        axis = CatalogAxis.VARIATION,
        technology = technology,
        font = null,
        status = CatalogStatus.NotYet(
            trackingIssue = "spec:§5 variation",
            currentBehavior = null,
            unpinnedReason = unpinnedReason,
        ),
        tags = setOf("documented-only"),
    )
}
```

Même gabarit `documented(...)` pour :

- `ContainerCatalog` : `container.woff2`, `container.woff` → `CORPUS_NOT_ACQUIRED`.
- `MetricsCatalog` : `metrics.vvar-real-font`, `metrics.mvar-real-font` → `NO_REAL_FONT_KNOWN`.
- `ColorCatalog` : en plus des trois scènes migrées, `color.colr-cff` en
  `OutOfScope("CFF-in-COLR is not part of the supported paint surface; the rationale is recorded in font-management.md.")`,
  et `color.cpal-variable` → `CORPUS_NOT_ACQUIRED`.
- `RobustnessCatalog` : les deux entrées `ExpectedRejection` de la tâche 7 (laisser le fichier vide
  de ce côté pour l'instant est interdit — cet axe doit être couvert ; y placer dès maintenant
  `robustness.truncated-sfnt` et `robustness.empty-input` en `NotYet` avec
  `CORPUS_NOT_ACQUIRED`, que la tâche 7 convertira en `ExpectedRejection`).

- [ ] **Step 6: Write the aggregate and its test**

```kotlin
// ExpectationCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** The aggregate of every declared expectation, grouped by axis. */
public object ExpectationCatalog {
    /** Every entry, in axis declaration order. */
    public val entries: List<CatalogEntry> =
        ContainerCatalog.entries + OutlineCatalog.entries + MetricsCatalog.entries +
            VariationCatalog.entries + ColorCatalog.entries + BitmapCatalog.entries +
            ScriptCatalog.entries + RobustnessCatalog.entries

    /** Entries grouped by axis, each list in declaration order. */
    public val byAxis: Map<CatalogAxis, List<CatalogEntry>> = entries.groupBy { entry -> entry.axis }
}
```

```kotlin
// ExpectationCatalogTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpectationCatalogTest {
    @Test
    fun theCatalogPassesItsOwnAudit() {
        val violations = CatalogAuditor.audit(ExpectationCatalog.entries)
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { violation -> "${violation.rule}: ${violation.detail}" })
    }

    @Test
    fun everyAxisIsDeclaredExactlyOncePerEntry() {
        val regrouped = ExpectationCatalog.byAxis.values.flatten()
        assertEquals(ExpectationCatalog.entries.size, regrouped.size)
    }

    @Test
    fun theMigratedGoldenScenesAreAllDeclared() {
        val expected = setOf(
            "glyph.outline.liberation-sans.A.64",
            "glyph.paint.emoji-two-colr-v0.u1F600.64",
            "glyph.bitmap.skia-ebdt-format1.u1F600.16",
            "line.latin.48", "line.greek.48", "line.cyrillic.48",
            "line.arabic.48", "line.devanagari.48", "line.mixed.48",
            "sheet.outline.liberation-latin.32", "sheet.outline.liberation-greek.32",
            "sheet.outline.liberation-cyrillic.32", "sheet.outline.amiri-arabic.32",
            "sheet.outline.noto-devanagari.32",
            "sheet.paint.bungee-color-latin.48", "sheet.paint.emoji-two-colr-v0.64",
        )
        val declared = ExpectationCatalog.entries
            .filter { entry -> entry.status is CatalogStatus.Supported }
            .mapNotNull { entry -> (entry.frame as? SceneFramePolicy.Pinned)?.let { entry.id } }
            .toSet()
        assertEquals(16, declared.size, "the sixteen migrated scenes must all be declared: $declared")
        assertEquals(expected.size, declared.size)
    }
}
```

Le troisième test n'a pas besoin des ids de scène : chaque entrée migrée porte le frame épinglé de
sa scène et il y en a exactement seize. Le rattachement entrée ↔ scène est vérifié par le cliquet
de la tâche 6, qui dispose du registre des rendus.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*Catalog*Test*'`
Expected: PASS — `CatalogStatusTest` (8), `CatalogAuditorTest` (6), `ExpectationCatalogTest` (3).

- [ ] **Step 8: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog
git commit -m "feat(e2e): declare the expectation catalog axes and audit rules"
```

## Task 3: Boîte d'encre et recadrage d'image

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/GoldenInkBox.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/GoldenImageReframer.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/GoldenInkBoxTest.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/GoldenImageReframerTest.kt`

**Interfaces:**
- Consumes: `GoldenImage` (existant, `commonMain`), `PixelFormat` (existant).
- Produces: `InkBox(minX, minY, maxX, maxY)` avec `width`/`height` dérivés ;
  `GoldenInkBox.of(image): InkBox?` (null si entièrement vide) ;
  `GoldenImageReframer.reframe(image, width, height, offsetX, offsetY): GoldenImage`.

- [ ] **Step 1: Write the failing ink-box test**

```kotlin
// GoldenInkBoxTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.graphiks.kalligraphie.e2e.GoldenImage

class GoldenInkBoxTest {
    @Test
    fun aFullyTransparentAlphaImageHasNoInkBox() {
        assertNull(GoldenInkBox.of(GoldenImage.alpha8(4, 3, ByteArray(12))))
    }

    @Test
    fun theInkBoxIsTightAroundNonZeroAlphaSamples() {
        // 5x4, single lit pixel at (2, 1).
        val pixels = ByteArray(20)
        pixels[1 * 5 + 2] = 0x40
        assertEquals(InkBox(minX = 2, minY = 1, maxX = 2, maxY = 1), GoldenInkBox.of(GoldenImage.alpha8(5, 4, pixels)))
    }

    @Test
    fun inkBoxDimensionsAreInclusive() {
        val box = InkBox(minX = 1, minY = 2, maxX = 4, maxY = 6)
        assertEquals(4, box.width)
        assertEquals(5, box.height)
    }

    @Test
    fun rgbaInkIsDecidedByTheAlphaChannelOnly() {
        // 2x1: first pixel has colour but no alpha, second has alpha.
        val pixels = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte())
        assertEquals(InkBox(minX = 1, minY = 0, maxX = 1, maxY = 0), GoldenInkBox.of(GoldenImage.rgba8(2, 1, pixels)))
    }

    @Test
    fun aSingleLitPixelInTheCornerIsFound() {
        val pixels = ByteArray(9)
        pixels[8] = 1
        assertEquals(InkBox(minX = 2, minY = 2, maxX = 2, maxY = 2), GoldenInkBox.of(GoldenImage.alpha8(3, 3, pixels)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenInkBoxTest*'`
Expected: FAIL — `Unresolved reference: GoldenInkBox`.

- [ ] **Step 3: Write the ink box**

```kotlin
// GoldenInkBox.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.PixelFormat

/** Tight box of ink pixels inside a canonical image, in inclusive image coordinates. */
public data class InkBox(
    /** Leftmost column holding ink. */
    public val minX: Int,
    /** Topmost row holding ink. */
    public val minY: Int,
    /** Rightmost column holding ink. */
    public val maxX: Int,
    /** Bottommost row holding ink. */
    public val maxY: Int,
) {
    /** Number of columns the box spans. */
    public val width: Int get() = maxX - minX + 1

    /** Number of rows the box spans. */
    public val height: Int get() = maxY - minY + 1
}

/**
 * Measures the ink of a canonical image. A pixel carries ink when its alpha is non-zero, so an
 * opaque black pixel and a transparent white one are told apart by coverage alone — the same rule
 * for [PixelFormat.ALPHA_8] and [PixelFormat.RGBA_8888].
 */
public object GoldenInkBox {
    /** Returns the tight ink box of [image], or `null` when no pixel carries coverage. */
    public fun of(image: GoldenImage): InkBox? {
        val bytes = image.copyCanonicalBytes()
        val bytesPerPixel = image.format.bytesPerPixel
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alphaIndex = (y * image.width + x) * bytesPerPixel + (bytesPerPixel - 1)
                if (bytes[alphaIndex].toInt() == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else InkBox(minX, minY, maxX, maxY)
    }
}
```

`PixelFormat` place l'octet de couverture en dernière position pour `ALPHA_8` comme pour
`RGBA_8888` (canal alpha en R,G,B,**A**), d'où `bytesPerPixel - 1`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenInkBoxTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the failing reframer test**

```kotlin
// GoldenImageReframerTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.graphiks.kalligraphie.e2e.GoldenImage

class GoldenImageReframerTest {
    @Test
    fun theSourceImageLandsAtTheOffset() {
        val source = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val framed = GoldenImageReframer.reframe(source, width = 4, height = 4, offsetX = 1, offsetY = 2)
        assertEquals(4, framed.width)
        assertEquals(4, framed.height)
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 2, 0,
                0, 3, 4, 0,
            ),
            framed.copyCanonicalBytes(),
        )
    }

    @Test
    fun clippingTheSourceIsRefused() {
        val source = GoldenImage.alpha8(3, 3, ByteArray(9))
        assertFailsWith<IllegalArgumentException> {
            GoldenImageReframer.reframe(source, width = 2, height = 2, offsetX = 0, offsetY = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            GoldenImageReframer.reframe(source, width = 3, height = 3, offsetX = 1, offsetY = 0)
        }
    }

    @Test
    fun aZeroSizedFrameIsRefused() {
        val source = GoldenImage.alpha8(1, 1, byteArrayOf(7))
        assertFailsWith<IllegalArgumentException> {
            GoldenImageReframer.reframe(source, width = 0, height = 1, offsetX = 0, offsetY = 0)
        }
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenImageReframerTest*'`
Expected: FAIL — `Unresolved reference: GoldenImageReframer`.

- [ ] **Step 7: Write the reframer**

```kotlin
// GoldenImageReframer.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenImage

/**
 * Places a rendered image inside a larger canonical frame without touching its pixels. Clipping is
 * refused rather than cropping: silently dropping ink would hide the very regression a golden scene
 * exists to catch.
 */
public object GoldenImageReframer {
    /** Copies [image] into a [width] x [height] frame at ([offsetX], [offsetY]). */
    public fun reframe(image: GoldenImage, width: Int, height: Int, offsetX: Int, offsetY: Int): GoldenImage {
        require(width > 0 && height > 0) { "A reframed image must be positive." }
        require(offsetX >= 0 && offsetY >= 0) { "A reframe offset must not be negative." }
        require(offsetX + image.width <= width && offsetY + image.height <= height) {
            "A reframe must not clip the source image."
        }
        val bytesPerPixel = image.format.bytesPerPixel
        val source = image.copyCanonicalBytes()
        val target = ByteArray(width * height * bytesPerPixel)
        for (y in 0 until image.height) {
            val sourceRow = y * image.width * bytesPerPixel
            val targetRow = ((y + offsetY) * width + offsetX) * bytesPerPixel
            source.copyInto(target, destinationOffset = targetRow, startIndex = sourceRow, endIndex = sourceRow + image.width * bytesPerPixel)
        }
        return when (image.format) {
            org.graphiks.kalligraphie.e2e.PixelFormat.ALPHA_8 -> GoldenImage.alpha8(width, height, target)
            org.graphiks.kalligraphie.e2e.PixelFormat.RGBA_8888 -> GoldenImage.rgba8(width, height, target)
        }
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenInkBoxTest*' --tests '*GoldenImageReframerTest*'`
Expected: PASS (5 + 3 tests).

- [ ] **Step 9: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog
git commit -m "feat(e2e): measure ink boxes and reframe canonical images"
```

## Task 4: Matérialiseur de scènes et migration des seize scènes

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/JvmGoldenEntry.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogSceneRenderer.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogSceneMaterializer.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneRenderers.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenDiagnosticCode.kt`
- Modify: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/JvmGoldenSceneCatalog.kt` (devient un shim)
- Test: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogSceneMaterializerTest.kt`

**Interfaces:**
- Consumes: `ExpectationCatalog.entries`, `CatalogEntry`, `SceneFramePolicy`, `GoldenInkBox`,
  `GoldenImageReframer`, `GoldenDiagnosticCode`, les helpers de fixture existants
  (`fixtureBytes`, `openOutlineFixture`, `openRenderableFixture`, `outlineRequirements`,
  `paintRequirements`, `bitmapRequirements`), `ComposedLineScenes.line`, `GlyphSheetScenes`.
- Produces: `CatalogSceneRenderer(fontPath, render: () -> GoldenRenderOutcome)`,
  `CatalogSceneMaterializer.materialize(entry, renderer): JvmGoldenEntry`,
  `CatalogSceneMaterializer.materializeAll(entries, renderers): List<JvmGoldenEntry>`,
  `JvmGoldenEntry(scene, render)` (déplacé depuis `JvmGoldenSceneCatalog.kt`),
  `GoldenDiagnosticCode.BLANK_SCENE`.

- [ ] **Step 1: Add the blank-scene diagnostic**

Ajouter la constante à `GoldenDiagnosticCode` :

```kotlin
    /** An auto-sized scene measured no ink at all. */
    BLANK_SCENE("e2e.blank-scene"),
```

- [ ] **Step 2: Write the failing materializer test**

```kotlin
// CatalogSceneMaterializerTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

class CatalogSceneMaterializerTest {
    @Test
    fun anAutoSizedSceneFramesItsInkBoxWithPadding() {
        val entry = autoSizedEntry(padding = 2)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(4, 3, inkAt(x = 1, y = 1, width = 4, height = 3)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        assertEquals(5, materialized.scene.width, "ink box is 2x2, padding 2 on each side")
        assertEquals(5, materialized.scene.height)
        val framed = assertIs<GoldenRenderOutcome.Rendered>(materialized.render()).image
        assertEquals(5, framed.width)
        assertEquals(5, framed.height)
    }

    @Test
    fun aPinnedSceneRefusesAFrameThatNoLongerMatches() {
        val entry = pinnedEntry(width = 10, height = 10)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(9, 10, ByteArray(90)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        val refused = assertIs<GoldenRenderOutcome.Refused>(materialized.render())
        assertEquals(GoldenDiagnosticCode.SCENE_BOUNDS_INVALID, refused.code)
    }

    @Test
    fun aPinnedSceneKeepsItsDeclaredFrameWhenTheRenderAgrees() {
        val entry = pinnedEntry(width = 10, height = 10)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(10, 10, ByteArray(100)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        assertEquals(10, materialized.scene.width)
        assertEquals(GoldenSceneFamily.GLYPH_OUTLINE, materialized.scene.family)
    }

    @Test
    fun anAutoSizedSceneWithNoInkIsRefused() {
        val entry = autoSizedEntry(padding = 1)
        val renderer = CatalogSceneRenderer("/fonts/liberation/LiberationSans-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(8, 8, ByteArray(64)))
        }

        val materialized = CatalogSceneMaterializer.materialize(entry, renderer)

        val refused = assertIs<GoldenRenderOutcome.Refused>(materialized.render())
        assertEquals(GoldenDiagnosticCode.BLANK_SCENE, refused.code)
    }

    @Test
    fun theRendererFontPathMustBelongToTheEntryCorpusKey() {
        val entry = autoSizedEntry(padding = 1)
        val renderer = CatalogSceneRenderer("/fonts/amiri/Amiri-Regular.ttf") {
            GoldenRenderOutcome.Rendered(GoldenImage.alpha8(2, 2, byteArrayOf(0, 0, 0, 1)))
        }

        val failure = CatalogSceneMaterializer.fontPathMismatch(entry, renderer)
        assertEquals(
            "entry outline.glyf declares corpus key liberation but renders /fonts/amiri/Amiri-Regular.ttf",
            failure,
        )
    }

    private fun autoSizedEntry(padding: Int) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = "glyf outlines",
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.AutoSized(padding = padding),
    )

    private fun pinnedEntry(width: Int, height: Int) = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = "glyf outlines",
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.Pinned(width = width, height = height),
    )

    private fun inkAt(x: Int, y: Int, width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height)
        pixels[y * width + x] = 0xFF.toByte()
        pixels[(y + 1) * width + x + 1] = 0xFF.toByte()
        return pixels
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogSceneMaterializerTest*'`
Expected: FAIL — `Unresolved reference: CatalogSceneMaterializer`.

- [ ] **Step 4: Write the renderer type, the entry holder and the materializer**

```kotlin
// CatalogSceneRenderer.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

/**
 * One entry's renderer, at its natural frame.
 *
 * Framing is owned by [CatalogSceneMaterializer], never by the renderer: the renderer draws and
 * says nothing about the frame, so a pinned frame and an auto-sized one go through the same code.
 */
internal class CatalogSceneRenderer(
    /** Resource path of the font this renderer loads; checked against the entry's corpus key. */
    val fontPath: String,
    /** Renders at the natural frame. */
    val render: () -> GoldenRenderOutcome,
)
```

```kotlin
// JvmGoldenEntry.kt  (déplacé depuis JvmGoldenSceneCatalog.kt, sans changement de forme)
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene

/** A catalogued scene paired with the renderer that produces its canonical image. */
internal class JvmGoldenEntry(
    val scene: GoldenScene,
    val render: () -> GoldenRenderOutcome,
)
```

```kotlin
// CatalogSceneMaterializer.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.math.max
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene

/** Turns a catalog entry plus its renderer into the golden scene the verifier consumes. */
internal object CatalogSceneMaterializer {
    /** Materializes every supported entry, failing on the first entry that has no renderer. */
    fun materializeAll(
        entries: List<CatalogEntry>,
        renderers: Map<String, CatalogSceneRenderer>,
    ): List<JvmGoldenEntry> = entries.mapNotNull { entry ->
        when (entry.status) {
            is CatalogStatus.Supported -> materialize(entry, renderers.getValue(entry.id))
            else -> null
        }
    }

    /** Materializes [entry] against [renderer]. */
    fun materialize(entry: CatalogEntry, renderer: CatalogSceneRenderer): JvmGoldenEntry {
        val family = requireNotNull(entry.family) { "${entry.id} is supported but declares no family" }
        val frame = requireNotNull(entry.frame) { "${entry.id} is supported but declares no frame" }
        val natural = lazy { renderer.render() }
        return when (frame) {
            is SceneFramePolicy.Pinned -> {
                val scene = GoldenScene(entry.id, family, frame.width, frame.height, entry.tags)
                JvmGoldenEntry(scene) {
                    when (val outcome = natural.value) {
                        is GoldenRenderOutcome.Refused -> outcome
                        is GoldenRenderOutcome.Rendered -> {
                            val image = outcome.image
                            if (image.width != frame.width || image.height != frame.height) {
                                refused(scene, GoldenDiagnosticCode.SCENE_BOUNDS_INVALID, "rendered ${image.width}x${image.height}")
                            } else {
                                outcome
                            }
                        }
                    }
                }
            }

            is SceneFramePolicy.AutoSized -> {
                val outcome = natural.value
                if (outcome is GoldenRenderOutcome.Refused) {
                    val scene = GoldenScene(entry.id, family, 1, 1, entry.tags)
                    JvmGoldenEntry(scene) { outcome }
                } else {
                    val image = (outcome as GoldenRenderOutcome.Rendered).image
                    val ink = GoldenInkBox.of(image)
                    if (ink == null) {
                        val scene = GoldenScene(entry.id, family, max(1, image.width), max(1, image.height), entry.tags)
                        JvmGoldenEntry(scene) { refused(scene, GoldenDiagnosticCode.BLANK_SCENE, "measured no ink") }
                    } else {
                        val width = ink.width + 2 * frame.padding
                        val height = ink.height + 2 * frame.padding
                        val scene = GoldenScene(entry.id, family, width, height, entry.tags)
                        JvmGoldenEntry(scene) {
                            GoldenRenderOutcome.Rendered(
                                GoldenImageReframer.reframe(
                                    image = image,
                                    width = width,
                                    height = height,
                                    offsetX = frame.padding - ink.minX,
                                    offsetY = frame.padding - ink.minY,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Returns a description of the corpus-key mismatch for [entry] and [renderer], or `null` when they agree. */
    fun fontPathMismatch(entry: CatalogEntry, renderer: CatalogSceneRenderer): String? {
        val key = entry.font ?: return null
        return if (renderer.fontPath.contains("/${key.value}/")) {
            null
        } else {
            "entry ${entry.id} declares corpus key ${key.value} but renders ${renderer.fontPath}"
        }
    }

    private fun refused(scene: GoldenScene, code: GoldenDiagnosticCode, what: String) = GoldenRenderOutcome.Refused(
        code = code,
        detail = "${scene.id} $what",
    )
}
```

Le matérialiseur ne rend qu'une fois par entrée : le `lazy` est partagé entre le calcul du cadre et
la lambda retournée.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogSceneMaterializerTest*'`
Expected: PASS (5 tests).

- [ ] **Step 6: Migrate the sixteen renderers**

Créer `SceneRenderers.kt` avec le registre complet. Les corps sont repris **verbatim** de
`JvmGoldenSceneCatalog.kt`, avec deux changements mécaniques pour chacun :

1. la signature devient `CatalogSceneRenderer(fontPath = "<chemin ressource>") { … }` — le
   paramètre `scene` disparaît ;
2. les appels `alpha8Outcome(scene, w, h, pixels)` / `rgba8Outcome(scene, w, h, pixels)` deviennent
   `GoldenRenderOutcome.Rendered(GoldenImage.alpha8(w, h, pixels))` /
   `GoldenRenderOutcome.Rendered(GoldenImage.rgba8(w, h, pixels))` — le contrôle de dimensions et
   le refus `SCENE_BOUNDS_INVALID` sont désormais la responsabilité du matérialiseur, qui les
   applique pour les deux politiques de cadre ;
3. la fonction `composed(scene) { … }` devient `composed { … }` : le `try/catch` reste, la
   comparaison de dimensions part.

Structure attendue du fichier (les 16 entrées, chacune avec son `fontPath` réel) :

```kotlin
// SceneRenderers.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.golden.ComposedLineScenes
import org.graphiks.kalligraphie.e2e.golden.GlyphSheetScenes
import org.graphiks.kalligraphie.e2e.golden.bitmapRequirements
import org.graphiks.kalligraphie.e2e.golden.fixtureBytes
import org.graphiks.kalligraphie.e2e.golden.openOutlineFixture
import org.graphiks.kalligraphie.e2e.golden.openRenderableFixture
import org.graphiks.kalligraphie.e2e.golden.outlineRequirements
import org.graphiks.kalligraphie.e2e.golden.paintRequirements
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.raster.BitmapRasterRequest
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult

/** The renderers of the migrated scenes, keyed by catalog entry id. */
internal object SceneRenderers {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"
    private const val AMIRI = "/fonts/amiri/Amiri-Regular.ttf"
    private const val NOTO_DEVANAGARI = "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf"
    private const val BUNGEE_COLOR = "/fonts/bungee-color/BungeeColor-Regular.ttf"
    private const val EMOJI_TWO_COLR_V0 = "/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf"
    private const val SKIA_EBDT_FORMAT1 = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"

    /** Every registered renderer. */
    val byId: Map<String, CatalogSceneRenderer> = mapOf(
        "outline.glyf-simple-composite" to CatalogSceneRenderer(LIBERATION_SANS, ::renderLiberationCapitalA),
        "color.colr-v0-single-glyph" to CatalogSceneRenderer(EMOJI_TWO_COLR_V0, ::renderEmojiTwoPaint),
        "bitmap.ebdt-format1" to CatalogSceneRenderer(SKIA_EBDT_FORMAT1, ::renderEbdtFormat1Bitmap),
        "script.latin.composed-line" to CatalogSceneRenderer(LIBERATION_SANS) {
            composed { ComposedLineScenes.line("Kalligraphie", "en", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.greek.composed-line" to CatalogSceneRenderer(LIBERATION_SANS) {
            composed { ComposedLineScenes.line("Καλλιγραφία", "el", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.cyrillic.composed-line" to CatalogSceneRenderer(LIBERATION_SANS) {
            composed { ComposedLineScenes.line("Каллиграфия", "ru", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.arabic.composed-line" to CatalogSceneRenderer(AMIRI) {
            composed { ComposedLineScenes.line("الخط العربي", "ar", BaseDirection.RIGHT_TO_LEFT, requiredFaces = 0) }
        },
        "script.devanagari.composed-line" to CatalogSceneRenderer(NOTO_DEVANAGARI) {
            composed { ComposedLineScenes.line("देवनागरी", "hi", BaseDirection.LEFT_TO_RIGHT, requiredFaces = 0) }
        },
        "script.mixed.composed-line" to CatalogSceneRenderer(LIBERATION_SANS) {
            composed {
                ComposedLineScenes.line(
                    "Kalligraphie — Ελληνικά — Кириллица — العربية — देवनागरी",
                    "en",
                    BaseDirection.LEFT_TO_RIGHT,
                    requiredFaces = 3,
                )
            }
        },
        "script.latin.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS) { composed { GlyphSheetScenes.outlineSheet(LIBERATION_SANS, LATIN, 32.0) } },
        "script.greek.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS) { composed { GlyphSheetScenes.outlineSheet(LIBERATION_SANS, GREEK, 32.0) } },
        "script.cyrillic.outline-sheet" to CatalogSceneRenderer(LIBERATION_SANS) { composed { GlyphSheetScenes.outlineSheet(LIBERATION_SANS, CYRILLIC, 32.0) } },
        "script.arabic.outline-sheet" to CatalogSceneRenderer(AMIRI) { composed { GlyphSheetScenes.outlineSheet(AMIRI, ARABIC, 32.0) } },
        "script.devanagari.outline-sheet" to CatalogSceneRenderer(NOTO_DEVANAGARI) { composed { GlyphSheetScenes.outlineSheet(NOTO_DEVANAGARI, DEVANAGARI, 32.0) } },
        "color.colr-v0-alphabet-sheet" to CatalogSceneRenderer(BUNGEE_COLOR) { composed { GlyphSheetScenes.paintSheet(BUNGEE_COLOR, LATIN_LETTERS, 48.0, paletteIndex = 0) } },
        "color.colr-v0-emoji-sheet" to CatalogSceneRenderer(EMOJI_TWO_COLR_V0) { composed { GlyphSheetScenes.paintSheet(EMOJI_TWO_COLR_V0, EMOJI, 64.0, paletteIndex = 0) } },
    )

    // Les listes de codepoints et les corps des trois scènes glyphiques sont repris du fichier
    // d'origine ; ils ne changent pas.
    private val LATIN_LETTERS: List<Int> = (0x41..0x5A).toList()
    private val LATIN: List<Int> = LATIN_LETTERS + (0x61..0x7A) + (0x30..0x39)
    private val GREEK: List<Int> = (0x391..0x3A9).filter { codepoint -> codepoint != 0x3A2 } + (0x3B1..0x3C9)
    private val CYRILLIC: List<Int> = (0x410..0x42F).toList() + (0x430..0x44F).toList()
    private val ARABIC: List<Int> = (0x621..0x63A).toList() + (0x641..0x64A).toList()
    private val DEVANAGARI: List<Int> = (0x905..0x939).toList() + (0x966..0x96F).toList()
    private val EMOJI: List<Int> = (0x1F600..0x1F607).filter { codepoint -> codepoint != 0x1F602 && codepoint != 0x1F604 }

    private fun renderLiberationCapitalA(): GoldenRenderOutcome = …  // corps d'origine, sans scene
    private fun renderEmojiTwoPaint(): GoldenRenderOutcome = …
    private fun renderEbdtFormat1Bitmap(): GoldenRenderOutcome = …
}
```

Les noms d'entrées doivent correspondre exactement à ceux déclarés dans les fichiers d'axes de la
tâche 2 : `SceneRenderers.byId.keys` et l'ensemble des entrées `Supported` sont égaux (la tâche 6
l'assure mécaniquement, mais l'écrire juste du premier coup évite un aller-retour).

- [ ] **Step 7: Reduce the old catalog to a shim**

```kotlin
// JvmGoldenSceneCatalog.kt  (fichier remplacé : il ne reste que la délégation)
package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.catalog.CatalogSceneMaterializer
import org.graphiks.kalligraphie.e2e.catalog.ExpectationCatalog
import org.graphiks.kalligraphie.e2e.catalog.JvmGoldenEntry
import org.graphiks.kalligraphie.e2e.catalog.SceneRenderers

/**
 * The JVM scene catalog, now derived from [ExpectationCatalog]: every supported entry is
 * materialized against its renderer, so a scene can no longer exist without a declared expectation
 * and a frame can no longer drift from its entry.
 */
internal object JvmGoldenSceneCatalog {
    fun entries(): List<JvmGoldenEntry> =
        CatalogSceneMaterializer.materializeAll(ExpectationCatalog.entries, SceneRenderers.byId)
}
```

- [ ] **Step 8: Verify the sixteen fingerprints are unchanged**

Run:
```bash
./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenVerificationTest*' --tests '*JvmGoldenSceneCatalogTest*'
git diff --exit-code kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv
```
Expected: PASS, et le `git diff` ne montre **aucune** modification du manifeste. C'est la
vérification centrale de la migration : mêmes ids, mêmes cadres épinglés, mêmes empreintes.

- [ ] **Step 9: Run the whole module**

Run: `./gradlew :kalligraphie:e2e:jvmTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add kalligraphie/e2e/src/commonMain kalligraphie/e2e/src/jvmTest
git commit -m "refactor(e2e): derive the golden catalog from the expectation catalog"
```

## Task 5: Scènes auto-dimensionnées de démonstration

**Files:**
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/BitmapCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/OutlineCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ColorCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/MetricsCatalog.kt`
- Modify: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneRenderers.kt`
- Modify: `kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv` (régénéré — ajouts uniquement)

**Interfaces:**
- Consumes: tout des tâches 1 à 4.
- Produces: six nouvelles entrées `Supported` en `AutoSized`, et les renderers correspondants.

**Note d'exécution** : quatre des six scènes ci-dessous dépendent de codepoints que seule
l'inspection tranche. Pour chaque police concernée, relever les codepoints avant d'écrire l'entrée :

```bash
cd /Volumes/Cache/Kalligraphie
uv run --with fonttools==4.65.0 python -c "
from fontTools.ttLib import TTFont
f = TTFont('test-fixtures/fonts/skia-cbdt/cbdt.ttf')
print('cmap:', sorted(f.getBestCmap().items())[:20])
print('strikes:', [(s.ppem, s.bitDepth) for s in f['CBLC'].strikes])
"
```

Adapter le chemin par police. Le codepoint retenu est celui qui résout la table visée ; il
détermine l'id de l'entrée (`…u<HEX>…`).

- [ ] **Step 1: Inspect the four fixtures**

Run les inspections fontTools sur : `skia-cbdt/cbdt.ttf`, `skia-sbix/sbix.ttf`,
`kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf`, `kalligraphie-var-vvar/KalligraphieVarVVAR.ttf`,
`cff-liberation/LiberationSans-CFF.otf`, `cff2-liberation/LiberationSans-CFF2.otf`.
Expected: un codepoint par fixture, plus la liste des strikes pour les bitmap.

- [ ] **Step 2: Declare the six entries**

Deux entrées par axe concerné, au gabarit de la tâche 2 mais avec `AutoSized` :

```kotlin
// BitmapCatalog.kt — ajouts
CatalogEntry(
    id = "bitmap.cbdt-png",                       // suffixé du codepoint relevé, ex. bitmap.cbdt-png.u0041.16
    axis = CatalogAxis.BITMAP,
    technology = "CBLC/CBDT PNG colour strike",
    font = CorpusKeys.SKIA_CBDT,
    status = CatalogStatus.Supported("<hash de la scène ajoutée>"),
    tags = setOf("bitmap:cbdt", "auto-sized"),
    family = GoldenSceneFamily.GLYPH_BITMAP,
    frame = SceneFramePolicy.AutoSized(padding = 1),
),
CatalogEntry(
    id = "bitmap.sbix-png",
    axis = CatalogAxis.BITMAP,
    technology = "sbix PNG strike",
    font = CorpusKeys.SKIA_SBIX,
    status = CatalogStatus.Supported("<hash>"),
    tags = setOf("bitmap:sbix", "auto-sized"),
    tables = setOf("sbix"),
    family = GoldenSceneFamily.GLYPH_BITMAP,
    frame = SceneFramePolicy.AutoSized(padding = 1),
),
```

L'entrée `bitmap.cbdt-png` ci-dessus revendique `tables = setOf("CBLC", "CBDT")`. Les quatre
autres suivent :

- `OutlineCatalog` : `outline.cff1-static` (`CFF_LIBERATION`, tables `CFF , cmap`) et
  `outline.cff2-static` (`CFF2_LIBERATION`, tables `CFF2, cmap`), famille `GLYPH_OUTLINE`.
- `ColorCatalog` : `color.colr-v1-variable` (`KALLIGRAPHIE_VAR_COLR`, famille `GLYPH_PAINT`, tables
  celles réellement portées — les relever à l'étape 1, l'entrée doit revendiquer `COLR` et `CPAL`
  au minimum).
- `MetricsCatalog` : `metrics.vvar-advance-height` (`KALLIGRAPHIE_VAR_VVAR`, famille
  `GLYPH_OUTLINE`, tables `VVAR, fvar, gvar`).

Toutes en `AutoSized(padding = 1)`.

Le `sinceCommit` de ces six entrées est relevé **après** le commit qui les introduit ; la valeur
`"<hash>"` est un marqueur à remplacer avant le commit (le test d'audit échoue sur une chaîne
vide, pas sur une chaîne fausse — relire `git log -1 --format=%h` juste avant de committer).

- [ ] **Step 3: Write the six renderers**

Ajouter d'abord les six chemins de ressources aux constantes privées de `SceneRenderers` :

```kotlin
    private const val SKIA_CBDT = "/fonts/skia-cbdt/cbdt.ttf"
    private const val SKIA_SBIX = "/fonts/skia-sbix/sbix.ttf"
    private const val KALLIGRAPHIE_VAR_COLR = "/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf"
    private const val KALLIGRAPHIE_VAR_VVAR = "/fonts/kalligraphie-var-vvar/KalligraphieVarVVAR.ttf"
    private const val CFF_LIBERATION = "/fonts/cff-liberation/LiberationSans-CFF.otf"
    private const val CFF2_LIBERATION = "/fonts/cff2-liberation/LiberationSans-CFF2.otf"
```

Puis les renderers, au gabarit des renderers migrés, en réutilisant les helpers existants :

```kotlin
// SceneRenderers.kt — ajouts
"bitmap.cbdt-png.u0041.16" to CatalogSceneRenderer(SKIA_CBDT) {
    openRenderableFixture(fixtureBytes(SKIA_CBDT), bitmapRequirements()).use { fixture ->
        val bitmap = fixture.bitmapOf(0x41)
        when (val result = GlyphRasterizer.rasterizeBitmap(bitmap, BitmapRasterRequest(GlyphColor(0, 0, 0, 255)))) {
            is RasterResult.Success -> GoldenRenderOutcome.Rendered(GoldenImage.rgba8(result.value.width, result.value.height, result.value.copyPixels()))
            is RasterResult.Failure -> GoldenRenderOutcome.Refused(GoldenDiagnosticCode.RENDER_FAILED, result.diagnostics.first().field)
        }
    }
},
"outline.cff2-static" to CatalogSceneRenderer(CFF2_LIBERATION) {
    openOutlineFixture(fixtureBytes(CFF2_LIBERATION)).use { fixture ->
        val outline = fixture.outlineOf(0x41)
        when (val result = GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0))) {
            is RasterResult.Success -> GoldenRenderOutcome.Rendered(GoldenImage.alpha8(result.value.width, result.value.height, result.value.copyPixels()))
            is RasterResult.Failure -> GoldenRenderOutcome.Refused(GoldenDiagnosticCode.RENDER_FAILED, result.diagnostics.first().field)
        }
    }
},
```

Les quatre autres suivent le même patron avec `fixture.bitmapOf`, `fixture.paintOf` +
`PaintRasterRequest`, ou `fixture.outlineOf` selon la représentation. Pour
`metrics.vvar-advance-height`, la scène rend le glyphe par la route outline comme les autres ; ce
qui est vérifié, c'est que la police (qui porte `VVAR`) se charge et se rend — le comportement
métrique lui-même reste couvert par les tests du module `font:scaler`.

- [ ] **Step 4: Regenerate the manifest and inspect the diff**

Run:
```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
git diff --stat kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv
git diff kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv
```
Expected: le diff **ajoute** six lignes et n'en modifie **aucune**. Toute modification d'une ligne
existante signifie que la migration a bougé une empreinte : revenir en arrière, ne pas committer.

- [ ] **Step 5: Verify the new scenes' auto-sized frames**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenVerificationTest*'`
Expected: PASS. Les cadres des six nouvelles scènes sont ceux mesurés par le matérialiseur, et le
test de fraîcheur (tâche 8) les confrontera ensuite à la matrice.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/e2e/src/commonMain kalligraphie/e2e/src/jvmTest
git commit -m "feat(e2e): add auto-sized scenes for CFF, CBDT, sbix and variable colour"
```

## Task 6: Exemptions de cadre épinglé et cliquet partie 1

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/resources/catalog/auto-sizing-exemptions.tsv`
- Test: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ExpectationCatalogRatchetTest.kt`
- Modify: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpWriter.kt` (si le registre des renderers y est référencé — vérifier avec `grep -n 'JvmGoldenSceneCatalog'`)

**Interfaces:**
- Consumes: `ExpectationCatalog`, `SceneRenderers.byId`, `CatalogSceneMaterializer`.
- Produces: `ExpectationCatalogRatchetTest` (étendu en tâche 7 et 8).

- [ ] **Step 1: Write the exemptions file**

Format identique au manifeste : en-tête, puis `id<TAB>WxH<TAB>raison`. Les seize lignes sont les
scènes migrées, cadres recopiés du manifeste committé :

```
# kalligraphie.e2e-exemptions/v1
glyph.outline.liberation-sans.A.64	43x45	migrated verbatim from JvmGoldenSceneCatalog
glyph.paint.emoji-two-colr-v0.u1F600.64	71x72	migrated verbatim from JvmGoldenSceneCatalog
glyph.bitmap.skia-ebdt-format1.u1F600.16	13x13	migrated verbatim from JvmGoldenSceneCatalog
line.latin.48	250x49	migrated verbatim from JvmGoldenSceneCatalog
line.greek.48	267x51	migrated verbatim from JvmGoldenSceneCatalog
line.cyrillic.48	299x49	migrated verbatim from JvmGoldenSceneCatalog
line.arabic.48	182x64	migrated verbatim from JvmGoldenSceneCatalog
line.devanagari.48	156x52	migrated verbatim from JvmGoldenSceneCatalog
line.mixed.48	1235x62	migrated verbatim from JvmGoldenSceneCatalog
sheet.outline.liberation-latin.32	576x140	migrated verbatim from JvmGoldenSceneCatalog
sheet.outline.liberation-greek.32	464x140	migrated verbatim from JvmGoldenSceneCatalog
sheet.outline.liberation-cyrillic.32	560x160	migrated verbatim from JvmGoldenSceneCatalog
sheet.outline.amiri-arabic.32	736x162	migrated verbatim from JvmGoldenSceneCatalog
sheet.outline.noto-devanagari.32	624x156	migrated verbatim from JvmGoldenSceneCatalog
sheet.paint.bungee-color-latin.48	656x90	migrated verbatim from JvmGoldenSceneCatalog
sheet.paint.emoji-two-colr-v0.64	1200x76	migrated verbatim from JvmGoldenSceneCatalog
```

L'en-tête `kalligraphie.e2e-exemptions/v1` n'est pas décoratif : le test le vérifie, pour qu'une
exemption ne puisse pas être ajoutée par une simple ligne sans que le format reste maîtrisé.

- [ ] **Step 2: Write the failing ratchet test**

```kotlin
// ExpectationCatalogRatchetTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class ExpectationCatalogRatchetTest {
    @Test
    fun everySupportedEntryHasARendererAndEveryRendererHasASupportedEntry() {
        val supported = ExpectationCatalog.entries
            .filter { entry -> entry.status is CatalogStatus.Supported }
            .map { entry -> entry.id }
            .toSet()
        assertEquals(supported, SceneRenderers.byId.keys, "supported entries and renderers must be the same set")
    }

    @Test
    fun everyRendererFontPathBelongsToItsEntryCorpusKey() {
        val mismatches = ExpectationCatalog.entries.mapNotNull { entry ->
            val renderer = SceneRenderers.byId[entry.id] ?: return@mapNotNull null
            CatalogSceneMaterializer.fontPathMismatch(entry, renderer)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun everySupportedEntryMaterializesOrRefusesTyped() {
        for (entry in ExpectationCatalog.entries.filter { entry -> entry.status is CatalogStatus.Supported }) {
            val materialized = CatalogSceneMaterializer.materialize(entry, SceneRenderers.byId.getValue(entry.id))
            val outcome = materialized.render()
            if (outcome is GoldenRenderOutcome.Refused) {
                assertTrue(
                    outcome.code.code.startsWith("e2e."),
                    "${entry.id} refused with a non-e2e code: ${outcome.code.code}",
                )
            }
        }
    }

    @Test
    fun everyPinnedFrameIsExemptedWithItsExactDimensions() {
        val exemptions = readExemptions()
        val pinned = ExpectationCatalog.entries.mapNotNull { entry ->
            val frame = entry.frame as? SceneFramePolicy.Pinned ?: return@mapNotNull null
            entry.id to "${frame.width}x${frame.height}"
        }.toMap()
        assertEquals(
            pinned,
            exemptions.mapValues { (_, record) -> record.frame },
            "pinned frames must be exempted with their exact dimensions, and exemptions must not be stale",
        )
    }

    @Test
    fun noNewEntryMayPinItsFrame() {
        val exemptions = readExemptions()
        assertTrue(
            exemptions.values.all { record -> record.reason.startsWith("migrated verbatim") },
            "a new pinned frame is an escape hatch: every exemption must be a migration record",
        )
    }

    private class Exemption(val frame: String, val reason: String)

    private fun readExemptions(): Map<String, Exemption> {
        val text = checkNotNull(object {}.javaClass.getResourceAsStream("/catalog/auto-sizing-exemptions.tsv")) {
            "the auto-sizing exemptions resource is missing"
        }.use { input -> input.readBytes().decodeToString() }
        val lines = text.split('\n').map { line -> line.removeSuffix("\r") }.filter { line -> line.isNotBlank() }
        assertEquals("# kalligraphie.e2e-exemptions/v1", lines.first(), "unrecognised exemptions header")
        return lines.drop(1).associate { line ->
            val fields = line.split('\t')
            assertEquals(3, fields.size, "expected id, frame and reason: $line")
            fields[0] to Exemption(frame = fields[1], reason = fields[2])
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails then passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ExpectationCatalogRatchetTest*'`
Expected: FAIL d'abord sur le fichier d'exemptions, puis PASS après l'étape 1 si les ids des
fichiers d'axes et du registre coïncident. Un échec sur
`everySupportedEntryHasARendererAndEveryRendererHasASupportedEntry` signale une faute de frappe
d'id entre un fichier d'axe et `SceneRenderers.byId` : corriger l'id, pas le test.

- [ ] **Step 4: Check the other consumers still resolve**

Run: `grep -rn 'JvmGoldenSceneCatalog\|JvmGoldenEntry' kalligraphie/e2e/src/jvmTest --include='*.kt'`
Expected: seuls `JvmGoldenSceneCatalog.kt` (shim), `JvmGoldenSceneCatalogTest.kt`,
`GoldenVerificationTest.kt`, `GoldenUpdateRunnerTest.kt`, `GoldenDumpWriter.kt` et
`GoldenDumpRunnerTest.kt` apparaissent. Corriger les imports de `JvmGoldenEntry` vers son nouveau
paquet (`org.graphiks.kalligraphie.e2e.catalog`) partout où nécessaire.

- [ ] **Step 5: Run the whole module and commit**

Run: `./gradlew :kalligraphie:e2e:jvmTest`
Expected: PASS.

```bash
git add kalligraphie/e2e/src/jvmTest
git commit -m "test(e2e): ratchet the catalog against scenes and exemptions"
```

## Task 7: Sondes de comportement épinglé et de rejet attendu

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogProbe.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogProbes.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/RobustnessCatalog.kt`
- Modify: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/ExpectationCatalogRatchetTest.kt`

**Interfaces:**
- Consumes: les helpers de fixture, `Kalligraphie.embedded`, `FontOperationResult`.
- Produces: `CatalogProbe(observe: () -> ProbeObservation)`,
  `ProbeObservation` (`Rejected(stage, diagnostic)`, `Succeeded(stage, observation)`),
  `CatalogProbes.byId: Map<String, CatalogProbe>`.

**Note d'exécution** : les codes de diagnostic des deux sondes se relèvent, ils ne s'inventent pas.
Après avoir écrit la sonde, faire échouer le test volontairement une fois en comparant à une chaîne
bidon, lire le code observé dans le message d'échec, et inscrire ce code dans l'entrée. C'est la
procédure de bénédiction habituelle des artefacts golden du dépôt.

- [ ] **Step 1: Write the probe types**

```kotlin
// CatalogProbe.kt
package org.graphiks.kalligraphie.e2e.catalog

/** What a probe observed on the current implementation. */
internal sealed interface ProbeObservation {
    /** The stage failed with exactly [diagnostic]. */
    data class Rejected(val stage: CatalogStage, val diagnostic: String) : ProbeObservation

    /** The stage succeeded; [observation] names the degraded behaviour. */
    data class Succeeded(val stage: CatalogStage, val observation: String) : ProbeObservation
}

/** One entry's behaviour probe, keyed by catalog entry id. */
internal class CatalogProbe(val observe: () -> ProbeObservation)
```

- [ ] **Step 2: Write the failing ratchet extension**

Ajouter à `ExpectationCatalogRatchetTest` :

```kotlin
    @Test
    fun everyProbeableEntryHasAProbeAndEveryProbeHasAProbeableEntry() {
        val probeable = ExpectationCatalog.entries
            .filter { entry ->
                entry.status is CatalogStatus.ExpectedRejection ||
                    (entry.status is CatalogStatus.NotYet && entry.status.currentBehavior != null)
            }
            .map { entry -> entry.id }
            .toSet()
        assertEquals(probeable, CatalogProbes.byId.keys, "probeable entries and probes must be the same set")
    }

    @Test
    fun everyProbeAgreesWithItsDeclaredStatus() {
        for (entry in ExpectationCatalog.entries) {
            val probe = CatalogProbes.byId[entry.id] ?: continue
            val observed = probe.observe()
            when (val status = entry.status) {
                is CatalogStatus.ExpectedRejection -> {
                    val rejected = assertIs<ProbeObservation.Rejected>(observed, "${entry.id} must be rejected")
                    assertEquals(status.stage, rejected.stage, "${entry.id} stage changed")
                    assertEquals(status.code, rejected.diagnostic, "${entry.id} diagnostic changed")
                }

                is CatalogStatus.NotYet -> when (val pinned = status.currentBehavior) {
                    is PinnedBehavior.RejectedAt -> {
                        val rejected = assertIs<ProbeObservation.Rejected>(observed, "${entry.id} must be rejected")
                        assertEquals(pinned.stage, rejected.stage, "${entry.id} stage changed")
                        assertEquals(pinned.diagnostic, rejected.diagnostic, "${entry.id} diagnostic changed")
                    }

                    is PinnedBehavior.SucceededWith -> {
                        val succeeded = assertIs<ProbeObservation.Succeeded>(observed, "${entry.id} must succeed")
                        assertEquals(pinned.stage, succeeded.stage, "${entry.id} stage changed")
                        assertEquals(pinned.observation, succeeded.observation, "${entry.id} observation changed")
                    }

                    null -> error("${entry.id} is probeable but declares no pinned behaviour")
                }

                else -> error("${entry.id} has a probe but is not probeable")
            }
        }
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ExpectationCatalogRatchetTest*'`
Expected: FAIL — `Unresolved reference: CatalogProbes`.

- [ ] **Step 4: Write the two robustness probes and their entries**

`RobustnessCatalog` : les deux entrées `NotYet`/`CORPUS_NOT_ACQUIRED` de la tâche 2 deviennent des
`ExpectedRejection` (un refus sur entrée hostile est un comportement définitif, pas un report) :

```kotlin
// RobustnessCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Hostile and malformed input: behaviours that must never soften. */
public object RobustnessCatalog {
    /** Declared robustness expectations. */
    public val entries: List<CatalogEntry> = listOf(
        expectedRejection(
            id = "robustness.truncated-sfnt",
            technology = "Truncated TrueType container",
            font = CorpusKeys.LIBERATION,
            code = "<code observé>",
            stage = CatalogStage.DECODE,
        ),
        expectedRejection(
            id = "robustness.empty-input",
            technology = "Zero-byte font source",
            font = CorpusKeys.LIBERATION,
            code = "<code observé>",
            stage = CatalogStage.DECODE,
        ),
    )

    private fun expectedRejection(id: String, technology: String, font: CorpusKey, code: String, stage: CatalogStage) =
        CatalogEntry(
            id = id,
            axis = CatalogAxis.ROBUSTNESS,
            technology = technology,
            font = font,
            status = CatalogStatus.ExpectedRejection(stage = stage, code = code),
            tags = setOf("hostile-input"),
        )
}
```

```kotlin
// CatalogProbes.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.e2e.golden.fixtureBytes

/** The registered probes, keyed by catalog entry id. */
internal object CatalogProbes {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"

    /** Every registered probe. */
    val byId: Map<String, CatalogProbe> = mapOf(
        "robustness.truncated-sfnt" to CatalogProbe {
            val full = fixtureBytes(LIBERATION_SANS)
            decodeOutcome(full.copyOf(full.size / 3))
        },
        "robustness.empty-input" to CatalogProbe { decodeOutcome(ByteArray(0)) },
    )

    /** Translates the facade's decode result into a probe observation. */
    private fun decodeOutcome(bytes: ByteArray): ProbeObservation =
        when (val result = Kalligraphie.embedded(sourceBytes = bytes, provenance = FontSourceProvenance(declaredName = "e2e robustness probe"))) {
            is FontOperationResult.Success<*> -> ProbeObservation.Succeeded(
                stage = CatalogStage.DECODE,
                observation = "decoding succeeds; the facade accepts ${bytes.size} bytes",
            )

            is FontOperationResult.Failure -> ProbeObservation.Rejected(
                stage = CatalogStage.DECODE,
                diagnostic = result.error.code,
            )

            is FontOperationResult.Cancelled -> ProbeObservation.Succeeded(
                stage = CatalogStage.DECODE,
                observation = "cancelled; no decode verdict",
            )
        }
}
```

`FontOperationResult` a trois variantes (`Success`, `Failure`, `Cancelled`) : le `when` doit être
exhaustif, et c'est `result.error.code` qui porte le diagnostic typé (vérifié dans
`kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/FontDiagnostics.kt:394-411`).
Mapper `Cancelled` sur une observation qui ne correspond à aucun comportement épinglé est
volontaire : une annulation pendant une sonde synchrone doit faire échouer le cliquet, pas passer
inaperçue.

- [ ] **Step 5: Bless the observed codes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*ExpectationCatalogRatchetTest*'`
Expected: FAIL avec `expected:<...> but was:<...>` mentionnant le code réellement observé. Inscrire
ces deux codes dans `RobustnessCatalog`, relancer.
Expected ensuite: PASS.

- [ ] **Step 6: Run the whole module and commit**

Run: `./gradlew :kalligraphie:e2e:jvmTest`
Expected: PASS.

```bash
git add kalligraphie/e2e/src/commonMain kalligraphie/e2e/src/jvmTest
git commit -m "feat(e2e): probe pinned behaviours and expected rejections"
```

## Task 8: Matrice de documentation générée

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogMatrixRenderer.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogMatrixRunnerTest.kt`
- Create: `docs/docs/generated/e2e-catalog-matrix.md`
- Create: `docs/docs/generated/e2e-catalog-matrix.fr.md`
- Modify: `kalligraphie/e2e/build.gradle.kts` (tâche `updateE2eGolden` étendue)
- Modify: `docs/mkdocs.yml` (nav)
- Modify: `CHANGELOG.md`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogMatrixRendererTest.kt`

**Interfaces:**
- Consumes: `ExpectationCatalog.entries`, `CatalogAxis`, `CatalogStatus`.
- Produces: `CatalogMatrixLanguage` (enum `EN`, `FR`),
  `CatalogMatrixRenderer.render(entries, language): String`,
  `CatalogMatrixRenderer.fileName(language): String`.

- [ ] **Step 1: Write the failing renderer test**

```kotlin
// CatalogMatrixRendererTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

class CatalogMatrixRendererTest {
    @Test
    fun theMatrixCountsMatchTheCatalog() {
        val rendered = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.EN)
        assertTrue(rendered.contains("| Supported | 1 |"), rendered)
    }

    @Test
    fun theTwoLanguagesDifferOnlyInTheirProse() {
        val en = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.EN)
        val fr = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.FR)
        assertTrue(en.contains("Supported"), en)
        assertTrue(fr.contains("Supporté"), fr)
        assertEquals(en.lines().count { line -> line.startsWith("| ") }, fr.lines().count { line -> line.startsWith("| ") })
    }

    @Test
    fun theMatrixNamesTheFontAndTheStatusOfEachEntry() {
        val rendered = CatalogMatrixRenderer.render(listOf(entry), CatalogMatrixLanguage.EN)
        assertTrue(rendered.contains("outline.glyf"), rendered)
        assertTrue(rendered.contains("liberation"), rendered)
        assertTrue(rendered.contains("abc1234"), rendered)
    }

    @Test
    fun theCommittedFileNamesAreStable() {
        assertEquals("e2e-catalog-matrix.md", CatalogMatrixRenderer.fileName(CatalogMatrixLanguage.EN))
        assertEquals("e2e-catalog-matrix.fr.md", CatalogMatrixRenderer.fileName(CatalogMatrixLanguage.FR))
    }

    private val entry = CatalogEntry(
        id = "outline.glyf",
        axis = CatalogAxis.OUTLINE,
        technology = "glyf outlines",
        font = CorpusKey("liberation"),
        status = CatalogStatus.Supported("abc1234"),
        family = GoldenSceneFamily.GLYPH_OUTLINE,
        frame = SceneFramePolicy.AutoSized(),
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogMatrixRendererTest*'`
Expected: FAIL — `Unresolved reference: CatalogMatrixRenderer`.

- [ ] **Step 3: Write the renderer**

```kotlin
// CatalogMatrixRenderer.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Language of the generated catalog matrix. */
public enum class CatalogMatrixLanguage {
    /** English. */
    EN,
    /** French. */
    FR,
}

/**
 * Renders the catalog as Markdown: a header, a per-axis count table, then one table per axis.
 *
 * The matrix is the only place where the catalog's numbers appear. Narrative pages point at it
 * instead of restating counts, so a status flip cannot leave the documentation lying.
 */
public object CatalogMatrixRenderer {
    /** Returns the Markdown document for [entries] in [language]. */
    public fun render(entries: List<CatalogEntry>, language: CatalogMatrixLanguage): String = buildString {
        appendLine(if (language == CatalogMatrixLanguage.EN) "# End-to-end expectation catalog" else "# Catalogue d'attentes end-to-end")
        appendLine()
        appendLine(
            if (language == CatalogMatrixLanguage.EN) {
                "Generated from `ExpectationCatalog` by `./gradlew :kalligraphie:e2e:updateE2eGolden`; do not edit by hand."
            } else {
                "Généré depuis `ExpectationCatalog` par `./gradlew :kalligraphie:e2e:updateE2eGolden` ; ne pas modifier à la main."
            },
        )
        appendLine()
        appendLine(if (language == CatalogMatrixLanguage.EN) "## Totals" else "## Totaux")
        appendLine()
        appendLine(
            if (language == CatalogMatrixLanguage.EN) "| Status | Entries |" else "| Statut | Entrées |",
        )
        appendLine("| --- | --- |")
        for (status in STATUS_LABELS.keys) {
            val count = entries.count { entry -> entry.status::class == status }
            appendLine("| ${STATUS_LABELS.getValue(status).getValue(language)} | $count |")
        }
        appendLine()
        for (axis in CatalogAxis.entries) {
            val axisEntries = entries.filter { entry -> entry.axis == axis }
            if (axisEntries.isEmpty()) continue
            appendLine("## ${axis}")
            appendLine()
            appendLine(
                if (language == CatalogMatrixLanguage.EN) {
                    "| Entry | Technology | Font | Status |"
                } else {
                    "| Entrée | Technologie | Police | Statut |"
                },
            )
            appendLine("| --- | --- | --- | --- |")
            for (entry in axisEntries) {
                appendLine("| `${entry.id}` | ${entry.technology} | ${entry.font?.value ?: "—"} | ${describe(entry.status, language)} |")
            }
            appendLine()
        }
    }

    /** Returns the committed file name of the matrix in [language]. */
    public fun fileName(language: CatalogMatrixLanguage): String = when (language) {
        CatalogMatrixLanguage.EN -> "e2e-catalog-matrix.md"
        CatalogMatrixLanguage.FR -> "e2e-catalog-matrix.fr.md"
    }

    private fun describe(status: CatalogStatus, language: CatalogMatrixLanguage): String = when (status) {
        is CatalogStatus.Supported -> if (language == CatalogMatrixLanguage.EN) {
            "Supported since ${status.sinceCommit}"
        } else {
            "Supporté depuis ${status.sinceCommit}"
        }

        is CatalogStatus.ExpectedRejection -> if (language == CatalogMatrixLanguage.EN) {
            "Expected rejection `${status.code}` at ${status.stage}"
        } else {
            "Rejet attendu `${status.code}` à ${status.stage}"
        }

        is CatalogStatus.NotYet -> when {
            status.currentBehavior != null -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; today pinned (`${status.trackingIssue}`)"
            } else {
                "Pas encore ; comportement actuel épinglé (`${status.trackingIssue}`)"
            }

            status.unpinnedReason == UnpinnedReason.NO_REAL_FONT_KNOWN -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; no real font known"
            } else {
                "Pas encore ; aucune police réelle connue"
            }

            else -> if (language == CatalogMatrixLanguage.EN) "Not yet; corpus not acquired" else "Pas encore ; corpus non acquis"
        }

        is CatalogStatus.OutOfScope -> if (language == CatalogMatrixLanguage.EN) {
            "Out of scope: ${status.rationale}"
        } else {
            "Hors périmètre : ${status.rationale}"
        }
    }

    private val STATUS_LABELS: Map<kotlin.reflect.KClass<out CatalogStatus>, Map<CatalogMatrixLanguage, String>> = mapOf(
        CatalogStatus.Supported::class to mapOf(
            CatalogMatrixLanguage.EN to "Supported",
            CatalogMatrixLanguage.FR to "Supporté",
        ),
        CatalogStatus.ExpectedRejection::class to mapOf(
            CatalogMatrixLanguage.EN to "Expected rejection",
            CatalogMatrixLanguage.FR to "Rejet attendu",
        ),
        CatalogStatus.NotYet::class to mapOf(
            CatalogMatrixLanguage.EN to "Not yet",
            CatalogMatrixLanguage.FR to "Pas encore",
        ),
        CatalogStatus.OutOfScope::class to mapOf(
            CatalogMatrixLanguage.EN to "Out of scope",
            CatalogMatrixLanguage.FR to "Hors périmètre",
        ),
    )
}
```

`kotlin.reflect.KClass` dans `commonMain` est disponible sur Kotlin/Native et JVM sans dépendance
supplémentaire ; si la compilation iOS s'en plaint, remplacer la map par un `when` sur une
fonction `statusName(status): String` et grouper via cette fonction (le test ne dépend pas de la
technique).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogMatrixRendererTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the writer and the freshness test**

```kotlin
// CatalogMatrixRunnerTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.e2e.golden.repositoryRoot

class CatalogMatrixRunnerTest {
    @Test
    fun writesTheMatrixOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_MATRIX") != "true") {
            return
        }
        for (language in CatalogMatrixLanguage.entries) {
            val target = matrixPath(language)
            Files.createDirectories(target.parent)
            Files.writeString(target, CatalogMatrixRenderer.render(ExpectationCatalog.entries, language))
        }
    }

    @Test
    fun theCommittedMatrixMatchesTheCatalog() {
        for (language in CatalogMatrixLanguage.entries) {
            val path = matrixPath(language)
            check(Files.exists(path)) {
                "$path is missing; run ./gradlew :kalligraphie:e2e:updateE2eGolden"
            }
            assertEquals(
                CatalogMatrixRenderer.render(ExpectationCatalog.entries, language),
                Files.readString(path),
                "$path is stale; run ./gradlew :kalligraphie:e2e:updateE2eGolden",
            )
        }
    }

    private fun matrixPath(language: CatalogMatrixLanguage): Path =
        repositoryRoot().resolve("docs/docs/generated/${CatalogMatrixRenderer.fileName(language)}")
}
```

- [ ] **Step 6: Wire the writer into the update task and the exclusions**

Dans `kalligraphie/e2e/build.gradle.kts` :

```kotlin
val matrixClass = "org.graphiks.kalligraphie.e2e.catalog.CatalogMatrixRunnerTest"
```

Ajouter `filter.excludeTestsMatching(matrixClass)` aux exclusions de `jvmTest` (le writer ne doit
pas s'exécuter pendant `check` ; le test de fraîcheur, lui, reste exécuté), puis dans la tâche
`updateE2eGolden` :

```kotlin
    filter.includeTestsMatching("$matrixClass.writesTheMatrixOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_E2E_MATRIX", "true")
```

- [ ] **Step 7: Generate, wire the nav, and check freshness**

Run:
```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
git status --short docs/docs/generated
```
Expected: les deux fichiers de matrice sont créés. Ajouter ensuite au `nav` de `docs/mkdocs.yml`,
après `End-to-End Golden: e2e-golden.md` :

```yaml
  - Font Expectation Catalog: generated/e2e-catalog-matrix.md
```

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogMatrixRunnerTest*'`
Expected: PASS.

- [ ] **Step 8: Update the CHANGELOG and commit**

Ajouter au `CHANGELOG.md`, en tête de la section non publiée, une entrée décrivant le catalogue
d'attentes, les statuts et la matrice générée.

```bash
git add kalligraphie/e2e docs/docs/generated docs/mkdocs.yml CHANGELOG.md
git commit -m "feat(e2e): generate the font expectation catalog matrix"
```

---

# Milestone B — Le corpus vérifiable

Livrable : les 19 familles de polices sont décrites dans un manifeste d'acquisition, un script les
vérifie hors ligne (hash, taille, licence, provenance) et un lint croise les tables réellement
présentes avec les revendications du catalogue.

## Task 9: Manifeste du corpus

**Files:**
- Create: `scripts/fonts/corpus.json`
- Create: `scripts/fonts/README.md`

**Interfaces:**
- Consumes: les 19 répertoires de `test-fixtures/fonts/` et leurs `PROVENANCE.md` ; `CorpusKeys`
  (tâche 2) pour les clés.
- Produces: le schéma `kalligraphie.font-corpus/v1` consommé par les tâches 10 à 12.

- [ ] **Step 1: Write the manifest for the first three families**

Structure du fichier (tableau `families`, une entrée par répertoire de `test-fixtures/fonts/`) :

```json
{
  "schema": "kalligraphie.font-corpus/v1",
  "families": [
    {
      "key": "skia-cbdt",
      "files": [
        {
          "path": "test-fixtures/fonts/skia-cbdt/cbdt.ttf",
          "url": "https://github.com/google/skia/blob/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/cbdt.ttf",
          "rawUrl": "https://raw.githubusercontent.com/google/skia/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/cbdt.ttf",
          "revision": "5f094e6de86302f604e6c3226b4ffc0afff1aa65",
          "sha256": "eb66fce167177e4df2355ed2c7a519e6a17a1efd062556a1ef4cea342318c680",
          "sizeBytes": 16760
        },
        {
          "path": "test-fixtures/fonts/skia-cbdt/planetcbdt.ttf",
          "url": "https://github.com/google/skia/blob/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/planetcbdt.ttf",
          "rawUrl": "https://raw.githubusercontent.com/google/skia/5f094e6de86302f604e6c3226b4ffc0afff1aa65/resources/fonts/planetcbdt.ttf",
          "revision": "5f094e6de86302f604e6c3226b4ffc0afff1aa65",
          "sha256": "53a88a71a10c2a32abc91284f95f71711b1a3010a5f6a6f9c67ceb499d866533",
          "sizeBytes": 115512
        }
      ],
      "license": "BSD-3-Clause",
      "licenseFile": "test-fixtures/fonts/skia-cbdt/LICENSE.md",
      "synthetic": false,
      "builtBy": null
    }
  ]
}
```

Ces trois premières entrées sont un exercice complet : `skia-cbdt` (deux fichiers, licence
BSD-3-Clause, URL GitHub épinglée par révision) prend la forme ci-dessus. Ajouter ensuite
`liberation` (fichier `.ttf` + `PROVENANCE.md` + licence) comme deuxième famille réelle, puis
`kalligraphie-var-colr` comme première famille `synthetic: true` (`builtBy:
"build_variable_colr_v1.py"`, pas d'`url`).

- [ ] **Step 2: Transcribe the sixteen remaining families**

Pour chaque répertoire restant de `test-fixtures/fonts/` : lire son `PROVENANCE.md` et recopier
URL, révision, SHA-256, taille, licence et fichier de licence. Les fichiers `.b64`/`.base64`
(`skia-colr-v1`, `twemoji-svginot-glyph5`) sont décrits par le chemin du fichier **encodé** tel
qu'il est committé — c'est ce fichier dont le hash est vérifié.

Run (contrôle de couverture) :
```bash
python3 -c "
import json, pathlib
manifest = json.load(open('scripts/fonts/corpus.json'))
keys = {f['key'] for f in manifest['families']}
dirs = {p.name for p in pathlib.Path('test-fixtures/fonts').iterdir() if p.is_dir()}
print('missing from manifest:', sorted(dirs - keys))
print('unknown in manifest:', sorted(keys - dirs))
"
```
Expected: les deux listes sont vides.

- [ ] **Step 3: Document the schema**

`scripts/fonts/README.md` : rôle du manifeste, sens de chaque champ (`url` absent pour
`synthetic`, `builtBy` obligatoire pour `synthetic`, `revision` pour une URL épinglée),
commandes des tâches 10 à 12, et la règle « une police complète, jamais sous-échantillonnée ».

- [ ] **Step 4: Commit**

```bash
git add scripts/fonts
git commit -m "chore(e2e): describe the font corpus in a manifest"
```

## Task 10: Script d'acquisition et de vérification

**Files:**
- Create: `scripts/fonts/fetch_fonts.py`
- Test: `scripts/fonts/tests/test_fetch_fonts.py`
- Test: `scripts/fonts/tests/fixtures/tiny-font.ttf` (octets de test, généré à l'étape 1)

**Interfaces:**
- Consumes: `scripts/fonts/corpus.json`.
- Produces: `fetch_fonts.py` avec les modes `--check`, `--fetch [--key K]`, `--provenance`, et les
  fonctions importables `load_manifest(path)`, `check_files(manifest, root)`,
  `ALLOWED_LICENSES`. La tâche 12 importe `load_manifest` et `ALLOWED_LICENSES`.

- [ ] **Step 1: Write the failing tests**

```python
# scripts/fonts/tests/test_fetch_fonts.py
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import fetch_fonts


def manifest_with(path, sha256, size, license_id="OFL-1.1", license_file=None):
    return {
        "schema": "kalligraphie.font-corpus/v1",
        "families": [
            {
                "key": "tiny",
                "files": [
                    {
                        "path": path,
                        "url": "https://example.invalid/tiny.ttf",
                        "rawUrl": "https://example.invalid/raw/tiny.ttf",
                        "revision": "0" * 40,
                        "sha256": sha256,
                        "sizeBytes": size,
                    }
                ],
                "license": license_id,
                "licenseFile": license_file,
                "synthetic": False,
                "builtBy": None,
            }
        ],
    }


class CheckFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.font = self.root / "test-fixtures/fonts/tiny/tiny.ttf"
        self.font.parent.mkdir(parents=True)
        self.bytes = b"\x00\x01\x00\x00tiny"
        self.font.write_bytes(self.bytes)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_matching_hash_and_size_pass(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes))
        self.assertEqual([], fetch_fonts.check_files(manifest, self.root))

    def test_a_wrong_hash_is_reported(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", "0" * 64, len(self.bytes))
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("sha256", errors[0])

    def test_a_wrong_size_is_reported(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes) + 1)
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("size", errors[0])

    def test_a_missing_file_is_reported(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/absent.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes))
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("missing", errors[0])

    def test_a_missing_license_file_is_reported(self):
        manifest = manifest_with(
            "test-fixtures/fonts/tiny/tiny.ttf",
            hashlib.sha256(self.bytes).hexdigest(),
            len(self.bytes),
            license_file="test-fixtures/fonts/tiny/LICENSE.txt",
        )
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("license", errors[0])

    def test_an_unknown_license_blocks(self):
        manifest = manifest_with(
            "test-fixtures/fonts/tiny/tiny.ttf",
            hashlib.sha256(self.bytes).hexdigest(),
            len(self.bytes),
            license_id="Proprietary-EULA",
        )
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("Proprietary-EULA", errors[0])

    def test_a_synthetic_family_without_a_builder_blocks(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes))
        manifest["families"][0]["synthetic"] = True
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("builtBy", errors[0])

    def test_a_real_family_without_a_url_blocks(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes))
        manifest["families"][0]["files"][0]["url"] = None
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("url", errors[0])

    def test_a_real_family_without_a_raw_url_or_a_note_blocks(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes))
        manifest["families"][0]["files"][0]["rawUrl"] = None
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual(1, len(errors))
        self.assertIn("fetchNote", errors[0])

    def test_a_real_family_with_a_fetch_note_instead_of_a_raw_url_is_accepted(self):
        manifest = manifest_with("test-fixtures/fonts/tiny/tiny.ttf", hashlib.sha256(self.bytes).hexdigest(), len(self.bytes))
        manifest["families"][0]["files"][0]["rawUrl"] = None
        manifest["families"][0]["files"][0]["fetchNote"] = "release archive; unzip manually"
        errors = fetch_fonts.check_files(manifest, self.root)
        self.assertEqual([], errors)


class ManifestShapeTest(unittest.TestCase):
    def test_the_committed_manifest_parses_and_uses_known_licenses(self):
        root = pathlib.Path(__file__).resolve().parents[3]
        manifest = fetch_fonts.load_manifest(root / "scripts/fonts/corpus.json")
        self.assertEqual("kalligraphie.font-corpus/v1", manifest["schema"])
        unknown = sorted({family["license"] for family in manifest["families"]} - fetch_fonts.ALLOWED_LICENSES)
        self.assertEqual([], unknown)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd /Volumes/Cache/Kalligraphie
python3 -m unittest discover -s scripts/fonts/tests -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'fetch_fonts'`.

- [ ] **Step 3: Write the script**

```python
#!/usr/bin/env python3
"""Acquires and verifies the font corpus described by corpus.json.

Modes:
  --check        offline verification of the committed files (hash, size, licences, provenance)
  --fetch        downloads the pinned sources and writes them in place (network, local use only)
  --key KEY      restricts --fetch to one family
  --provenance   checks that every family still has its PROVENANCE.md and licence file
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import urllib.request

# Licences whose terms allow redistributing the font file in this repository.
# A new licence requires a human review first; the check below fails closed.
# CC-BY-4.0 covers the two emoji families (emoji-two-colr-v0, twemoji-svginot-glyph5);
# MIT is the repository licence and covers the fixtures generated in-tree.
ALLOWED_LICENSES = frozenset(
    {
        "Apache-2.0",
        "BSD-3-Clause",
        "CC0-1.0",
        "CC-BY-4.0",
        "DejaVu",
        "MIT",
        "OFL-1.1",
        "Unlicense",
    }
)

SCHEMA = "kalligraphie.font-corpus/v1"
SIGNIFICANT_LICENSE_FIELD = "license"


def load_manifest(path: pathlib.Path) -> dict:
    """Loads and shape-checks the corpus manifest."""
    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    if manifest.get("schema") != SCHEMA:
        raise SystemExit(f"{path}: expected schema {SCHEMA}, found {manifest.get('schema')!r}")
    return manifest


def sha256_of(path: pathlib.Path) -> str:
    """Returns the SHA-256 of a file."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_files(manifest: dict, root: pathlib.Path) -> list[str]:
    """Verifies every declared file and every family's metadata. Returns the error list."""
    errors: list[str] = []
    for family in manifest["families"]:
        key = family["key"]
        license_id = family.get("license")
        if license_id not in ALLOWED_LICENSES:
            errors.append(f"{key}: licence {license_id!r} is not in the allowed set; review it before adding")
        if family.get("synthetic") and not family.get("builtBy"):
            errors.append(f"{key}: a synthetic family must name its builtBy script")
        if license_id in ALLOWED_LICENSES:
            license_file = family.get("licenseFile")
            if not license_file:
                errors.append(f"{key}: no licenseFile declared")
            elif not (root / license_file).exists():
                errors.append(f"{key}: license file {license_file} is missing")
        for record in family["files"]:
            path = root / record["path"]
            if not path.exists():
                errors.append(f"{key}: {record['path']} is missing")
                continue
            size = path.stat().st_size
            if size != record["sizeBytes"]:
                errors.append(f"{key}: {record['path']} size is {size}, manifest expects {record['sizeBytes']}")
            digest = sha256_of(path)
            if digest != record["sha256"]:
                errors.append(f"{key}: {record['path']} sha256 is {digest}, manifest expects {record['sha256']}")
            if not family.get("synthetic") and not record.get("url"):
                errors.append(f"{key}: {record['path']} is not synthetic and declares no url")
            if not family.get("synthetic") and not (record.get("rawUrl") or record.get("fetchNote")):
                errors.append(
                    f"{key}: {record['path']} needs a rawUrl or a fetchNote explaining the manual re-download"
                )
    return errors


def fetch(manifest: dict, root: pathlib.Path, only_key: str | None) -> int:
    """Downloads the declared sources, refusing to overwrite a file whose hash already matches."""
    written = 0
    for family in manifest["families"]:
        if only_key and family["key"] != only_key:
            continue
        if family.get("synthetic"):
            raise SystemExit(f"{family['key']}: synthetic families are rebuilt by {family.get('builtBy')}, not fetched")
        for record in family["files"]:
            path = root / record["path"]
            if path.exists() and sha256_of(path) == record["sha256"]:
                continue
            print(f"fetching {record['rawUrl']}")
            with urllib.request.urlopen(record["rawUrl"]) as response:  # noqa: S310 - pinned, reviewed URLs
                payload = response.read()
            digest = hashlib.sha256(payload).hexdigest()
            if digest != record["sha256"]:
                raise SystemExit(f"{record['path']}: upstream hash {digest} does not match the pinned {record['sha256']}")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            written += 1
    return written


def provenance(manifest: dict, root: pathlib.Path) -> list[str]:
    """Checks that every family directory still carries its PROVENANCE.md and licence file."""
    errors: list[str] = []
    for family in manifest["families"]:
        directory = (root / family["files"][0]["path"]).parent
        if not (directory / "PROVENANCE.md").exists():
            errors.append(f"{family['key']}: PROVENANCE.md is missing in {directory.name}/")
        license_file = family.get("licenseFile")
        if license_file and not (root / license_file).exists():
            errors.append(f"{family['key']}: licence file {license_file} is missing")
    return errors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verify committed files offline")
    parser.add_argument("--fetch", action="store_true", help="download pinned sources")
    parser.add_argument("--key", help="restrict --fetch to one family key")
    parser.add_argument("--provenance", action="store_true", help="verify PROVENANCE.md presence")
    parser.add_argument("--manifest", default="scripts/fonts/corpus.json")
    arguments = parser.parse_args(argv)

    root = pathlib.Path(__file__).resolve().parents[2]
    manifest = load_manifest(root / arguments.manifest)
    errors: list[str] = []
    if arguments.check:
        errors += check_files(manifest, root)
    if arguments.provenance:
        errors += provenance(manifest, root)
    if arguments.fetch:
        print(f"wrote {fetch(manifest, root, arguments.key)} file(s)")
    if not (arguments.check or arguments.provenance or arguments.fetch):
        parser.print_help()
        return 0
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
```

Les URL de `corpus.json` sont de deux formes : `url` pointe vers la page GitHub lisible depuis
`PROVENANCE.md`, `rawUrl` vers le fichier brut que `--fetch` télécharge. Le manifeste doit porter
les deux pour une famille non synthétique — `check_files` refuse une famille réelle sans `rawUrl`
plutôt que de deviner la transformation.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m unittest discover -s scripts/fonts/tests -v`
Expected: PASS (10 tests).

- [ ] **Step 5: Run the check against the real corpus**

Run: `python3 scripts/fonts/fetch_fonts.py --check --provenance`
Expected: aucune erreur. Toute erreur signale une transcription fautive de `corpus.json` (c'est
exactement le mode d'échec n° 5 du Review Focus) : corriger le manifeste depuis le
`PROVENANCE.md`, jamais l'inverse.

- [ ] **Step 6: Commit**

```bash
git add scripts/fonts
git commit -m "chore(e2e): verify the font corpus offline"
```

## Task 11: Export des revendications de tables

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/resources/catalog/claimed-tables.json` (généré)
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaims.kt`
- Test: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaimsRunnerTest.kt`
- Modify: `kalligraphie/e2e/build.gradle.kts` (writer exclu de `jvmTest`, inclus dans `updateE2eGolden`)

**Interfaces:**
- Consumes: `ExpectationCatalog.entries`.
- Produces: `CatalogClaims.render(entries): String` (JSON canonique trié),
  `CatalogClaims.unclaimedAllowlist` (map clé → table → raison), et le fichier committé
  `claimed-tables.json` que la tâche 12 lit.

**Note d'exécution** : le champ `tables: Set<String>` de `CatalogEntry` (tâche 1) est renseigné ici
pour les seize entrées migrées — sans lui, l'audit signale `supported-without-tables`. Les tables
revendiquées sont celles que la scène exerce réellement, pas toutes celles de la police : la
couverture exhaustive des tables d'une police est le travail du lint de la tâche 12, pas celui des
entrées.

- [ ] **Step 1: Write the failing claims test**

```kotlin
// CatalogClaimsRunnerTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.golden.repositoryRoot

class CatalogClaimsRunnerTest {
    @Test
    fun writesTheClaimsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_CLAIMS") != "true") {
            return
        }
        val target = claimsPath()
        Files.createDirectories(target.parent)
        Files.writeString(target, CatalogClaims.render(ExpectationCatalog.entries))
    }

    @Test
    fun theCommittedClaimsMatchTheCatalog() {
        val path = claimsPath()
        check(Files.exists(path)) { "$path is missing; run ./gradlew :kalligraphie:e2e:updateE2eGolden" }
        assertEquals(
            CatalogClaims.render(ExpectationCatalog.entries),
            Files.readString(path),
            "$path is stale; run ./gradlew :kalligraphie:e2e:updateE2eGolden",
        )
    }

    @Test
    fun everyAllowlistedTableCarriesAReason() {
        for ((key, tables) in CatalogClaims.unclaimedAllowlist) {
            assertTrue(key.isNotBlank())
            for ((table, reason) in tables) {
                assertTrue(reason.isNotBlank(), "$key/$table is allowlisted without a reason")
            }
        }
    }

    @Test
    fun everyClaimedTableIsDeclaredByAnEntryThatNamesItsFont() {
        val claims = CatalogClaims.claimsOf(ExpectationCatalog.entries)
        for ((key, tables) in claims) {
            assertTrue(key.isNotBlank())
            assertTrue(tables.isNotEmpty(), "$key is claimed by entries that declare no table")
        }
    }

    private fun claimsPath(): Path = repositoryRoot().resolve("kalligraphie/e2e/src/jvmTest/resources/catalog/claimed-tables.json")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogClaimsRunnerTest*'`
Expected: FAIL — `Unresolved reference: CatalogClaims`.

- [ ] **Step 3: Write the claims renderer**

```kotlin
// CatalogClaims.kt
package org.graphiks.kalligraphie.e2e.catalog

/**
 * Renders the catalog's table claims for the corpus lint.
 *
 * The Kotlin model is the only authority on what is claimed; the Python lint reads this export and
 * checks it against the tables the fonts really carry. A table that is really present and claimed
 * nowhere fails the lint, which is how an unclaimed technology is caught.
 */
public object CatalogClaims {
    /**
     * Tables no entry claims on purpose, per corpus key, with the reason. Starts empty: an entry
     * lands here only after the lint of task 12 named the table, never in advance.
     */
    public val unclaimedAllowlist: Map<String, Map<String, String>> = emptyMap()

    /** Groups the claimed tables of [entries] by corpus key. */
    public fun claimsOf(entries: List<CatalogEntry>): Map<String, Set<String>> = entries
        .filter { entry -> entry.font != null }
        .groupBy { entry -> entry.font!!.value }
        .mapValues { (_, axisEntries) -> axisEntries.flatMapTo(sortedSetOf()) { entry -> entry.tables } }

    /** Returns the canonical JSON export of [entries]. */
    public fun render(entries: List<CatalogEntry>): String = buildString {
        appendLine("{")
        appendLine("  \"schema\": \"kalligraphie.e2e-claims/v1\",")
        appendLine("  \"fonts\": {")
        val claims = claimsOf(entries).entries.sortedBy { (key, _) -> key }
        claims.forEachIndexed { index, (key, tables) ->
            append("    \"$key\": [")
            append(tables.sorted().joinToString(", ") { table -> "\"$table\"" })
            append("]")
            appendLine(if (index == claims.size - 1) "" else ",")
        }
        appendLine("  },")
        appendLine("  \"allowUnclaimed\": {")
        val allowlist = unclaimedAllowlist.entries.sortedBy { (key, _) -> key }
        allowlist.forEachIndexed { index, (key, tables) ->
            append("    \"$key\": {")
            append(tables.entries.sortedBy { (table, _) -> table }.joinToString(", ") { (table, reason) -> "\"$table\": \"$reason\"" })
            append("}")
            appendLine(if (index == allowlist.size - 1) "" else ",")
        }
        appendLine("  }")
        appendLine("}")
    }
}
```

`toSortedMap()` est une extension JVM : dans `commonMain` on trie donc des listes d'entrées
(`.entries.sortedBy { … }`). `sortedSetOf()` fait partie du stdlib commun et convient pour
l'ensemble de tables.

- [ ] **Step 4: Wire the writer, generate, and run**

Ajouter `val claimsClass = "org.graphiks.kalligraphie.e2e.catalog.CatalogClaimsRunnerTest"` au
`build.gradle.kts`, l'exclure de `jvmTest` et l'inclure dans `updateE2eGolden` avec
`environment("KALLIGRAPHIE_E2E_CLAIMS", "true")`.

Run:
```bash
./gradlew :kalligraphie:e2e:updateE2eGolden
./gradlew :kalligraphie:e2e:jvmTest --tests '*CatalogClaimsRunnerTest*'
```
Expected: PASS, et `claimed-tables.json` créé. Vérifier à l'œil que chaque famille du corpus y
apparaît (l'allowlist ne doit pas servir à masquer une famille entière non revendiquée).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e/src/commonMain kalligraphie/e2e/src/commonTest kalligraphie/e2e/src/jvmTest kalligraphie/e2e/build.gradle.kts
git commit -m "feat(e2e): export the catalog's table claims for the corpus lint"
```

## Task 12: Lint d'exhaustivité des tables

**Files:**
- Create: `scripts/fonts/check_exhaustiveness.py`
- Test: `scripts/fonts/tests/test_check_exhaustiveness.py`

**Interfaces:**
- Consumes: `scripts/fonts/corpus.json`, `kalligraphie/e2e/src/jvmTest/resources/catalog/claimed-tables.json`,
  `fetch_fonts.load_manifest` et `fetch_fonts.ALLOWED_LICENSES`.
- Produces: `check_exhaustiveness.py` exécutable en CI, code de sortie 1 en cas de violation.

- [ ] **Step 1: Write the failing tests**

```python
# scripts/fonts/tests/test_check_exhaustiveness.py
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import check_exhaustiveness


class ExhaustivenessTest(unittest.TestCase):
    def test_a_table_present_in_the_font_and_claimed_nowhere_is_reported(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "glyf", "COLR"},
            claimed={"cmap", "glyf"},
            allow_unclaimed={},
        )
        self.assertEqual(1, len(violations))
        self.assertIn("COLR", violations[0])

    def test_an_allowlisted_table_is_accepted(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "DSIG"},
            claimed={"cmap"},
            allow_unclaimed={"DSIG": "digital signature, not consumed"},
        )
        self.assertEqual([], violations)

    def test_a_claimed_table_the_font_does_not_carry_is_reported(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap"},
            claimed={"cmap", "SVG "},
            allow_unclaimed={},
        )
        self.assertEqual(1, len(violations))
        self.assertIn("SVG", violations[0])

    def test_a_table_both_claimed_and_allowlisted_is_reported(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap"},
            claimed={"cmap"},
            allow_unclaimed={"cmap": "redundant"},
        )
        self.assertEqual(1, len(violations))
        self.assertIn("both claimed and allowlisted", violations[0])

    def test_a_table_outside_the_significant_set_is_ignored(self):
        violations = check_exhaustiveness.compare(
            key="tiny",
            real_tables={"cmap", "DSIG", "LTSH"},
            claimed={"cmap"},
            allow_unclaimed={},
        )
        self.assertEqual([], violations)


if __name__ == "__main__":
    unittest.main()
```

Le dernier test fixe la sémantique : « table réellement portée » signifie « table de l'ensemble
significatif » (`SIGNIFICANT_TABLES` ci-dessous), pas toute table du fichier.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m unittest discover -s scripts/fonts/tests -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'check_exhaustiveness'`.

- [ ] **Step 3: Write the lint**

```python
#!/usr/bin/env python3
"""Checks that every significant table a corpus font carries is claimed by the catalog.

Reads the claims exported from the Kotlin catalog (`claimed-tables.json`), so there is exactly one
authority on what is claimed: the model. Requires fontTools.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import fetch_fonts

# Tables whose presence in a corpus font must be claimed by the catalog or explicitly allowlisted.
# Cosmetic or purely informational tables are left out deliberately: claiming them would add noise
# without adding an expectation.
SIGNIFICANT_TABLES = {
    "avar", "BASE", "CBDT", "CBLC", "CFF ", "CFF2", "cmap", "COLR", "CPAL", "cvar", "EBDT",
    "EBLC", "fvar", "GDEF", "glyf", "GPOS", "gvar", "GSUB", "head", "hhea", "hmtx", "HVAR",
    "kern", "loca", "maxp", "morx", "MVAR", "name", "OS/2", "post", "sbix", "STAT", "SVG ",
    "vhea", "vmtx", "VORG", "VARC", "VVAR",
}


def compare(key: str, real_tables: set[str], claimed: set[str], allow_unclaimed: dict[str, str]) -> list[str]:
    """Compares the claimed tables of one font with the tables it really carries.

    [real_tables] may be wider than the significant set: anything outside it is ignored, so a
    cosmetic table can never produce a violation.
    """
    violations: list[str] = []
    significant = real_tables & SIGNIFICANT_TABLES
    for table in sorted(significant):
        if table in claimed and table in allow_unclaimed:
            violations.append(f"{key}: {table} is both claimed and allowlisted")
        elif table not in claimed and table not in allow_unclaimed:
            violations.append(f"{key}: {table} is carried by the font and claimed nobody; claim it or allowlist it")
    for table in sorted(claimed - real_tables):
        violations.append(f"{key}: {table} is claimed but the font does not carry it")
    return violations


def real_tables_of(path: pathlib.Path, keys: set[str]) -> set[str]:
    """Returns the significant tables of the font or collection at [path], using fontTools."""
    from fontTools.ttLib import TTCollection, TTFont  # imported lazily so --help works without fontTools

    found: set[str] = set()
    if path.suffix.lower() == ".ttc":
        with TTCollection(path, lazy=True) as collection:
            for font in collection.fonts:
                found |= {tag for tag in font.keys() if tag in keys}
        return found
    font = TTFont(path, lazy=True)
    try:
        return {tag for tag in font.keys() if tag in keys}
    finally:
        font.close()


def decoded_font_path(path: pathlib.Path, scratch: pathlib.Path) -> pathlib.Path:
    """Returns [path] itself, or the decoded font when [path] is a base64-wrapped fixture."""
    if path.suffix not in {".b64", ".base64"}:
        return path
    payload = base64.b64decode(path.read_bytes())
    scratch.mkdir(parents=True, exist_ok=True)
    decoded = scratch / (path.stem if path.suffix == ".b64" else path.stem + ".ttf")
    decoded.write_bytes(payload)
    return decoded


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", default="scripts/fonts/corpus.json")
    parser.add_argument("--claims", default="kalligraphie/e2e/src/jvmTest/resources/catalog/claimed-tables.json")
    arguments = parser.parse_args(argv)

    root = pathlib.Path(__file__).resolve().parents[2]
    manifest = fetch_fonts.load_manifest(root / arguments.corpus)
    with open(root / arguments.claims, encoding="utf-8") as handle:
        claims = json.load(handle)
    if claims.get("schema") != "kalligraphie.e2e-claims/v1":
        raise SystemExit(f"{arguments.claims}: unexpected schema {claims.get('schema')!r}")

    violations: list[str] = []
    with tempfile.TemporaryDirectory() as scratch:
        scratch_path = pathlib.Path(scratch)
        for family in manifest["families"]:
            tables: set[str] = set()
            for record in family["files"]:
                path = decoded_font_path(root / record["path"], scratch_path)
                tables |= real_tables_of(path, SIGNIFICANT_TABLES)
            if not tables:
                violations.append(f"{family['key']}: no font file yielded any significant table; the lint would be blind here")
                continue
            violations += compare(
                key=family["key"],
                real_tables=tables,
                claimed=set(claims["fonts"].get(family["key"], [])),
                allow_unclaimed=claims["allowUnclaimed"].get(family["key"], {}),
            )

    for violation in violations:
        print(f"error: {violation}", file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
```

Les fixtures emballées en base64 sont décodées dans un répertoire temporaire : sans cela, les deux
familles concernées seraient silencieusement absentes du lint, et un trou de couverture vaut un
échec — d'où le message « the lint would be blind here » pour une famille dont aucun fichier ne
livre de table significative.

Les imports en tête du fichier :

```python
import argparse
import base64
import json
import pathlib
import sys
import tempfile

import fetch_fonts
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m unittest discover -s scripts/fonts/tests -v`
Expected: PASS (5 + 10 tests).

- [ ] **Step 5: Run the lint against the real corpus**

Run:
```bash
cd /Volumes/Cache/Kalligraphie
uv run --with fonttools==4.65.0 python scripts/fonts/check_exhaustiveness.py
```
Expected: soit une sortie vide, soit une liste de tables réellement présentes et non revendiquées.
Chaque table listée est une décision à prendre : soit une entrée du catalogue la revendique (ajouter
la table à son `tables`, puis régénérer `claimed-tables.json` par `updateE2eGolden`), soit elle
entre dans `CatalogClaims.unclaimedAllowlist` avec sa raison (puis même régénération). C'est le
cœur du dispositif : ignorer une table ne doit pas être possible par omission.

- [ ] **Step 6: Commit**

```bash
git add scripts/fonts
git commit -m "chore(e2e): lint the corpus tables against the catalog claims"
```

## Task 13: Job CI hors ligne et CHANGELOG

**Files:**
- Create: `.github/workflows/font-corpus.yml`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `scripts/fonts/fetch_fonts.py`, `scripts/fonts/check_exhaustiveness.py`,
  `scripts/fonts/tests/`.
- Produces: le job `font-corpus` exécuté sur les PR touchant le corpus ou le catalogue.

- [ ] **Step 1: Write the workflow**

```yaml
name: Font corpus verification

on:
  pull_request:
    paths:
      - 'test-fixtures/**'
      - 'scripts/fonts/**'
      - 'kalligraphie/e2e/**'
      - '.github/workflows/font-corpus.yml'
  push:
    branches: [master]
    paths:
      - 'test-fixtures/**'
      - 'scripts/fonts/**'
      - 'kalligraphie/e2e/**'
      - '.github/workflows/font-corpus.yml'

permissions:
  contents: read

jobs:
  corpus:
    name: Corpus manifest, licences and table claims
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.x'
      - name: Install fontTools
        run: python -m pip install fonttools==4.65.0
      - name: Verify the committed corpus offline
        run: python3 scripts/fonts/fetch_fonts.py --check --provenance
      - name: Verify the lint's own tests
        run: python3 -m unittest discover -s scripts/fonts/tests -v
      - name: Lint the tables against the catalog claims
        run: python3 scripts/fonts/check_exhaustiveness.py
```

Aucune étape ne télécharge de police : `--fetch` n'est jamais invoqué en CI.

- [ ] **Step 2: Verify the workflow locally**

Run:
```bash
cd /Volumes/Cache/Kalligraphie
python3 scripts/fonts/fetch_fonts.py --check --provenance && python3 -m unittest discover -s scripts/fonts/tests && uv run --with fonttools==4.65.0 python scripts/fonts/check_exhaustiveness.py
```
Expected: les trois commandes réussissent. `uv` fournit fontTools localement sans polluer
l'interpréteur courant ; en CI c'est `pip install fonttools==4.65.0` qui s'en charge.

- [ ] **Step 3: Update the CHANGELOG**

Ajouter une entrée décrivant le corpus vérifié, le lint d'exhaustivité et le job CI.

- [ ] **Step 4: Run the full verification and commit**

Run:
```bash
./gradlew check
```
Expected: PASS (le job `test` de `font-tests.yml` exécute `check`, qui inclut `jvmTest` du module
e2e et donc toutes les tâches de ce plan sauf le lint Python, couvert par le nouveau job).

```bash
git add .github/workflows/font-corpus.yml CHANGELOG.md
git commit -m "ci(e2e): verify the font corpus and its table claims offline"
```

---

## Couverture du spec par ce plan

| Section du spec | Tâches |
|---|---|
| §2 Modèle (statuts, entrée, clé de corpus, règle d'honnêteté) | 1, 2 |
| §3 Scènes et sondes générées, auto-dimensionnement | 3, 4, 5, 7 |
| §3 Test de cliquet | 2 (audit), 6 (scènes, exemptions), 7 (sondes), 8 (fraîcheur) |
| §3 Liaisons de rendu | 4, 5 |
| §4 Manifeste d'acquisition | 9 |
| §4 Script d'acquisition, garde-fou licence | 10 |
| §4 Lint d'exhaustivité et export des revendications | 11, 12 |
| §4 Job CI hors ligne | 13 |
| §5 Contenu initial (migration des 16 scènes, `Supported`/`NotYet`/`OutOfScope` documentés) | 2, 5, 7 |
| §5 Nouvelles polices réelles par axe | phases 3-4 (plans séparés) |
| §6 Matrice générée, nav, `updateE2eGolden` étendu | 8 |
| §6 Pages bilingues narratives, maillage `font-management` | phase 5 (plan séparé) |
| §7 Gestion d'erreur fail-closed | 1, 2, 4, 6, 7, 10, 11, 12 |
| §8 Tests du dispositif | 1, 2, 3, 4, 8 (Kotlin), 10, 12 (Python) |

## Reste à planifier (hors périmètre de ce plan)

- **Phase 3** : acquisition des polices réelles des axes CONTAINER, OUTLINE, METRICS, VARIATION,
  COLOR, BITMAP (Source Sans 3, Source Han, Noto Sans JP complet, Roboto Flex, Fraunces, Noto Color
  Emoji, twemoji complet, Source Serif Variable…), conversion des `CORPUS_NOT_ACQUIRED` en
  `Supported` ou en attentes vérifiées, arbitrage sur le poids de Source Han OTC.
- **Phase 4** : écritures (hébreu, thaï, hangul, tamil, bengali, nastaliq) et leurs scènes.
- **Phase 5** : pages `docs/docs/e2e-catalog.md` et `.fr.md`, maillage avec `font-management.md`,
  politique de mise à jour du catalogue pour un contributeur.
