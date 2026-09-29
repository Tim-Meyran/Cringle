# Cringle – Entscheidungen

Verbindliche Entscheidungen während der Entwicklung. Sie ergänzen `Architecture.md` und haben bei Widersprüchen Vorrang vor den dortigen Vorschlägen (**[Zu bestätigen]**). Stand: 2026-09-28. Die Entscheidungen sind in `Architecture.md` v0.3 eingearbeitet.

## Technik

- **Sprache und Build:** Kotlin mit Gradle, JDK 21.
- **Coroutinen** sind erlaubt und werden für die Contract-API genutzt. Die engine-interne Tether-Kommunikation läuft asynchron.
- **Schema-Sprache:** ein eigenes Cringle-Format, keine Wiederverwendung von JSON Schema, Protobuf o. Ä. als Definitionssprache.
- **Classloading:** child-first, ein Classloader pro Provider und Fabric-Instanz.

## Driver

- Jeder Driver existiert **genau einmal pro Engine**. Blocks nutzen die entsprechenden Instanzen der Engine.
- Blocks können über einen Driver Einträge ins DWH schreiben.

## Isolation

- **Kind-JVM für untrusted Blocks wird zunächst nicht umgesetzt** (Issue #18 ist zurückgestellt). Die Isolationsregel wird trotzdem umgesetzt und ist fail-closed: Ergibt sie `process`, wird der Block nicht in-process gestartet, sondern der Start schlägt fehl.
- **Isolationsregel:** Es gewinnt die strengere von zwei Angaben: (1) dem Vertrauensstatus des Plugins (`trusted`/`untrusted`, zentral im Repository vom Betreiber gesetzt) und (2) dem Wunsch in der Block Config (vom Blueprint-Autor pro Block). Die Stufen sind aufsteigend geordnet von `shared` (normaler Fabric-Thread) bis `process` (eigener Kind-JVM-Prozess). Der Autor kann verschärfen, nie lockern.

## Repository und Vertrauen

- Welches Repository für den Vertrauensstatus eines Plugins zuständig ist, wenn Engines in einem anderen Maschinenpark laufen, regelt der **ManagementServer**. Er kennt die Zuordnung Maschinenpark → Repository, ermittelt den Vertrauensstatus dort und gibt ihn beim Deploy an die Engine weiter.

## Nutzerverwaltung

- Eine **frühe, einfache Nutzerverwaltung** kommt auf die frühe Roadmap (Issue #22). Während der Entwicklung authentifizieren sich Nutzer mit **Tokens**.

## Roadmap

- Ein einfaches **Cringle-Gradle-Plugin** für Plugin- und Project-Autoren gehört in die frühe Roadmap (Issue #21), ebenso ein Testkit (Issue #23).

## Sonstiges

- `Tethera.png` darf in `Cringle.png` umbenannt werden. Das Diagramm ist noch nicht angepasst (siehe Architecture.md Kapitel 29).
- **Repository-Struktur:** `proto/`, `spec/`, `kotlin/` (Gradle-Multi-Modul, inkl. `testkit` und `gradle-plugin`), `docs/` (siehe Issue 001).

- **CI-Anbieter:** GitHub (GitHub Actions).
- **Lizenz:** Apache License 2.0 (`LICENSE`, `NOTICE`). Jede Quelldatei trägt den SPDX-Header `Apache-2.0`. Neue Abhängigkeiten nur mit Apache-2.0-, MIT-, BSD- oder EPL-2.0-Lizenz, alles andere nach Rückfrage.
- **Aufgaben auf GitHub:** Alle Umsetzungsaufgaben sind GitHub Issues (Label `agent-task`) mit Meilensteinen M0–M9. Abhängigkeiten stehen in der Zeile `**Depends on:**` und als „blocked by“-Beziehung. Es gibt keine Issue-Dateien mehr im Repository.
- **Automatischer Merge:** Ein Pull Request wird per Auto-Merge (Squash) gemergt, sobald alle erforderlichen CI-Checks grün sind. Branch Protection auf `master` erzwingt einen Pull Request und beide CI-Checks (Linux und Windows), auch für Administratoren. Eingerichtet wird das einmalig mit `scripts/setup-github-repo.py`.
- **Arbeit mit LLM-Agenten (Details in `AGENTS.md`):** Agenten arbeiten Issues ab, ein Issue pro Branch (`issue/<Nummer>-slug`) und Pull Request. Sie erstellen den Pull Request selbst mit `gh` und aktivieren Auto-Merge; `scripts/next-issue.py` listet abholbereite Issues.
