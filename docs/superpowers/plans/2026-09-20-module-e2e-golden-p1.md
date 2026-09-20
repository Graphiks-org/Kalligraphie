# Module `:kalligraphie:e2e` — Phase 1 (scaffold + harnais golden) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Créer le module `:kalligraphie:e2e` avec son modèle golden portable, son manifest d'empreintes, son comparateur, son harnais auto-testé et une scène smoke rendue par le rasterizer déterministe.

**Architecture:** Module KMP non publié (convention `kmp-library`, comme `:kalligraphie:conformance`). Le modèle et l'algorithme vivent en `commonMain` pur (aucun `expect`/`actual`) ; le catalogue de scènes et le rendu vivent côté `jvmTest` (ils dépendent des fixtures de test et de la facade JVM). Une empreinte SHA-256 des octets canoniques de chaque image, comparée à un manifest TSV versionné, constitue la vérification.

**Tech Stack:** Kotlin Multiplatform 2.4.10, Gradle KTS, `kotlin("test")`, facade `:kalligraphie`, rasterizer `:kalligraphie:raster-cpu`.

**Spec de référence :** `docs/superpowers/specs/2026-09-20-module-e2e-golden-design.md` (§3, §4, §5, §6, §10, §11).

> **Portée de ce plan :** uniquement la **phase P1** du spec. Les phases P2 (consolidation `test-fixtures/` + migration des 5 journeys), P3 (migration de la conformance de composition `raster-cpu`) et P4 (doc + policy) feront l'objet de plans séparés, car elles exigent la lecture ligne à ligne des tests existants.

---

## Refinements vs spec (à connaître avant d'exécuter)

Le spec validé décrit la cible ; ce plan tranche trois détails d'implémentation que la lecture du code impose. Ce sont des précisions, pas des changements de direction :

