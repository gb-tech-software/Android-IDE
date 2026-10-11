package dev.android.ide.editor

/**
 * Shared file classification used by SAF creation, editor tabs, Monaco, and
 * the file tree. Keep language IDs limited to grammars bundled with Monaco.
 */
enum class FileIconKind {
    KOTLIN,
    JAVA,
    XML,
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

object EditorLanguageRegistry {
    const val PLAIN_TEXT = "plaintext"

    private data class Definition(
        val languageId: String,
        val extensions: Set<String> = emptySet(),
        val fileNames: Set<String> = emptySet(),
        val mimeType: String,
        val iconKind: FileIconKind,
    )

    private val definitions = listOf(
        Definition("kotlin", extensions = setOf("kt", "kts"), mimeType = "text/x-kotlin", iconKind = FileIconKind.KOTLIN),
        Definition("java", extensions = setOf("java"), mimeType = "text/x-java", iconKind = FileIconKind.JAVA),
        Definition("xml", extensions = setOf("xml", "plist", "xsd", "xsl", "xslt"), mimeType = "text/xml", iconKind = FileIconKind.XML),
        Definition("json", extensions = setOf("json", "jsonc", "json5"), mimeType = "application/json", iconKind = FileIconKind.JSON),
        Definition("markdown", extensions = setOf("md", "mdx"), mimeType = "text/markdown", iconKind = FileIconKind.MARKDOWN),
        Definition("groovy", extensions = setOf("gradle", "gradle.kts"), mimeType = "text/x-groovy", iconKind = FileIconKind.GRADLE),
        Definition("python", extensions = setOf("py", "pyw", "pyi"), mimeType = "text/x-python", iconKind = FileIconKind.PYTHON),
        Definition("javascript", extensions = setOf("js", "mjs", "cjs", "jsx"), mimeType = "text/javascript", iconKind = FileIconKind.JAVASCRIPT),
        Definition("typescript", extensions = setOf("ts", "mts", "cts", "tsx"), mimeType = "text/typescript", iconKind = FileIconKind.TYPESCRIPT),
        Definition("html", extensions = setOf("html", "htm"), mimeType = "text/html", iconKind = FileIconKind.HTML),
        Definition("css", extensions = setOf("css", "scss", "less", "sass"), mimeType = "text/css", iconKind = FileIconKind.CSS),
        Definition("shell", extensions = setOf("sh", "bash", "zsh", "fish"), mimeType = "text/x-sh", iconKind = FileIconKind.SHELL),
        Definition("bat", extensions = setOf("bat", "cmd"), mimeType = "text/x-batch", iconKind = FileIconKind.SHELL),
        Definition("powershell", extensions = setOf("ps1", "psm1", "psd1"), mimeType = "text/x-powershell", iconKind = FileIconKind.SHELL),
        Definition("c", extensions = setOf("c", "h"), mimeType = "text/x-csrc", iconKind = FileIconKind.C_CPP),
        Definition("cpp", extensions = setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), mimeType = "text/x-c++src", iconKind = FileIconKind.C_CPP),
        Definition("rust", extensions = setOf("rs"), mimeType = "text/x-rust", iconKind = FileIconKind.RUST),
        Definition("go", extensions = setOf("go", "mod", "sum"), mimeType = "text/x-go", iconKind = FileIconKind.GO),
        Definition("ruby", extensions = setOf("rb", "rake", "gemspec"), mimeType = "text/x-ruby", iconKind = FileIconKind.RUBY),
        Definition("swift", extensions = setOf("swift"), mimeType = "text/x-swift", iconKind = FileIconKind.SWIFT),
        Definition(PLAIN_TEXT, extensions = setOf("php", "php3", "php4", "php5", "phtml"), mimeType = "text/x-php", iconKind = FileIconKind.PHP),
        Definition(PLAIN_TEXT, extensions = setOf("toml"), mimeType = "text/x-toml", iconKind = FileIconKind.TOML),
        Definition("yaml", extensions = setOf("yaml", "yml"), mimeType = "text/x-yaml", iconKind = FileIconKind.YAML),
        Definition("sql", extensions = setOf("sql"), mimeType = "text/x-sql", iconKind = FileIconKind.SQL),
        Definition(PLAIN_TEXT, extensions = setOf("proto"), mimeType = "text/plain", iconKind = FileIconKind.PROTO),
        Definition(PLAIN_TEXT, extensions = setOf("graphql", "gql"), mimeType = "text/plain", iconKind = FileIconKind.GRAPHQL),
        Definition(PLAIN_TEXT, extensions = setOf("ini", "properties", "conf", "cfg", "env"), fileNames = setOf(".env", ".env.local", ".env.example", ".env.examples", ".env.development", ".env.production", ".env.test", ".env.development.local", ".env.production.local", "env.example", "env.examples", ".editorconfig"), mimeType = "text/plain", iconKind = FileIconKind.ENV),
        Definition(PLAIN_TEXT, extensions = setOf("dart"), mimeType = "text/plain", iconKind = FileIconKind.DART),
        Definition(PLAIN_TEXT, extensions = setOf("lua"), mimeType = "text/plain", iconKind = FileIconKind.LUA),
        Definition(PLAIN_TEXT, extensions = setOf("r", "rmd"), mimeType = "text/plain", iconKind = FileIconKind.R),
        Definition(PLAIN_TEXT, extensions = setOf("scala", "sc"), mimeType = "text/plain", iconKind = FileIconKind.SCALA),
        Definition(PLAIN_TEXT, extensions = setOf("pl", "pm", "pod"), mimeType = "text/plain", iconKind = FileIconKind.PERL),
        Definition(PLAIN_TEXT, extensions = setOf("ex", "exs"), mimeType = "text/plain", iconKind = FileIconKind.ELIXIR),
        Definition("dockerfile", fileNames = setOf("dockerfile", "containerfile"), mimeType = "text/plain", iconKind = FileIconKind.DOCKER),
        Definition("yaml", fileNames = setOf("docker-compose.yml", "docker-compose.yaml"), mimeType = "text/x-yaml", iconKind = FileIconKind.DOCKER),
        Definition(PLAIN_TEXT, fileNames = setOf("makefile", "gnumakefile"), mimeType = "text/x-makefile", iconKind = FileIconKind.MAKE),
        Definition(PLAIN_TEXT, fileNames = setOf(".gitignore", ".gitattributes", ".gitmodules", "gitignore"), mimeType = "text/plain", iconKind = FileIconKind.GIT),
        Definition("json", fileNames = setOf("package-lock.json", "npm-shrinkwrap.json"), mimeType = "application/json", iconKind = FileIconKind.LOCKFILE),
        Definition("yaml", fileNames = setOf("pnpm-lock.yaml"), mimeType = "text/x-yaml", iconKind = FileIconKind.LOCKFILE),
        Definition("yaml", fileNames = setOf("yarn.lock"), mimeType = "text/plain", iconKind = FileIconKind.LOCKFILE),
        Definition(PLAIN_TEXT, fileNames = setOf("gradle.properties", "gradlew", "gradlew.bat"), mimeType = "text/plain", iconKind = FileIconKind.GRADLE),
        // Monaco has no bundled CMake grammar in this project.
        Definition(PLAIN_TEXT, fileNames = setOf("cmakelists.txt"), mimeType = "text/plain", iconKind = FileIconKind.CMAKE),
        Definition(PLAIN_TEXT, extensions = setOf("txt", "rst", "adoc"), mimeType = "text/plain", iconKind = FileIconKind.TEXT),
        Definition(PLAIN_TEXT, fileNames = setOf("readme", "readme.txt", "license", "copying", "notice"), mimeType = "text/plain", iconKind = FileIconKind.TEXT),
        Definition("image", extensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tiff", "tif"), mimeType = "application/octet-stream", iconKind = FileIconKind.IMAGE),
        Definition(PLAIN_TEXT, extensions = setOf("zip", "jar", "war", "aar", "tar", "gz", "bz2", "xz", "7z", "rar"), mimeType = "application/octet-stream", iconKind = FileIconKind.ARCHIVE),
        Definition(PLAIN_TEXT, extensions = setOf("woff", "woff2", "ttf", "otf", "eot"), mimeType = "application/octet-stream", iconKind = FileIconKind.FONT),
        Definition(PLAIN_TEXT, extensions = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac"), mimeType = "audio/*", iconKind = FileIconKind.AUDIO),
        Definition(PLAIN_TEXT, extensions = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v"), mimeType = "video/*", iconKind = FileIconKind.VIDEO),
        Definition(PLAIN_TEXT, extensions = setOf("db", "sqlite", "sqlite3", "realm"), mimeType = "application/octet-stream", iconKind = FileIconKind.DATABASE),
        Definition(PLAIN_TEXT, extensions = setOf("pdf"), mimeType = "application/pdf", iconKind = FileIconKind.PDF),
    )

    private val byFileName = definitions
        .flatMap { definition -> definition.fileNames.map { it to definition } }
        .toMap()
    private val byExtension = definitions
        .flatMap { definition -> definition.extensions.map { it to definition } }
        .toMap()

    fun languageForFileName(name: String): String {
        val definition = definitionFor(name)
        return if (definition?.languageId == "image") PLAIN_TEXT else definition?.languageId ?: PLAIN_TEXT
    }

    fun mimeTypeForFileName(name: String): String =
        definitionFor(name)?.mimeType ?: "text/plain"

    fun iconKindForFileName(name: String): FileIconKind =
        definitionFor(name)?.iconKind ?: FileIconKind.GENERIC

    fun templateForFileName(name: String): String? =
        if (languageForFileName(name) == "html") HTML_TEMPLATE else null

    private fun definitionFor(name: String): Definition? {
        val fileName = name.substringAfterLast('/').lowercase()
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
