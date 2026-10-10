// SPDX-License-Identifier: Apache-2.0

package acme.orders

import cringle.contract.MigrationException
import cringle.contract.SteppedProcessor
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The update processor of the sample plugin (`processors.update` of its manifest, `docs/migration-guide.md`). The engine runs it before the blocks of a
 * fabric start, on the data folder of each block of this plugin, when the folder was written by an older version than the one that runs now. Each step is
 * "what version X changed": here version 1.1.0 moved `pending.txt` into the folder `orders/` and version 1.2.0 added a `schema-version` file.
 */
public class OrdersUpdate : SteppedProcessor() {
    init {
        step("1.1.0") { context ->
            val old = context.dataDirectory.resolve("pending.txt")
            if (Files.exists(old)) {
                val folder = Files.createDirectories(context.dataDirectory.resolve("orders"))
                Files.move(old, folder.resolve("pending.txt"), StandardCopyOption.ATOMIC_MOVE)
            }
        }
        step("1.2.0") { context ->
            Files.writeString(context.dataDirectory.resolve("schema-version"), "2\n")
        }
    }
}

/**
 * The downgrade processor (`processors.downgrade`): runs when the folder was written by a newer version than the one that runs now (a rollback). A step of a
 * downgrade processor undoes that version, and the steps run from the newest to the oldest.
 */
public class OrdersDowngrade : SteppedProcessor() {
    init {
        step("1.2.0") { context -> Files.deleteIfExists(context.dataDirectory.resolve("schema-version")) }
        step("1.1.0") { context ->
            val moved = context.dataDirectory.resolve("orders").resolve("pending.txt")
            if (Files.exists(moved)) {
                Files.move(moved, context.dataDirectory.resolve("pending.txt"), StandardCopyOption.ATOMIC_MOVE)
            } else if (Files.exists(context.dataDirectory.resolve("orders"))) {
                throw MigrationException("orders/ exists but holds no pending.txt: cannot move it back")
            }
        }
    }
}

/**
 * The processor of a project that depends on this plugin (`processors.update` of the project, `sample-project`): it gets the folder of the whole instance
 * of the project, below it one folder per block, and leaves a note for the operator.
 */
public class OrdersInstanceUpdate : SteppedProcessor() {
    init {
        step("0.3.0") { context -> context.log("the instance folder ${context.dataDirectory.fileName} was brought from ${context.from} to ${context.to}") }
    }
}