1. **Emplacement du catalogue/renderer** — le spec §4 les place en `jvmMain`. Or ils chargent des **fixtures de test** (polices) : les placer en `jvmMain` imposerait d'y mettre des ressources de test. Décision : `commonMain` porte le modèle pur et `GoldenRenderOutcome` ; le catalogue et le rendu vivent en `jvmTest`. `jvmMain` reste vide en P1 et accueillera les adaptateurs quand des scènes non-test apparaîtront.
2. **Localisation d'un mismatch** — le manifest ne stocke qu'une empreinte, donc la vérification manifest↔image ne peut pas localiser le premier octet divergent. Décision : `GoldenComparison.Mismatch` porte les hashes et les dimensions ; un helper `GoldenImageDiff.firstDifference(expected, actual)` localise le premier octet + ses coordonnées et est validé par le harnais. Le dump opt-in localise visuellement côté manifest.
3. **Codes de diagnostic supplémentaires** — le spec §6 ne couvre ni une ligne de manifest malformée ni le mismatch lui-même. Décision : ajouter `e2e.manifest-malformed` (parse d'une ligne invalide) et `e2e.mismatch` (empreinte rendue ≠ empreinte enregistrée). Le spec doit être amendé sur ces deux points lors de P4.

4. **Durcissements issus de la revue de Task 3** — (a) le garde d'overflow de `GoldenImage` bornait le produit *après* la multiplication par `bytesPerPixel`, laquelle s'enroule pour `RGBA_8888` et laissait construire une image incohérente ; le bornage se fait désormais sur le nombre de pixels **avant** le passage aux octets (Task 3). (b) `GoldenScene.width`/`height` n'a plus qu'un sens — les dimensions canoniques attendues de l'image — et le vérificateur les assère contre l'image rendue pour **toutes** les familles, y compris à sortie serrée, de sorte qu'une dérive de dimension échoue même après régénération du manifest (Tasks 6, 7).

Deux autres points de séquençage :

- **Ressources de test** : en P1, `:kalligraphie:e2e` branche temporairement les ressources `jvmTest` de `:kalligraphie` (même mécanisme que `raster-cpu`) pour atteindre `/fonts/liberation/...`. La bascule vers `rootProject.file("test-fixtures")` se fait en P2 après la consolidation décrite au spec §8.
- **SHA-256** : l'implémentation portable existe dans `:kalligraphie:api` mais est `internal` (inaccessible entre modules). On duplique l'algorithme dans `commonMain` plutôt que d'élargir la surface publique de `:kalligraphie:api`. Une factorisation dans un module interne partagé est envisageable si un troisième consommateur apparaît (spec §13).

---

## File Structure

**Créés :**

- `kalligraphie/e2e/build.gradle.kts` — câblage du module, filtres de test, tâches opt-in.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenSceneFamily.kt` — familles de scènes.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenScene.kt` — descripteur de scène.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/PixelFormat.kt` — formats de pixel.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenImage.kt` — image canonique.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenDiagnosticCode.kt` — codes `e2e.*`.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/Sha256.kt` — digest portable + `sha256Hex`.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenFingerprint.kt` — entrée d'empreinte.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenManifest.kt` — codec TSV.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenComparison.kt` — verdicts + `GoldenRenderOutcome`.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenVerifier.kt` — comparaison pure.
- `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenImageDiff.kt` — localisation du premier octet divergent.
- `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/Sha256Test.kt`
- `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenImageTest.kt`
- `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenManifestTest.kt`
- `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenVerifierTest.kt`
- `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenHarnessTest.kt`
- `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/E2eFontFixture.kt` — fixture polices (sous-ensemble, par consommateur).
- `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/JvmGoldenSceneCatalog.kt` — catalogue + scène smoke.
- `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenVerificationTest.kt` — vérification bloquante.
- `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenUpdateRunnerTest.kt` — régénération du manifest (opt-in).
- `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpWriter.kt` — écriture PGM/PPM.
- `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpRunnerTest.kt` — dump opt-in.
- `kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv` — manifest généré puis commité.

**Modifié :**

- `settings.gradle.kts` — `include(":kalligraphie:e2e")`.

---

## Task 1: Squelette du module et câblage build

**Files:**
- Create: `kalligraphie/e2e/build.gradle.kts`
- Modify: `settings.gradle.kts` (ajouter l'include après la ligne 46 `include(":kalligraphie:conformance")`)

- [ ] **Step 1: Ajouter le module à `settings.gradle.kts`**

Dans `settings.gradle.kts`, après `include(":kalligraphie:conformance")`, ajouter :

```kotlin
include(":kalligraphie:e2e")
```

- [ ] **Step 2: Créer `kalligraphie/e2e/build.gradle.kts`**

```kotlin
plugins {
    id("ygdrasil.conventions.kmp-library")
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:raster-cpu"))
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Temporary bridge until P2 consolidates the shared font set into test-fixtures/.
tasks.named<Copy>("jvmTestProcessResources") {
    from(project(":kalligraphie").layout.projectDirectory.dir("src/jvmTest/resources"))
}

val updateClass = "org.graphiks.kalligraphie.e2e.golden.GoldenUpdateRunnerTest"
val dumpClass = "org.graphiks.kalligraphie.e2e.golden.GoldenDumpRunnerTest"
val e2eJvmTestTask = tasks.named<Test>("jvmTest")

e2eJvmTestTask.configure {
    filter.excludeTestsMatching(updateClass)
    filter.excludeTestsMatching(dumpClass)
}

tasks.register<Test>("updateE2eGolden") {
    group = "verification"
    description = "Regenerates the committed golden fingerprint manifest from the scene catalog."
    testClassesDirs = e2eJvmTestTask.get().testClassesDirs
    classpath = e2eJvmTestTask.get().classpath
    filter.includeTestsMatching("$updateClass.writesTheManifestOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_E2E_UPDATE", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("e2eGoldenDumps") {
    group = "verification"
    description = "Writes opt-in golden inspection dumps outside the repository."
    testClassesDirs = e2eJvmTestTask.get().testClassesDirs
    classpath = e2eJvmTestTask.get().classpath
    filter.includeTestsMatching("$dumpClass.writesDumpsOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_E2E_DUMPS", "true")
    outputs.upToDateWhen { false }
}
```

- [ ] **Step 3: Vérifier que le module se configure et compile à vide**

Run: `./gradlew :kalligraphie:e2e:assemble`
Expected: `BUILD SUCCESSFUL`. Si l'include est mal orthographié, Gradle échoue avec `Project with path ':kalligraphie:e2e' could not be found`.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts kalligraphie/e2e/build.gradle.kts
git commit -m "build(e2e): scaffold the non-published e2e module"
```

---

## Task 2: Digest SHA-256 portable

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/Sha256.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/Sha256Test.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`Sha256Test.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun emptyInputMatchesTheKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun abcMatchesTheKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun twoBlockMessageMatchesTheKnownVector() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*Sha256Test*'`
Expected: FAIL — `Unresolved reference: sha256Hex` (compilation).

- [ ] **Step 3: Implémenter `Sha256.kt`**

Transcription portable de l'algorithme déjà présent dans `:kalligraphie:api` (`FontIdentity.kt`, `internal`), dupliquée ici car `internal` ne franchit pas les modules.

```kotlin
package org.graphiks.kalligraphie.e2e

/**
 * Portable lowercase hexadecimal SHA-256 digest.
 *
 * The algorithm is duplicated from the internal implementation in
 * `:kalligraphie:api` rather than widening that module's published surface.
 */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = Sha256.digest(bytes)
    val chars = CharArray(digest.size * 2)
    val hexDigits = "0123456789abcdef"
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xFF
        chars[index * 2] = hexDigits[value ushr 4]
        chars[index * 2 + 1] = hexDigits[value and 0x0F]
    }
    return chars.concatToString()
}

internal object Sha256 {
    private val initialHash = intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19,
    )

    private val roundConstants = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )

    fun digest(bytes: ByteArray): ByteArray {
        val padded = pad(bytes)
        val hash = initialHash.copyOf()
        val schedule = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                schedule[index] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (index in 16 until 64) {
                schedule[index] = schedule[index - 16] + smallSigma0(schedule[index - 15]) +
                    schedule[index - 7] + smallSigma1(schedule[index - 2])
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]

            for (index in 0 until 64) {
                val temp1 = h + bigSigma1(e) + choose(e, f, g) + roundConstants[index] + schedule[index]
                val temp2 = bigSigma0(a) + majority(a, b, c)
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
            offset += 64
        }

        return ByteArray(32).also { result ->
            for (index in hash.indices) {
                val value = hash[index]
                val base = index * 4
                result[base] = (value ushr 24).toByte()
                result[base + 1] = (value ushr 16).toByte()
                result[base + 2] = (value ushr 8).toByte()
                result[base + 3] = value.toByte()
            }
        }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val bitLength = bytes.size.toLong() * 8L
        val paddingLength = ((56 - ((bytes.size + 1) % 64)) + 64) % 64
        val output = ByteArray(bytes.size + 1 + paddingLength + 8)
        bytes.copyInto(output, endIndex = bytes.size)
        output[bytes.size] = 0x80.toByte()
        for (index in 0 until 8) {
            output[output.size - 1 - index] = (bitLength ushr (index * 8)).toByte()
        }
        return output
    }

    private fun choose(x: Int, y: Int, z: Int): Int = (x and y) xor (x.inv() and z)

    private fun majority(x: Int, y: Int, z: Int): Int = (x and y) xor (x and z) xor (y and z)

    private fun bigSigma0(value: Int): Int = value.rotateRight(2) xor value.rotateRight(13) xor value.rotateRight(22)

    private fun bigSigma1(value: Int): Int = value.rotateRight(6) xor value.rotateRight(11) xor value.rotateRight(25)

    private fun smallSigma0(value: Int): Int = value.rotateRight(7) xor value.rotateRight(18) xor (value ushr 3)

    private fun smallSigma1(value: Int): Int = value.rotateRight(17) xor value.rotateRight(19) xor (value ushr 10)
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*Sha256Test*'`
Expected: PASS — les trois vecteurs sont des valeurs normatives connues (vide, `"abc"`, message de 71 octets sur deux blocs).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/Sha256.kt \
        kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/Sha256Test.kt
git commit -m "feat(e2e): add a portable SHA-256 digest for golden fingerprints"
```

---

## Task 3: Format de pixel, image canonique et familles de scènes

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/PixelFormat.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenSceneFamily.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenScene.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenImage.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenImageTest.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`GoldenImageTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoldenImageTest {
    @Test
    fun alpha8CopiesAndExposesRowMajorBytes() {
        val pixels = byteArrayOf(1, 2, 3, 4, 5, 6)
        val image = GoldenImage.alpha8(width = 2, height = 3, pixels = pixels)
        pixels[0] = 99
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), image.copyCanonicalBytes().toList())
        assertEquals(2, image.width)
        assertEquals(3, image.height)
        assertEquals(PixelFormat.ALPHA_8, image.format)
    }

    @Test
    fun rgba8RequiresFourBytesPerPixel() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImage.rgba8(width = 1, height = 1, pixels = byteArrayOf(1, 2, 3))
        }
        val image = GoldenImage.rgba8(width = 1, height = 1, pixels = byteArrayOf(1, 2, 3, 4))
        assertEquals(4, image.copyCanonicalBytes().size)
    }

    @Test
    fun alpha8RejectsMismatchedPixelCount() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImage.alpha8(width = 2, height = 2, pixels = byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun sceneRejectsBlankIdAndNegativeDimensions() {
        assertFailsWith<IllegalArgumentException> {
            GoldenScene(id = " ", family = GoldenSceneFamily.GLYPH_OUTLINE, width = 1, height = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GoldenScene(id = "scene", family = GoldenSceneFamily.GLYPH_OUTLINE, width = -1, height = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GoldenScene(id = "scene", family = GoldenSceneFamily.GLYPH_OUTLINE, width = 1, height = 0)
        }
    }

    @Test
    fun equalityIsBasedOnContent() {
        val a = GoldenImage.alpha8(1, 1, byteArrayOf(7))
        val b = GoldenImage.alpha8(1, 1, byteArrayOf(7))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun theReturnedBytesAreIndependentOfTheImage() {
        val image = GoldenImage.alpha8(2, 1, byteArrayOf(1, 2))
        val returned = image.copyCanonicalBytes()
        returned[0] = 99
        assertEquals(listOf<Byte>(1, 2), image.copyCanonicalBytes().toList())
    }

    @Test
    fun rgba8RejectsAnOverflowingPixelCount() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImage.rgba8(width = Int.MAX_VALUE, height = Int.MAX_VALUE, pixels = byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun alpha8RejectsTooManyPixels() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImage.alpha8(width = 2, height = 2, pixels = byteArrayOf(1, 2, 3, 4, 5))
        }
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenImageTest*'`
Expected: FAIL — `Unresolved reference: GoldenImage`, `GoldenScene`, `PixelFormat`, `GoldenSceneFamily`.

- [ ] **Step 3: Créer `PixelFormat.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/** Pixel layout of a canonical golden image. */
public enum class PixelFormat(
    /** Bytes stored per pixel in the canonical serialization. */
    public val bytesPerPixel: Int,
) {
    /** One eight-bit coverage sample per pixel, row-major. */
    ALPHA_8(1),

    /** Four straight (non-premultiplied) channels per pixel in R, G, B, A order. */
    RGBA_8888(4),
}
```

- [ ] **Step 3b: Créer `GoldenSceneFamily.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/** Route a golden scene exercises. */
public enum class GoldenSceneFamily {
    /** One glyph rendered from an outline representation. */
    GLYPH_OUTLINE,

    /** One glyph composited from a paint graph. */
    GLYPH_PAINT,

    /** One glyph rendered from a bitmap strike. */
    GLYPH_BITMAP,

    /** A multi-glyph alphabet sheet for one script. */
    ALPHABET_SHEET,

    /** A composed text line through the paragraph facade. */
    COMPOSED_LINE,

    /** A composed paragraph through the facade. */
    PARAGRAPH,

    /** A composed flow across one or more regions. */
    FLOW_REGION,
}
```

- [ ] **Step 3c: Créer `GoldenScene.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/**
 * Declarative identity of one golden scene.
 *
 * [width] and [height] are the dimensions of the canonical image the scene is
 * expected to produce, for every family. The verifier asserts them against the
 * rendered image, so a scene frame is a checked contract, not documentation.
 */
public data class GoldenScene(
    /** Stable identifier; also the manifest key. */
    public val id: String,
    /** Route the scene exercises. */
    public val family: GoldenSceneFamily,
    /** Expected canonical image width in pixels; must be positive. */
    public val width: Int,
    /** Expected canonical image height in pixels; must be positive. */
    public val height: Int,
    /** Free-form labels for filtering and reporting. */
    public val tags: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "A golden scene id must not be blank." }
        require(width > 0) { "A golden scene width must be positive." }
        require(height > 0) { "A golden scene height must be positive." }
    }
}
```

- [ ] **Step 3d: Créer `GoldenImage.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/**
 * Immutable canonical image whose bytes are the golden fingerprint subject.
 *
 * Pixels are stored row-major with no padding, in the source orientation of the
 * rasterizer. Nothing here flips, premultiplies, or reorders channels: the same
 * logical image always serializes to the same bytes.
 */
public class GoldenImage private constructor(
    /** Image width in pixels. */
    public val width: Int,
    /** Image height in pixels. */
    public val height: Int,
    /** Pixel layout. */
    public val format: PixelFormat,
    canonicalBytes: ByteArray,
) {
    private val captured: ByteArray = canonicalBytes.copyOf()

    init {
        require(width >= 0) { "A golden image width must be non-negative." }
        require(height >= 0) { "A golden image height must be non-negative." }
        val pixels = width.toLong() * height.toLong()
        require(pixels <= (Int.MAX_VALUE / format.bytesPerPixel).toLong()) {
            "Canonical byte count exceeds the maximum buffer size."
        }
        val expected = pixels * format.bytesPerPixel.toLong()
        require(captured.size == expected.toInt()) { "Canonical byte count does not match the image dimensions." }
    }

    /** Returns a caller-owned copy of the canonical bytes. */
    public fun copyCanonicalBytes(): ByteArray = captured.copyOf()

    override fun equals(other: Any?): Boolean =
        other is GoldenImage && width == other.width && height == other.height &&
            format == other.format && captured.contentEquals(other.captured)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        return 31 * result + captured.contentHashCode()
    }

    override fun toString(): String =
        "GoldenImage(width=$width, height=$height, format=$format, bytes=${captured.size})"

    public companion object {
        /** Wraps eight-bit coverage samples (one byte per pixel, row-major). */
        public fun alpha8(width: Int, height: Int, pixels: ByteArray): GoldenImage =
            GoldenImage(width, height, PixelFormat.ALPHA_8, pixels)

        /** Wraps straight RGBA samples (four bytes per pixel in R, G, B, A order). */
        public fun rgba8(width: Int, height: Int, pixels: ByteArray): GoldenImage =
            GoldenImage(width, height, PixelFormat.RGBA_8888, pixels)
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenImageTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/PixelFormat.kt \
        kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenSceneFamily.kt \
        kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenScene.kt \
        kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenImage.kt \
        kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenImageTest.kt
git commit -m "feat(e2e): add the golden scene, image, and pixel-format model"
```

---

## Task 4: Codes de diagnostic et empreinte

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenDiagnosticCode.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenFingerprint.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenFingerprintTest.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`GoldenFingerprintTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoldenFingerprintTest {
    @Test
    fun ofDerivesTheFingerprintFromTheCanonicalBytes() {
        val scene = GoldenScene("glyph.outline.smoke", GoldenSceneFamily.GLYPH_OUTLINE, 2, 2, setOf("smoke"))
        val image = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val fingerprint = GoldenFingerprint.of(scene, image)
        assertEquals("glyph.outline.smoke", fingerprint.sceneId)
        assertEquals(GoldenSceneFamily.GLYPH_OUTLINE, fingerprint.family)
        assertEquals(2, fingerprint.width)
        assertEquals(2, fingerprint.height)
        assertEquals(PixelFormat.ALPHA_8, fingerprint.format)
        assertEquals(sha256Hex(byteArrayOf(1, 2, 3, 4)), fingerprint.sha256)
    }

    @Test
    fun rejectsAMalformedDigest() {
        assertFailsWith<IllegalArgumentException> {
            GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "NOT-A-DIGEST")
        }
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenFingerprintTest*'`
Expected: FAIL — `Unresolved reference: GoldenFingerprint`.

- [ ] **Step 3: Créer `GoldenDiagnosticCode.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/** Stable diagnostic codes reported by the e2e harness. */
public enum class GoldenDiagnosticCode(
    /** Wire code, prefixed `e2e.`. */
    public val code: String,
) {
    /** The scene failed to render. */
    RENDER_FAILED("e2e.render-failed"),

    /** The rendered digest differs from the recorded digest. */
    MISMATCH("e2e.mismatch"),

    /** A scene's declared frame and its rendered bounds disagree. */
    SCENE_BOUNDS_INVALID("e2e.scene-bounds-invalid"),

    /** A catalogued scene has no manifest entry. */
    MANIFEST_MISSING_ENTRY("e2e.manifest-missing-entry"),

    /** A manifest entry has no catalogued scene. */
    MANIFEST_STALE_ENTRY("e2e.manifest-stale-entry"),

    /** A scene id appears more than once. */
    MANIFEST_DUPLICATE_ID("e2e.manifest-duplicate-id"),

    /** The manifest was written under a different canonicalization version. */
    CANONICALIZATION_VERSION_MISMATCH("e2e.canonicalization-version-mismatch"),

    /** The manifest text is not well formed. */
    MANIFEST_MALFORMED("e2e.manifest-malformed"),
}
```

- [ ] **Step 4: Créer `GoldenFingerprint.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/**
 * One manifest record: the identity and content digest of a scene's canonical image.
 */
public data class GoldenFingerprint(
    /** Scene identifier this record describes. */
    public val sceneId: String,
    /** Family of the scene. */
    public val family: GoldenSceneFamily,
    /** Recorded image width in pixels. */
    public val width: Int,
    /** Recorded image height in pixels. */
    public val height: Int,
    /** Recorded pixel layout. */
    public val format: PixelFormat,
    /** Lowercase hexadecimal SHA-256 of the canonical bytes. */
    public val sha256: String,
) {
    init {
        require(sceneId.isNotBlank()) { "A golden fingerprint scene id must not be blank." }
        require(width >= 0) { "A golden fingerprint width must be non-negative." }
        require(height >= 0) { "A golden fingerprint height must be non-negative." }
        require(sha256.length == 64 && sha256.all { char -> char in "0123456789abcdef" }) {
            "A golden fingerprint digest must be a 64-character lowercase hexadecimal string."
        }
    }

    public companion object {
        /** Derives the fingerprint of [image] under the identity of [scene]. */
        public fun of(scene: GoldenScene, image: GoldenImage): GoldenFingerprint = GoldenFingerprint(
            sceneId = scene.id,
            family = scene.family,
            width = image.width,
            height = image.height,
            format = image.format,
            sha256 = sha256Hex(image.copyCanonicalBytes()),
        )
    }
}
```

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenFingerprintTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenDiagnosticCode.kt \
        kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenFingerprint.kt \
        kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenFingerprintTest.kt
git commit -m "feat(e2e): add golden fingerprints and diagnostic codes"
```

---

## Task 5: Codec du manifest TSV

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenManifest.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenManifestTest.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`GoldenManifestTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoldenManifestTest {
    private val a = GoldenFingerprint("a", GoldenSceneFamily.GLYPH_OUTLINE, 3, 4, PixelFormat.ALPHA_8, "11".repeat(32))
    private val b = GoldenFingerprint("b", GoldenSceneFamily.COMPOSED_LINE, 10, 2, PixelFormat.RGBA_8888, "22".repeat(32))

    @Test
    fun roundTripsThroughTheCanonicalText() {
        val manifest = GoldenManifest.of(listOf(b, a))
        val text = GoldenManifest.serialize(manifest)
        assertEquals(
            "kalligraphie.golden/v1 canonicalization=1\n" +
                "a\tGLYPH_OUTLINE\t3x4\tALPHA_8\tsha256:${"11".repeat(32)}\n" +
                "b\tCOMPOSED_LINE\t10x2\tRGBA_8888\tsha256:${"22".repeat(32)}\n",
            text,
        )
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(GoldenManifest.parse(text))
        assertEquals(manifest, parsed.manifest)
    }

    @Test
    fun rejectsADifferentCanonicalizationVersion() {
        val text = "kalligraphie.golden/v1 canonicalization=2\na\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(text))
        assertEquals(GoldenDiagnosticCode.CANONICALIZATION_VERSION_MISMATCH, rejected.code)
    }

    @Test
    fun rejectsDuplicateIds() {
        val line = "a\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("kalligraphie.golden/v1 canonicalization=1\n$line$line"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_DUPLICATE_ID, rejected.code)
    }

    @Test
    fun rejectsAMalformedLine() {
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("kalligraphie.golden/v1 canonicalization=1\nnot-a-record\n"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun rejectsAMissingHeader() {
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("a\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun rejectsUnsortedEntries() {
        val swapped = "kalligraphie.golden/v1 canonicalization=1\n" +
            "b\tCOMPOSED_LINE\t10x2\tRGBA_8888\tsha256:${"22".repeat(32)}\n" +
            "a\tGLYPH_OUTLINE\t3x4\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(swapped))
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenManifestTest*'`
Expected: FAIL — `Unresolved reference: GoldenManifest`.

- [ ] **Step 3: Créer `GoldenManifest.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/**
 * Ordered, canonical set of golden fingerprints.
 *
 * Entries are unique by [GoldenFingerprint.sceneId] and kept sorted by id so the
 * serialized text is a stable, human-reviewable diff.
 */
public class GoldenManifest private constructor(
    /** Fingerprints sorted by scene id. */
    public val entries: List<GoldenFingerprint>,
) {
    private val byId: Map<String, GoldenFingerprint> = entries.associateBy { entry -> entry.sceneId }

    /** Returns the fingerprint registered for [sceneId], or `null`. */
    public fun fingerprintOf(sceneId: String): GoldenFingerprint? = byId[sceneId]

    override fun equals(other: Any?): Boolean =
        other is GoldenManifest && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "GoldenManifest(entries=${entries.size})"

    public companion object {
        /** Manifest header line identifying the schema and the canonicalization version. */
        public const val HEADER: String = "kalligraphie.golden/v1"

        /**
         * Canonicalization algorithm version. Bumping this value invalidates every
         * existing manifest wholesale, forcing a deliberate regeneration.
         */
        public const val CANONICALIZATION_VERSION: Int = 1

        /** Builds a manifest from [entries], sorted by scene id and rejecting duplicates. */
        public fun of(entries: List<GoldenFingerprint>): GoldenManifest {
            val sorted = entries.sortedBy { entry -> entry.sceneId }
            require(sorted.map { entry -> entry.sceneId }.distinct().size == sorted.size) {
                "A golden manifest must not contain duplicate scene ids."
            }
            return GoldenManifest(sorted)
        }

        /** Serializes [manifest] to its canonical text form. */
        public fun serialize(manifest: GoldenManifest): String = buildString {
            append(HEADER)
            append(" canonicalization=")
            append(CANONICALIZATION_VERSION)
            append('\n')
            for (entry in manifest.entries) {
                append(entry.sceneId)
                append('\t')
                append(entry.family.name)
                append('\t')
                append(entry.width)
                append('x')
                append(entry.height)
                append('\t')
                append(entry.format.name)
                append("\tsha256:")
                append(entry.sha256)
                append('\n')
            }
        }

        /** Parses canonical manifest [text], failing closed with a typed code. */
        public fun parse(text: String): GoldenManifestParseResult {
            val lines = text.split('\n')
            val records = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            if (records.isEmpty()) {
                return GoldenManifestParseResult.Rejected(GoldenDiagnosticCode.MANIFEST_MALFORMED, "empty manifest")
            }

            val header = records.first()
            val expectedPrefix = "$HEADER canonicalization="
            if (!header.startsWith(expectedPrefix)) {
                return GoldenManifestParseResult.Rejected(
                    GoldenDiagnosticCode.MANIFEST_MALFORMED,
                    "missing or unrecognised header: $header",
                )
            }
            val version = header.removePrefix(expectedPrefix).toIntOrNull()
                ?: return GoldenManifestParseResult.Rejected(
                    GoldenDiagnosticCode.MANIFEST_MALFORMED,
                    "unparsable canonicalization version: $header",
                )
            if (version != CANONICALIZATION_VERSION) {
                return GoldenManifestParseResult.Rejected(
                    GoldenDiagnosticCode.CANONICALIZATION_VERSION_MISMATCH,
                    "manifest canonicalization=$version, harness expects $CANONICALIZATION_VERSION; run updateE2eGolden",
                )
            }

            val entries = ArrayList<GoldenFingerprint>(records.size - 1)
            var previousId: String? = null
            for (record in records.drop(1)) {
                val fields = record.split('\t')
                if (fields.size != 5) {
                    return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED,
                        "expected 5 tab-separated fields: $record",
                    )
                }
                val id = fields[0]
                if (id.isBlank()) {
                    return GoldenManifestParseResult.Rejected(GoldenDiagnosticCode.MANIFEST_MALFORMED, "blank scene id")
                }
                if (previousId != null && id <= previousId) {
                    return GoldenManifestParseResult.Rejected(
                        if (id == previousId) GoldenDiagnosticCode.MANIFEST_DUPLICATE_ID else GoldenDiagnosticCode.MANIFEST_MALFORMED,
                        "scene ids must be unique and sorted: $id",
                    )
                }
                previousId = id

                val family = GoldenSceneFamily.entries.firstOrNull { candidate -> candidate.name == fields[1] }
                    ?: return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unknown family: ${fields[1]}",
                    )
                val dimensions = fields[2].split('x')
                val width = dimensions.getOrNull(0)?.toIntOrNull()
                val height = dimensions.getOrNull(1)?.toIntOrNull()
                if (dimensions.size != 2 || width == null || height == null || width < 0 || height < 0) {
                    return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unparsable dimensions: ${fields[2]}",
                    )
                }
                val format = PixelFormat.entries.firstOrNull { candidate -> candidate.name == fields[3] }
                    ?: return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unknown pixel format: ${fields[3]}",
                    )
                val digest = fields[4].removePrefix("sha256:")
                if (!fields[4].startsWith("sha256:") || digest.length != 64 || digest.any { char -> char !in "0123456789abcdef" }) {
                    return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unparsable digest: ${fields[4]}",
                    )
                }

                entries.add(GoldenFingerprint(id, family, width, height, format, digest))
            }
            return GoldenManifestParseResult.Parsed(GoldenManifest(entries))
        }
    }
}

/** Outcome of parsing canonical manifest text. */
public sealed interface GoldenManifestParseResult {
    /** The manifest parsed and validated. */
    public data class Parsed(
        /** Parsed manifest. */
        public val manifest: GoldenManifest,
    ) : GoldenManifestParseResult

    /** The manifest was refused with a stable code and a human-readable detail. */
    public data class Rejected(
        /** Stable diagnostic code. */
        public val code: GoldenDiagnosticCode,
        /** Human-readable reason without sensitive data. */
        public val detail: String,
    ) : GoldenManifestParseResult
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenManifestTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenManifest.kt \
        kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenManifestTest.kt
git commit -m "feat(e2e): add the canonical golden manifest codec"
```

---

## Task 6: Comparateur pur et diff de première différence

**Files:**
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenComparison.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenVerifier.kt`
- Create: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenImageDiff.kt`
- Test: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenVerifierTest.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`GoldenVerifierTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GoldenVerifierTest {
    private fun image(value: Byte): GoldenImage = GoldenImage.alpha8(1, 1, byteArrayOf(value))

    private fun manifestOf(vararg pairs: Pair<GoldenScene, GoldenImage>): GoldenManifest =
        GoldenManifest.of(pairs.map { (scene, img) -> GoldenFingerprint.of(scene, img) })

    @Test
    fun reportsMatchedWhenTheDigestAgrees() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val manifest = manifestOf(scene to image(1))
        val result = GoldenVerifier.verify(listOf(scene), mapOf("s" to image(1)), manifest)
        assertEquals(listOf<GoldenComparison>(GoldenComparison.Matched("s")), result)
    }

    @Test
    fun reportsMismatchWithBothDigests() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val manifest = manifestOf(scene to image(1))
        val result = assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to image(2)), manifest).single(),
        )
        assertEquals(sha256Hex(byteArrayOf(1)), result.expectedSha256)
        assertEquals(sha256Hex(byteArrayOf(2)), result.actualSha256)
    }

    @Test
    fun reportsAMissingEntryForANewScene() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val result = assertIs<GoldenComparison.MissingInManifest>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to image(1)), GoldenManifest.of(emptyList())).single(),
        )
        assertEquals("s", result.sceneId)
    }

    @Test
    fun reportsAStaleEntryWithNoScene() {
        val stale = GoldenScene("stale", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val result = assertIs<GoldenComparison.StaleManifestEntry>(
            GoldenVerifier.verify(emptyList(), emptyMap(), manifestOf(stale to image(1))).single(),
        )
        assertEquals("stale", result.sceneId)
    }

    @Test
    fun reportsAnUncataloguedManifestEntryEvenWhenAnImageWasRendered() {
        val ghost = GoldenScene("ghost", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val manifest = manifestOf(ghost to image(1))
        val result = assertIs<GoldenComparison.StaleManifestEntry>(
            GoldenVerifier.verify(emptyList(), mapOf("ghost" to image(1)), manifest).single(),
        )
        assertEquals("ghost", result.sceneId)
    }

    @Test
    fun rejectsACataloguedSceneWithoutARenderedImage() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        assertFailsWith<IllegalArgumentException> {
            GoldenVerifier.verify(listOf(scene), emptyMap(), manifestOf(scene to image(1)))
        }
    }

    @Test
    fun rejectsADuplicateCataloguedSceneId() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        assertFailsWith<IllegalArgumentException> {
            GoldenVerifier.verify(listOf(scene, scene), mapOf("s" to image(1)), manifestOf(scene to image(1)))
        }
    }

    @Test
    fun reportsMismatchWhenTheRecordedFamilyDiffers() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val recorded = GoldenFingerprint(
            "s", GoldenSceneFamily.GLYPH_BITMAP, 1, 1, PixelFormat.ALPHA_8, sha256Hex(byteArrayOf(1)),
        )
        assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to image(1)), GoldenManifest.of(listOf(recorded))).single(),
        )
    }

    @Test
    fun firstDifferenceLocatesAnRgbaChannel() {
        val expected = GoldenImage.rgba8(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val actual = GoldenImage.rgba8(2, 1, byteArrayOf(1, 2, 3, 4, 5, 9, 7, 8))
        val difference = GoldenImageDiff.firstDifference(expected, actual)
        assertEquals(5, difference?.byteOffset)
        assertEquals(1, difference?.x)
        assertEquals(0, difference?.y)
    }

    @Test
    fun firstDifferenceRejectsMismatchedDimensions() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImageDiff.firstDifference(
                GoldenImage.alpha8(1, 1, byteArrayOf(1)),
                GoldenImage.alpha8(2, 1, byteArrayOf(1, 2)),
            )
        }
    }

    @Test
    fun reportsMismatchWhenTheRenderedSizeDoesNotMatchTheSceneFrame() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val rendered = GoldenImage.alpha8(2, 1, byteArrayOf(1, 2))
        val manifest = GoldenManifest.of(listOf(GoldenFingerprint.of(scene, rendered)))
        val result = assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to rendered), manifest).single(),
        )
        assertEquals(1, result.expectedWidth)
        assertEquals(1, result.expectedHeight)
        assertEquals(2, result.actualWidth)
    }

    @Test
    fun firstDifferenceLocatesTheOffendingByte() {
        val expected = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val actual = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 9, 4))
        val difference = GoldenImageDiff.firstDifference(expected, actual)
        assertEquals(2, difference?.byteOffset)
        assertEquals(0, difference?.x)
        assertEquals(1, difference?.y)
    }

    @Test
    fun firstDifferenceIsNullForIdenticalImages() {
        val image = GoldenImage.alpha8(1, 1, byteArrayOf(7))
        assertNull(GoldenImageDiff.firstDifference(image, image))
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenVerifierTest*'`
Expected: FAIL — `Unresolved reference: GoldenVerifier`, `GoldenComparison`, `GoldenImageDiff`.

- [ ] **Step 3: Créer `GoldenComparison.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/** Verdict of comparing one scene's rendered image against the manifest. */
public sealed interface GoldenComparison {
    /** Scene id common to every verdict. */
    public val sceneId: String

    /** The rendered digest equals the recorded digest. */
    public data class Matched(
        override val sceneId: String,
    ) : GoldenComparison

    /** The rendered digest differs from the recorded digest. */
    public data class Mismatch(
        override val sceneId: String,
        /** Digest recorded in the manifest. */
        public val expectedSha256: String,
        /** Digest of the rendered image. */
        public val actualSha256: String,
        /** Expected width: the scene frame declared by the catalog. */
        public val expectedWidth: Int,
        /** Expected height: the scene frame declared by the catalog. */
        public val expectedHeight: Int,
        /** Rendered width. */
        public val actualWidth: Int,
        /** Rendered height. */
        public val actualHeight: Int,
    ) : GoldenComparison

    /** A catalogued scene has no manifest entry. */
    public data class MissingInManifest(
        override val sceneId: String,
    ) : GoldenComparison

    /** A manifest entry has no catalogued scene. */
    public data class StaleManifestEntry(
        override val sceneId: String,
    ) : GoldenComparison
}

/** Outcome of rendering one scene. */
public sealed interface GoldenRenderOutcome {
    /** The scene rendered one canonical image. */
    public data class Rendered(
        /** Canonical image produced by the renderer. */
        public val image: GoldenImage,
    ) : GoldenRenderOutcome

    /** The scene refused to render. */
    public data class Refused(
        /** Stable diagnostic code. */
        public val code: GoldenDiagnosticCode,
        /** Human-readable reason without sensitive data. */
        public val detail: String,
    ) : GoldenRenderOutcome
}
```

- [ ] **Step 4: Créer `GoldenVerifier.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/**
 * Pure comparison between a scene catalog, the rendered images, and the manifest.
 *
 * Fails closed: a scene without a manifest entry and a manifest entry without a
 * scene are both surfaced, never silently accepted.
 */
public object GoldenVerifier {
    /**
     * Compares [scenes] and their [rendered] images against [manifest].
     *
     * Results follow catalog order for catalogued scenes, then manifest order for
     * stale entries.
     *
     * @throws IllegalArgumentException when [scenes] contains a duplicate id, or when
     * a catalogued scene has no entry in [rendered]. A render refusal is reported as
     * [GoldenRenderOutcome.Refused] by the renderer before verification is attempted.
     */
    public fun verify(
        scenes: List<GoldenScene>,
        rendered: Map<String, GoldenImage>,
        manifest: GoldenManifest,
    ): List<GoldenComparison> {
        val ids = scenes.map { scene -> scene.id }
        require(ids.distinct().size == ids.size) { "A golden scene catalog must not contain duplicate ids." }
        val cataloguedIds = ids.toSet()
        require(cataloguedIds.all { id -> rendered.containsKey(id) }) {
            "Every catalogued scene must have a rendered image; refuse renders are reported before verification."
        }

        val results = ArrayList<GoldenComparison>(scenes.size)
        for (scene in scenes) {
            val recorded = manifest.fingerprintOf(scene.id)
            if (recorded == null) {
                results.add(GoldenComparison.MissingInManifest(scene.id))
                continue
            }
            val image = rendered.getValue(scene.id)
            val actual = GoldenFingerprint.of(scene, image)
            val recordMatches = recorded.sha256 == actual.sha256 &&
                recorded.family == actual.family &&
                recorded.width == actual.width &&
                recorded.height == actual.height &&
                recorded.format == actual.format
            val frameMatches = image.width == scene.width && image.height == scene.height
            results.add(
                if (recordMatches && frameMatches) {
                    GoldenComparison.Matched(scene.id)
                } else {
                    GoldenComparison.Mismatch(
                        sceneId = scene.id,
                        expectedSha256 = recorded.sha256,
                        actualSha256 = actual.sha256,
                        expectedWidth = scene.width,
                        expectedHeight = scene.height,
                        actualWidth = actual.width,
                        actualHeight = actual.height,
                    )
                },
            )
        }

        for (entry in manifest.entries) {
            if (entry.sceneId !in cataloguedIds) {
                results.add(GoldenComparison.StaleManifestEntry(entry.sceneId))
            }
        }
        return results
    }
}
```

- [ ] **Step 5: Créer `GoldenImageDiff.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e

/** Location of the first byte where two canonical images differ. */
public data class GoldenImageDifference(
    /** Zero-based offset of the first differing byte. */
    public val byteOffset: Int,
    /** Pixel column of the differing byte. */
    public val x: Int,
    /** Pixel row of the differing byte. */
    public val y: Int,
)

/** Locates the first differing byte between two canonical images. */
public object GoldenImageDiff {
    /**
     * Returns the first differing byte location, or `null` when the canonical bytes
     * are identical.
     *
     * @throws IllegalArgumentException when the images have different dimensions or formats.
     */
    public fun firstDifference(expected: GoldenImage, actual: GoldenImage): GoldenImageDifference? {
        require(expected.width == actual.width && expected.height == actual.height && expected.format == actual.format) {
            "Images must share dimensions and format to be compared byte by byte."
        }
        val expectedBytes = expected.copyCanonicalBytes()
        val actualBytes = actual.copyCanonicalBytes()
        val bytesPerPixel = expected.format.bytesPerPixel
        for (offset in expectedBytes.indices) {
            if (expectedBytes[offset] != actualBytes[offset]) {
                val pixelIndex = offset / bytesPerPixel
                return GoldenImageDifference(
                    byteOffset = offset,
                    x = if (expected.width == 0) 0 else pixelIndex % expected.width,
                    y = if (expected.width == 0) 0 else pixelIndex / expected.width,
                )
            }
        }
        return null
    }
}
```

- [ ] **Step 6: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenVerifierTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenComparison.kt \
        kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenVerifier.kt \
        kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenImageDiff.kt \
        kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenVerifierTest.kt
git commit -m "feat(e2e): add the golden verifier and image diff"
```

---

## Task 7: Fixture polices et catalogue JVM avec la scène smoke

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/E2eFontFixture.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/JvmGoldenSceneCatalog.kt`
- Test: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/JvmGoldenSceneCatalogTest.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`JvmGoldenSceneCatalogTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class JvmGoldenSceneCatalogTest {
    @Test
    fun smokeOutlineSceneRendersTheLiberationSansCapitalA() {
        val entry = JvmGoldenSceneCatalog.entries().single { candidate -> candidate.scene.id == "glyph.outline.liberation-sans.A.64" }
        val rendered = assertIs<GoldenRenderOutcome.Rendered>(entry.render())
        assertEquals(43, rendered.image.width)
        assertEquals(45, rendered.image.height)
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = JvmGoldenSceneCatalog.entries().map { entry -> entry.scene.id }
        assertEquals(ids.distinct().size, ids.size)
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*JvmGoldenSceneCatalogTest*'`
Expected: FAIL — `Unresolved reference: JvmGoldenSceneCatalog`.

- [ ] **Step 3: Créer `E2eFontFixture.kt`**

Sous-ensemble des fixtures du `raster-cpu`, dupliqué par consommateur comme prévu au spec §8.

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.assertIs

/** Owns one render asset and its resolver lease; [close] releases the asset, then the resolver lease. */
internal class E2eFontFixture(
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
    private val resolver: FontAssetResolverHandle,
) : AutoCloseable {
    override fun close() {
        try {
            assertIs<FontOperationResult.Success<Unit>>(asset.close())
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }
}

/** Opens an outline-capable fixture for [bytes]. */
internal fun openOutlineFixture(bytes: ByteArray, layoutSize: LayoutUnit = LayoutUnit(2_048f)): E2eFontFixture {
    val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
        Kalligraphie.embedded(
            sourceBytes = bytes,
            provenance = FontSourceProvenance(declaredName = "e2e golden fixture"),
        ),
    ).value
    val requirements = FontAccessRequirementsSnapshot.renderable(listOf(outlineProfile()))
    val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(catalog.openAssetResolver()).value
    try {
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, requirements),
        ).value
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize)),
        ).value
        val asset = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
            instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements),
        ).value
        return E2eFontFixture(instance, asset, resolver)
    } catch (error: Throwable) {
        resolver.close()
        throw error
    }
}

/** Resolves the outline of [codePoint] through the instance, then the render asset. */
internal fun E2eFontFixture.outlineOf(codePoint: Int): GlyphOutlineIR {
    val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
        instance.resolveGlyph(codePoint),
    ).value.glyphId
    val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
        asset.resolveGlyph(FontGlyphRequest(glyph)),
    ).value
    return assertIs<GlyphRepresentation.Outline>(representation).outline
}

