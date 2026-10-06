Cringle – Architektur

Status: Entwurf v0.3
Grundlage: Architekturübersicht (Tethera.png, Stand vor der Umbenennung) und Design-Diskussion
Vorgängername: Das Projekt hieß bis zur Umbenennung *Tethera*. Der Begriff Tether bleibt als Fachbegriff erhalten.

> Lesehinweise
> - Der Aufbau folgt der Entscheidung, die sprachunabhängige Plattform-Spezifikation (Teil A) strikt von der Kotlin-Engine-Implementierung (Teil B) zu trennen. Teil C enthält die Roadmap.
> - [Zu bestätigen] markiert Punkte, die aus einem Vorschlag entstanden sind und noch nicht ausdrücklich bestätigt wurden.
> - [Offen] markiert bewusst geparkte Themen. Sie sind in Kapitel 30 gesammelt.
> - Alles, was nicht so markiert ist, ist eine getroffene Entscheidung.
> - Entscheidungen, die während der Entwicklung getroffen werden, sind hier eingearbeitet und zusätzlich in decisions.md gesammelt. Die Umsetzungsaufgaben liegen als GitHub Issues im Repository, gruppiert nach den Meilensteinen M0–M9.
> - Technische Basis: Kotlin, Gradle, JDK 21.

---

1. Einleitung und Ziele

1.1 Was Cringle ist

Cringle ist ein Framework, in dem Anwendungen aus Blocks zusammengesetzt werden, die über Tethers miteinander kommunizieren. Eine Anwendung wird nicht als monolithisches Programm geschrieben, sondern als Blueprint aus fertigen Blocks zusammengesteckt, in einem Project versioniert und auf Engines ausgeführt, die über mehrere Maschinen verteilt sein können.

Typische Anwendungsfälle:

- eine Website mit REST-Endpoints, deren Nutzer über die Cringle-Nutzerverwaltung authentifiziert werden
- ein gekapselter externer Dienst wie Google Calendar, der als Shared Service von mehreren Anwendungen genutzt wird
- Anbindung von Geräten über serielle Schnittstellen oder TCP
- verteilte Verarbeitungsketten über mehrere Maschinen hinweg

1.2 Leitprinzipien

1. Plattformunabhängigkeit. Das nach außen sichtbare Verhalten der Cringle-Plattform ist überall gleich, unabhängig von Sprache und Betriebssystem der Engine. Was Protokoll ist, steht in Teil A.
2. Kotlin zuerst. Engines werden zunächst nur in Kotlin (JVM) entwickelt. Weitere Implementierungen folgen später und müssen dieselben Isolationskonzepte mit ihren eigenen Mitteln umsetzen.
3. Trennung von Vertrag und Implementierung. JVM-spezifische Mechanismen wie Classloading sind niemals Teil des Protokolls.
4. Vertrauen ist explizit. Vertrauensbeziehungen entstehen durch einen manuellen Akt und werden über Zertifikate abgesichert. Es gibt keine automatische Aufnahme fremder Maschinen.
5. Der Blueprint-Autor arbeitet mit fertigen Blocks. Driver, Classloading, Serialisierung und Laufzeitdetails sind ihm verborgen.
6. Isolation ist Standard, nicht Option. Fabric-Instanzen teilen keinen Laufzeitzustand. Der Blueprint-Autor kann Isolation verschärfen, aber nie lockern.
7. Reproduzierbarkeit. Project-Versionen sind unveränderlich, Abhängigkeiten werden beim Deploy gelockt, jede Version trägt einen Hash.

1.3 Abgrenzung

Cringle ist kein Container-Orchestrator und kein Ersatz für Kubernetes. Die Verteilungseinheit ist die Fabric auf einer Engine, nicht der Container. Cringle bringt eigene Mechanismen für Trust, Discovery, Deployment und Observability mit, weil diese eng mit dem Block- und Tether-Modell verzahnt sind.

---

2. Glossar

| Begriff | Bedeutung |
|---|---|
| Block | Verarbeitungseinheit mit eigenem Lifecycle. Implementiert Geschäftslogik und nutzt ausschließlich Driver für die Interaktion mit der Außenwelt. |
| Port | Anschlusspunkt eines Blocks. Definiert die unterstützten Tether-Typen und Schemas. |
| VarArg-Port | Port, der als Liste umgesetzt wird. Die Anzahl wird beim Start des Blueprints festgelegt. |
| Tether | Abstrakte Datenverbindung zwischen zwei Blocks. Wird zur Laufzeit durch einen Driver realisiert. |
| Blueprint | Statische Definition einer Menge verbundener Blocks. Kann mehrfach instanziiert werden. Hat keine eigene Version. |
| Fabric | Laufzeit-Instanz eines Blueprints auf genau einer Engine. |
| Fabric Config | Deployment-Anweisung im Project. Legt fest, welche Blueprints wie oft und auf welchen logischen Rollen/Labels realisiert werden. |
| Block Config | Konfiguration eines einzelnen Blocks innerhalb eines Blueprints. |
| Project | Versionierte, unveränderliche Einheit mit einem oder mehreren Blueprints, Schemas, Fabric Config, Binaries und Dependencies. |
| Plugin | Versioniertes Paket mit BlockProvidern, Drivern, Binaries, Schemas und optionalen Update-/Downgrade-Processoren. |
| BlockProvider | Erzeugt Block-Instanzen und injiziert deren Driver. |
| Driver | Einheitliche Schnittstelle, über die Blocks mit der Außenwelt interagieren: Tether-Transport, Dateisystem, TCP, Serial, Logging, DWH, Nutzerverwaltung. Hat einen Lifecycle und ein Isolationslevel. Jeder Driver existiert genau einmal pro Engine. |
| Schema | Typsystem für komplexe Datentypen, die über Tethers übertragen werden. Über Namespace-IDs eindeutig auflösbar. |
| Binaries | Statische Ressourcen (z. B. HTML-Dateien), die ein Block zur Laufzeit braucht. |
| Engine | Laufzeitumgebung, die Fabrics ausführt. In der Kotlin-Implementierung ein eigener JVM-Prozess. |
| Daemon | Dauerhaft laufender Prozess pro Maschine. Legt Engines an und startet sie. |
| Router | Pro Maschine. Hält Registry, Trust Store und Nutzerverwaltung und kennt Remote Router. |
| Registry | Teil des Routers. Speichert, welche Fabrics auf welchen Engines laufen (Fabric Lookup). |
| ManagementServer | Zentrale Steuerinstanz für CLI und WebUI. Führt Deployments und Placement durch. |
| Repository | Eigenständiger Dienst für Projects, Plugins, Schemas und den Vertrauensstatus von Plugins. |
| DWH | Data Warehouse pro Engine für Tether-Nachrichten und andere komplexe Daten. |
| Shared Service | Eigenständig deployter Blueprint, den mehrere Fabrics und Projects über Tethers nutzen. |
| Placement | Auflösung logischer Rollen/Labels auf konkrete Engines zur Deploy-Zeit. |

---

Teil A – Cringle Platform Specification (sprachunabhängig)

3. Komponentenübersicht

3.1 Topologie

mermaid
flowchart TB
    CLI[CLI] -->|gRPC + mTLS| MS
    WebUI[WebUI] -->|REST| MS
    MS[ManagementServer] -->|gRPC + mTLS| REPO[(Repository)]

    subgraph M1[Maschine A]
        D1[Daemon]
        R1[Router<br/>Registry · Trust Store · Nutzerverwaltung]
        E1[Engine 1]
        E2[Engine N]
        D1 -. startet .-> E1
        D1 -. startet .-> E2
        E1 -->|registriert sich| R1
        E2 -->|registriert sich| R1
    end

    subgraph M2[Maschine B]
        D2[Daemon]
        R2[Router]
        E3[Engine 1]
        D2 -. startet .-> E3
        E3 --> R2
    end

    MS -->|gRPC + mTLS| D1
    MS -->|gRPC + mTLS| D2
    MS -->|Management-API| E1
    MS -->|Management-API| E2
    MS -->|Management-API| E3
    R1 <-->|Router-Trust, Fabric-Lookup| R2
    E1 <-.Tether.-> E3


