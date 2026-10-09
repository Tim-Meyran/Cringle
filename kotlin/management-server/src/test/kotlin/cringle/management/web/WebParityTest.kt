// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.management.ManagementStore
import cringle.management.test.ManagementTls
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CLI and WebUI do the same simple operations (#214): every command of the CLI either has a route of the web layer in the table below, which is also the table in
 * `docs/webui.md`, or is named as having none, with the reason. A new CLI command fails this test until it is put in one of the two lists.
 */
class WebParityTest {
    @TempDir
    lateinit var dir: Path

    /** command → `METHOD path` (a path variable filled with a value). */
    private val routes = mapOf(
        "login" to "POST /login", "logout" to "POST /logout", "whoami" to "GET /",
        "machine add" to "POST /machines", "machine list" to "GET /machines", "machine remove" to "POST /machines/m1/remove",
        "engine create" to "POST /engines", "engine start" to "POST /engines/m1/e1/start", "engine stop" to "POST /engines/m1/e1/stop",
        "engine delete" to "POST /engines/m1/e1/delete", "engine list" to "GET /engines", "engine status" to "GET /engines/list", "engine tag" to "POST /engines/m1/e1/tags",
        "fabric list" to "GET /fabrics", "fabric status" to "GET /fabrics/m1/e1/f1", "fabric start" to "POST /fabrics/m1/e1/f1/start",
        "fabric stop" to "POST /fabrics/m1/e1/f1/stop", "fabric remove" to "POST /fabrics/m1/e1/f1/remove",
        "deploy" to "POST /deployments", "rollback" to "POST /deployments/p/rollback", "undeploy" to "POST /deployments/p/undeploy",
        "bind" to "POST /bindings", "unbind" to "POST /bindings/p/s/unbind", "bindings" to "GET /deployments/list",
        "logs" to "GET /logs/list", "metrics" to "GET /metrics/list",
        "dwh list" to "GET /dwh/list", "dwh query" to "GET /dwh/records", "dwh record" to "POST /dwh/recording", "dwh retention" to "POST /dwh/retention",
        "router add" to "POST /trust/routers", "router remove" to "POST /trust/routers/remove", "router list" to "GET /trust",
        "trust list" to "GET /trust/list", "trust add" to "POST /trust/probe", "trust add-component" to "POST /trust/components", "trust revoke" to "POST /trust/revoke",
        "repo publish" to "POST /packages/upload", "repo list" to "GET /packages", "repo versions" to "GET /packages/list", "repo trust" to "POST /packages/p/trust",
        "user create" to "POST /users", "user list" to "GET /users", "user delete" to "POST /users/u1/delete",
        "user grant" to "POST /users/u1/grant", "user revoke" to "POST /users/u1/revoke",
        "group create" to "POST /groups", "group list" to "GET /groups", "group grant" to "POST /groups/g1/grant", "group revoke" to "POST /groups/g1/revoke",
        "token create" to "POST /users/u1/tokens", "token list" to "GET /users/list", "token revoke" to "POST /tokens/t1/revoke",
        "config list" to "GET /config/m1/list", "config get" to "GET /config/m1", "config set" to "POST /config/m1/bind", "config unset" to "POST /config/m1/bind/unset",
        "registry key" to "GET /registries", "registry trust" to "POST /registries", "registry list" to "GET /registries/list", "registry untrust" to "POST /registries/r1/delete",
        "registry grant" to "POST /registries/r1/grant", "registry revoke" to "POST /registries/r1/revoke", "registry issue-token" to "POST /registries/token",
    )

    /** The commands that have no page, and why. */
    private val withoutPage = mapOf(
        "engine collect" to "switches the log collection of a daemon; an operator task of a machine",
        "cache cleanup" to "maintenance of the package cache, run by the ManagementServer on a schedule (--cache-max-unused-days)",
        "recover" to "runs at the start of the ManagementServer",
        "repo download" to "downloads a file; the browser has no use for it",
        "cert status" to "reads the certificate files of the local home; no server involved",
        "cert renew" to "renews the certificate files of the local home; no server involved",
        "self-update" to "updates the installation of the command line tool",
        "setup" to "changes the settings file of the installed services on this machine; no server involved",
        "shell" to "the interactive mode of the command line tool; the web interface is the interactive way there",
    )

    private fun cliCommands(): Set<String> {
        val source = Files.readString(Path.of("..", "cli", "src", "main", "kotlin", "cringle", "cli", "Commands.kt"))
        return Regex("""listOf\("([a-z-]+)"(?:, "([a-z-]+)")?\), """").findAll(source).map { listOfNotNull(it.groupValues[1], it.groupValues[2].ifEmpty { null }).joinToString(" ") }.toSet()
    }

    @Test
    fun everyCliCommandHasARouteOrAReason() {
        val commands = cliCommands()
        assertTrue(commands.size > 40, "the commands of the CLI were not found: $commands")
        assertEquals(commands.sorted(), (routes.keys + withoutPage.keys).sorted(), "the CLI and the parity lists differ")
    }

    @Test
    fun everyRouteOfTheTableExistsAndTheDocsListsEveryCommand() {
        val users = UserManager(FileUserStore(dir.resolve("users.json")))
        val tls = ManagementTls(dir.resolve("tls"))
        val core = tls.core(ManagementStore(dir.resolve("state.json")))
        val web = WebServer(core, users, 0)
        try {
            for ((command, route) in routes) {
                val (method, path) = route.split(' ', limit = 2)
                assertNotNull(web.router.find(method, path), "'cringle $command' has no route $route")
            }
        } finally {
            web.close()
            core.close()
        }
        val docs = Files.readString(Path.of("..", "..", "docs", "webui.md"))
        for (command in routes.keys + withoutPage.keys) assertTrue(docs.contains("`cringle $command`"), "docs/webui.md does not mention `cringle $command`")
    }
}