internal fun outlineProfile(): OutlineProfile = OutlineProfile(
    maxBytes = 1_000_000,
    maxContours = 256,
    maxPoints = 16_384,
    maxCompositeDepth = 16,
    maxCompositeComponents = 256,
)

internal fun fixtureBytes(path: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream(path)) { "fixture resource $path is missing" }
        .use { input -> input.readBytes() }
```

- [ ] **Step 4: Créer `JvmGoldenSceneCatalog.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenScene
import org.graphiks.kalligraphie.e2e.GoldenSceneFamily
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import kotlin.test.assertIs

/** A catalogued scene paired with the renderer that produces its canonical image. */
internal class JvmGoldenEntry(
    val scene: GoldenScene,
    val render: () -> GoldenRenderOutcome,
)

/** The JVM scene catalog. One smoke scene in phase 1; composed families arrive in phase 3. */
internal object JvmGoldenSceneCatalog {
    private const val LIBERATION_SANS = "/fonts/liberation/LiberationSans-Regular.ttf"

    fun entries(): List<JvmGoldenEntry> = listOf(smokeOutlineScene())

    private fun smokeOutlineScene(): JvmGoldenEntry {
        val scene = GoldenScene(
            id = "glyph.outline.liberation-sans.A.64",
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            width = 43,
            height = 45,
            tags = setOf("smoke", "scripts:latin"),
        )
        return JvmGoldenEntry(scene) {
            openOutlineFixture(fixtureBytes(LIBERATION_SANS)).use { fixture ->
                val outline = fixture.outlineOf(0x41)
                when (val result = GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 64.0))) {
                    is RasterResult.Success -> {
                        val image = result.value
                        if (image.width != scene.width || image.height != scene.height) {
                            GoldenRenderOutcome.Refused(
                                code = GoldenDiagnosticCode.SCENE_BOUNDS_INVALID,
                                detail = "Liberation Sans 'A' rendered ${image.width}x${image.height}, " +
                                    "expected ${scene.width}x${scene.height}",
                            )
                        } else {
                            GoldenRenderOutcome.Rendered(
                                GoldenImage.alpha8(image.width, image.height, image.copyPixels()),
                            )
                        }
                    }

                    is RasterResult.Failure -> GoldenRenderOutcome.Refused(
                        code = GoldenDiagnosticCode.RENDER_FAILED,
                        detail = "Liberation Sans 'A' refused: ${result.diagnostics.first().field}",
                    )
                }
            }
        }
    }
}
```

> **Note :** la frame `43 × 45` est la taille canonique attendue, reprise de `raster-cpu`'s `OutlineConformanceTest`. Le vérificateur (Task 6) assère que l'image rendue fait exactement cette taille ; une dérive de dimension échoue même si le manifest a été régénéré.

- [ ] **Step 5: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*JvmGoldenSceneCatalogTest*'`
Expected: PASS — l'image fait 43 × 45 (mêmes valeurs que `raster-cpu`'s `OutlineConformanceTest`).

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/E2eFontFixture.kt \
        kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/JvmGoldenSceneCatalog.kt \
        kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/JvmGoldenSceneCatalogTest.kt
