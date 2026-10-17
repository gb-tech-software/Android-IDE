package dev.android.ide.ui.components

import dev.android.ide.data.model.EditorSettings

internal data class EditorSymbolPair(
    val label: String,
    val opening: String,
    val closing: String,
)

internal data class EditorSnippetShortcut(
    val label: String,
    val trigger: String,
    val body: String,
)

internal data class EditorSymbolBarContent(
    val pairs: List<EditorSymbolPair>,
    val punctuation: List<String>,
    val snippets: List<EditorSnippetShortcut>,
    val languageLabel: String,
    val contextLabel: String,
)

private data class SnippetContexts(
    val declarations: List<EditorSnippetShortcut>,
    val block: List<EditorSnippetShortcut>,
    val expression: List<EditorSnippetShortcut>,
    val markupTag: List<EditorSnippetShortcut> = emptyList(),
)

private fun snippet(label: String, trigger: String, body: String) =
    EditorSnippetShortcut(label = label, trigger = trigger, body = body)

internal fun editorSymbolBarContent(
    languageId: String,
    linePrefix: String,
    lineSuffix: String,
    hasSelection: Boolean,
): EditorSymbolBarContent {
    val language = languageId.lowercase()
    val isMarkup = language in setOf("html", "xml", "svg", "javascriptreact", "typescriptreact")
    val isJson = language == "json" || language == "jsonc"
    val isJsLike = language in setOf("javascript", "typescript", "javascriptreact", "typescriptreact")
    val isCssLike = language in setOf("css", "scss", "less")
    val isMarkdown = language == "markdown" || language == "mdx"

    val pairLabels = when {
        isJson -> listOf("{}", "[]", "\"\"")
        isMarkup -> listOf("<>", "\"\"", "''", "{}")
        isMarkdown -> listOf("**", "``", "[]")
        else -> EditorSettings.DEFAULT_SYMBOLS.filterNot { it == "<>" || it == "``" } +
            if (isJsLike || isMarkdown) listOf("``") else emptyList()
    }
    val pairs = pairLabels.distinct().mapNotNull { label ->
        when (label) {
            "()" -> EditorSymbolPair(label, "(", ")")
            "{}" -> EditorSymbolPair(label, "{", "}")
            "[]" -> EditorSymbolPair(label, "[", "]")
            "\"\"" -> EditorSymbolPair(label, "\"", "\"")
            "''" -> EditorSymbolPair(label, "'", "'")
            "``" -> EditorSymbolPair(label, "`", "`")
            "<>" -> EditorSymbolPair(label, "<", ">")
            "**" -> EditorSymbolPair(label, "**", "**")
            else -> null
        }
    }

    val punctuation = when {
        isJson -> listOf(":", ",")
        isMarkup -> listOf("=", "/", "-")
        isMarkdown -> listOf("#", "-", ">")
        language == "python" -> listOf(":", "=", "->", ".")
        language == "kotlin" || language == "java" -> listOf("=", ":", ";", "->", ".")
        isJsLike -> listOf("=", "=>", ".", "?.", ";", ":", "/")
        isCssLike -> listOf(":", ";", "#", ".", "-")
        else -> listOf("=", ":", ";", ".")
    }

    val contexts = when {
        language == "kotlin" -> SnippetContexts(
            declarations = listOf(
                snippet("fun", "fun", """fun ${'$'}{1:name}(${ '$' }{2:parameters}): ${ '$' }{3:Unit} {
	${'$'}0
}"""),
                snippet("class", "class", """class ${'$'}{1:Name} {
	${'$'}0
}"""),
                snippet("data class", "data", """data class ${'$'}{1:Name}(${ '$' }{2:val id: String})"""),
            ),
            block = listOf(
                snippet("if", "if", """if (${ '$' }{1:condition}) {
	${'$'}0
}"""),
                snippet("when", "when", """when (${ '$' }{1:value}) {
	${'$'}0
}"""),
                snippet("try", "try", """try {
	${'$'}0
} catch (${ '$' }{1:error}: Exception) {
	${'$'}0
}"""),
            ),
            expression = listOf(
                snippet("println", "println", """println(${ '$' }{1:value})"""),
                snippet("lambda", "lambda", """{ ${ '$' }{1:value} -> ${'$'}0 }"""),
                snippet("when", "when", """when (${ '$' }{1:value}) {
	${'$'}0
}"""),
            ),
        )
        language == "java" -> SnippetContexts(
            declarations = listOf(
                snippet("class", "class", """class ${'$'}{1:Name} {
	${'$'}0
}"""),
                snippet("main", "main", """public static void main(String[] args) {
	${'$'}0
}"""),
                snippet("method", "method", """public ${ '$' }{1:void} ${ '$' }{2:name}(${ '$' }{3:parameters}) {
	${'$'}0
}"""),
            ),
            block = listOf(
                snippet("if", "if", """if (${ '$' }{1:condition}) {
	${'$'}0
}"""),
                snippet("for", "for", """for (int ${ '$' }{1:i} = 0; ${ '$' }{1:i} < ${ '$' }{2:count}; ${ '$' }{1:i}++) {
	${'$'}0
}"""),
                snippet("try", "try", """try {
	${'$'}0
} catch (${ '$' }{1:Exception} e) {
	${'$'}0
}"""),
            ),
            expression = listOf(
                snippet("println", "System.out.println", """System.out.println(${ '$' }{1:value});"""),
                snippet("new", "new", """new ${ '$' }{1:Type}(${ '$' }{2:arguments})"""),
                snippet("lambda", "lambda", """(${ '$' }{1:parameters}) -> ${'$'}0"""),
            ),
        )
        language == "python" -> SnippetContexts(
            declarations = listOf(
                snippet("def", "def", """def ${'$'}{1:name}(${ '$' }{2:parameters}):
	${'$'}0"""),
                snippet("class", "class", """class ${'$'}{1:Name}:
	${'$'}0"""),
                snippet("main", "ifmain", """if __name__ == "__main__":
	${'$'}0"""),
            ),
            block = listOf(
                snippet("if", "if", """if ${ '$' }{1:condition}:
	${'$'}0"""),
                snippet("for", "for", """for ${ '$' }{1:item} in ${ '$' }{2:items}:
	${'$'}0"""),
                snippet("try", "try", """try:
	${'$'}0
except ${ '$' }{1:Exception} as error:
	pass"""),
            ),
            expression = listOf(
                snippet("print", "print", """print(${ '$' }{1:value})"""),
                snippet("lambda", "lambda", """lambda ${ '$' }{1:value}: ${'$'}0"""),
                snippet("list comp", "list", """[${ '$' }{1:value} for ${ '$' }{2:item} in ${ '$' }{3:items}]"""),
            ),
        )
        isMarkup -> SnippetContexts(
            declarations = listOf(
                snippet("div", "div", """<div class="${ '$' }{1:container}">
	${'$'}0
</div>"""),
                snippet("link", "link", """<a href="${ '$' }{1:#}">${ '$' }{2:link text}</a>"""),
                snippet("form", "form", """<form action="${ '$' }{1:/}" method="${ '$' }{2:post}">
	${'$'}0
</form>"""),
            ),
            block = listOf(
                snippet("section", "section", """<section>
	${'$'}0
</section>"""),
                snippet("list", "ul", """<ul>
	<li>${ '$' }{1:item}</li>
</ul>"""),
                snippet("button", "button", """<button type="button">${'$'}0</button>"""),
            ),
            expression = listOf(
                snippet("image", "img", """<img src="${ '$' }{1:image.png}" alt="${ '$' }{2:description}">"""),
                snippet("div", "div", """<div>${'$'}0</div>"""),
                snippet("button", "button", """<button type="button">${'$'}0</button>"""),
            ),
            markupTag = listOf(
                snippet("class", "class", """class="${ '$' }{1:value}""""),
                snippet("id", "id", """id="${ '$' }{1:value}""""),
                snippet("aria-label", "aria", """aria-label="${ '$' }{1:description}""""),
            ),
        )
        language == "javascript" -> SnippetContexts(
            declarations = listOf(
                snippet("function", "function", """function ${'$'}{1:name}(${ '$' }{2:args}) {
	${'$'}0
}"""),
                snippet("arrow", "const", """const ${ '$' }{1:name} = (${ '$' }{2:args}) => {
	${'$'}0
}"""),
                snippet("class", "class", """class ${'$'}{1:Name} {
	${'$'}0
}"""),
            ),
            block = listOf(
                snippet("if", "if", """if (${ '$' }{1:condition}) {
	${'$'}0
}"""),
                snippet("for", "for", """for (const ${ '$' }{1:item} of ${ '$' }{2:items}) {
	${'$'}0
}"""),
                snippet("try", "try", """try {
	${'$'}0
} catch (${ '$' }{1:error}) {
	${'$'}0
}"""),
            ),
            expression = listOf(
                snippet("log", "console", """console.log(${ '$' }{1:value});"""),
                snippet("async", "async", """async function ${'$'}{1:name}(${ '$' }{2:args}) {
	${'$'}0
}"""),
                snippet("template", "template", """`${ '$' }{1:value}`"""),
            ),
        )
        language == "typescript" -> SnippetContexts(
            declarations = listOf(
                snippet("function", "function", """function ${'$'}{1:name}(${ '$' }{2:args}): ${ '$' }{3:void} {
	${'$'}0
}"""),
                snippet("interface", "interface", """interface ${'$'}{1:Name} {
	${'$'}0
}"""),
                snippet("type", "type", """type ${'$'}{1:Name} = ${ '$' }{2:string};"""),
            ),
            block = listOf(
                snippet("if", "if", """if (${ '$' }{1:condition}) {
	${'$'}0
}"""),
                snippet("for", "for", """for (const ${ '$' }{1:item} of ${ '$' }{2:items}) {
	${'$'}0
}"""),
                snippet("try", "try", """try {
	${'$'}0
} catch (${ '$' }{1:error}) {
	${'$'}0
}"""),
            ),
            expression = listOf(
                snippet("arrow", "const", """const ${ '$' }{1:name} = (${ '$' }{2:args}): ${ '$' }{3:void} => {
	${'$'}0
}"""),
                snippet("log", "console", """console.log(${ '$' }{1:value});"""),
                snippet("type assertion", "as", """${ '$' }{1:value} as ${ '$' }{2:Type}"""),
            ),
        )
        isCssLike -> SnippetContexts(
            declarations = listOf(
                snippet("rule", "rule", """${ '$' }{1:.selector} {
	${ '$' }{2:property}: ${ '$' }{3:value};
}"""),
                snippet("media", "media", """@media (${ '$' }{1:max-width: 600px}) {
	${'$'}0
}"""),
                snippet("flex", "flex", """display: flex;
	gap: ${ '$' }{1:1rem};"""),
            ),
            block = listOf(
                snippet("property", "property", """${ '$' }{1:property}: ${ '$' }{2:value};"""),
                snippet("nested rule", "rule", """& ${ '$' }{1:.child} {
	${'$'}0
}"""),
                snippet("media", "media", """@media (${ '$' }{1:max-width: 600px}) {
	${'$'}0
}"""),
            ),
            expression = listOf(
                snippet("flex", "flex", """display: flex;"""),
                snippet("grid", "grid", """display: grid;
	grid-template-columns: repeat(${ '$' }{1:3}, minmax(0, 1fr));"""),
                snippet("center", "center", """display: flex;
	align-items: center;
	justify-content: center;"""),
            ),
        )
        isJson -> SnippetContexts(
            declarations = listOf(
                snippet("object", "object", """{
	"${ '$' }{1:key}": ${ '$' }{2:value}
}"""),
                snippet("array", "array", """[${ '$' }{1:item}]"""),
                snippet("property", "property", """"${ '$' }{1:key}": ${ '$' }{2:value}"""),
            ),
            block = listOf(
                snippet("object", "object", """{
	"${ '$' }{1:key}": ${ '$' }{2:value}
}"""),
                snippet("array", "array", """[${ '$' }{1:item}]"""),
                snippet("property", "property", """"${ '$' }{1:key}": ${ '$' }{2:value}"""),
            ),
            expression = listOf(
                snippet("property", "property", """"${ '$' }{1:key}": ${ '$' }{2:value}"""),
                snippet("array", "array", """[${ '$' }{1:item}]"""),
                snippet("object", "object", """{ "${ '$' }{1:key}": ${ '$' }{2:value} }"""),
            ),
        )
        isMarkdown -> SnippetContexts(
            declarations = listOf(
                snippet("heading", "#", """# ${'$'}{1:Heading}"""),
                snippet("code fence", "code", """```${'$'}{1:language}
${'$'}0
```"""),
                snippet("list", "-", """- ${'$'}{1:item}"""),
            ),
            block = listOf(
                snippet("code fence", "code", """```${'$'}{1:language}
${'$'}0
```"""),
                snippet("quote", ">", """> ${'$'}{1:quoted text}"""),
                snippet("task", "task", """- [ ] ${'$'}{1:task}"""),
            ),
            expression = listOf(
                snippet("link", "link", """[${'$'}{1:text}](${ '$' }{2:https://})"""),
                snippet("image", "image", """![${'$'}{1:alt}](${ '$' }{2:image.png})"""),
                snippet("bold", "bold", """**${'$'}{1:text}**"""),
            ),
        )
        else -> SnippetContexts(emptyList(), emptyList(), emptyList())
    }

    val withinMarkupTag = isMarkup && linePrefix.lastIndexOf('<') > linePrefix.lastIndexOf('>')
    val insideBlock = linePrefix.count { it == '{' } > linePrefix.count { it == '}' } ||
        linePrefix.trimEnd().endsWith('{') || lineSuffix.trimStart().startsWith('}') ||
        (language == "python" && linePrefix.trimEnd().endsWith(':'))
    val contextName: String
    val candidates = when {
        withinMarkupTag -> { contextName = "markup tag"; contexts.markupTag.ifEmpty { contexts.declarations } }
        linePrefix.isBlank() && !insideBlock -> { contextName = "line start"; contexts.declarations }
        insideBlock -> { contextName = "inside block"; contexts.block }
        else -> { contextName = "expression"; contexts.expression }
    }
    val typedWord = Regex("[\\p{L}_][\\p{L}\\p{N}_-]*$").find(linePrefix)?.value.orEmpty()
    val typedToken = linePrefix.takeLastWhile { !it.isWhitespace() }
    val typedPrefixes = listOf(typedWord, typedToken).filter { it.isNotBlank() }.distinct()
    val allTemplates = contexts.declarations + contexts.block + contexts.expression + contexts.markupTag
    val matching = if (typedPrefixes.isEmpty()) emptyList() else {
        candidates.filter { template -> typedPrefixes.any { template.trigger.startsWith(it, ignoreCase = true) } }
            .ifEmpty { allTemplates.filter { template -> typedPrefixes.any { template.trigger.startsWith(it, ignoreCase = true) } } }
    }
    val selectedTemplates = (matching.ifEmpty { candidates }).distinctBy { it.label }.take(3)
    val contextLabel = if (hasSelection) "selection" else contextName
    val displayLanguage = when (language) {
        "javascriptreact" -> "JSX"
        "typescriptreact" -> "TSX"
        "jsonc" -> "JSON"
        "mdx" -> "MDX"
        else -> language.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    return EditorSymbolBarContent(
        pairs = pairs,
        punctuation = punctuation,
        snippets = selectedTemplates,
        languageLabel = displayLanguage,
        contextLabel = contextLabel,
    )
}
