# Mesure de ligne éditable

Kalligraphie fournit un `runner` (programme d’exécution) JVM `opt-in`
(à activation explicite) pour le parcours consommateur public d’une ligne
éditable. Il émet des observations non publiées issues d’une exécution
explicitement configurée ; ce n’est pas un `benchmark` (mesure comparative de
référence) et il ne contient aucun seuil de latence ni aucune affirmation de
performance. Cet outil appartient aux sources de test, reste exclu de `jvmTest`
et de `check` par défaut, et s’exécute uniquement avec la tâche dédiée
`:kalligraphie:editableLineMeasurement`.

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

Les profils de décodage sont sans état : leurs stockages et fragments empruntés
sont préparés hors chronométrage, et aucun cache (mémoire de réutilisation)
conservé n’est revendiqué. Le
programme n’accepte que des décodages et des mises en page complets et réussis.
Ses oracles fixes couvrent les valeurs scalaires, les frontières source,
l’absence attendue de diagnostic, la partition complète des runs, les deux
directions LTR et RTL, les identifiants et avances de glyphes DejaVu contrôlés
indépendamment avec `hb-shape`, la provenance directe des glyphes et tous les
carets aux frontières de scalaires.

## Invocation légère reproductible

L’activation explicite et un chemin Markdown absolu hors du dépôt sont
obligatoires. L’option `--rerun-tasks` (réexécution forcée des tâches) empêche
Gradle de réutiliser un résultat antérieur lorsque les variables d’environnement
changent. Un échauffement et deux itérations forment un `smoke run` (exécution
légère de validation) de tout le programme, mais ne produisent pas
d’observations adaptées à une comparaison :

```bash
env \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT=true \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_WARMUP=1 \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_ITERATIONS=2 \
  KALLIGRAPHIE_EDITABLE_LINE_MEASUREMENT_OUTPUT=/tmp/kalligraphie-editable-line.md \
  ./gradlew :kalligraphie:editableLineMeasurement \
  --rerun-tasks --no-daemon
```

Utilisez des nombres positifs plus élevés uniquement pour consigner une
observation locale volontaire. Un rapport reste lié à l’environnement qu’il
enregistre et ne constitue pas un objectif de performance du projet.

## Contenu et limites du rapport

Le rapport Markdown enregistre :

- le commit Git, la machine, le système d’exploitation, l’architecture et la
  JVM ;
- les versions des données Unicode, d’ICU4J et de HarfBuzz embarqué ;
- le SHA-256 de la fonte DejaVu versionnée ;
- l’identité, la description, l’encodage, les tailles en unités source et en
  scalaires, ainsi que la fragmentation exacte du corpus ;
- la frontière chronométrée et l’état froid, chaud ou sans état de chaque
  profil ;
- le nombre d’échauffements et d’itérations mesurées ;
- les latences p50, p95 et p99 selon `nearest-rank` (rang le plus proche), en
  nanosecondes ;
- la moyenne des octets alloués par le thread (fil d’exécution) mesuré lorsque
  la JVM expose ce compteur ;
- la variation signée du `heap` (tas mémoire) utilisé, après deux demandes
  explicites `System.gc()` avant et après chaque profil et sans demande de GC
  (ramasse-miettes) entre les itérations mesurées ;
- la mémoire native explicitement marquée `unavailable` (indisponible), car le
  parcours JVM public n’expose aucune frontière fiable pour les octets natifs
  conservés.

Les champs d’allocation et de tas décrivent ce petit programme, pas une
comptabilité universelle du processus ou du cache. La variation signée du tas
peut être négative après la politique GC documentée. La mémoire native n’est
pas estimée. Le rapport ne contient aucun compteur d’appels, aucun compteur
interne de cache, aucun seuil de succès ni aucune mesure de rendu.
