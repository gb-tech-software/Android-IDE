package dev.android.ide.editor

/** Visual identity for a file; deliberately independent of its editor language. */
enum class FileIconKind {
    KOTLIN,
    JAVA,
    XML,
    SVG,
    JSON,
    YAML,
    MARKDOWN,
    PYTHON,
    TOML,
    ENV,
    GRADLE,
    PROTO,
    GRAPHQL,
    MAKE,
    CMAKE,
    LOCKFILE,
    DART,
    LUA,
    R,
    SCALA,
    PERL,
    ELIXIR,
    IMAGE,
    TEXT,
    HTML,
    CSS,
    JAVASCRIPT,
    TYPESCRIPT,
    C_CPP,
    RUST,
    GO,
    SWIFT,
    RUBY,
    PHP,
    SQL,
    C,
    SHELL,
    DOCKER,
    GIT,
    CONFIG,
    DATABASE,
    ARCHIVE,
    FONT,
    AUDIO,
    VIDEO,
    PDF,
    CODE,
    GENERIC,
}

/** Content representation, not an icon or editor-language decision. */
enum class FileContentKind {
    TEXT,
    RASTER_IMAGE,
    BINARY,
}

/**
 * File-name classification shared by the editor, file tree and SAF layer.
 * Language IDs are chosen only from Monaco 0.55.1's bundled language services
 * and basic-language contributions. An icon never implies an editor language.
 */
object EditorLanguageRegistry {
    const val PLAIN_TEXT = "plaintext"

    private data class Definition(
        val languageId: String,
        val extensions: Set<String> = emptySet(),
        val fileNames: Set<String> = emptySet(),
        val mimeType: String,
        val iconKind: FileIconKind,
        val contentKind: FileContentKind = FileContentKind.TEXT,
    )