3.2 Zuordnung und Kardinalitäten

| Komponente | Vorkommen | Persistenz |
|---|---|---|
| Daemon | genau einer pro Maschine | Liste der registrierten Engines |
| Router mit Registry | genau einer pro Maschine | Fabric Lookup, Trust Store, Nutzer |
| Engine | beliebig viele pro Maschine | eigene Config unter ~/.cringle/engines/<id> |
| Fabric | beliebig viele pro Engine | Working Dir, Logging Dir, DWH-Partition |
| ManagementServer | zentral pro Installation, Fallback vorgesehen [Offen] | siehe 7.4 |
| Repository | zentral, von mehreren Maschinenparks nutzbar | Projects, Plugins, Schemas, Trust-Status |
| CLI / WebUI | beliebig viele | zustandslos |

3.3 Wer spricht mit wem

- CLI und WebUI sprechen ausschließlich mit dem ManagementServer. Es gibt keine direkte Verbindung zwischen CLI und WebUI und keine direkte Verbindung zu Engine, Router oder Repository.
- Der ManagementServer spricht mit dem Repository, mit den Daemons und mit der Management-API jeder Engine.
- Engines registrieren sich bei ihrem lokalen Router und senden ihm Heartbeats.
- Router sprechen untereinander, sobald ein Remote Router hinzugefügt und ihm vertraut wurde.
- Blocks sprechen ausschließlich über Driver, also niemals direkt mit Router, ManagementServer oder Repository.

---

4. Maschinenebene: Daemon, Router und Registry

4.1 Daemon

Der Daemon ist der Bootstrap-Punkt einer Maschine. Ohne ihn kann der ManagementServer auf dieser Maschine nichts tun, weil kein Prozess existiert, der eine Engine starten könnte.

- Pro Maschine läuft genau ein Cringle-Daemon. Er startet beim Systemstart über die Mittel des Betriebssystems (systemd-Unit, Windows-Dienst).
- Er nimmt die Anweisungen des ManagementServers entgegen, eine Engine anzulegen und zu starten, und startet den Engine-Prozess tatsächlich.
- Nach einem Neustart der Maschine startet er auf Anweisung des ManagementServers alle registrierten Engines wieder.
- Rechte können pro Maschine bzw. Daemon vergeben werden (siehe 6.3).

4.2 Router und Registry

- Pro Maschine läuft ein Router. Er enthält die Registry, den Trust Store und die Nutzerverwaltung.
- Die Registry speichert, welche Fabrics auf welchen Engines laufen (Fabric Lookup). Sie ist damit die maßgebliche Quelle für das aktuelle Placement, nicht der ManagementServer und nicht das Project.
- Alle Engines der Maschine registrieren sich bei der lokalen Registry.
- Die lokale Registry kennt Remote Router und cached deren Engines, damit Lookups über Maschinengrenzen hinweg nicht bei jedem Zugriff über das Netz gehen.
- Engines senden periodisch einen Heartbeat mit wenigen wichtigen Monitoringdaten (siehe 16.2).
- Die Adresse der lokalen Registry steht in der Engine-Config.
- Remote Router werden vom ManagementServer hinzugefügt. Die Registry- und Trust-Daten liegen im Router.

4.3 Verhältnis von Daemon, Router und Registry

Daemon und Router sind logisch getrennte Komponenten mit getrennten Verantwortlichkeiten, dürfen aber im selben Prozess laufen. Für die Spezifikation zählt die logische Trennung, für den Betrieb genügt ein Prozess pro Maschine.

4.4 Anlegen und Starten einer Engine

mermaid
sequenceDiagram
    participant U as CLI/WebUI
    participant MS as ManagementServer
    participant D as Daemon
    participant R as Router/Registry
    participant E as Engine

    U->>MS: Engine auf Maschine A anlegen
    MS->>R: Engine-Eintrag anlegen (ID)
    MS->>D: Engine starten (ID, Name)
    D->>E: Prozess starten
    E->>E: Config unter ~/.cringle/engines/<id> erzeugen
    E->>E: Schlüsselpaar und Zertifikat erzeugen
    E->>R: registrieren (Zertifikat)
    MS->>E: über Management-API konfigurieren
    E-->>R: Heartbeat (periodisch)


1. Der ManagementServer legt die Engine in der lokalen Registry an.
2. Der Daemon startet die Engine mit ihrer ID. Der Name kann als Startargument übergeben werden.
3. Die Engine erzeugt ihre eigene Config unter ~/.cringle/engines/<id>.
4. Die Engine erzeugt ihr Schlüsselpaar und ihr Zertifikat und registriert sich bei der lokalen Registry.
5. Die Engine wird anschließend über die Management-API konfiguriert.
6. Die Identität des ManagementServers wird über die Nutzerverwaltung festgestellt.

---

5. Trust und Zertifikate

5.1 Modell

Cringle nutzt keine zentrale CA. Vertrauen entsteht durch einen manuellen Akt und wird danach transitiv weitergegeben.

- Jede Engine erzeugt ihr eigenes Zertifikat und weist sich damit aus.
- Ein Remote Router wird über CLI oder WebUI hinzugefügt, und ihm wird ausdrücklich vertraut.
- Wird einem Remote Router vertraut, wird allen Engines transitiv vertraut, die sich bei diesem Router registrieren. Einzelne Engines müssen nicht nochmals bestätigt werden.
- Der Trust Store liegt im Router.
- Die gesamte Komponenten-Kommunikation läuft über diese Zertifikatsinfrastruktur (mTLS, siehe 18).

5.2 Identität über die Zeit

Jede Engine behält eine stabile Identität. Das Schlüsselpaar ist persistent, bei einem Renewal wird das Zertifikat erneuert, die Identität bleibt erhalten. Andernfalls würde jedes Renewal die transitive Vertrauensbeziehung zerreißen.

Ein Renewal-Prozess ist vorgesehen. Auslöser, Fristen und der Umgang mit abgelaufenen Zertifikaten sind [Offen].

5.3 Zwei getrennte Vertrauensachsen

Router-Vertrauen und Nutzer-Vertrauen sind entkoppelt:

- Man kann einem Router technisch vertrauen, ohne seinen Nutzern Rechte zu geben.
- Man kann Nutzern einer fremden Registry vertrauen, unabhängig davon, welche Maschinen dort laufen.

Das ist wichtig, weil sonst jede technische Kopplung automatisch Zugriffsrechte vergeben würde.

---

6. Nutzerverwaltung und Rechte

6.1 Ein gemeinsamer Nutzerraum

Die Nutzerverwaltung gehört zur Registry, nicht zum Repository. Sie ist an die Registry der jeweiligen Maschine gebunden.

Verwaltet werden in demselben Nutzerraum:

- Nutzer und Gruppen, die CLI und WebUI bedienen
- Endnutzer der in Cringle gebauten Anwendungen, etwa Besucher einer Website mit REST-Endpoints

Die Unterscheidung erfolgt ausschließlich über Rechte und Rollen, nicht über getrennte Namensräume. Die Nutzerverwaltung steht Blocks als Driver zur Verfügung, sodass eine in Cringle gebaute Anwendung ihre eigene Authentifizierung nicht selbst bauen muss.

6.2 Föderation

- Einer fremden Registry kann vertraut werden.
- Deren Nutzer tragen dann den Identifier ihrer Registry.
- Sie werden entweder automatisch vertraut oder manuell hinzugefügt.

Ob Rechte pro fremdem Nutzer oder pro fremder Registry vergeben werden, ist [Offen].

6.3 Rechte-Scopes

Rechte können für folgende Objekte und Funktionen vergeben werden:

| Scope | Beispiele |
|---|---|
| Maschine / Daemon | Engines anlegen und starten, Daemon-Status einsehen |
| Project | Version veröffentlichen, deployen, Rollback auslösen |
| Fabric | Status einsehen, starten und stoppen, Logs und DWH lesen, Breakpoints setzen |
| Framework-Funktionen | Plugins vertrauen, Remote Router hinzufügen, Nutzer verwalten |

