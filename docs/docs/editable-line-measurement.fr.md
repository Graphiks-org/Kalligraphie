# Mesure de ligne éditable

Kalligraphie mesure le parcours consommateur public d’une ligne éditable dans le
module non publié `:kalligraphie:bench`. Il émet des observations non publiées
issues d’une exécution explicitement configurée ; ce n’est pas un `benchmark`
(mesure comparative de référence) et il ne contient aucun seuil de latence ni
aucune affirmation de performance. La mesure est `opt-in` (à activation
explicite) : elle s’exécute uniquement avec la tâche `jvmBenchmarkBenchmark` du
module et jamais dans le cadre de `check`.

Les profils de ligne éditable appartiennent à la moitié « paragraphe » du
module. Ils composent le texte à travers la façade de paragraphe, qui exige la
capacité `END_TO_END_LAYOUT` ; toutes les plateformes déclarent cette capacité
depuis que les façades et l’analyse Unicode portable sont passées dans
`commonMain`, donc ces profils s’exécutent sur la JVM, sur Android et sur le
simulateur iOS. Le seul profil qu’une plateforme ne peut pas servir est nommé
comme différé, au lieu que le module publie silencieusement moins de profils.

Le corpus de texte réel fixe est `Edit سلام 😀 café`. Les profils de décodage
UTF-8 et UTF-16 empruntent un stockage immuable appartenant à l’application au
moyen de quatre `slices` (fragments de source) :

- UTF-8 : 24 octets découpés en `[5, 9, 5, 5]` ;
- UTF-16 : 17 unités de code découpées en `[5, 5, 3, 4]`.

Chaque jointure correspond à une frontière complète de scalaire Unicode. Les
profils de mise en page utilisent le même texte de 16 scalaires comme une ligne
à base LTR, avec du latin, de l’arabe, un emoji et des runs BiDi (séquences
bidirectionnelles). Ils façonnent la fonte DejaVu Sans versionnée dans
`kalligraphie/shaping/src/jvmTest/resources/fonts/dejavu/DejaVuSans.ttf` avec le
backend HarfBuzz (moteur de façonnage) embarqué. La lecture du fichier de fonte
et le décodage du texte précèdent la mesure de mise en page. Aucun renderer
(moteur de rendu), rasterizer (convertisseur en pixels), calcul GPU ni parcours
de matérialisation de glyphe n’est mesuré.

## Profils et frontières chronométrées

Le rapport enregistre ces profils dans l’ordre suivant :

1. `BorrowedFragmentedUtf8Decode` démarre juste avant l’appel public
   `decodeUtf8(...)` et se termine après la consommation des scalaires, des
   plages source et des diagnostics, ainsi que leur contrôle par des oracles
   littéraux indépendants.
2. `BorrowedFragmentedUtf16Decode` applique la même frontière à
   `decodeUtf16(...)`.
3. `ColdMixedBidiLine` est le profil `cold` (état froid). Il démarre avant la
   capture en mémoire du catalogue embarqué et l’ouverture de la session. Il
   inclut la résolution de face, l’instanciation de fonte, la création de la
   requête, la mise en page, la consommation du résultat public et la fermeture
   de la session. Chaque échauffement et chaque mesure préparent un nouveau
   catalogue, une nouvelle instance et une nouvelle session.
4. `WarmMixedBidiLine` est le profil `warm` (état chaud). Il prépare une instance
   de fonte, ouvre une session et l’amorce par une mise en page réussie hors
   chronométrage. Le `warmup` (échauffement) et les itérations mesurées
   réutilisent cette session. Le chronomètre entoure `session.layout(...)` et la
   consommation des plages de ligne et de run, des glyphes, des carets (positions
   de curseur), des diagnostics, de la provenance et des avances ; la préparation
   et la fermeture sont exclues.

Les profils de décodage sont chauds et sans état : leurs stockages et fragments
empruntés sont préparés hors chronométrage, leur échauffement configuré précède
les mesures, et aucun cache (mémoire de réutilisation) conservé n’est revendiqué. Le
programme n’accepte que des décodages et des mises en page complets et réussis.
Ses oracles fixes couvrent les valeurs scalaires, les frontières source,
l’absence attendue de diagnostic, la partition complète des runs, les deux
directions LTR et RTL, les identifiants et avances de glyphes DejaVu contrôlés
indépendamment avec `hb-shape` 14.4.0, la provenance directe des glyphes et tous les
carets aux frontières de scalaires.

## Exécution reproductible

Le module mesure avec kotlinx-benchmark (JMH sur la JVM) : échauffement,
itérations, durée d’itération d’une seconde et format JSON du rapport viennent de
sa configuration de `benchmark`, pas de variables d’environnement. Une seule
commande mesure tous les profils que la plateforme sert — les profils de ligne
éditable sont quatre des trente-sept que le module enregistre, et le simulateur
iOS en exécute trente-six. Résultats et compteurs
sont écrits sous le répertoire `build` du module, que git ignore :

```bash
./gradlew :kalligraphie:bench:jvmBenchmarkBenchmark
```

`./gradlew :kalligraphie:bench:measurementReport` joint ensuite cette exécution
aux autres plateformes dans `build/bench/report-jvm.md` et un comparatif. Aucune
des deux tâches n’appartient à `check` : une mesure se demande, elle ne se
planifie pas, et aucun test fonctionnel n’affirme une durée.

Un rapport reste lié à l’environnement qu’il enregistre et ne constitue pas un
objectif de performance du projet.

## Contenu et limites du rapport

Le rapport Markdown enregistre, pour l’exécution entière :

- le commit mesuré, la machine, le système d’exploitation, le runtime et la
  politique de cache/GC ;
- l’identité et la description du corpus, ainsi que le SHA-256 de chaque fixture
  réellement lue ;
- et, pour chaque profil : sa route, sa frontière chronométrée, son état de
  cache, ses nombres d’échauffements et d’itérations mesurées, les latences p50,
  p95 et p99 selon `nearest-rank` (rang le plus proche) en nanosecondes, les
  compteurs prouvant ce que l’opération chronométrée a consommé, et des valeurs
  de mémoire étiquetées `measured`, `estimated` ou `unavailable`.

Les profils de ligne éditable publient les scalaires, glyphes et mises en page
qu’ils ont consommés à côté de la latence, car un programme de mesure ne peut pas
distinguer une opération rapide d’une opération qui n’a rien fait : le module
refuse un profil dont les compteurs sont absents ou vides. Les valeurs
d’allocation et de tas décrivent ce petit programme, pas une comptabilité
universelle du processus ou du cache. La mémoire native est publiée
`unavailable` avec sa raison, jamais estimée ni présentée comme une mesure. Le
rapport ne contient aucun seuil de succès ni aucune mesure de rendu.
