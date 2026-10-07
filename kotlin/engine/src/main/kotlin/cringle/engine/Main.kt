// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import cringle.common.logging.CringleLogging
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

/** Entry point of an engine process. Exit code 2 signals invalid arguments, 0 a graceful shutdown. */
public fun main(args: Array<String>) {
    val engine = try {
        val parsed = EngineArgs.parse(args.toList())
        CringleLogging.init(CringleHome.resolve(parsed.home), "engine", "main")
        Engine.create(parsed)
    } catch (e: EngineArgsException) {
        System.err.println("error: ${e.message}")
        System.err.println(EngineArgs.USAGE)
        exitProcess(2)
    }
    Runtime.getRuntime().addShutdownHook(Thread({ engine.stop() }, "engine-shutdown"))
    engine.start()
    LoggerFactory.getLogger("cringle.engine").info("engine started on management port {}", engine.managementPort)
    println("management-port=${engine.managementPort}")
    System.out.flush()
    engine.awaitTermination()
}
