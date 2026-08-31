# Mesure du layout incrémental

Kalligraphie fournit un point d’entrée JVM opt-in (activé explicitement) pour
une mesure engine-only (moteur uniquement) du layout incrémental. Cet outil
reste dans les sources de test : ce n’est ni un test fonctionnel de latence, ni
un résultat de benchmark (mesure comparative) publié. Il exécute la vraie
`JvmIncrementalParagraphLayoutSession`, l’analyse ICU, HarfBuzz embarqué et les
fixtures (données de test fixes) de fontes GDEF et Amiri versionnées dans le
dépôt.

L’intervalle chronométré commence immédiatement avant
`session.layout(...)`. Les snapshots (instantanés immuables), catalogues de
fontes, deltas et requêtes sont construits avant le départ du chronomètre. Il se
termine seulement après vérification de la couverture complète demandée et
consommation des lignes, runs (séquences typographiques), glyphes, carets
(repères d’insertion), diagnostics et état du suffixe. Le scheduling
(ordonnancement applicatif) et le renderer (moteur de rendu) sont exclus.

## Profils

- `InteractiveEdit` alterne un remplacement préparé entre `cafe` et un emoji
  dans une seule session, en réutilisant le dernier état publié.
- `ViewportLayout` alterne deux plages de viewport (zone visible) avec un
  overscan (marge de lignes complètes hors zone visible) de deux lignes, sur le
  même snapshot immuable.
- `Cancellation` demande tout le corpus et déclenche l’annulation coopérative
  après un nombre fixe de vérifications du token (jeton d’annulation). Seul un
  résultat annulé typé est accepté ; une couverture partielle ne compte jamais
  comme succès rapide.

Chaque profil ouvre une nouvelle session. Une initialisation non chronométrée,
si nécessaire, puis le warmup (préchauffage) configuré précèdent les itérations
mesurées. L’état du cache (mémoire interne de réutilisation) mesuré est donc
indiqué comme chaud. Deux demandes `System.gc()` sont effectuées avant et après
chaque profil, jamais entre les itérations mesurées.

## Exécution reproductible

Exécutez explicitement le point d’entrée JUnit opt-in. L’option
`--rerun-tasks` force Gradle à relancer la mesure même si seules les variables
d’environnement ont changé. Conservez le rapport produit hors du dépôt :

```bash
rtk env \
  KALLIGRAPHIE_MEASUREMENT=true \
  KALLIGRAPHIE_MEASUREMENT_WARMUP=5 \
  KALLIGRAPHIE_MEASUREMENT_ITERATIONS=20 \
  KALLIGRAPHIE_MEASUREMENT_OUTPUT=/tmp/kalligraphie-incremental-layout.md \
  ./gradlew :kalligraphie:jvmTest \
  --tests org.graphiks.kalligraphie.IncrementalLayoutBenchmarkTest.runConfiguredMeasurementProfiles \
  --rerun-tasks --no-daemon
```

Pour un smoke run (exécution de fumée, courte vérification du parcours) des
trois profils, utilisez un warmup de `1` et `2` itérations. Un tel rapport ne
permet aucune revendication de cible de latence.

## Champs du rapport

Le rapport Markdown contient :

- le commit Git (révision) mesuré, la machine, l’OS, l’architecture et la JVM ;
- les versions Unicode et HarfBuzz ;
- le hash SHA-256 (empreinte cryptographique) de chaque fonte ;
- l’identité, la description et les nombres de scalaires et paragraphes du
  corpus ;
- la couverture, l’overscan, l’état du cache, la politique de GC (ramasse-miettes),
  le warmup et le nombre d’itérations ;
- les percentiles nearest-rank (rang supérieur) p50, p95 et p99 en nanosecondes ;
- les octets alloués par thread (fil d’exécution) si la JVM fournit ce compteur ;
- la variation signée du tas JVM après les demandes de GC documentées ;
- un état explicitement indisponible pour la mémoire native retenue, que le
  backend (composant d’exécution) n’expose pas ;
- le délai p95 de retour après annulation pour `Cancellation` ;
- les moyennes de scalaires, lignes et paragraphes rematérialisés pour les
  profils réussis, ou un état indisponible lorsque l’annulation masque
  volontairement les diagnostics partiels.

Utilisez ce modèle lors de la copie d’un résultat dans une description de
revue :

```text
Commit / machine / OS / JVM :
Unicode / HarfBuzz / SHA-256 des fontes :
Corpus / couverture / overscan :
État du cache / warmup / itérations / politique GC :
p50 / p95 / p99 :
Allocations :
Mémoire JVM retenue :
Mémoire native retenue :
Délai d’annulation :
Texte / lignes / paragraphes rematérialisés :
Limites et mesures indisponibles :
```

Les champs d’allocation et de mémoire retenue décrivent ce runner (programme
de mesure) réduit ; ils ne constituent pas une comptabilité universelle de la
mémoire JVM ou native. La variation du tas peut être négative après GC. Les
octets natifs retenus restent indisponibles tant que le backend ne fournit pas
une frontière de comptabilité fiable.

## Interprétation et limites

Le runner publie des observations, sans seuil de réussite ou d’échec. Un
résultat ne soutient une revendication de performance que si son environnement
complet et sa politique de reference profile (profil de référence) sont
identifiés séparément. Les contrôles fonctionnels Gradle ne vérifient jamais
le temps écoulé.

La session actuelle ne réutilise un checkpoint (point de reprise) que s’il
provient de sa publication courante. La sélection exacte d’une ligne peut
examiner de manière conservative (prudente) jusqu’à la prochaine frontière
UAX #14 obligatoire, ou jusqu’à la fin du document lorsqu’il n’en reste aucune.
Les diagnostics de rematérialisation et la latence peuvent donc croître pour un
long paragraphe avec soft wrap (retour à la ligne automatique) ; la correction
reste prioritaire.

