// SPDX-License-Identifier: Apache-2.0

package cringle.management.web

/**
 * The icons of the WebUI: inline SVG in a 24 x 24 grid, drawn with the current text color (stroke), so they follow the theme and need no file and
 * no style from outside (content security policy). The set is small on purpose: the entries of the navigation and the few actions that need a picture.
 */
internal object Icons {
    private val paths: Map<String, String> = mapOf(
        "dashboard" to "<rect x=\"3\" y=\"3\" width=\"7\" height=\"9\" rx=\"1.5\"/><rect x=\"14\" y=\"3\" width=\"7\" height=\"5\" rx=\"1.5\"/><rect x=\"14\" y=\"12\" width=\"7\" height=\"9\" rx=\"1.5\"/><rect x=\"3\" y=\"16\" width=\"7\" height=\"5\" rx=\"1.5\"/>",
        "connect" to "<path d=\"M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7\"/><path d=\"M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7\"/>",
        "machines" to "<rect x=\"2\" y=\"3\" width=\"20\" height=\"8\" rx=\"2\"/><rect x=\"2\" y=\"13\" width=\"20\" height=\"8\" rx=\"2\"/><path d=\"M6 7h.01M6 17h.01\"/>",
        "engines" to "<rect x=\"5\" y=\"5\" width=\"14\" height=\"14\" rx=\"2\"/><rect x=\"9\" y=\"9\" width=\"6\" height=\"6\" rx=\"1\"/><path d=\"M9 2v3M15 2v3M9 19v3M15 19v3M19 9h3M19 15h3M2 9h3M2 15h3\"/>",
        "fabrics" to "<path d=\"M12 2 2 7l10 5 10-5z\"/><path d=\"m2 17 10 5 10-5M2 12l10 5 10-5\"/>",
        "deployments" to "<path d=\"M22 2 11 13\"/><path d=\"M22 2 15 22l-4-9-9-4z\"/>",
        "debugger" to "<rect x=\"8\" y=\"6\" width=\"8\" height=\"14\" rx=\"4\"/><path d=\"M19 7l-3 2M5 7l3 2M19 19l-3-2M5 19l3-2M20 13h-4M4 13h4M10 4l1 2M14 4l-1 2\"/>",
        "logs" to "<path d=\"M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z\"/><path d=\"M14 2v6h6M8 13h8M8 17h8M8 9h2\"/>",
        "metrics" to "<path d=\"M22 12h-4l-3 9L9 3l-3 9H2\"/>",
        "dwh" to "<ellipse cx=\"12\" cy=\"5\" rx=\"9\" ry=\"3\"/><path d=\"M3 5v14c0 1.7 4 3 9 3s9-1.3 9-3V5M3 12c0 1.7 4 3 9 3s9-1.3 9-3\"/>",
        "drafts" to "<path d=\"M12 20h9\"/><path d=\"M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z\"/>",
        "config" to "<path d=\"M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6\"/>",
        "users" to "<path d=\"M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2\"/><circle cx=\"9\" cy=\"7\" r=\"4\"/><path d=\"M23 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8\"/>",
        "groups" to "<circle cx=\"12\" cy=\"8\" r=\"3\"/><circle cx=\"5\" cy=\"10\" r=\"2\"/><circle cx=\"19\" cy=\"10\" r=\"2\"/><path d=\"M7 20v-2a5 5 0 0 1 10 0v2M1 20v-1a3 3 0 0 1 3-3M23 20v-1a3 3 0 0 0-3-3\"/>",
        "invites" to "<rect x=\"2\" y=\"4\" width=\"20\" height=\"16\" rx=\"2\"/><path d=\"m22 7-10 6L2 7\"/>",
        "registries" to "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M2 12h20M12 2a15 15 0 0 1 0 20 15 15 0 0 1 0-20\"/>",
        "trust" to "<path d=\"M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z\"/><path d=\"m9 12 2 2 4-4\"/>",
        "packages" to "<path d=\"M16.5 9.4 7.5 4.2\"/><path d=\"M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4a2 2 0 0 0 1-1.7z\"/><path d=\"M3.3 7 12 12l8.7-5M12 22V12\"/>",
        "plus" to "<path d=\"M12 5v14M5 12h14\"/>",
        "x" to "<path d=\"M18 6 6 18M6 6l12 12\"/>",
        "dot" to "<circle cx=\"12\" cy=\"12\" r=\"3\"/>",
    )

    private val byLabel = mapOf(
        "Dashboard" to "dashboard", "Connect" to "connect", "Machines" to "machines", "Engines" to "engines", "Fabrics" to "fabrics", "Deployments" to "deployments",
        "Debugger" to "debugger", "Logs" to "logs", "Metrics" to "metrics", "Data warehouse" to "dwh", "Drafts" to "drafts", "Configuration" to "config",
        "Users" to "users", "Groups" to "groups", "Invites" to "invites", "Registries" to "registries", "Trust" to "trust", "Packages" to "packages",
    )

    /** The icon [name] (see [paths]); an unknown name is a dot. */
    fun svg(name: String, size: Int = 18, cssClass: String = "ico"): Html =
        raw("<svg class=\"$cssClass\" viewBox=\"0 0 24 24\" width=\"$size\" height=\"$size\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">${paths[name] ?: paths.getValue("dot")}</svg>")

    /** The icon of the navigation entry with [label]. */
    fun forEntry(label: String): Html = svg(byLabel[label] ?: "dot")
}
