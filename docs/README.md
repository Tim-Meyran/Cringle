# Dokumentation

| Dokument | Inhalt |
|---|---|
| [Architecture.md](Architecture.md) | Maßgebliche Architektur von Cringle: Plattform-Spezifikation (Teil A), Kotlin-Engine (Teil B), Roadmap mit Meilensteinen M0–M9 (Teil C). Deutsch. |
| [decisions.md](decisions.md) | Verbindliche Entscheidungen, die während der Entwicklung getroffen wurden. Sie haben bei Widersprüchen Vorrang vor den Vorschlägen (`[Zu bestätigen]`) in der Architektur. |
| [status.md](status.md) | Stand des Projekts: Meilensteine, offene Arbeit und Entscheidungen, die noch nicht in `decisions.md` stehen. Englisch. |
| [trust.md](trust.md) | Identität, Trust Store und mTLS der Komponenten, Enrollment, `cringle trust`. Englisch. |
| [cli.md](cli.md) | Die Kommandozeile `cringle`: Verbindung mit gepinntem TLS, Befehle. Englisch. |
| [management-server.md](management-server.md) | ManagementServer: Zustand, Deployment, Kanäle und Trust. Englisch. |
| [block-data.md](block-data.md) | Datenordner eines Blocks (`BlockContext.dataDirectory`), unabhängig von Fabric-ID und Version. Englisch. |
| [ci.md](ci.md) | Die GitHub-Workflows und die Self-hosted-Runner (Labels, was sie bereitstellen müssen). Englisch. |
| [users.md](users.md) | Benutzer, Gruppen, Rollen und ihre Gültigkeitsbereiche (Scopes). Englisch. |
| [webui.md](webui.md) | WebUI des ManagementServers (htmx, Alpine.js, Drawflow): Start, Login, Sicherheit, Aufbau von Seiten. Englisch. |
| [daemon-service.md](daemon-service.md) | Daemon als Dienst, Engine-Identität und Trust-Dateien. Englisch. |
| [gradle-plugin.md](gradle-plugin.md) | Gradle-Plugins `cringle.plugin` und `cringle.project`, `cringlePublish`. Englisch. |
| [running-from-the-ide.md](running-from-the-ide.md) | Gradle-Tasks `runDaemon`, `runManagementServer`, `runEngine`, `runCli` zum Starten und Debuggen in der IDE. Englisch. |
| [releasing.md](releasing.md) | Release-Archive und Installer. Englisch. |
| [logging.md](logging.md) | Einheitliches Logging der Kotlin-Prozesse (Daemon, Engine, Management-Server). |
| [observability.md](observability.md) | Logs, metrics, heartbeat, data warehouse, recording and retention. Englisch. |
| [shared-services.md](shared-services.md) | Shared services: `provides`, service tethers, `cringle bind`, failover, two machines. Englisch. |
| [updating.md](updating.md) | Self-update of an installed Cringle: `cringle self-update`, rollback, `--allow-major`. Englisch. |
| [getting-started.md](getting-started.md) | From install to a running application: log in, plugin, project, deploy, look at it. Englisch. |
| [operations.md](operations.md) | Operating a site: processes and folders, settings and ports, more machines, logs, updates, backups, certificates, troubleshooting. Englisch. |
| [security.md](security.md) | Trust, TLS, tokens and scopes, the web interface, code that runs; what to do on a new installation. Englisch. |
| [migration-guide.md](migration-guide.md) | Versions of data: processors, backups, failure, rollback. Englisch. |
| [debugging.md](debugging.md) | Remote debugging of a fabric: breakpoints on tethers, held values, resume, step. Englisch. |
| [acceptance-1.0.md](acceptance-1.0.md) | Chapter 26 of the architecture against the code: what is done, changed, partly done or open. Englisch. |

Nicht in `docs/`:

- **Aufgaben** sind GitHub Issues (Label `agent-task`), gruppiert nach den Meilensteinen M0–M9. Übersicht: `gh issue list --label agent-task` oder die Meilenstein-Ansicht auf GitHub.
- **Arbeitsweise für Agenten und Mitwirkende:** [`../AGENTS.md`](../AGENTS.md).
- **Protokoll und Spezifikationen** (sprachunabhängig): [`../proto/`](../proto), [`../spec/`](../spec).
