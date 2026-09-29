// SPDX-License-Identifier: Apache-2.0

package cringle.engine

import kotlin.system.exitProcess

/** Entry point of an engine process. Exit code 2 signals invalid arguments, 0 a graceful shutdown. */
public fun main(args: Array<String>) {
    val engine = try {
        Engine.create(EngineArgs.parse(args.toList()))
    } catch (e: EngineArgsException) {
        System.err.println("error: ${e.message}")
        System.err.println(EngineArgs.USAGE)
        exitProcess(2)
    }
    System.err.println("WARNING: INSECURE DEV MODE - management API is unauthenticated (loopback only)")
    Runtime.getRuntime().addShutdownHook(Thread({ engine.stop() }, "engine-shutdown"))
    engine.start()
    println("management-port=${engine.managementPort}")
    System.out.flush()
    engine.awaitTermination()
}
