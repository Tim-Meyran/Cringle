// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The stylesheet has one theme, dark, and its text colors can be read: every text token on every surface it is used on has at least 4.5:1 (WCAG AA). */
class ThemeTest {
    private val css = ThemeTest::class.java.getResourceAsStream("/web/app.css")!!.use { String(it.readBytes()) }
    private val root = Regex(""":root\s*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(css)!!.groupValues[1]

    private fun rgb(hex: String): List<Double> = (0..2).map { hex.substring(1 + it * 2, 3 + it * 2).toInt(16) / 255.0 }

    private fun color(name: String): List<Double> {
        val value = Regex("--$name:\\s*([^;]+);").find(root)?.groupValues?.get(1)?.trim() ?: error("no token --$name")
        return if (value.startsWith("#")) rgb(value) else error("--$name is not a plain color: $value")
    }

    /** The tint `--name-tint` (rgba) laid over [under]. */
    private fun tint(name: String, under: List<Double>): List<Double> {
        val m = Regex("--$name-tint:\\s*rgba\\((\\d+),\\s*(\\d+),\\s*(\\d+),\\s*([0-9.]+)\\)").find(root)!!.groupValues
        val over = listOf(m[1], m[2], m[3]).map { it.toInt() / 255.0 }
        val alpha = m[4].toDouble()
        return over.indices.map { over[it] * alpha + under[it] * (1 - alpha) }
    }

    private fun luminance(c: List<Double>): Double {
        val l = c.map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
    }

    private fun contrast(a: List<Double>, b: List<Double>): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(text: String, background: String, foreground: List<Double>, surface: List<Double>) {
        val ratio = contrast(foreground, surface)
        assertTrue(ratio >= 4.5, "$text on $background has only %.2f:1".format(ratio))
    }

    @Test
    fun thereIsOneThemeAndItIsDark() {
        assertFalse(css.contains("prefers-color-scheme"), "no second theme")
        assertTrue(Regex("""color-scheme:\s*dark;""").containsMatchIn(root))
    }

    @Test
    fun textIsReadableOnEverySurface() {
        for (surface in listOf("bg", "surface", "surface-2", "surface-3")) {
            for (text in listOf("text", "text-muted", "text-faint")) assertReadable("--$text", "--$surface", color(text), color(surface))
        }
        for (surface in listOf("bg", "surface", "surface-2")) {
            for (name in listOf("accent", "success", "warning", "danger", "info")) assertReadable("--$name", "--$surface", color(name), color(surface))
        }
        assertReadable("--accent-ink", "--accent (primary button)", color("accent-ink"), color("accent"))
    }

    @Test
    fun badgesAndNoticesAreReadableOnTheirTint() {
        for (surface in listOf("surface", "surface-2", "bg")) {
            for (name in listOf("success", "warning", "danger", "info", "accent")) {
                assertReadable("--$name", "its tint on --$surface", color(name), tint(name, color(surface)))
            }
        }
    }
}