    private val definitions = listOf(
        Definition("kotlin", extensions = setOf("kt", "kts"), mimeType = "text/x-kotlin", iconKind = FileIconKind.KOTLIN),
        Definition("kotlin", fileNames = setOf("build.gradle.kts", "settings.gradle.kts"), mimeType = "text/x-kotlin", iconKind = FileIconKind.GRADLE),
        Definition("java", extensions = setOf("java"), mimeType = "text/x-java", iconKind = FileIconKind.JAVA),
        Definition("xml", extensions = setOf("xml", "plist", "xsd", "xsl", "xslt"), mimeType = "text/xml", iconKind = FileIconKind.XML),
        // Monaco bundles XML, not a separate SVG language. SVG remains editable text and keeps its own icon.
        Definition("xml", extensions = setOf("svg"), mimeType = "image/svg+xml", iconKind = FileIconKind.SVG),
        Definition("json", extensions = setOf("json", "jsonc"), mimeType = "application/json", iconKind = FileIconKind.JSON),
        Definition(PLAIN_TEXT, extensions = setOf("json5"), mimeType = "application/json5", iconKind = FileIconKind.JSON),
        Definition("markdown", extensions = setOf("md", "mdx", "rmd"), mimeType = "text/markdown", iconKind = FileIconKind.MARKDOWN),
        // Monaco 0.55.1 has no Groovy grammar. Gradle Groovy files remain plain text;
        // Kotlin DSL files are handled above using Monaco's Kotlin grammar.
        Definition(PLAIN_TEXT, extensions = setOf("gradle"), mimeType = "text/x-groovy", iconKind = FileIconKind.GRADLE),
        Definition("python", extensions = setOf("py", "pyw", "pyi"), mimeType = "text/x-python", iconKind = FileIconKind.PYTHON),
        Definition("javascript", extensions = setOf("js", "mjs", "cjs"), mimeType = "text/javascript", iconKind = FileIconKind.JAVASCRIPT),
        Definition("javascriptreact", extensions = setOf("jsx"), mimeType = "text/javascript", iconKind = FileIconKind.JAVASCRIPT),
        Definition("typescript", extensions = setOf("ts", "mts", "cts"), mimeType = "text/typescript", iconKind = FileIconKind.TYPESCRIPT),
        Definition("typescriptreact", extensions = setOf("tsx"), mimeType = "text/typescript", iconKind = FileIconKind.TYPESCRIPT),
        Definition("html", extensions = setOf("html", "htm"), mimeType = "text/html", iconKind = FileIconKind.HTML),
        Definition("css", extensions = setOf("css"), mimeType = "text/css", iconKind = FileIconKind.CSS),
        Definition("scss", extensions = setOf("scss"), mimeType = "text/x-scss", iconKind = FileIconKind.CSS),
        Definition("less", extensions = setOf("less"), mimeType = "text/x-less", iconKind = FileIconKind.CSS),
        Definition(PLAIN_TEXT, extensions = setOf("sass"), mimeType = "text/x-sass", iconKind = FileIconKind.CSS),
        Definition("shell", extensions = setOf("sh", "bash", "zsh", "fish"), mimeType = "text/x-sh", iconKind = FileIconKind.SHELL),
        Definition("bat", extensions = setOf("bat", "cmd"), mimeType = "text/x-batch", iconKind = FileIconKind.SHELL),
        Definition("powershell", extensions = setOf("ps1", "psm1", "psd1"), mimeType = "text/x-powershell", iconKind = FileIconKind.SHELL),
        // Monaco's bundled C/C++ grammar uses the `cpp` language ID for both,
        // but the tree must not show a C++ icon for C sources and headers.
        Definition("cpp", extensions = setOf("c", "h"), mimeType = "text/x-csrc", iconKind = FileIconKind.C),
        Definition("cpp", extensions = setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), mimeType = "text/x-c++src", iconKind = FileIconKind.C_CPP),
        Definition("rust", extensions = setOf("rs"), mimeType = "text/x-rust", iconKind = FileIconKind.RUST),
        Definition("go", extensions = setOf("go", "mod", "sum"), mimeType = "text/x-go", iconKind = FileIconKind.GO),
        Definition("ruby", extensions = setOf("rb", "rake", "gemspec"), mimeType = "text/x-ruby", iconKind = FileIconKind.RUBY),
        Definition("swift", extensions = setOf("swift"), mimeType = "text/x-swift", iconKind = FileIconKind.SWIFT),
        Definition("php", extensions = setOf("php", "php3", "php4", "php5", "phtml"), mimeType = "text/x-php", iconKind = FileIconKind.PHP),
        // Monaco has no bundled TOML grammar; preserve the TOML icon without claiming language support.
        Definition(PLAIN_TEXT, extensions = setOf("toml"), mimeType = "text/x-toml", iconKind = FileIconKind.TOML),
        Definition("yaml", extensions = setOf("yaml", "yml"), mimeType = "text/x-yaml", iconKind = FileIconKind.YAML),
        Definition("sql", extensions = setOf("sql"), mimeType = "text/x-sql", iconKind = FileIconKind.SQL),
        Definition("protobuf", extensions = setOf("proto"), mimeType = "text/plain", iconKind = FileIconKind.PROTO),
        Definition("graphql", extensions = setOf("graphql", "gql"), mimeType = "text/graphql", iconKind = FileIconKind.GRAPHQL),
        Definition("ini", extensions = setOf("ini", "properties", "conf", "cfg", "env"), fileNames = setOf(".env", ".env.local", ".env.example", ".env.examples", ".env.development", ".env.production", ".env.test", ".env.development.local", ".env.production.local", ".editorconfig", ".envrc", ".dev.vars", ".dev.vars.local", ".dev.vars.example", "env.example", "env.examples", "dev.vars", "dev.vars.example"), mimeType = "text/plain", iconKind = FileIconKind.ENV),
        Definition("dart", extensions = setOf("dart"), mimeType = "text/x-dart", iconKind = FileIconKind.DART),
        Definition("lua", extensions = setOf("lua"), mimeType = "text/x-lua", iconKind = FileIconKind.LUA),
        Definition("r", extensions = setOf("r"), mimeType = "text/x-r-source", iconKind = FileIconKind.R),
        Definition("scala", extensions = setOf("scala", "sc"), mimeType = "text/x-scala", iconKind = FileIconKind.SCALA),
        Definition("perl", extensions = setOf("pl", "pm", "pod"), mimeType = "text/x-perl", iconKind = FileIconKind.PERL),
        Definition("elixir", extensions = setOf("ex", "exs"), mimeType = "text/x-elixir", iconKind = FileIconKind.ELIXIR),
        Definition("dockerfile", fileNames = setOf("dockerfile", "containerfile"), mimeType = "text/plain", iconKind = FileIconKind.DOCKER),
        Definition("yaml", fileNames = setOf("docker-compose.yml", "docker-compose.yaml"), mimeType = "text/x-yaml", iconKind = FileIconKind.DOCKER),
        // Monaco has no Make/CMake grammar in the pinned bundle.
        Definition(PLAIN_TEXT, fileNames = setOf("makefile", "gnumakefile"), mimeType = "text/x-makefile", iconKind = FileIconKind.MAKE),
        Definition(PLAIN_TEXT, fileNames = setOf(".gitignore", ".gitattributes", ".gitmodules", "gitignore"), mimeType = "text/plain", iconKind = FileIconKind.GIT),
        Definition("json", fileNames = setOf("package-lock.json", "npm-shrinkwrap.json"), mimeType = "application/json", iconKind = FileIconKind.LOCKFILE),
        Definition("yaml", fileNames = setOf("pnpm-lock.yaml"), mimeType = "text/x-yaml", iconKind = FileIconKind.LOCKFILE),
        Definition("yaml", fileNames = setOf("yarn.lock"), mimeType = "text/plain", iconKind = FileIconKind.LOCKFILE),
        Definition("json", fileNames = setOf("bun.lock", "composer.lock"), mimeType = "application/json", iconKind = FileIconKind.LOCKFILE),
        Definition("yaml", fileNames = setOf("bun.lockb"), mimeType = "application/octet-stream", iconKind = FileIconKind.LOCKFILE, contentKind = FileContentKind.BINARY),
        Definition(PLAIN_TEXT, fileNames = setOf("cargo.lock", "gemfile.lock", "podfile.lock", "mix.lock", "poetry.lock"), mimeType = "text/plain", iconKind = FileIconKind.LOCKFILE),
        Definition(PLAIN_TEXT, extensions = setOf("lock"), mimeType = "text/plain", iconKind = FileIconKind.LOCKFILE),
        Definition("ini", fileNames = setOf("gradle.properties"), mimeType = "text/plain", iconKind = FileIconKind.GRADLE),
        Definition("shell", fileNames = setOf("gradlew"), mimeType = "text/x-sh", iconKind = FileIconKind.GRADLE),
        Definition("bat", fileNames = setOf("gradlew.bat"), mimeType = "text/x-batch", iconKind = FileIconKind.GRADLE),
        Definition(PLAIN_TEXT, fileNames = setOf("cmakelists.txt"), mimeType = "text/plain", iconKind = FileIconKind.CMAKE),
        Definition(PLAIN_TEXT, extensions = setOf("txt", "rst", "adoc"), mimeType = "text/plain", iconKind = FileIconKind.TEXT),
        Definition(PLAIN_TEXT, fileNames = setOf("readme", "readme.txt", "license", "copying", "notice"), mimeType = "text/plain", iconKind = FileIconKind.TEXT),
        Definition(PLAIN_TEXT, extensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "tiff", "tif"), mimeType = "application/octet-stream", iconKind = FileIconKind.IMAGE, contentKind = FileContentKind.RASTER_IMAGE),
        Definition(PLAIN_TEXT, extensions = setOf("zip", "jar", "war", "aar", "tar", "gz", "bz2", "xz", "7z", "rar"), mimeType = "application/octet-stream", iconKind = FileIconKind.ARCHIVE, contentKind = FileContentKind.BINARY),
        Definition(PLAIN_TEXT, extensions = setOf("woff", "woff2", "ttf", "otf", "eot"), mimeType = "application/octet-stream", iconKind = FileIconKind.FONT, contentKind = FileContentKind.BINARY),
        Definition(PLAIN_TEXT, extensions = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac"), mimeType = "audio/*", iconKind = FileIconKind.AUDIO, contentKind = FileContentKind.BINARY),
        Definition(PLAIN_TEXT, extensions = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v"), mimeType = "video/*", iconKind = FileIconKind.VIDEO, contentKind = FileContentKind.BINARY),
        Definition(PLAIN_TEXT, extensions = setOf("db", "sqlite", "sqlite3", "realm"), mimeType = "application/octet-stream", iconKind = FileIconKind.DATABASE, contentKind = FileContentKind.BINARY),
        Definition(PLAIN_TEXT, extensions = setOf("pdf"), mimeType = "application/pdf", iconKind = FileIconKind.PDF, contentKind = FileContentKind.BINARY),
    )

    private val byFileName = definitions
        .flatMap { definition -> definition.fileNames.map { it to definition } }
        .toMap()
    private val byExtension = definitions
        .flatMap { definition -> definition.extensions.map { it to definition } }
        .toMap()

    fun languageForFileName(name: String): String = definitionFor(name)?.languageId ?: PLAIN_TEXT

    fun mimeTypeForFileName(name: String): String =
        definitionFor(name)?.mimeType ?: "text/plain"

    fun iconKindForFileName(name: String): FileIconKind =
        definitionFor(name)?.iconKind ?: FileIconKind.GENERIC

    fun contentKindForFileName(name: String): FileContentKind =
        definitionFor(name)?.contentKind ?: FileContentKind.TEXT

    fun templateForFileName(name: String): String? =
        if (languageForFileName(name) == "html") HTML_TEMPLATE else null

    private fun definitionFor(name: String): Definition? {
        val fileName = name.substringAfterLast('/').lowercase()
        if (fileName.endsWith(".gradle.kts")) return byFileName["build.gradle.kts"]
        // Monaco has no bundled dotenv language ID; its built-in INI grammar
        // provides highlighting for the shared key/value and comment syntax.
        if (fileName == ".env" || fileName.startsWith(".env.") || fileName == ".envrc" || fileName == ".dev.vars" || fileName.startsWith(".dev.vars.") || fileName == "dev.vars" || fileName.startsWith("dev.vars.")) return byExtension["env"]
        val extension = fileName.substringAfterLast('.', "")
        return byFileName[fileName] ?: byExtension[extension]
    }

    private const val HTML_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>New Page</title>
</head>
<body>
</body>
</html>
"""
}
