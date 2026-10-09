# Data folder of a block

A block that keeps persistent state (a database file, a cache, an offset) gets a folder for it: `BlockContext.dataDirectory` (#256, Architecture 14.3). It is `null` if the engine gives none (a unit test of a block, for example).

- **Where:** `<home>/data/<project>/<blueprint>/<n>/<block id>` on the machine of the engine, where `<n>` is the number of the instance of the blueprint (the number at the end of the fabric id, `shop-app-1` → `1`). It does **not** depend on the id of the fabric, so it is the same after a redeploy, after an update of the project to a new version and after a Blue-Green update, which changes the id (`shop-app-1` ↔ `shop-app-1b`).
- **Lifetime:** the folder is created with the fabric and stays when the fabric is removed or the project is undeployed; it is not cleaned up by the framework. Delete it by hand to start a block from nothing.
- **Machines:** the data is on the machine of the engine. A project that is deployed again and lands on an engine of another machine starts with an empty folder. Engines of the same machine share `<home>/data`, so a move between them keeps the data.
- **One user at a time:** two running fabrics of the same instance must not write into the same folder. A block that uses the folder declares the exclusive resource `OTHER` with the label `data` in its definition (`spec/package-format.md`, section 4); a project with such a block is updated by stopping the old fabric first (`docs/management-server.md`, "Update strategy"), so the old and the new never run together.
- **Migration:** see below.
- **Safety:** the folder is below `<home>/data`; project, blueprint and block names are checked against the package name grammar before a path is built, and the path is checked not to leave the folder.

## Migration (processors)

A plugin names a `Processor` class in `processors.update` / `processors.downgrade` of its manifest; it migrates the folder of each block of that plugin. A project can name classes of the plugins it uses; they migrate the whole instance folder (`<home>/data/<project>/<blueprint>/<n>`). Write a `SteppedProcessor` (`step("2.0.0") { ... }`) to get a chain of versions for free.

- **When:** every start of a fabric, before the blocks start. The version a folder was written by is recorded in `<instance>/.cringle/versions.properties`. A first run, or a different plugin in the same block, only records the version. If the version is higher the update processor runs, if it is lower the downgrade processor of the version that runs now. No processor: the version is only recorded.
- **Backup:** before the processor runs, the folder is copied to `<folder>.backup-<from>` (a block `b` → `b.backup-1.0.0` next to it; the instance → `<n>.backup-1.0.0`). An existing backup is kept, so a retry does not overwrite the first one.
- **Failure:** the fabric does not start; its state is `MIGRATION_FAILED`, the failure holds the step, the message and the backup path. The version is not recorded, the data stays as the processor left it, nothing is retried by itself. After the cause is fixed (or the backup restored by hand) the next start of the fabric runs the processor again on the folder as it is.
- **Order:** the processors of the blocks in blueprint order, then the one of the project.
- Display in the management server, CLI and WebUI is #259.
