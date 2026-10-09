// android-ide/android/java/dev/android/ide/viewmodel/model/AppScreen.kt
//
// Top-level navigation destinations for the IDE application.

package dev.android.ide.viewmodel.model

enum class AppScreen {
    /** Orientation and top-level navigation entry point. */
    HOME,

    /** Recent projects list + open-project action. */
    PROJECTS,

    /** Monaco editor with file tree sidebar. */
    EDITOR,

    /** Developer-oriented details for the selected project. */
    PROJECT_DETAILS,

    /** Theme, preferences, and coming-soon feature previews. */
    SETTINGS,

    /** Terminal surface is unavailable until the runtime service is implemented. */
    TERMINAL,

    /** Browser surface is unavailable until the browser service is implemented. */
    BROWSER,

}
