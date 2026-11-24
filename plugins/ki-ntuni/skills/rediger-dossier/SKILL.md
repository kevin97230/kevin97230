---
name: rediger-dossier
description: Rédiger une section de dossier Ki-Tuni du Centre TEORA avec le serveur MCP KI-NTUNI. À utiliser quand l'utilisateur veut travailler sur un dossier Ki-Tuni, rédiger ou compléter une section, ou chercher dans les cours.
---

# Rédiger un dossier Ki-Tuni

Le serveur KI-NTUNI n'autorise que la **lecture** et l'**enregistrement de brouillons**. Rien n'est publié ni validé automatiquement.

## Déroulé

1. `list_dossiers` — lister les dossiers et demander à l'utilisateur lequel traiter.
2. `get_dossier_context` — charger le thème, les consignes et l'état des sections du dossier choisi.
3. Collecter la matière **uniquement** via :
   - `get_kituni_entries` (entrées Ki-Tuni du dossier)
   - `search_courses` puis `get_course_chunk` (extraits de cours)
4. Rédiger la section, puis l'enregistrer avec `save_section_draft`.
5. S'il manque une information nécessaire, ne pas combler : appeler `report_lacune` en décrivant précisément ce qui manque.

## Règles

- Ne jamais calculer ni inventer un chiffre, une date, une citation ou un fait.
- N'utiliser que le thème fourni et les résultats des outils ci-dessus ; citer la source (entrée ou extrait de cours) de chaque affirmation.
- **Un seul dossier par conversation.** Si l'utilisateur veut en changer, lui proposer d'ouvrir une nouvelle conversation.
- Rédiger en français, sauf demande contraire.