Das konkrete Rollen- und Rechtemodell ist [Offen].

6.4 Authentifizierung

- Nutzer authentifizieren sich per Token oder Username/Passwort.
- Maschinen und Komponenten authentifizieren sich per Zertifikat.
- Erste Ausbaustufe (früh in der Entwicklung): eine einfache Nutzerverwaltung mit Tokens und wenigen globalen Rollen (Administrator, Operator, Betrachter, Anwendungs-Endnutzer). Das Rechtemodell ist so angelegt, dass die Scopes aus 6.3 später ergänzt werden können. Username/Passwort folgt danach.

---

7. ManagementServer

7.1 Rolle

Der ManagementServer ist die zentrale Steuerinstanz. CLI und WebUI arbeiten ausschließlich über ihn.

7.2 Aufgaben

- Bietet gRPC für Maschinen und Komponenten und zusätzlich REST für die WebUI.
- Verwaltet Engines über deren Management-API: Deploy-Befehle, Konfiguration, Status, Breakpoints, Log-Abfragen.
- Verwaltet das Repository über dessen API. Er kennt die Zuordnung Maschinenpark to zuständiges Repository. Damit legt er fest, welches Repository für den Vertrauensstatus eines Plugins maßgeblich ist, wenn Engines in einem anderen Maschinenpark laufen. Er ermittelt den Status dort und gibt ihn beim Deployment an die Engine weiter.
- Führt beim Deployment das Placement durch: Auflösung logischer Rollen und Labels auf konkrete Engines.
- Bindet beim Deployment abstrakte Shared-Service-Abhängigkeiten an konkrete Instanzen (siehe 13).
- Stößt nach einem Systemneustart über die Daemons den Start der registrierten Engines und anschließend der Fabrics an.
- Fügt Remote Router hinzu und führt den Trust-Vorgang aus.
- Besitzt einen Cleanup-Prozess für nicht mehr benutzte Versionen im lokalen Cache der Maschinen.
- Kann die Logs aller verfügbaren Engines einsehen.

7.3 Was der ManagementServer nicht ist

Er ist nicht die maßgebliche Quelle für das aktuelle Placement. Diese Rolle hat die Registry. Der ManagementServer ist die Instanz, die Placement *entscheidet* und *wiederherstellt*, aber der laufende Zustand wird in den Registries geführt.

7.4 Ausfallsicherheit

Ein Fallback-ManagementServer auf einem anderen System ist vorgesehen. Wie Zustand und Führungsrolle übergeben werden, ist [Offen]. Laufende Fabrics sind von einem Ausfall nicht betroffen, weil Tether-Verbindungen und Registry-Lookups ohne ManagementServer funktionieren. Ohne ihn sind lediglich Deployments, Konfigurationsänderungen und zentrale Log-Abfragen nicht möglich.

---

8. Repository, Projects und Plugins

8.1 Repository

- Eigenständiger Dienst, der vom ManagementServer über eine API verwaltet wird.
- Verwaltet Projects und Plugins inklusive Versionen und Hashes.
- Enthält Schemas.
- Legt den Vertrauensstatus von Plugins (trusted / untrusted) zentral fest. Dieser Status gilt für alle Engines gleichermaßen, nicht pro Engine. Welches Repository für welche Engine zuständig ist, entscheidet der ManagementServer (siehe 7.2).
- Bietet einen Download-Endpoint für Projects und Plugins.
- Kann auch von einem anderen Maschinenpark angesprochen werden, etwa aus der Produktion. Ein Export und Import per ZIP zwischen Umgebungen ist nicht vorgesehen.

8.2 Project

Ein Project ist versioniert und nach der Veröffentlichung unveränderlich. Es enthält:

- einen oder mehrere Blueprints
- Schemas
- Fabric Config: welche Blueprints ausgeführt werden und welche Vorgaben für die Engine-Auswahl gelten, ausgedrückt über logische Rollen und Labels
- Binaries
- Dependencies auf Plugins und andere Projects, mit konkreten Versionen und npm-artigen Kompatibilitätsangaben

Ein Project ist zwischen Umgebungen portabel, weil es Engines ausschließlich über logische Rollen und Labels referenziert und niemals über konkrete Engine-IDs.

8.3 Plugin

Ein Plugin ist versioniert und enthält:

- BlockProvider, die Block-Implementierungen bereitstellen
- Driver
- Binaries
- Schemas
- optional Update-/Downgrade-Processoren

8.4 Versionierung

- Versioniert werden Projects und Plugins.
- Blueprints haben keine eigene Version. Sie hängen an der Version ihres Projects bzw. Plugins.
- Die Versionierung erfolgt auf Project-Ebene. Ändert sich ein Blueprint, bekommt das gesamte Project eine neue Version.
- Dependencies enthalten konkrete Versionen und npm-artige Kompatibilitätsangaben.
- Ein Lock-Konzept fixiert die beim Deploy aufgelösten Versionen, damit ein erneutes Deployment und ein Rollback reproduzierbar sind.
- Zu jeder Version wird ein Hash gesichert und beim Download geprüft.

8.5 Paketformat (erste Version)

Projects und Plugins sind einfache ZIP-Dateien mit:

- Binary-Ordnern
- JARs für den Classloader
- JSON-Config-Dateien

Das Format ist bewusst simpel gehalten und kann später ersetzt werden, ohne dass sich das Modell ändert.

---

9. Blueprint, Fabric und Block

9.1 Blueprint und Fabric

- Ein Blueprint ist die statische Definition, eine Fabric die Laufzeit-Instanz.
- Ein Blueprint kann mehrfach instanziiert werden, auf derselben oder auf verschiedenen Engines.
- Ein Blueprint läuft immer vollständig in genau einer Engine. Es gibt keine über Engines verteilte Fabric.
- Blueprints können über Tethers mit anderen Blueprints verbunden werden, auch auf anderen Engines und auch aus anderen Projects.
- Jede Fabric besitzt Working Dir und Logging Dir, Block Config, Binaries und Fabric Config.
- Mehrere Instanzen desselben Blueprints teilen keinen Laufzeitzustand (siehe 17).

9.2 Block

- Jeder Block hat einen eigenen Lifecycle und kann unabhängig von anderen Blocks derselben Fabric gestartet und gestoppt werden.
- Abgestürzte Blocks können optional automatisch neu gestartet werden. Eine Retry-Anzahl ist konfigurierbar.
- Jeder Block hat eigene, isolierte Laufzeitpfade, etwa den Log-Ordner pro Fabric und Block.
- Ein Block nutzt für alle Interaktionen mit der Außenwelt ausschließlich Driver.
- Die von einem Block benötigten Driver stehen bei der Erstellung der Block-Definition fest. Der Blueprint-Autor muss sich nicht um Driver kümmern.
- Blocks und Provider definieren ihre Schemas und Configs in der Block-Definition selbst.

9.3 Ports

- Jeder Port eines Blocks definiert die unterstützten Tether-Typen und Schemas.
- Der Blueprint-Editor kann damit prüfen, ob zwei Ports überhaupt verbunden werden dürfen.
- Es gibt VarArg-Ports, die als Listen umgesetzt werden. Die Anzahl wird beim Start des Blueprints festgelegt und ändert sich danach nicht.
- Dynamisches Hinzufügen von Tether-Verbindungen zur Laufzeit ist damit ausgeschlossen. Ob das später nötig wird, ist [Offen].

9.4 Lifecycle-Überblick

| Ebene | Ereignisse | Verantwortlich |
|---|---|---|
| Engine | anlegen, starten, konfigurieren, stoppen | Daemon und ManagementServer |
| Fabric | deployen, starten, pausieren, stoppen, migrieren, entfernen | Engine auf Anweisung des ManagementServers |
| Block | erzeugen, starten, stoppen, Restart mit Retry-Limit | Engine |
| Driver | starten, verbinden, Retry, stoppen | Engine bzw. Driver selbst |

---

10. Tether

10.1 Begriff

Ein Tether ist die abstrakte Datenverbindung zwischen zwei Blocks. Er soll alle Arten der Inter-Prozess-Kommunikation ermöglichen:

- synchron, etwa Request/Response
- asynchron, etwa Fire-and-forget
- Streaming, einschließlich roher Byte-Streams

10.2 Typ und Modus

- Der Typ des Tethers wird beim Anlegen des Blueprints festgelegt.
- Ein Block kann mehrere Tether-Typen unterstützen. Welcher genutzt wird, entscheidet der Blueprint.
- Der Modus kann sich zur Laufzeit nicht ändern.

10.3 Garantien

- Die Cringle-API garantiert Non-Blocking-Verhalten pro Block-Ausführung. Ein Block kann durch einen Tether-Aufruf nicht seinen Ausführungskontext blockieren.
- Konkrete Eigenschaften wie Backpressure, Retries und Zustellungsgarantien werden in den jeweiligen Configs festgelegt, nicht im Kernkonzept.

10.4 Realisierung

- Tethers werden durch Driver realisiert.
- Neben TCP werden weitere Tether-Typen implementiert, unter anderem ein lokaler Tether für Blocks innerhalb derselben Engine und ein lokaler IPC-Tether für isolierte Prozesse (siehe 21).
- Tethers zwischen Blueprints verschiedener Engines und verschiedener Projects sind möglich.

10.5 Verschlüsselung

Die Tether-Datenübertragung nutzt die bestehende Zertifikatsinfrastruktur, sofern das Protokoll dies unterstützt und es im Blueprint aktiviert ist. Der Autor kann, wenn verfügbar, zwischen verschiedenen Verfahren wählen.

Die vollständige Tether-Spezifikation mit Wireformat, Zustellungsgarantien und Backpressure-Modellen ist [Offen] und wird in einem eigenen Dokument ausgearbeitet.

---

11. Driver

11.1 Rolle

Driver sind der einheitliche Zugang eines Blocks zu Cringle und zur Außenwelt. Dazu zählen Betriebssystem, Dateisystem, Netzwerk, Nutzerverwaltung, Logging und DWH. Ein Block hat keinen anderen Weg nach draußen.

11.2 Eigenschaften

- Driver werden bei der Erzeugung eines Blocks durch den BlockProvider injiziert und sind unabhängig vom Tether-Transport.
- Driver haben einen Lifecycle.
- Es gibt verschiedene Driver-Typen mit unterschiedlichem Isolationslevel.
- Jeder Driver existiert genau einmal pro Engine. Blocks nutzen die entsprechenden Instanzen der Engine. Die Trennung zwischen Blocks (Pfade, Log-Tags, Kontingente) übernimmt der Driver anhand der Identität des aufrufenden Blocks. So lassen sich zum Beispiel Portkonflikte bei TCP früh erkennen.
- Blocks können über den DWH-Driver Einträge ins Data Warehouse schreiben.
- Ein Block soll von Retry-Details der Driver möglichst wenig mitbekommen.
- Failover von Shared Services ist Aufgabe des Drivers (siehe 13).

11.3 Eingebaute Driver

Die Engine bringt mit:

| Driver | Zweck |
|---|---|
| Tether | Transport zwischen Blocks |
| TCP | Netzwerkzugriff, eine Instanz pro Engine |
| Serial | serielle Schnittstellen, exklusive Ressource |
| Filesystem | Zugriff auf die isolierten Laufzeitpfade |
| Logging | einheitlicher Logging-Mechanismus, nicht dateibasiert |
| DWH | Zugriff auf das Data Warehouse |
| Nutzerverwaltung | Authentifizierung und Rechte für Anwendungs-Endnutzer |

11.4 Driver aus Plugins

Plugins können weitere Driver bereitstellen, die beim Laden des Plugins in der Fabric verfügbar werden. Es wird allgemein von "Driver" gesprochen, nicht von "Plugin-Drivern". Für Blocks ist kein Unterschied sichtbar.

Die genaue Driver-Schnittstelle und die Lifecycle-Details sind [Offen]. Der Scope ist entschieden: ein Driver pro Engine.

---

12. Schema

- Schemas beschreiben pro Project und Plugin, welche komplexen Datentypen im Blueprint zur Verfügung stehen. Diese Typen werden über Tethers übertragen.
- Schemas können über die WebUI oder über Konfigurationsdateien erstellt und bearbeitet werden.
- Es gibt Cringle-Standardschemas.
- Schemas werden über eindeutige Namespace-IDs aufgelöst, auch über Plugin- und Projektgrenzen hinweg. Damit können Blocks aus unterschiedlicher Herkunft denselben Typ meinen.
- Abwärtskompatibilität ist nicht zwingend. Eine Schema-Änderung kann ein Redeploy erfordern. Das ist eine bewusste Entscheidung zugunsten von Einfachheit.
- Schemas werden zur Laufzeit geprüft.
- Über Prozessgrenzen hinweg dient das Schema als Serialisierungsvertrag (siehe 17 und 21).

