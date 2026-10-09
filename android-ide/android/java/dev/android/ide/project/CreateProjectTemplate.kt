package dev.android.ide.project

/** User-facing starter choices offered by Create New. */
enum class CreateProjectTemplate(
    val title: String,
    val summary: String,
) {
    FROM_SCRATCH("Start from scratch", "Start with only a README.md and add your own files."),
    NODE_APP("Node.js application", "Start a runnable Node.js application."),
    NPM_PACKAGE("npm package", "Build a reusable package with npm."),
    PNPM_PACKAGE("pnpm package", "Build a reusable package with pnpm."),
    NODE_SERVER("Node.js HTTP server", "Start a small HTTP server and continue from Android IDE."),
    STATIC_WEB("Static web project", "Start an HTML, CSS, and JavaScript project."),
}