git commit -m "feat(e2e): add the JVM scene catalog and the smoke outline scene"
```

---

## Task 8: Vérification bloquante et manifest committé

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenVerificationTest.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenUpdateRunnerTest.kt`
- Create: `kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv` (généré à l'étape 5)

- [ ] **Step 1: Créer `GoldenVerificationTest.kt` (doit échouer, manifest absent)**

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenComparison
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenManifest
import org.graphiks.kalligraphie.e2e.GoldenManifestParseResult
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome
import org.graphiks.kalligraphie.e2e.GoldenVerifier

class GoldenVerificationTest {
    @Test
    fun everyCataloguedSceneMatchesTheCommittedManifest() {
        val text = checkNotNull(object {}.javaClass.getResourceAsStream("/golden/manifest.tsv")) {
            "the golden manifest resource is missing; run ./gradlew :kalligraphie:e2e:updateE2eGolden"
        }.use { input -> input.readBytes().decodeToString() }
        val manifest = when (val parsed = GoldenManifest.parse(text)) {
            is GoldenManifestParseResult.Parsed -> parsed.manifest
            is GoldenManifestParseResult.Rejected -> error("${parsed.code.code}: ${parsed.detail}")
        }

        val entries = JvmGoldenSceneCatalog.entries()
        val rendered = LinkedHashMap<String, GoldenImage>()
        for (entry in entries) {
            when (val outcome = entry.render()) {
                is GoldenRenderOutcome.Rendered -> rendered[entry.scene.id] = outcome.image
                is GoldenRenderOutcome.Refused -> error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
            }
        }

        val results = GoldenVerifier.verify(entries.map { entry -> entry.scene }, rendered, manifest)
        val failures = results.filterNot { result -> result is GoldenComparison.Matched }
        assertTrue(
            failures.isEmpty(),
            buildString {
                appendLine("golden verification failed:")
                for (failure in failures) {
                    when (failure) {
                        is GoldenComparison.Mismatch ->
                            appendLine(
                                "  ${GoldenDiagnosticCode.MISMATCH.code}: ${failure.sceneId} " +
                                    "expected=${failure.expectedSha256} actual=${failure.actualSha256} " +
                                    "expectedSize=${failure.expectedWidth}x${failure.expectedHeight} " +
                                    "actualSize=${failure.actualWidth}x${failure.actualHeight} " +
                                    "(run ./gradlew :kalligraphie:e2e:e2eGoldenDumps to inspect)",
                            )

                        is GoldenComparison.MissingInManifest ->
                            appendLine("  ${GoldenDiagnosticCode.MANIFEST_MISSING_ENTRY.code}: ${failure.sceneId} (run updateE2eGolden)")

                        is GoldenComparison.StaleManifestEntry ->
                            appendLine("  ${GoldenDiagnosticCode.MANIFEST_STALE_ENTRY.code}: ${failure.sceneId} (run updateE2eGolden)")

                        is GoldenComparison.Matched -> Unit
                    }
                }
            },
        )
    }
}
```

- [ ] **Step 2: Créer `GoldenUpdateRunnerTest.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.graphiks.kalligraphie.e2e.GoldenDiagnosticCode
import org.graphiks.kalligraphie.e2e.GoldenFingerprint
import org.graphiks.kalligraphie.e2e.GoldenManifest
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class GoldenUpdateRunnerTest {
    @Test
    fun writesTheManifestOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_UPDATE") != "true") {
            return
        }
        val fingerprints = ArrayList<GoldenFingerprint>()
        for (entry in JvmGoldenSceneCatalog.entries()) {
            when (val outcome = entry.render()) {
                is GoldenRenderOutcome.Rendered -> fingerprints.add(GoldenFingerprint.of(entry.scene, outcome.image))
                is GoldenRenderOutcome.Refused ->
                    error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
            }
        }
        val target = repositoryRoot().resolve("kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv")
        Files.createDirectories(target.parent)
        Files.writeString(target, GoldenManifest.serialize(GoldenManifest.of(fingerprints)))
    }
}

