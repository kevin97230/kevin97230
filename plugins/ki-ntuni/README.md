# Plugin KI-NTUNI

Connecte Claude Code au serveur MCP KI-NTUNI du Centre TEORA.
Claude pourra **lire** les dossiers et **rédiger des brouillons**, rien d'autre.

## Contenu

| Élément | Rôle |
|---|---|
| `.mcp.json` | Serveur MCP HTTP `ki-ntuni` (URL sans jeton, authentification OAuth) |
| `skills/rediger-dossier` | Méthode de rédaction : `list_dossiers` → `get_dossier_context` → sources → `save_section_draft` |
| `commands/dossier.md` | Commande `/ki-ntuni:dossier [dossier] [section]` |

## Installation (Claude Code)

```
/plugin marketplace add kevin97230/kevin97230
/plugin install ki-ntuni@centre-teora
```

Puis `/mcp` → `ki-ntuni` → **Authenticate** : la page KI-NTUNI s'ouvre et explique les autorisations → **Autoriser**.

## Alternative : connecteur claude.ai / app Claude

1. Claude → Paramètres → Connecteurs → Ajouter un connecteur personnalisé.
2. Nom `KI-NTUNI`, URL : `https://zkxluotjzqzqvgynguza.supabase.co/functions/v1/mcp`
3. Connecter → page KI-NTUNI → Autoriser.

## Outils exposés

`list_dossiers`, `get_dossier_context`, `get_kituni_entries`, `search_courses`, `get_course_chunk`, `save_section_draft`, `report_lacune`.
