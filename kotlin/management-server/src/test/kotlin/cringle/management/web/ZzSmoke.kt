package cringle.management.web

import cringle.management.ServiceTestBase
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class ZzSmoke : ServiceTestBase() {
    @Test
    fun smoke() {
        val out = Path.of(System.getenv("SMOKE_DIR"))
        deploy("orders-service")
        val server = WebServer(core, null, 0).start()
        closeables += server
        Files.writeString(out.resolve("port"), "${server.port}")
        while (!Files.exists(out.resolve("stop"))) Thread.sleep(500)
    }
}
