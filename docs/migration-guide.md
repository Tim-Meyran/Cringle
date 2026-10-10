# Migration guide: versions, data, rollback

A new version of a plugin or a project often needs its stored data changed. Cringle runs **processors** for that, keeps a **backup** before each run, and can go **back** to the version before. This page is the path an author and an operator take; the details of the data folder are in [block-data.md](block-data.md), the update strategy in [management-server.md](management-server.md).

## What is migrated, and where

- A block writes only below its data folder: `<home>/data/<project>/<blueprint>/<n>/<block>` (`BlockContext.dataDirectory`). It does not depend on the id of the fabric or on the version, so the data survives an update.
- A **plugin** migrates the folder of each of its blocks. A **project** can migrate the whole instance folder `<home>/data/<project>/<blueprint>/<n>`.
- The version that wrote a folder is recorded in `<instance>/.cringle/versions.properties`.

## Writing a processor

A processor is a class with a public constructor without arguments, in the plugin (a project names a class of a plugin it uses). The simplest form is a `SteppedProcessor`: one step per version that changed the data.

```kotlin
class OrdersUpdate : SteppedProcessor() {
    init {
        step("1.1.0") { ctx -> /* move pending.txt into orders/ */ }
        step("1.2.0") { ctx -> /* write the schema version */ }
    }
}
```

On an update from 1.0.0 to 1.2.0 both steps run in ascending order; from 1.1.0 only the second. The downgrade processor undoes the steps in descending order. `MigrationContext` gives the folder, the version it comes from and the version it goes to.

Rules that keep a migration safe:

1. **Idempotent steps.** A failed run is repeated on the data as the processor left it.
2. **Only the folder you are given.** Never read or write outside it.
3. **One step per version.** Do not rewrite an old step after it was released.
4. **Offer a downgrade if you offer an update.** A rollback is refused up front if the running version (or a plugin whose version changes) has an update processor and the version to go back to has no downgrade processor, because the data could not be brought back. An update that loses information cannot be undone by a processor: take your own backup first (below).

Name the classes in the build (`docs/gradle-plugin.md`):

```kotlin
cringle {
    processors {
        update = "acme.orders.OrdersUpdate"
        downgrade = "acme.orders.OrdersDowngrade"
    }
}
```

The sample `kotlin/gradle-plugin/samples/sample-plugin` has both classes (`OrdersMigration.kt`) and `sample-project` has one for the project.

## What happens on a deploy

1. A project or a plugin with **processors** is always updated **stop, migrate, start**: the old fabric uses the data folder, so the old and the new never run together (the result says `stop-then-start (...)`). Without processors a project is updated blue-green (the new fabrics next to the old ones, the old ones removed when the new run) unless it has an exclusive resource, provides a service or `--no-blue-green` is given (`management-server.md`, "Update strategy").
2. Before the blocks start, the processors run (blocks in blueprint order, then the project).
3. Before a processor runs, the folder is copied to `<folder>.backup-<from>` next to it (`orders.backup-1.0.0`). An existing backup is kept, so a retry does not overwrite the first one.
4. On success the new version is recorded and the blocks start.

## When a migration fails

The deploy fails (`FAILED_PRECONDITION`) and the fabric does not start. Its state is `MIGRATION_FAILED`; `cringle fabric status <id>` and the page *Fabrics* show the step, the message and the path of the backup. The version is **not** recorded, nothing is retried by itself, and **and the previous version is not restored**, because its data may have been changed by the migration. The fabric stays on its engine, wanted running (it shows under *Needs attention*).

Then:

1. Fix the cause (a new version of the plugin) **or** restore the backup by hand: stop the fabric, delete the folder, rename `<folder>.backup-<from>` to the folder.
2. Run the migration again: `cringle fabric start <id>` (the *Retry* button of the web interface). Or remove the fabric (`cringle undeploy <project>`) and deploy another version.

## Rollback

```bash
cringle rollback <project>              # back to the version deployed before
cringle rollback <project> --to 1.0.0   # to a version named
```

The previous version is deployed again (with the lock file it had; an ordinary deploy, so stop-then-start for a project with processors); the **downgrade processor** of the version that comes back runs on the data (for example `3.0.0->2.0.0`), with a backup first. A rollback never restores a backup by itself: the data stays as the downgrade processor leaves it. Take your own backup (below) before an update that deletes data.

## Backups of your own

The automatic backup protects against a failed processor, not against a wrong result. Before a migration of important data, copy `<home>/data/<project>` while the project is stopped (`cringle undeploy <project>` or stop its fabrics). Restore by copying back with the project stopped.

## Checking a migration before it reaches production

- Test the processor class on a folder in a unit test (the testkit gives a temporary directory) from each older version you support.
- Run the update and the rollback in a test installation first. `OperationsEndToEndTest` in `kotlin/management-server` is the model: update over three versions, rollback, certificate renewal, restart.
