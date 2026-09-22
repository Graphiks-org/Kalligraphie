# Matrice de conformité

L'autorité de conformité portable de Kalligraphie est l'ensemble des tests
d'interface publique du module non publié `:kalligraphie:conformance`. Ce module
n'est pas un artefact consommateur : il déclare la surface de capacités portables
de chaque plateforme de référence et exerce directement la façade publique
(facade).

Cette page publie la matrice résultante : quelles capacités portables chaque
plateforme de référence déclare, quels tests portent l'autorité de conformité et
sur quelles plateformes ils s'exécutent, pourquoi aucune tolérance numérique
n'est requise aujourd'hui, et ce qui reste hors périmètre.

## Déclaration des capacités

`PortableCapability` énumère les capacités portables dont la disponibilité doit
être déclarée explicitement : `UNICODE_ANALYSIS`, `SHAPING`, `END_TO_END_LAYOUT`
et `GLYPH_REPRESENTATION_VARIANTS`. Chaque plateforme retourne une
`PortableCapabilityIdentity` depuis la fonction `expect`/`actual`
`currentPortableCapabilityIdentity()`, construite à partir d'une
`CapabilityDeclaration` par capacité (`capability`, `available`, `profileId`).

La disponibilité d'une capacité est déclarée, jamais déduite de la compilation.
`PortableCapabilityIdentity` valide que chaque capacité est déclarée exactement
une fois et qu'aucune n'est omise, puis expose `presenceOf(capability)` pour la
disponibilité déclarée et `absenceDiagnostic(capability)` pour le diagnostic
déterministe
`CAPABILITY_ABSENCE_DIAGNOSTIC_CODE = "conformance.portable-capability-absent"`,
retourné lorsque — et seulement lorsque — la capacité est indisponible.

## Matrice des capacités par plateforme

| Plateforme | Analyse Unicode | Shaping (façonnage) | Mise en page de bout en bout (end-to-end layout) | Variantes de représentation des glyphes | Profil |
| --- | --- | --- | --- | --- | --- |
| JVM | Présente | Présent | Présente | Présentes | `jvm-reference` |
| iOS | Absente | Présent | Absente | Présentes | `absent` / `bundled-harfbuzz` / `portable-glyph` |
| Android | Absente | Présent | Absente | Présentes | `absent` / `bundled-harfbuzz` / `portable-glyph` |

Le JVM déclare la surface de capacités de référence complète. Android déclare le
façonnage (shaping) présent via le backend HarfBuzz embarqué (API 28+), et iOS
déclare le façonnage (shaping) présent via le backend HarfBuzz embarqué ; les deux
cibles mobiles déclarent l'analyse Unicode et la mise en page de bout en bout
`absent` ainsi que la route de représentation des glyphes présente. Le diagnostic
d'absence est émis pour chaque capacité absente, indépendamment du fait qu'un
appelant la requière (requires).

## Couverture des tests

Le décodage portable, l'annulation (cancellation) et la déclaration des capacités
sont des comportements portables plutôt que des capacités conditionnées : chaque
plateforme qui exécute la suite partagée les exerce, et les résultats observables
sont invariants d'une plateforme à l'autre.

| Test | Couverture observable | Plateformes exécutées |
| --- | --- | --- |
| `PortableDecodingConformanceTest` (`commonTest`) | Scalaires UTF-8 et largeurs source ; paire de substitution (surrogate pair) UTF-16 ; UTF-8 malformé → `U+FFFD` avec `text.malformed-utf8` ; une jointure (seam) de tranche (slice) qui coupe un scalaire lève une exception. | JVM, iOS |
| `CancellationConformanceTest` (`commonTest`) | Annulation préalable et en cours de parcours (traversal) → `Cancelled` ; limites de scalaires et d'unités source → `LimitExceeded` ; les issues non réussies ne portent aucun instantané (snapshot) partiel. | JVM, iOS |
| `PlatformCapabilityConformanceTest` (`commonTest`) | La matrice de capacités déclarée par plateforme ; le diagnostic d'absence exactement lorsqu'une capacité est indisponible ; le décodage s'exécute indépendamment des capacités. | JVM, iOS |
| `AndroidPortableConformanceTest` (`androidDeviceTest`) | Le même décodage de façade et la même annulation, ainsi que l'identité de capacités Android, sur un environnement d'exécution Android réel. | Android |
| `IosPortableConformanceTest` (`iosSimulatorArm64Test`) | Le même décodage de façade et la même annulation, ainsi que l'identité de capacités du simulateur iOS, sur l'environnement d'exécution du simulateur iOS. | iOS |

La suite partagée s'exécute depuis `commonTest` sur JVM et iOS.
`androidDeviceTest` n'hérite pas de `commonTest`, donc
`AndroidPortableConformanceTest` exerce directement la façade publique sur
Android plutôt que de réutiliser les tests partagés ; `iosSimulatorArm64Test`
hérite de `commonTest` et exécute en plus `IosPortableConformanceTest` sur
l'environnement d'exécution du simulateur.

### Commandes de vérification

```bash
./gradlew :kalligraphie:conformance:jvmTest
./gradlew :kalligraphie:conformance:iosSimulatorArm64Test
./gradlew :kalligraphie:conformance:connectedAndroidDeviceTest
```

Le test sur appareil Android s'exécute via `connectedAndroidDeviceTest` ou
l'appareil géré (managed device) `mediumPhone` déclaré. `iosArm64` est compilé
mais les tests s'exécutent sur `iosSimulatorArm64`. La tâche de l'appareil géré
`mediumPhone` est déclarée dans le build mais n'est pas encore câblée dans
l'intégration continue (CI).

## Tolérance numérique

Aucune tolérance numérique n'est requise. Le pipeline portable ne produit
actuellement que des résultats de décodage identiques bit à bit — scalaires
entiers, largeurs d'unités source et codes de diagnostic — donc chaque assertion
de conformité est exacte. Une tolérance ne sera introduite que lorsque de la
géométrie portable existera à comparer.

## Limites connues

- L'analyse Unicode portable et la mise en page de bout en bout appartiennent à
  des chantiers distincts. Android déclare le façonnage (shaping) présent via le
  backend HarfBuzz embarqué, qui exige l'API 28 ou ultérieure ; le socle Android
  partagé a été relevé de l'API 24 à l'API 28, un changement cassant délibéré
  pour les consommateurs API 24–27. iOS déclare le façonnage (shaping) présent
  via le backend HarfBuzz embarqué et l'analyse absente.
- La lecture et la rastérisation des polices ne font pas partie de ce module.
- `iosArm64` est compilé mais les tests s'exécutent sur `iosSimulatorArm64` ;
  l'exécution sur appareil physique n'est pas effectuée sur les runners
  (exécuteurs) hébergés.
- `androidDeviceTest` n'hérite pas de `commonTest` ; les tests sur appareil
  Android exercent directement la façade publique plutôt que la suite partagée.
- La tâche de l'appareil géré Gradle `mediumPhone` est déclarée dans le build
  mais n'est pas encore câblée dans l'intégration continue.