/** Locates the repository root by walking up from the test working directory. */
internal fun repositoryRoot(): Path {
    var candidate: Path? = Path.of("").toAbsolutePath().normalize()
    while (candidate != null) {
        if (Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    error("Could not locate the repository root from the test working directory.")
}
```

- [ ] **Step 3: Lancer la vérification pour constater l'échec attendu**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenVerificationTest*'`
Expected: FAIL avec `the golden manifest resource is missing; run ./gradlew :kalligraphie:e2e:updateE2eGolden`.

- [ ] **Step 4: Générer le manifest**

Run: `./gradlew :kalligraphie:e2e:updateE2eGolden`
Expected: `BUILD SUCCESSFUL` ; le fichier `kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv` est créé.

- [ ] **Step 5: Inspecter le manifest généré**

Run: `cat kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv`
Expected : en-tête `kalligraphie.golden/v1 canonicalization=1` puis une ligne
`glyph.outline.liberation-sans.A.64\tGLYPH_OUTLINE\t43x45\tALPHA_8\tsha256:bade575a06ee0217858ff2b2eb9850f0c949323ca47a307666f99495fdbf2ca3`.
Le hash doit être **identique** à `EXPECTED_A_SHA256` dans `raster-cpu`'s `OutlineConformanceTest`, ce qui prouve que la canonicalisation reproduit les octets existants.

- [ ] **Step 6: Lancer la vérification pour vérifier qu'elle passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenVerificationTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenVerificationTest.kt \
        kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenUpdateRunnerTest.kt \
        kalligraphie/e2e/src/jvmTest/resources/golden/manifest.tsv
git commit -m "test(e2e): verify catalogued scenes against the committed golden manifest"
```

---

## Task 9: Dump opt-in PGM/PPM

**Files:**
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpWriter.kt`
- Create: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpRunnerTest.kt`
- Test: `kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpWriterTest.kt`

- [ ] **Step 1: Écrire le test qui échoue**

`GoldenDumpWriterTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.e2e.GoldenImage

class GoldenDumpWriterTest {
    @Test
    fun writesABinaryPgmForAnAlphaImage() {
        val image = GoldenImage.alpha8(2, 1, byteArrayOf(0, 127))
        val bytes = GoldenDumpWriter.encode(image)
        assertEquals("P5\n2 1\n255\n", bytes.copyOfRange(0, 9).decodeToString())
        assertEquals(listOf<Byte>(0, 127), bytes.copyOfRange(9, 11).toList())
    }

    @Test
    fun writesABinaryPpmForAnRgbaImage() {
        val image = GoldenImage.rgba8(1, 1, byteArrayOf(10, 20, 30, 255))
        val bytes = GoldenDumpWriter.encode(image)
        assertEquals("P6\n1 1\n255\n", bytes.copyOfRange(0, 9).decodeToString())
        assertEquals(listOf<Byte>(10, 20, 30), bytes.copyOfRange(9, 12).toList())
    }
}
```

- [ ] **Step 2: Lancer le test pour vérifier qu'il échoue**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenDumpWriterTest*'`
Expected: FAIL — `Unresolved reference: GoldenDumpWriter`.

- [ ] **Step 3: Créer `GoldenDumpWriter.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.PixelFormat

/** Encodes canonical images as binary PGM (coverage) or PPM (RGBA) for human inspection. */
internal object GoldenDumpWriter {
    /** Returns the binary PGM/PPM bytes for [image]. The header is ASCII, the raster is binary. */
    fun encode(image: GoldenImage): ByteArray {
        val pixels = image.copyCanonicalBytes()
        val header: String
        val raster: ByteArray
        when (image.format) {
            PixelFormat.ALPHA_8 -> {
                header = "P5\n${image.width} ${image.height}\n255\n"
                raster = pixels
            }

            PixelFormat.RGBA_8888 -> {
                header = "P6\n${image.width} ${image.height}\n255\n"
                raster = ByteArray(image.width * image.height * 3) { index ->
                    val pixelIndex = (index / 3) * 4 + (index % 3)
                    pixels[pixelIndex]
                }
            }
        }
        return header.encodeToByteArray() + raster
    }
}
```

- [ ] **Step 4: Lancer le test pour vérifier qu'il passe**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenDumpWriterTest*'`
Expected: PASS.

- [ ] **Step 5: Créer `GoldenDumpRunnerTest.kt`**

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class GoldenDumpRunnerTest {
    @Test
    fun writesDumpsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_DUMPS") != "true") {
            return
        }
        val output = checkNotNull(System.getenv("KALLIGRAPHIE_E2E_DUMPS_OUTPUT")) {
            "KALLIGRAPHIE_E2E_DUMPS_OUTPUT must point to an absolute directory outside the repository."
        }
        val directory = Path.of(output)
        assertTrue(directory.isAbsolute, "the dump output must be an absolute path")
        assertTrue(!directory.normalize().startsWith(repositoryRoot()), "the dump output must be outside the repository")
        Files.createDirectories(directory)

        for (entry in JvmGoldenSceneCatalog.entries()) {
            when (val outcome = entry.render()) {
                is GoldenRenderOutcome.Rendered -> {
                    val extension = if (outcome.image.format.name == "ALPHA_8") "pgm" else "ppm"
                    Files.write(directory.resolve("${entry.scene.id}.$extension"), GoldenDumpWriter.encode(outcome.image))
                }

                is GoldenRenderOutcome.Refused ->
                    error("${outcome.code.code} (${entry.scene.id}): ${outcome.detail}")
            }
        }
    }
}
```

- [ ] **Step 6: Vérifier que le dump exige une sortie explicite**

Run: `./gradlew :kalligraphie:e2e:e2eGoldenDumps`
Expected: FAIL avec `KALLIGRAPHIE_E2E_DUMPS_OUTPUT must point to an absolute directory outside the repository.` — la tâche est la seule à poser `KALLIGRAPHIE_E2E_DUMPS=true`, donc l'opt-in n'est jamais silencieux quand la sortie manque.

Run: `KALLIGRAPHIE_E2E_DUMPS_OUTPUT=/tmp/kalligraphie-e2e ./gradlew :kalligraphie:e2e:e2eGoldenDumps`
Expected: `BUILD SUCCESSFUL` ; `/tmp/kalligraphie-e2e/glyph.outline.liberation-sans.A.64.pgm` existe.

- [ ] **Step 7: Commit**

```bash
git add kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpWriter.kt \
        kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpWriterTest.kt \
        kalligraphie/e2e/src/jvmTest/kotlin/org/graphiks/kalligraphie/e2e/golden/GoldenDumpRunnerTest.kt
git commit -m "feat(e2e): add opt-in golden inspection dumps"
```

---

## Task 10: Harnais auto-testé et câblage `check`

**Files:**
- Create: `kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenHarnessTest.kt`

- [ ] **Step 1: Écrire le test d'auto-validation du harnais**

`GoldenHarnessTest.kt` :

```kotlin
package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoldenHarnessTest {
    @Test
    fun aDeliberateMutationIsDetected() {
        val scene = GoldenScene("harness.mutated", GoldenSceneFamily.GLYPH_OUTLINE, 2, 2)
        val original = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val manifest = GoldenManifest.of(listOf(GoldenFingerprint.of(scene, original)))
        val mutated = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 5))

        val result = assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("harness.mutated" to mutated), manifest).single(),
        )
        assertTrue(result.expectedSha256 != result.actualSha256)
    }

    @Test
    fun theManifestRoundTripsUnderTheHarness() {
        val scene = GoldenScene("harness.roundtrip", GoldenSceneFamily.COMPOSED_LINE, 4, 1)
        val image = GoldenImage.rgba8(4, 1, ByteArray(16) { index -> index.toByte() })
        val manifest = GoldenManifest.of(listOf(GoldenFingerprint.of(scene, image)))
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(
            GoldenManifest.parse(GoldenManifest.serialize(manifest)),
        )
        assertEquals(manifest, parsed.manifest)
        assertEquals(
            listOf<GoldenComparison>(GoldenComparison.Matched("harness.roundtrip")),
            GoldenVerifier.verify(listOf(scene), mapOf("harness.roundtrip" to image), parsed.manifest),
        )
    }

    @Test
    fun theCanonicalizationGuardRejectsAStaleManifest() {
        val text = "kalligraphie.golden/v1 canonicalization=999\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(text))
        assertEquals(GoldenDiagnosticCode.CANONICALIZATION_VERSION_MISMATCH, rejected.code)
    }
}
```

- [ ] **Step 2: Lancer le test**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests '*GoldenHarnessTest*'`
Expected: PASS.

- [ ] **Step 3: Lancer le module complet via `check`**

Run: `./gradlew :kalligraphie:e2e:check`
Expected: `BUILD SUCCESSFUL` — `jvmTest` inclut `GoldenVerificationTest`, `GoldenHarnessTest`, et tous les tests `commonTest` exécutés sur JVM ; les runners opt-in sont exclus par filtre.

- [ ] **Step 4: Vérifier l'intégration au `check` racine**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL` et les tests de `:kalligraphie:e2e:jvmTest` apparaissent dans l'exécution. Sur un hôte non macOS, les cibles natives ne s'exécutent pas (comportement existant, cf. `conformance`).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/e2e/src/commonTest/kotlin/org/graphiks/kalligraphie/e2e/GoldenHarnessTest.kt
git commit -m "test(e2e): self-validate the golden harness and wire it into check"
```

---

## Self-Review (à exécuter par l'auteur du plan avant remise)

**1. Couverture du spec :**

| Section du spec | Tâche(s) |
| --- | --- |
| §3 Module + plugin non publié + `explicitApi` | Task 1 |
| §3 Dépendances + ressources + jvmArgs | Task 1 |
| §3 Tâches `updateE2eGolden` / `e2eGoldenDumps` exclues de `check` | Task 1, 8, 9 |
| §4 `GoldenScene` / `GoldenSceneFamily` | Task 3 |
| §4 `GoldenImage` / `PixelFormat` | Task 3 |
| §4 `GoldenFingerprint` / `GoldenManifest` | Task 4, 5 |
| §4 `GoldenComparison` | Task 6 |
| §4 Catalogue + renderer JVM | Task 7 (avec refinement #1) |
| §5 Canonicalisation + manifest TSV + garde version | Task 3, 5 |
| §6 Comparaison exacte + verdicts + codes | Task 4, 6, 8 |
| §10 Bloquant via `check` | Task 10 |
| §11 Auto-validation du harnais | Task 10 (+ tests de Tasks 2–6) |
| §12 Invariants (non publié, hors graphe consommateur) | Task 1 (convention `kmp-library`) |

Non couvert volontairement en P1 : §7 frontière journeys, §8 absorption/fixtures, §9 P2–P4. Ce sont des plans distincts.

**2. Placeholder scan :** aucun `TODO`/`TBD`, aucun brouillon, aucune valeur inventée. Les trois vecteurs SHA-256 sont normatifs (vide, `"abc"`, message de 71 octets). Les dimensions `43 × 45` et le hash de la scène smoke sont repris tels quels de `raster-cpu`'s `OutlineConformanceTest` et vérifiés à Task 8 étape 5.

**3. Cohérence des types :** `GoldenScene(id, family, width, height, tags)`, `GoldenImage.alpha8/rgba8(width, height, pixels)`, `GoldenFingerprint.of(scene, image)`, `GoldenManifest.of/serialize/parse`, `GoldenVerifier.verify(scenes, rendered, manifest)`, `GoldenRenderOutcome.Rendered/Refused`, `GoldenImageDiff.firstDifference(expected, actual)`, `GoldenDumpWriter.encode(image)` — utilisés de façon identique dans toutes les tâches.

**4. Corrections faites pendant la revue :**
- Ajout de `e2e.mismatch` et `e2e.manifest-malformed` (refinement #3) — à reporter dans le spec lors de P4.
- `GoldenImageDiff` extrait de `GoldenComparison` pour rester honnête sur l'impossibilité de localiser un octet depuis un manifest à empreinte seule.
- Ressources de test branchées en pont temporaire (P1) avant la bascule `test-fixtures` (P2).
- Garde d'overflow de `GoldenImage` corrigé et sémantique de `GoldenScene.width/height` unifiée puis assérée par le vérificateur (refinement #4).

---

## Execution Handoff

Après sauvegarde : choisir l'exécution (subagent-driven recommandé, ou inline via `executing-plans`).
