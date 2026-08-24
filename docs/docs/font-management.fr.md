# Gestion des polices

Kalligraphie fournit actuellement une pile OpenType limitée à la JVM. Elle est
conçue pour le traitement déterministe des polices et leur planification ; ce
n’est pas un backend de rendu multiplateforme.

## Pris en charge sur la JVM

- Chargement de données de police depuis des bytes et des fichiers.
- Provenance déterministe des sources.
- Parsing (analyse) SFNT/OpenType.
- Mise à l’échelle des polices.
- Traitement des données Unicode.
- Shaping (façonnage du texte) et planification des glyphes.

La planification des glyphes produit des informations indépendantes du
renderer (moteur de rendu). Elle n’implique ni upload GPU ni prise en charge
complète du rendu de texte.

## Non pris en charge

La pile actuelle ne fournit pas :

- L’exécution Android ou iOS.
- Des contrats de texte GPU ou l’upload d’atlas GPU.
- Des bridges (ponts) de shaping natifs.
- Le fallback (repli) automatique vers les polices système.
- Une promesse complète de prise en charge des emoji ou du rendu.

Android et iOS nécessitent des implémentations et des tests de plateforme
distincts. Les intégrations GPU et natives restent différées jusqu’à ce qu’un
renderer ou une frontière de plateforme les justifie.
