# Getting started

From nothing to a running application in about ten minutes: install, log in, build a plugin and a project, deploy, look at it. Each step names the document with the details.

You need a JDK 21 or newer on the machine. A Cringle installation is **one site**: the daemon, the management server (web interface, users) and the repository run together on one machine; more machines join later ([operations.md](operations.md), "More machines").

## 1. Install

Linux (systemd), in a terminal:

```bash
curl -fsSL https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.sh | sudo sh -s -- --start
```

Windows, in PowerShell (it asks for administrative rights itself):

```powershell
irm https://github.com/Tim-Meyran/Cringle/releases/latest/download/install.ps1 -OutFile install.ps1; powershell -ExecutionPolicy Bypass -File .\install.ps1 -Start
```

On a terminal the installer asks about the address the services listen on, the host name other machines use and the ports; the defaults are right for a first try. Options and the settings: [daemon-service.md](daemon-service.md).

## 2. Log in

```bash
sudo cat /var/lib/cringle/management/bootstrap-token        # Windows: type C:\ProgramData\Cringle\management\bootstrap-token
cringle login --server 127.0.0.1:7500 --yes --token-file <file with the token>
cringle whoami
cringle machine list
```

`--yes` accepts the key of the server on the same machine; for another machine compare the fingerprint first ([security.md](security.md)). The web interface is at `https://127.0.0.1:8443`; log in with the same token. Create a user of your own and give out invites instead of passing the admin token around ([users.md](users.md)).

## 3. Write a plugin

A plugin contributes blocks. Start from `kotlin/gradle-plugin/samples/sample-plugin` (a provider, a driver, a block `orders`, a test with the testkit, and the migration processors). Its build file is the whole description:

```kotlin
plugins { kotlin("jvm") version "2.1.10"; id("cringle.plugin") }
cringle {
    name = "acme-orders"
    provider("acme.orders.OrdersProvider")
    block("orders") { port("in", PortDirection.IN, "acme.orders/Order", TetherType.REQUEST_RESPONSE) /* ... */ }
}
```

```bash
./gradlew test cringlePackage            # build/distributions/acme-orders-1.2.0.cringle
```

Details: [gradle-plugin.md](gradle-plugin.md). Running a block in the IDE: [running-from-the-ide.md](running-from-the-ide.md).

## 4. Write a project

A project has no code: blueprints (which blocks, connected by which tethers), the plugins they come from, and where its fabrics run (roles and labels). Start from `kotlin/gradle-plugin/samples/sample-project` or from `samples/shared-service` (two projects, one provides a service the other uses).

```bash
./gradlew cringlePackage
```

Or draw the blueprint in the web interface (page *Drafts*) and deploy from there ([webui.md](webui.md)).

## 5. Publish and deploy

```bash
./gradlew cringlePublish -Pcringle.fingerprint=<fingerprint of the repository>   # plugin first, then the project
cringle engine create local                       # an engine to run on, once (see cli.md)
cringle engine start local <engine-id>
cringle deploy acme-shop
cringle fabric list
cringle logs --fabric <id>
```

`deploy` picks the newest version that fits, resolves the plugins (a lock file keeps the result), places the fabrics on engines with the right roles and starts them. A new version of a deployed project replaces the old one without a gap (blue-green) or, with data migrations, stop-then-start: [management-server.md](management-server.md), [migration-guide.md](migration-guide.md). Back: `cringle rollback acme-shop`.

## 6. Look at it

- `cringle fabric status <id>`: state, assertions ([observability.md](observability.md)).
- `cringle metrics --fabrics --tethers`, `cringle logs`, `cringle dwh query ...`: numbers, logs and recorded messages.
- `cringle debug break <fabric> <tether> on`: stop a tether and look at the value ([debugging.md](debugging.md)).
- The same in the web interface: machines, fabrics, deployments, logs, metrics.

## Where next

[operations.md](operations.md) to run it, [security.md](security.md) to understand who may do what, [cli.md](cli.md) for every command, [samples/README.md](../samples/README.md) for the samples, [acceptance-1.0.md](acceptance-1.0.md) for what is there and what is not.
