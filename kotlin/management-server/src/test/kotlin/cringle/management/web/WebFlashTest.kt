// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import cringle.contract.UserRole
import cringle.management.ServiceTestBase
import cringle.router.users.FileUserStore
import cringle.router.users.UserManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The rule that keeps the self-refreshing lists from eating feedback (`.claude/skills/htmx/SKILL.md`): what a request answers to the person who made
 * it (errors, a new token, the form of the next step) is an out-of-band part of the answer for `#flash` in the page frame, and the lists that are
 * polled (`GET .../list`) contain none of it, so a refresh can neither remove it nor, with nothing to say, clear it.
 */
@Tag("integration")
class WebFlashTest : ServiceTestBase() {
    private lateinit var users: UserManager
    private lateinit var admin: WebTestClient

    private fun web() {
        users = UserManager(FileUserStore(dir.resolve("users.json")))
        val token = users.bootstrap()!!
        val server = WebServer(core, users, 0, failedLoginDelay = java.time.Duration.ZERO).start()
        closeables += server
        admin = WebTestClient(server.port, core.identity.publicKeyFingerprint).login(token)
    }

    private val oob = "hx-swap-oob=\"beforeend:#flash\""
    private val polled = listOf("machines", "engines", "fabrics", "users", "groups", "drafts", "deployments", "dwh", "trust", "packages")

    /** The main part of an answer: everything before the first out-of-band part. */
    private fun main(answer: String) = answer.substringBefore("<div $oob>")

    @Test
    fun aNewTokenComesAsAFlashOutsideTheListAndStaysWhenTheListRefreshes() {
        web()
        val id = users.createUser("bob", setOf(UserRole.VIEWER)).user.id
        val answer = admin.post("/users/$id/tokens", mapOf("label" to "laptop")).body()
        val token = Regex("data-copy=\"(crt_[^\"]+)\"").find(answer)!!.groupValues[1]
        assertTrue(answer.contains("<div $oob>") && answer.contains("data-sticky") && answer.contains("shown once"), answer)
        assertFalse(main(answer).contains(token), "the token must not be part of the list that is swapped")
        // the refresh sends no feedback at all, so htmx leaves #flash as it is
        val poll = admin.get("/users/list").body()
        assertFalse(poll.contains(token) || poll.contains("hx-swap-oob"), poll)
    }

    @Test
    fun anErrorIsAFlashToo() {
        web()
        val answer = admin.post("/groups", mapOf("name" to "")).body()
        assertTrue(answer.contains("<div $oob>") && answer.contains("class=\"notice error\" role=\"alert\""), answer)
        assertFalse(main(answer).contains("role=\"alert\""), main(answer))
    }

    @Test
    fun theFormOfTheNextStepIsAFlashAndTheListsCarryNoFeedback() {
        web()
        val answer = admin.post("/trust/probe", mapOf("address" to "127.0.0.1:1")).body()
        assertTrue(answer.contains("<div $oob>"), answer) // the probe fails here: the error is a flash
        for (page in polled) {
            val list = admin.get("/$page/list").body()
            assertFalse(list.contains("hx-swap-oob"), "$page list sends out-of-band parts")
            assertFalse(list.contains("class=\"notice"), "$page list holds a notice")
            assertFalse(list.contains("role=\"alert\""), "$page list holds an alert")
        }
    }

    @Test
    fun everyPageHasExactlyOneFlashRegionAndEveryPolledPageRefreshesOnlyTheList() {
        web()
        for (page in polled) {
            val html = admin.get("/$page").body()
            assertEquals(1, Regex("id=\"flash\"").findAll(html).count(), "$page: flash region")
            assertEquals(1, Regex("id=\"list\"").findAll(html).count(), "$page: list")
            assertTrue(html.contains("hx-get=\"/$page/list\"") && html.contains("hx-trigger=\"every 5s [cringleIdle()]\""), "$page: refresh")
            // the flash region is outside the polled list
            assertTrue(html.indexOf("id=\"flash\"") < html.indexOf("id=\"list\""), "$page: flash before list")
        }
    }
}