Die Schema-Definitionssprache ist ein eigenes Cringle-Format. Es wird nicht auf JSON Schema, Protobuf oder Ähnliches aufgesetzt. Die konkrete Syntax wird als Vorschlag in spec/schema.md erarbeitet (Issue #4). Das Wireformat über Prozessgrenzen ist [Offen].

---

13. Shared Services und Service-Discovery

13.1 Begriff

Ein Shared Service ist ein eigenständig deployter Blueprint, den mehrere Fabrics und Projects nutzen. Typisches Beispiel ist ein Blueprint, der Google Calendar kapselt, sodass nicht jede Anwendung ihre eigene Anbindung mitbringen muss.

13.2 Auflösung in drei Stufen [Zu bestätigen]

1. Design-Zeit: Der Blueprint referenziert eine abstrakte Abhängigkeit, etwa über den erwarteten Schema-Typ, nicht über eine konkrete Instanz.
2. Deploy-Zeit: Der Nutzer wählt die konkrete Instanz. Der ManagementServer bindet sie.
3. Laufzeit: Die Adresse wird über die Registry aufgelöst.

13.3 Caching und Failover

- Die Auflösung wird gecached, damit nicht jeder Zugriff über die Registry läuft.
- Nach einem Failover wird neu gesucht.
- Der Healthcheck ist eine explizite Schleife und Aufgabe des Drivers, nicht des Blocks.
- Ein Failover kann beim Deploy oder später definiert werden.
- Der Wechsel ist für Blocks transparent.

Auswahlregeln bei mehreren Kandidaten, Prioritäten und ein mögliches Capability-Modell sind [Offen].

---

14. Deployment

14.1 Ablauf

mermaid
sequenceDiagram
    participant U as CLI/WebUI
    participant MS as ManagementServer
    participant REPO as Repository
    participant R as Registry
    participant E as Engine

    U->>MS: Project-Version deployen
    MS->>REPO: Version, Dependencies, Hashes auflösen
    MS->>MS: Lock erzeugen
    MS->>MS: Placement (Rollen/Labels to Engines)
    MS->>E: Artefakte anfordern
    E->>REPO: Download (Hash-Prüfung)
    E->>E: in ~/.cringle/ entpacken
    MS->>E: Fabric erzeugen und starten
    E->>R: Fabric registrieren
    MS->>R: Placement sichern


- Projects werden manuell über den ManagementServer deployed.
- Die Zuweisung der Blueprints zu Engines erfolgt zur Deploy-Zeit durch den ManagementServer anhand der logischen Rollen und Labels der Fabric Config.
- Der Ort, an dem Fabrics laufen, wird in der Registry gesichert.
- Nach einem Systemneustart stellt der ManagementServer die Fabrics auf den zugehörigen Engines wieder her.

14.2 Update-Strategie: Blue-Green

- Blue-Green ist der Standard und kann optional deaktiviert werden.
- Die alte Fabric läuft weiter, während die neue aufgesetzt und migriert wird. Erst danach erfolgt der Wechsel.
- Schlägt die Migration fehl, bleibt die alte Fabric aktiv. Es entsteht kein halb migrierter Zustand.
- Bei Blocks mit exklusiven Ressourcen, etwa festen Ports oder seriellen Schnittstellen, muss der Wechsel mit den betroffenen Drivern abgestimmt werden, oder Blue-Green wird für diese Fabric deaktiviert.

Die konkrete Umschaltmechanik mit exklusiven Ressourcen ist [Offen].

14.3 Rollback und Migration

- Ein Rollback auf eine frühere Project-Version soll möglich sein. Da Project-Versionen unveränderlich sind und Dependencies gelockt werden, ist der alte Stand reproduzierbar.
- Update- und Downgrade-Processoren können in Plugins und Projects definiert werden.
- Mehrere Schritte werden nacheinander ausgeführt, etwa 1 to 2 to 3, in beiden Richtungen.
- Schlägt ein Update oder Downgrade fehl, wird der Vorgang abgebrochen, bis der Fehler manuell behoben ist. Es gibt keine automatische Reparatur.

Aufbau der Processoren und der Umgang mit Zustand beim Rollback sind [Offen].

---

15. Artefakte und Cache

- Lokal installierte Projects und Plugins werden unter ~/.cringle/ entpackt und verwaltet. Alle Engines der Maschine greifen auf diesen Cache zu.
- Pro Project und Plugin wird nur eine Version entpackt. Alle Engines referenzieren dieselben Dateien.
- Engine-Configs liegen unter ~/.cringle/engines/<id>.
- Nicht benutzte Versionen werden nach X Tagen oder durch den Cleanup-Prozess des ManagementServers entfernt. Die Frist und der Umgang mit Referenzen sind [Offen].
- Der Download erfolgt über den Download-Endpoint des Repositorys. Der Hash jeder Version wird geprüft.
- Binaries sind statische Ressourcen, etwa die HTML-Dateien einer einfachen Website. Sie werden im Project gesichert, beim Deployment entpackt, von Cringle automatisch verwaltet und später gelöscht.
- Jeder Block besitzt zusätzlich eigene isolierte Laufzeitpfade.

15.1 Verzeichnislayout [Zu bestätigen]


~/.cringle/
├── engines/
│   └── <engine-id>/
│       ├── config.json
│       ├── certs/
│       └── fabrics/
│           └── <fabric-id>/
│               ├── working/
│               └── logs/
│                   └── <block-id>/
├── projects/
│   └── <project>/<version>/
├── plugins/
│   └── <plugin>/<version>/
└── dwh/


---

16. Observability

16.1 Logging

- Es gibt einen einheitlichen Logging-Mechanismus pro Engine, den Blocks über einen Driver nutzen. Er ist nicht dateibasiert.
- Zusätzlich kann optional dateibasiert geloggt werden: Wenn Blocks Prozesse mit eigenen Logdateien ansprechen, können diese in den von Cringle bereitgestellten Log-Ordner pro Fabric und Block schreiben. So sammelt Cringle auch Logs fremder Prozesse ein.
- Logs werden zunächst von jeder Engine selbst gesichert.
- Pro Maschine kann ein LoggingCollector aktiviert werden, der die Logs bestimmter Engines sammelt.
- Der ManagementServer kann die Logs aller verfügbaren Engines einsehen.
- Das Logging steht in keinem Zusammenhang mit journald oder ähnlichen Mechanismen des Betriebssystems.
- Logdaten sollten nach Fabric und Block getaggt sein [Zu bestätigen].

16.2 Monitoring

- Metriken: CPU und Speicher pro Block, Fabric und Engine, Tether-Durchsatz, Fehlerzahlen.
- Der ManagementServer kann sie abfragen (Pull).
- Zusätzlich sendet jede Engine einen periodischen Heartbeat mit wenigen wichtigen Daten an die Registry (Push).

16.3 Assertions

- Prüfungen zur Laufzeit, mit denen bestimmte Zustände zugesichert werden, etwa "Fabric läuft".
- Sie werden unter anderem vom Blueprint-Autor definiert.
- Details sind [Offen].

16.4 Remote-Debugging

- Der ManagementServer kann Breakpoints bei Blocks aktivieren.
- Eine pausierte Fabric erlaubt es, die Ein- und Ausgabedaten der Blocks einzusehen.
- Die Breakpoint-Mechanik und die Pufferung der Tether-Nachrichten bei pausierten Fabrics sind [Offen].

16.5 Data Warehouse

- Läuft pro Engine und ist über einen Driver verfügbar.
- Gedacht für Tether-Nachrichten, aber auch für andere komplexe Daten.
- Aufzeichnungsmodus: Eine Fabric kann in einen Modus geschaltet werden, in dem jede Nachricht ins DWH geschrieben wird. Sonst wird pro Tether im Blueprint definiert, was gesichert wird.
- Retention-Policy: zeit- und größenbasiert, konfigurierbar pro Block und pro Tether.
- Die Daten sollten logisch nach Fabric-Instanz, Block und Tether partitioniert sein, damit Rechte, Retention und Löschung greifen. Ein gemeinsamer Store pro Engine genügt [Zu bestätigen].

Aufbau und Schnittstelle des DWH sind [Offen].

---

17. Isolationsanforderungen

Diese Anforderungen gelten für jede Engine-Implementierung. Die Umsetzung ist implementierungsspezifisch, für Kotlin siehe Teil B.

1. Fabric-Instanzen sind voneinander isoliert. Auch mehrere Instanzen desselben Blueprints teilen keinen Laufzeitzustand.
2. Abhängigkeiten von Providern kollidieren nicht. Blocks im selben Blueprint dürfen inkompatible Versionen derselben Bibliothek nutzen.
3. Nicht vertrauenswürdige Blocks laufen in einem eigenen Prozess und kommunizieren über einen lokalen Tether-Typ. Die Kotlin-Engine setzt das zunächst nicht um (siehe 21); bis dahin gilt Regel 4 fail-closed.
4. Regel für die Isolation: Es gewinnt die strengere von zwei Angaben: dem Vertrauensstatus des Plugins (trusted/untrusted, vom Betreiber zentral im Repository gesetzt) und dem Wunsch in der Block Config (vom Blueprint-Autor pro Block). Die Isolationsstufen sind aufsteigend geordnet von shared (im normalen Fabric-Thread) bis process (eigener Kind-Prozess). Der Blueprint-Autor kann verschärfen, aber nie lockern. Kann eine Engine die resultierende Stufe nicht umsetzen, startet der Block nicht (fail-closed). Er läuft nie mit schwächerer Isolation.
5. Der Vertrauensstatus eines Plugins wird zentral im Repository gesetzt, nicht pro Engine.
6. Driver haben je nach Typ unterschiedliche Isolationslevel (siehe 11).
7. Über die Prozessgrenze werden Nachrichten anhand des Schemas serialisiert.
8. Ein Block darf nicht auf die Laufzeitpfade oder Zertifikate anderer Blocks zugreifen.

---

18. Kommunikationsprotokolle

| Strecke | Protokoll |
|---|---|
| ManagementServer and Engine (Management-API) | gRPC mit mTLS |
| ManagementServer and Repository | gRPC mit mTLS |
| ManagementServer and Daemon | gRPC mit mTLS |
| Engine and Router/Registry | gRPC mit mTLS |
| Router and Router | gRPC mit mTLS |
| WebUI and ManagementServer | REST, zusätzlich vom ManagementServer angeboten |
| CLI and ManagementServer | ManagementServer-API |
| Block and Block (Tether) | je nach Tether-Typ, optional über die Zertifikatsinfrastruktur verschlüsselt |

- Die .proto-Dateien liegen im sprachunabhängigen Teil des Projekts und sind die Single Source of Truth für alle Engine-Implementierungen.
- Nutzer authentifizieren sich per Token oder Username/Passwort, Maschinen per Zertifikat.
- Die Aufteilung der .proto-Dateien und der API-Oberfläche ist [Offen].

---

Teil B – Kotlin Engine Implementation

19. Prozessmodell

- Technische Basis: Kotlin, Gradle, JDK 21.
- Jede Engine ist ein eigener JVM-Prozess.
- Jede Fabric läuft in einem eigenen Thread.
- Ein Thread isoliert keine statischen Klassenzustände. Die Isolation zwischen Fabrics wird deshalb über Classloader erreicht (siehe 20).
- Ein Fehler in der JVM, etwa OutOfMemoryError, System.exit() oder ein nativer Absturz, kann die gesamte Engine betreffen. Dagegen dient die Prozessisolation nicht vertrauenswürdiger Blocks (siehe 21).

20. Classloading

Entscheidung: ein Classloader pro Provider und pro Fabric-Instanz.


Parent-Classloader (Cringle-Contract)
│   Block, BlockProvider, Tether-Schnittstellen, Driver-Schnittstellen
├── Fabric 1 · Provider A   (child-first)
├── Fabric 1 · Provider B   (child-first)
├── Fabric 2 · Provider A   (child-first, eigene Kopie)
└── Fabric 2 · Provider C   (child-first)


- Ein schmaler gemeinsamer Parent-Classloader enthält nur die Cringle-Contract-Interfaces.
- Darunter liegt pro Fabric-Instanz und Provider ein eigener Child-Classloader mit child-first-Delegation für alles außerhalb der Contract-Pakete. Das ist entschieden.
- Damit können Blocks im selben Blueprint unterschiedliche Versionen derselben Bibliothek nutzen, etwa commons-collections.
- Parallele Instanzen desselben Blueprints teilen keine statischen Zustände.
- Kosten: höherer Speicherbedarf und längere Ladezeiten bei vielen parallelen Instanzen. Das ist der bewusst akzeptierte Preis für echte Isolation.

21. Isolierte Blocks (untrusted)

> Zurückgestellt: Die Umsetzung mit Kind-JVM-Prozessen ist vorerst nicht Teil der Entwicklung. Die Isolationsregel aus 17 wird trotzdem früh umgesetzt und ist fail-closed: Ergibt sie process, wird der Block nicht gestartet. Das Kapitel beschreibt den geplanten Zielzustand.

- Ein als untrusted eingestufter Block läuft in einem eigenen Kind-JVM-Prozess, der von der Engine gestartet wird und nur den Classloader dieses Providers enthält [Zu bestätigen].
- Die Kommunikation läuft über einen lokalen IPC-Tether, unter Linux über einen Unix Domain Socket, unter Windows über eine Named Pipe, als eigener Tether-Typ.
- Nachrichten werden über die Prozessgrenze anhand des Schemas serialisiert.
- Ressourcenbegrenzung über Mittel des Betriebssystems, unter Linux cgroups, unter Windows Job Objects, dazu eingeschränkte Dateisystemrechte, damit der Block nicht auf Nachbar-Blocks oder das Engine-Zertifikat zugreifen kann.
- Der Lifecycle wird wie bei In-Process-Blocks überwacht. Ein Absturz wird über Exit-Code oder Heartbeat-Timeout erkannt.
- Nachteil: höherer Speicher- und Startaufwand pro isoliertem Block.

22. Programmiermodell für Plugin-Autoren

- Es gibt Interface-Klassen für Block und BlockProvider im Contract-Parent-Classloader.
- Blocks besitzen grundlegende Hook-Methoden für den Lifecycle und können auf Tether-Ereignisse reagieren.
- Die JSON-Config ergänzt das Interface um Metadaten: Name, Schema-Referenzen, Ports, benötigte Driver. Die Engine liest die Config, um zu wissen, was sie injizieren muss, und instanziiert den Block über das Interface.
- Der BlockProvider erzeugt Block-Instanzen und injiziert deren Driver.
- Ob der BlockProvider eigene Lifecycle-Methoden erhält, etwa beim Laden und Entladen, wird entschieden, wenn es Vorteile bringt [Offen].
- Die Cringle-API garantiert Non-Blocking-Verhalten. In Kotlin ist sie Coroutine-basiert (suspend-Funktionen und Flows). Die engine-interne Tether-Kommunikation läuft dadurch asynchron.
- Plugin- und Project-Autoren bauen ihre Pakete mit Gradle. Ein einfaches Cringle-Gradle-Plugin erzeugt daraus das ZIP mit JARs, JSON-Configs und Binaries. Ein Testkit erlaubt es, Blocks ohne laufende Engine zu testen.

23. Weitere Engine-Implementierungen

- Andere Implementierungen sollen dieselben Isolationskonzepte aus Kapitel 17 mit ihren eigenen Mitteln umsetzen, etwa Prozesse oder Container statt Classloader.
- Das nach außen sichtbare Verhalten, also Protokolle, Registrierung, Trust und Schema-Validierung, ist identisch.
- Blueprints laufen auch auf Engines anderer Sprachen, sofern die referenzierten Blocks, Plugins und Driver in dieser Implementierung verfügbar sind.

---

Teil C – Roadmap bis 1.0.0

> Die Reihenfolge und die Zuordnung zu Meilensteinen sind ein Vorschlag [Zu bestätigen]. Der Inhalt der Features folgt den Entscheidungen aus Teil A und B.

24. Leitgedanken der Roadmap

1. Von innen nach außen. Erst Engine und Blocks in einem einzigen Prozess, dann eine Maschine, dann mehrere Maschinen, dann Komfort und Härtung.
2. Früh ein lauffähiges Ende-zu-Ende-Szenario. Ab M4 soll ein einfaches Beispiel deploybar sein, auch wenn Trust, WebUI und Observability noch fehlen.
3. Der Contract zuerst. .proto-Dateien und Contract-Interfaces entstehen früh, weil alles andere darauf aufbaut.
4. Sicherheit nicht nachrüsten. mTLS und Trust kommen, bevor mehrere Maschinen beteiligt sind, nicht danach.
5. Was nicht in 1.0.0 gehört, wird ausdrücklich benannt (siehe 27).

25. Meilensteine

M0 – Fundament (0.1.0)

Ziel: Das Repository steht, der Contract ist definiert, ein Build läuft.

- Struktur des Quellcode-Repositorys: proto/, spec/, kotlin/ (Gradle-Multi-Modul), docs/; WebUI und Beispiele folgen in späteren Meilensteinen
- Erste .proto-Dateien für Registry, Management-API und Repository-API
- Contract-Interfaces Block, BlockProvider, Driver- und Tether-Schnittstellen
- Build- und CI-Pipeline, Codestil, Lizenz (CI: GitHub Actions, Lizenz: Apache-2.0)
- Dieses Architekturdokument als gepflegte Grundlage

Fertig, wenn: ein leeres Plugin gegen den Contract kompiliert.

M1 – Engine-Kern (0.2.0)

Ziel: Eine Engine führt eine Fabric mit mehreren Blocks in einem Prozess aus.

- Engine als JVM-Prozess, Start mit ID und Name
- Engine-Config unter ~/.cringle/engines/<id>
- Fabric pro Thread
- Classloader pro Provider und Fabric-Instanz, Parent-Classloader mit Contract
- Block-Lifecycle mit Hook-Methoden
- Restart abgestürzter Blocks mit konfigurierbarer Retry-Anzahl
- BlockProvider mit Driver-Injection
- JSON-Config für Block-Definitionen: Name, Ports, Schemas, benötigte Driver
- Isolierte Laufzeitpfade pro Fabric und Block
- Lokaler In-Process-Tether als erster Tether-Typ, asynchron auf Coroutinen
- Schema-Grundgerüst (Modell, Namespace-Auflösung, Laufzeitprüfung) und Paketformat-Grundlagen, soweit Engine und Tests sie brauchen
- Isolationsregel mit Stufen shared bis process, fail-closed
- Testkit für Blocks und Plugins

Fertig, wenn: zwei Blocks im selben Blueprint mit kollidierenden Bibliotheksversionen gleichzeitig laufen und Daten austauschen.

M2 – Schema und Tether-Typen (0.3.0)

Ziel: Typisierte Kommunikation über verschiedene Transporte.

- Schema-Definitionssprache und Wireformat [Offen]
- Auflösung über eindeutige Namespace-IDs über Plugin- und Projektgrenzen
- Cringle-Standardschemas
- Laufzeitprüfung der Schemas
- Ports mit unterstützten Tether-Typen und Schemas
- VarArg-Ports als Listen, Anzahl beim Start festgelegt
- Tether-Modi: synchron, asynchron, Streaming einschließlich roher Byte-Streams
- Non-Blocking-Garantie pro Block-Ausführung
- TCP-Tether (Byte-Transport für externe Kommunikation), Serial-Tether; Filesystem ist ein Driver, kein Tether-Typ
- Backpressure- und Retry-Konfiguration pro Tether

Fertig, wenn: zwei Blocks über TCP Bytes austauschen und ein Schema-Verstoß an lokalen Tethers zur Laufzeit erkannt wird. Schema-Nachrichten für alle Tether-Typen folgen mit dem gemeinsamen Wire-Format (M6, Cross-Engine-Tether).

M3 – Artefakte und Repository (0.4.0)

Ziel: Projects und Plugins kommen aus einer verwalteten Quelle.

- Repository als eigenständiger Dienst mit API
- Paketformat: ZIP mit Binary-Ordnern, JARs und JSON-Configs
- Project mit Blueprints, Schemas, Fabric Config, Binaries, Dependencies
- Plugin mit BlockProvidern, Drivern, Binaries, Schemas
- Versionierung auf Project-Ebene, unveränderliche Versionen
- npm-artige Kompatibilitätsangaben in Dependencies
- Hash pro Version und Prüfung beim Download
- Download-Endpoint
- Lokaler Cache unter ~/.cringle/, eine entpackte Version pro Project und Plugin
- Binaries beim Deployment entpacken und später löschen
- Einfaches Cringle-Gradle-Plugin zum Bauen von Plugin- und Project-Paketen

Fertig, wenn: eine Engine ein Plugin aus dem Repository lädt, den Hash prüft und daraus Blocks instanziiert.

M4 – Maschinenebene und erstes Deployment (0.5.0)

Ziel: Erstes vollständiges Deployment über ManagementServer, Daemon und Registry.

- Daemon pro Maschine, Start über systemd bzw. Windows-Dienst
- Engine über den Daemon anlegen und starten
- Router mit Registry, Fabric Lookup
- Registrierung der Engines bei der lokalen Registry
- Heartbeat der Engines
- ManagementServer mit gRPC-API
- Management-API der Engine: Konfiguration, Status, Deploy
- Fabric Config mit logischen Rollen und Labels
- Placement zur Deploy-Zeit
- Lock-Konzept für aufgelöste Versionen
- Wiederherstellung der Fabrics nach einem Systemneustart
- CLI für die einfachen Operationen
- Frühe Nutzerverwaltung mit Tokens und einfachen globalen Rollen
- Zuordnung Maschinenpark to Repository im ManagementServer

Fertig, wenn: ein Project per CLI deployed wird, nach einem Neustart der Maschine automatisch wieder läuft und die Registry das Placement kennt.

M5 – Trust und Verschlüsselung (0.6.0)

Ziel: Alle Komponenten kommunizieren abgesichert.

- Selbst erzeugte Zertifikate pro Engine, stabile Identität über ein persistentes Schlüsselpaar
- Trust Store im Router
- Manueller Trust-Vorgang über CLI und WebUI
- Transitives Vertrauen: Vertrauen zum Router schließt seine Engines ein
- mTLS auf allen Komponenten-Strecken
- Remote Router hinzufügen, Engines fremder Router cachen
- Authentifizierung: Nutzer per Token oder Username/Passwort, Maschinen per Zertifikat
- Optionale Verschlüsselung der Tether-Übertragung über dieselbe Infrastruktur, Verfahrenswahl im Blueprint

Fertig, wenn: zwei Maschinen sich gegenseitig vertrauen und ein Tether zwischen ihren Engines verschlüsselt läuft.

M6 – Verteilung und Shared Services (0.7.0)

Ziel: Anwendungen über mehrere Engines und Projects hinweg.

- Tethers zwischen Blueprints verschiedener Engines
- Tethers zwischen Blueprints verschiedener Projects
- Shared Services als eigenständig deployte Blueprints
- Abstrakte Abhängigkeit zur Design-Zeit, konkrete Bindung zur Deploy-Zeit [Zu bestätigen]
- Laufzeit-Auflösung über die Registry, mit Cache
- Healthcheck-Schleife im Driver
- Failover, für Blocks transparent, beim Deploy oder später definierbar
- Neusuche nach einem Failover

Fertig, wenn: ein Shared Service von zwei Projects auf verschiedenen Maschinen genutzt wird und ein Ausfall automatisch auf eine Alternative umschaltet.

M7 – Observability (0.8.0)

Ziel: Der Betrieb ist beobachtbar und analysierbar.

- Logging-Driver, einheitlicher Mechanismus pro Engine, nicht dateibasiert
- Optionales dateibasiertes Logging im Log-Ordner pro Fabric und Block für Fremdprozesse
- Tagging nach Fabric und Block [Zu bestätigen]
- LoggingCollector pro Maschine
- Zentrale Log-Einsicht über den ManagementServer
- Monitoring-Metriken: CPU, Speicher, Tether-Durchsatz, Fehlerzahlen
- Heartbeat mit wenigen wichtigen Monitoringdaten
- DWH pro Engine mit Driver-Zugriff
- Aufzeichnungsmodus pro Fabric und Definition pro Tether
- Retention zeit- und größenbasiert, pro Block und pro Tether
- Partitionierung nach Fabric-Instanz, Block und Tether [Zu bestätigen]

Fertig, wenn: der ManagementServer Logs und Metriken aller Engines zeigt und Tether-Nachrichten mit Retention im DWH liegen.

M8 – WebUI und Blueprint-Editor (0.9.0)

Ziel: Blueprints werden visuell gebaut, die Plattform wird grafisch bedient.

- WebUI in Svelte 5 mit SvelteFlow
- REST-Schnittstelle des ManagementServers für die WebUI
- Visueller Blueprint-Editor mit Blocks, Ports und Tethers
- Kompatibilitätsprüfung beim Verbinden anhand von Tether-Typ und Schema
- Verwaltung von Deployments
- Schema-Editor
- Nutzer-, Gruppen- und Rechteverwaltung in der Oberfläche
- Trust-Vorgang für Router in der Oberfläche
- Funktionale Angleichung von CLI und WebUI bei den einfachen Operationen

Fertig, wenn: ein Blueprint vollständig in der WebUI entsteht und von dort deployed wird.

M9 – Betrieb, Sicherheit und Härtung (1.0.0)

Ziel: Produktionsreife.

- Blue-Green als Standard, optional deaktivierbar
- Abstimmung mit Drivern bei exklusiven Ressourcen [Offen]
- Rollback auf frühere Project-Versionen
- Update- und Downgrade-Processoren in Plugins und Projects, mehrstufig
- Abbruch bei fehlgeschlagener Migration, manuelle Behebung
- Vertrauensstatus von Plugins zentral im Repository
- Isolation nicht vertrauenswürdiger Blocks im eigenen Kind-JVM-Prozess (zurückgestellt, siehe 21)
- Lokaler IPC-Tether für isolierte Blocks
- Ressourcenbegrenzung über cgroups bzw. Job Objects
- Isolationsregel als strengere Stufe aus Plugin-Status und Block Config (früher umgesetzt, siehe M1)
- Rechte-Scopes für Maschine, Project, Fabric und Framework-Funktionen
- Föderierte Nutzer mit Registry-Identifier
- Assertions, definierbar unter anderem durch den Blueprint-Autor
- Remote-Debugging mit Breakpoints und Einsicht in Ein- und Ausgabedaten
- Zertifikats-Renewal
- Cleanup-Prozess für nicht benutzte Versionen
- Dokumentation, Beispiel-Projects und Migrationsleitfaden

Fertig, wenn: alle Punkte aus Kapitel 26 erfüllt sind.

26. Feature-Liste für 1.0.0

Laufzeit

- Engine als eigener Prozess, mehrere Engines pro Maschine
- Mehrere Fabrics pro Engine, isoliert voneinander
- Mehrfache Instanziierung desselben Blueprints ohne gemeinsamen Zustand
- Block-Lifecycle mit automatischem Restart und Retry-Limit
- Classloader pro Provider und Fabric-Instanz
- Isolationsregel (strengere Stufe gewinnt, fail-closed)
- Isolierte Blocks in eigenem Prozess mit Ressourcenbegrenzung (zurückgestellt)
- Isolierte Laufzeitpfade pro Fabric und Block

Kommunikation

- Tether synchron, asynchron und als Stream, einschließlich roher Byte-Streams
- Tether-Typ fix bei Design-Zeit, kein Moduswechsel zur Laufzeit
- Non-Blocking-Garantie pro Block-Ausführung
- Tether-Typen: lokal, IPC, TCP, Serial, Filesystem
- Backpressure- und Retry-Konfiguration
- Tethers über Engine- und Projektgrenzen hinweg
- Optionale Verschlüsselung über die Zertifikatsinfrastruktur mit Verfahrenswahl

Modell und Typen

- Ports mit Tether-Typen und Schemas
- VarArg-Ports als Listen, Anzahl beim Start festgelegt
- Schemas pro Project und Plugin, Standardschemas
- Auflösung über eindeutige Namespace-IDs
- Laufzeitprüfung
- Serialisierung über Prozessgrenzen anhand des Schemas

Driver

- Driver als einziger Zugang zur Außenwelt
- Injection durch den BlockProvider
- Lifecycle pro Driver
- Isolationslevel pro Driver-Typ, ein Driver pro Engine
- Eingebaute Driver: Tether, TCP, Serial, Filesystem, Logging, DWH, Nutzerverwaltung
- Driver aus Plugins, in der Fabric verfügbar
- Retry-Details vor dem Block verborgen
- Healthcheck und Failover im Driver

Artefakte und Versionierung

- Repository als eigener Dienst mit API und Download-Endpoint
- Projects und Plugins als ZIP mit Binaries, JARs und JSON-Configs
- Unveränderliche Project-Versionen
- npm-artige Kompatibilitätsangaben und Lock-Konzept
- Hash pro Version mit Prüfung
- Zentraler Vertrauensstatus für Plugins
- Cache unter ~/.cringle/ mit einer entpackten Version pro Artefakt
- Cleanup nach Frist und durch den ManagementServer

Deployment

- Manuelles Deployment über den ManagementServer
- Fabric Config mit logischen Rollen und Labels
- Placement zur Deploy-Zeit
- Portabilität zwischen Umgebungen ohne Export und Import
- Wiederherstellung nach Systemneustart
- Blue-Green als Standard, deaktivierbar
- Rollback auf frühere Versionen
- Mehrstufige Update- und Downgrade-Processoren mit Abbruch bei Fehlern

Maschinen und Vertrauen

- Daemon pro Maschine
- Router mit Registry pro Maschine
- Fabric Lookup
- Heartbeat der Engines
- Remote Router mit Caching
- Selbst erzeugte Zertifikate mit stabiler Identität
- Manueller Trust-Vorgang, transitives Vertrauen zu Engines
- mTLS auf allen Komponenten-Strecken
- Zertifikats-Renewal

Nutzer und Rechte

- Nutzerverwaltung in der Registry
- Ein gemeinsamer Nutzerraum für Framework und Anwendungen
- Nutzerverwaltung als Driver für Blocks
- Föderation mit Registry-Identifier, automatisch oder manuell
- Rechte-Scopes: Maschine, Project, Fabric, Framework-Funktionen
- Token, Username/Passwort und Zertifikate

Observability

- Logging-Driver, nicht dateibasiert
- Optionales dateibasiertes Logging für Fremdprozesse
- LoggingCollector pro Maschine
- Zentrale Log-Einsicht
- Monitoring-Metriken und Heartbeat
- DWH pro Engine mit Driver
- Aufzeichnungsmodus und Definition pro Tether
- Retention zeit- und größenbasiert, pro Block und pro Tether
- Assertions
- Remote-Debugging mit Breakpoints und Dateneinsicht

Bedienung

- ManagementServer mit gRPC und REST
- CLI für die einfachen Operationen
- WebUI in Svelte 5 mit SvelteFlow
- Visueller Blueprint-Editor mit Kompatibilitätsprüfung
- Deployment-Verwaltung in der WebUI
- Schema-Editor

Grundlagen

- .proto-Dateien im sprachunabhängigen Teil als Single Source of Truth
- Trennung von Plattform-Spezifikation und Kotlin-Implementierung
- Dokumentation und Beispiel-Projects

27. Ausdrücklich nicht in 1.0.0

- Weitere Engine-Implementierungen außerhalb der JVM. Die Spezifikation ist darauf vorbereitet, die Umsetzung folgt später.
- Automatische Skalierung oder automatisches Rescheduling von Fabrics.
- Dynamisches Hinzufügen von Tether-Verbindungen zur Laufzeit.
- Erzwungene Abwärtskompatibilität von Schemas.
- Ein eigenes Paketformat jenseits von ZIP.
- Ausfallsicherheit des ManagementServers über den Fallback hinaus.

28. Nach 1.0.0

Kandidaten für spätere Versionen, ohne Reihenfolge:

- Zweite Engine-Implementierung in einer anderen Sprache als Machbarkeitsnachweis der Protokolltrennung
- Hochverfügbarer ManagementServer
- Capability-Modell für Shared Services mit Prioritäten und Auswahlregeln
- Dynamische VarArg-Ports
- Signierte Plugins und ein Trust-Modell für Herausgeber
- Marktplatz oder Katalog für Plugins
- Feinere Ressourcen-Quotas pro Fabric

---

29. Abweichungen von der Architekturübersicht

Die Architekturübersicht (Tethera.png) sollte an folgenden Stellen angepasst werden:

1. User Management gehört neben die Registry, nicht in das Repository.
2. Die Verbindung zwischen CLI und WebUI ist falsch. Beide sprechen ausschließlich mit dem ManagementServer.
3. "OS" ist nur das Label für die graue Hintergrundbox und keine Komponente.
4. Der Daemon fehlt im Diagramm.
5. Logging und DWH sind als Driver zu ergänzen. Das DWH fehlt im Diagramm.
6. Die Engine-Config liegt unter ~/.cringle/engines/<id>.
7. Der Projektname lautet nun Cringle. Die Datei darf in Cringle.png umbenannt werden.

---

30. Offene Punkte

Ausfallsicherheit und Betrieb
- Ausfallsicherheit des ManagementServers, Fallback auf einem anderen System, Übergabe der Führungsrolle
- Zertifikats-Renewal im Detail: Auslöser, Kulanzfrist bei abgelaufenen Zertifikaten, Fingerprint-Pinning gegenüber dem Router als implizite CA
- Fehlerbehandlung und Retry-Konfiguration der Driver

Nutzer und Rechte
- Konkretes Rollen- und Rechtemodell
- Föderierte Nutzer: Rechte pro Nutzer oder pro Registry

Tether und Driver
- Vollständige Tether-Spezifikation: Modi, Zustellungsgarantien, Backpressure, Wireformat
- Genaue Driver-Schnittstelle und Lifecycle-Details (der Scope ist entschieden: ein Driver pro Engine)
- Lifecycle-Hooks des BlockProvider
- Tether-Verbindungen zu VarArg-Ports zur Laufzeit

Observability
- Details der Assertions
- Aufbau und Schnittstelle des DWH
- Breakpoint-Mechanik und Pufferung der Tether-Nachrichten bei pausierten Fabrics

Deployment und Versionierung
- Umschaltmechanik bei Blue-Green mit exklusiven Ressourcen
- Aufbau der Update- und Downgrade-Processoren, Umgang mit Zustand beim Rollback
- Cleanup-Details des Caches: Frist X, Referenzen

Discovery
- Endgültiges Modell für die Auflösung von Shared Services: Capabilities, Prioritäten beim Failover, Auswahlregeln

Sonstiges
- Wireformat des Schemas über Prozessgrenzen (die Sprache ist ein eigenes Format, Syntax-Vorschlag in Issue #4)
- Aufteilung der .proto-Dateien und der API-Oberfläche
- Verzeichnislayout unter ~/.cringle/
