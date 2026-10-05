# Dokumentation

| Dokument | Inhalt |
|---|---|
| [Architecture.md](Architecture.md) | Maßgebliche Architektur von Cringle: Plattform-Spezifikation (Teil A), Kotlin-Engine (Teil B), Roadmap mit Meilensteinen M0–M9 (Teil C). Deutsch. |
| [decisions.md](decisions.md) | Verbindliche Entscheidungen, die während der Entwicklung getroffen wurden. Sie haben bei Widersprüchen Vorrang vor den Vorschlägen (`[Zu bestätigen]`) in der Architektur. |
| [logging.md](logging.md) | Einheitliches Logging der Kotlin-Prozesse (Daemon, Engine, Management-Server). |

Nicht in `docs/`:

- **Aufgaben** sind GitHub Issues (Label `agent-task`), gruppiert nach den Meilensteinen M0–M9. Übersicht: `gh issue list --label agent-task` oder die Meilenstein-Ansicht auf GitHub.
- **Arbeitsweise für Agenten und Mitwirkende:** [`../AGENTS.md`](../AGENTS.md).
- **Protokoll und Spezifikationen** (sprachunabhängig): [`../proto/`](../proto), [`../spec/`](../spec).
