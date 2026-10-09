/**
 * android-ide/android/assets/editor/monaco-init.js
 *
 * Monaco Editor initialisation and bidirectional bridge to the Kotlin layer.
 *
 * Inbound protocol  (Kotlin → JS via WebView.evaluateJavascript):
 *   { type: "loadFile",         path, content, language }
 *   { type: "setTheme",         theme }
 *   { type: "setFontSize",      size }
 *   { type: "requestSave",      path }
 *   { type: "closeTab",         path }
 *   { type: "forceLayout" }
 *   { type: "executeCommand",   command }
 *   { type: "insertText",       text }
 *   { type: "setEditorOptions", tabSize?, wordWrap?, lineNumbers?, fontSize? }
 *   { type: "selectMatch", line, column, length }
 *   { type: "showFind" }
 *   { type: "showReplace" }
 *
 * Outbound protocol (JS → Kotlin via AndroidBridge.onMessage):
 *   { type: "ready" }
 *   { type: "contentChanged", path, content }
 *   { type: "cursorMoved",    line, column }
 *   { type: "fileSaved",      path }
 *
 * Monaco version: 0.55.1 (bundled — see scripts/fetch-monaco.sh).
 * The require.config paths entry "vs" resolves to the local vs/ directory.
 * No network requests are made at runtime.
 *
 * Model URI scheme:
 *   The 'path' value from Kotlin is a SAF content:// URI. We encode it with
 *   encodeURIComponent:  androidide:///files/<encodeURIComponent(path)>
 *   'currentPath' always stores the original SAF URI for bridge messages.
 *
 * Layout strategy:
 *   automaticLayout is NOT used because Android WebView's ResizeObserver is
 *   unreliable. Instead, editor.layout() is called explicitly:
 *   1. After editor creation (best-effort initial sizing).
 *   2. On the window 'resize' event (orientation changes, IME show/hide).
 *   3. On a 'forceLayout' message from Kotlin (with a requestAnimationFrame
 *      retry to catch any pending Blink synchronization).
 *   4. In loadFile() after setModel(), with a requestAnimationFrame retry.
 *
 * Performance notes:
 *   - contextmenu disabled (Android long-press is handled natively).
 *   - links disabled (reduces highlight passes on every edit).
 *   - insertText uses editor.executeEdits for atomic paste with correct undo.
 *   - Content-change debounce is 150 ms (was 300 ms) for more responsive dirty-marking.
 *
 * Root cause of Monaco visibility defect on Android WebView (fixed here):
 *   DOM element offsetWidth / offsetHeight are driven by Blink's layout pass.
 *   That pass can complete AFTER Kotlin's evaluateJavascript fires, so those
 *   values may be 0 even though the WebView has its final dimensions.
 *   window.innerWidth / window.innerHeight are set by the Android WebView
 *   engine directly from the View's measured dimensions — they are always
 *   correct once the page has loaded. applyLayout() now uses window.inner*
 *   as the authoritative source instead of #editor-root.offsetWidth/Height.
 */

// ---------------------------------------------------------------------------
// Bridge
// ---------------------------------------------------------------------------

function postToNative(msg) {
  var json = JSON.stringify(msg);
  if (typeof window.AndroidBridge !== 'undefined') {
    window.AndroidBridge.onMessage(json);
  } else {
    window.parent.postMessage(json, '*');
  }
}

// ---------------------------------------------------------------------------
// Layout management
// ---------------------------------------------------------------------------

/**
 * Apply the current viewport dimensions to Monaco.
 * Uses window.innerWidth / window.innerHeight (always correct on Android WebView).
 */
function applyLayout() {
  if (!editor) return;
  var w = window.innerWidth  || 0;
  var h = window.innerHeight || 0;
  if (w > 0 && h > 0) {
    editor.layout({ width: w, height: h });
  }
}

window.addEventListener('resize', function () {
  applyLayout();
  requestAnimationFrame(applyLayout);
});

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

var editor = null;          // monaco.editor.IStandaloneCodeEditor
var currentPath = null;     // SAF URI of the currently loaded file
var contentChangeTimer = null;
var CONTENT_CHANGE_DEBOUNCE_MS = 150;   // faster dirty-marking (was 300)
var lspRequestId = 1;
var lspPending = Object.create(null);
var lspProviderDisposables = [];

// ---------------------------------------------------------------------------
// Monaco loader
// ---------------------------------------------------------------------------

function postLspMessage(message) {
  postToNative({ type: 'languageServerMessage', message: JSON.stringify(message) });
}

function flushPendingContentChange() {
  if (!editor || !currentPath || contentChangeTimer === null) return;
  clearTimeout(contentChangeTimer);
  contentChangeTimer = null;
  postToNative({ type: 'contentChanged', path: currentPath, content: editor.getValue() });
}

function requestLsp(method, params, timeoutMs) {
  var id = lspRequestId++;
  return new Promise(function (resolve, reject) {
    lspPending[id] = { resolve: resolve, reject: reject };
    postLspMessage({ jsonrpc: '2.0', id: id, method: method, params: params });
    // A failed or unavailable runtime must not leave Monaco's completion UI hanging.
    setTimeout(function () {
      if (lspPending[id]) {
        delete lspPending[id];
        reject(new Error('Language server request timed out'));
      }
    }, timeoutMs || 8000);
  });
}

function lspPosition(position) {
  return { line: Math.max(0, position.lineNumber - 1), character: Math.max(0, position.column - 1) };
}

function monacoPosition(position) {
  return { lineNumber: Math.max(1, (position.line || 0) + 1), column: Math.max(1, (position.character || 0) + 1) };
}

function lspRange(range) {
  if (!range) return undefined;
  return new monaco.Range(
    monacoPosition(range.start).lineNumber,
    monacoPosition(range.start).column,
    monacoPosition(range.end).lineNumber,
    monacoPosition(range.end).column
  );
}

function normalizeCompletionItems(result) {
  var items = Array.isArray(result) ? result : (result && result.items) || [];
  return items.map(function (item) {
    var textEdit = item.textEdit;
    var insertText = item.insertText || item.label || '';
    var range = textEdit && textEdit.range ? lspRange(textEdit.range) : undefined;
    if (textEdit && textEdit.newText != null) insertText = textEdit.newText;
    return {
      label: item.label || insertText,
      kind: item.kind || monaco.languages.CompletionItemKind.Text,
      detail: item.detail,
      documentation: item.documentation,
      filterText: item.filterText,
      sortText: item.sortText,
      insertText: insertText,
      range: range,
      insertTextRules: item.insertTextFormat === 2
        ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
        : undefined,
    };
  });
}

function localSnippetItems(language) {
  var snippets = {
    javascript: [
      ['fn', 'function ${1:name}(${2:args}) {\n\t$0\n}', 'Function'],
      ['log', 'console.log(${1:value});', 'Console log'],
      ['async', 'async function ${1:name}(${2:args}) {\n\t$0\n}', 'Async function'],
    ],
    typescript: [
      ['fn', 'function ${1:name}(${2:args}): ${3:void} {\n\t$0\n}', 'Function'],
      ['iface', 'interface ${1:Name} {\n\t$0\n}', 'Interface'],
      ['type', 'type ${1:Name} = ${2:unknown};', 'Type alias'],
    ],
    python: [
      ['def', 'def ${1:name}(${2:args}):\n\t$0', 'Function'],
      ['class', 'class ${1:Name}:\n\tdef __init__(self${2:, args}):\n\t\t$0', 'Class'],
      ['ifmain', 'if __name__ == "__main__":\n\t$0', 'Main guard'],
    ],
    html: [
      ['html5', '<!doctype html>\n<html lang="en">\n<head>\n\t<meta charset="UTF-8">\n\t<meta name="viewport" content="width=device-width, initial-scale=1.0">\n\t<title>${1:Document}</title>\n</head>\n<body>\n\t$0\n</body>\n</html>', 'HTML document'],
      ['div', '<div class="${1:container}">\n\t$0\n</div>', 'Div container'],
    ],
    css: [
      ['rule', '${1:.selector} {\n\t${2:property}: ${3:value};\n}', 'CSS rule'],
      ['media', '@media (${1:max-width: 600px}) {\n\t$0\n}', 'Media query'],
    ],
    json: [
      ['object', '"${1:key}": ${2:value}', 'JSON property'],
    ],
    kotlin: [
      ['fun', 'fun ${1:name}(${2:parameters}): ${3:Unit} {\n\t$0\n}', 'Function'],
      ['class', 'class ${1:Name} {\n\t$0\n}', 'Class'],
      ['data', 'data class ${1:Name}(${2:val id: String})', 'Data class'],
      ['when', 'when (${1:value}) {\n\t$0\n}', 'When expression'],
    ],
    java: [
      ['class', 'class ${1:Name} {\n\t$0\n}', 'Class'],
      ['main', 'public static void main(String[] args) {\n\t$0\n}', 'Main method'],
      ['method', 'public ${1:void} ${2:name}(${3:parameters}) {\n\t$0\n}', 'Method'],
      ['try', 'try {\n\t$0\n} catch (${1:Exception} e) {\n\t$0\n}', 'Try/catch'],
    ],
    markdown: [
      ['heading', '# ${1:Heading}', 'Heading'],
      ['link', '[${1:text}](${2:https://})', 'Link'],
      ['code', '```${1:language}\n$0\n```', 'Code fence'],
    ],
    xml: [
      ['element', '<${1:element}>\n\t$0\n</$1>', 'Element'],
      ['attribute', '${1:name}="${2:value}"', 'Attribute'],
    ],
    shell: [
      ['if', 'if [ ${1:condition} ]; then\n\t$0\nfi', 'If statement'],
      ['for', 'for ${1:item} in ${2:items}; do\n\t$0\ndone', 'For loop'],
      ['case', 'case ${1:value} in\n\t${2:pattern})\n\t\t$0\n\t\t;;\nesac', 'Case statement'],
      ['fn', '${1:name}() {\n\t$0\n}', 'Shell function'],
    ],
    groovy: [
      ['plugins', 'plugins {\n\tid ${1:"plugin.id"} version ${2:"1.0"}\n}', 'Gradle plugins block'],
      ['dependency', '${1:implementation}(${2:"group:artifact:version"})', 'Gradle dependency'],
    ],
  };
  snippets.javascriptreact = snippets.javascript;
  snippets.typescriptreact = snippets.typescript;
  snippets.jsonc = snippets.json;
  return (snippets[language] || []).map(function (item) {
    return {
      label: item[0],
      kind: monaco.languages.CompletionItemKind.Snippet,
      detail: item[2],
      insertText: item[1],
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range: undefined,
    };
  });
}

function handleLanguageServerMessage(raw) {
  var message;
  try { message = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (e) { return; }
  if (message.id != null && lspPending[message.id]) {
    var pending = lspPending[message.id];
    delete lspPending[message.id];
    if (message.error) pending.reject(new Error(message.error.message || 'Language server error'));
    else pending.resolve(message.result);
    return;
  }
  if (message.method === 'textDocument/publishDiagnostics') {
    var model = editor && editor.getModel();
    var diagnostics = (message.params && message.params.diagnostics) || [];
    if (model) {
      monaco.editor.setModelMarkers(model, 'androidide-lsp', diagnostics.map(function (diagnostic) {
        return {
          severity: diagnostic.severity === 1 ? monaco.MarkerSeverity.Error
            : diagnostic.severity === 2 ? monaco.MarkerSeverity.Warning
            : diagnostic.severity === 3 ? monaco.MarkerSeverity.Info
            : monaco.MarkerSeverity.Hint,
          message: diagnostic.message || '',
          source: diagnostic.source,
          startLineNumber: monacoPosition(diagnostic.range.start).lineNumber,
          startColumn: monacoPosition(diagnostic.range.start).column,
          endLineNumber: monacoPosition(diagnostic.range.end).lineNumber,
          endColumn: monacoPosition(diagnostic.range.end).column,
        };
      }));
    }
  }
}

function registerLanguageProviders() {
  var languages = ['javascript', 'typescript', 'javascriptreact', 'typescriptreact', 'python', 'html', 'css', 'json', 'jsonc', 'kotlin', 'java', 'xml', 'markdown', 'shell', 'groovy'];
  var lspCompletionLanguages = ['javascript', 'typescript', 'javascriptreact', 'typescriptreact', 'python', 'html', 'css', 'json', 'jsonc'];
  var localCompletionTriggers = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_'.split('');
  languages.forEach(function (language) {
    lspProviderDisposables.push(monaco.languages.registerCompletionItemProvider(language, {
      triggerCharacters: localCompletionTriggers,
      provideCompletionItems: function (model, position) {
        var prefix = model.getWordUntilPosition(position).word || '';
        var local = localSnippetItems(language).filter(function (item) {
          return !prefix || item.label.toLowerCase().indexOf(prefix.toLowerCase()) === 0;
        });
        return Promise.resolve({ suggestions: local });
      },
    }));
    if (lspCompletionLanguages.indexOf(language) !== -1) {
      lspProviderDisposables.push(monaco.languages.registerCompletionItemProvider(language, {
        triggerCharacters: ['.', ':', '/', '<', '"', "'"],
        provideCompletionItems: function (model, position) {
        return requestLsp('textDocument/completion', {
          textDocument: { uri: model.uri.toString() },
          position: lspPosition(position),
          context: { triggerKind: 1 },
        }, 600).then(function (result) {
          return { suggestions: normalizeCompletionItems(result) };
        }).catch(function () {
          return { suggestions: [] };
        });
        },
      }));
    }
    lspProviderDisposables.push(monaco.languages.registerHoverProvider(language, {
      provideHover: function (model, position) {
        return requestLsp('textDocument/hover', {
          textDocument: { uri: model.uri.toString() },
          position: lspPosition(position),
        }).then(function (result) {
          if (!result) return null;
          var contents = Array.isArray(result.contents) ? result.contents : [result.contents];
          return { contents: contents.map(function (content) {
            return typeof content === 'string' ? { value: content } : { value: content.value || '' };
          }), range: lspRange(result.range) };
        }).catch(function () { return null; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerDefinitionProvider(language, {
      provideDefinition: function (model, position) {
        return requestLsp('textDocument/definition', {
          textDocument: { uri: model.uri.toString() },
          position: lspPosition(position),
        }).then(function (result) {
          var locations = Array.isArray(result) ? result : (result ? [result] : []);
          return locations.filter(function (location) { return location.range; }).map(function (location) {
            return { uri: model.uri, range: lspRange(location.range) };
          });
        }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerReferenceProvider(language, {
      provideReferences: function (model, position, context) {
        return requestLsp('textDocument/references', {
          textDocument: { uri: model.uri.toString() },
          position: lspPosition(position),
          context: { includeDeclaration: !!context.includeDeclaration },
        }).then(function (result) {
          return (result || []).filter(function (location) { return location.range; }).map(function (location) {
            return { uri: model.uri, range: lspRange(location.range) };
          });
        }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerDocumentFormattingEditProvider(language, {
      provideDocumentFormattingEdits: function (model, options) {
        return requestLsp('textDocument/formatting', {
          textDocument: { uri: model.uri.toString() },
          options: { tabSize: options.tabSize, insertSpaces: options.insertSpaces },
        }).then(function (result) {
          return (result || []).filter(function (edit) { return edit.range; }).map(function (edit) {
            return { range: lspRange(edit.range), text: edit.newText || '' };
          });
        }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerDocumentRangeFormattingEditProvider(language, {
      provideDocumentRangeFormattingEdits: function (model, range, options) {
        return requestLsp('textDocument/rangeFormatting', {
          textDocument: { uri: model.uri.toString() },
          range: {
            start: lspPosition({ lineNumber: range.startLineNumber, column: range.startColumn }),
            end: lspPosition({ lineNumber: range.endLineNumber, column: range.endColumn }),
          },
          options: { tabSize: options.tabSize, insertSpaces: options.insertSpaces },
        }).then(function (result) {
          return (result || []).filter(function (edit) { return edit.range; }).map(function (edit) {
            return { range: lspRange(edit.range), text: edit.newText || '' };
          });
        }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerCodeActionProvider(language, {
      provideCodeActions: function (model, range, context) {
        return requestLsp('textDocument/codeAction', {
          textDocument: { uri: model.uri.toString() },
          range: { start: lspPosition({ lineNumber: range.startLineNumber, column: range.startColumn }), end: lspPosition({ lineNumber: range.endLineNumber, column: range.endColumn }) },
          context: { diagnostics: context.markers || [] },
        }).then(function (result) {
          return { actions: (result || []).filter(function (action) { return action.title; }).map(function (action) {
            return { title: action.title, kind: action.kind, isPreferred: action.isPreferred, edit: action.edit };
          }), dispose: function () {} };
        }).catch(function () { return { actions: [], dispose: function () {} }; });
      },
    }));
  });
}

require.config({ paths: { vs: 'vs' } });

require(['vs/editor/editor.main'], function () {

  // Define AndroidIDE dark theme
  monaco.editor.defineTheme('androidide-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'comment',  foreground: '6a9955' },
      { token: 'keyword',  foreground: '569cd6', fontStyle: 'bold' },
      { token: 'string',   foreground: 'ce9178' },
      { token: 'number',   foreground: 'b5cea8' },
      { token: 'type',     foreground: '4ec9b0' },
    ],
    colors: {
      'editor.background':              '#1e1e1e',
      'editor.foreground':              '#d4d4d4',
      'editorLineNumber.foreground':    '#858585',
      'editor.lineHighlightBackground': '#2d2d2d',
      'editorCursor.foreground':        '#aeafad',
      'editor.selectionBackground':     '#264f78',
    }
  });

  // Define AndroidIDE light theme
  monaco.editor.defineTheme('androidide-light', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'comment', foreground: '008000' },
      { token: 'keyword', foreground: '0000ff', fontStyle: 'bold' },
      { token: 'string',  foreground: 'a31515' },
      { token: 'number',  foreground: '098658' },
      { token: 'type',    foreground: '267f99' },
    ],
    colors: {
      'editor.background':              '#ffffff',
      'editor.foreground':              '#000000',
      'editorLineNumber.foreground':    '#237893',
      'editor.lineHighlightBackground': '#f0f0f0',
      'editorCursor.foreground':        '#000000',
      'editor.selectionBackground':     '#add6ff',
    }
  });

  editor = monaco.editor.create(document.getElementById('editor-root'), {
    value: '',
    language: 'plaintext',
    theme: 'androidide-dark',
    fontSize: 14,
    lineNumbers: 'on',
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    wordWrap: 'off',
    renderWhitespace: 'selection',
    // automaticLayout intentionally omitted — ResizeObserver is unreliable
    // on Android WebView. Layout is managed explicitly via applyLayout().
    padding: { top: 8, bottom: 8 },
    scrollbar: {
      vertical: 'auto',
      horizontal: 'auto',
      verticalScrollbarSize: 10,
      horizontalScrollbarSize: 10,
    },

    // ── Performance settings for Android WebView ─────────────────────────
    // Context menu disabled: Android handles long-press natively; the WebView
    // context menu is slow and duplicates functionality.
    contextmenu: false,

    // LSP-backed suggestions are debounced and cancelled by the native session;
    // keep Monaco's lightweight local suggestion UI enabled on mobile.
    quickSuggestions: { other: true, comments: false, strings: true },
    suggestOnTriggerCharacters: true,
    acceptSuggestionOnCommitCharacter: true,
    acceptSuggestionOnEnter: 'smart',
    snippetSuggestions: 'inline',
    parameterHints: { enabled: true },

    folding: false,

    // Link detection runs a regex over every visible line on every edit.
    links: false,

    renderValidationDecorations: 'on',

    // Smooth caret animation adds GPU compositing overhead on Android.
    cursorSmoothCaretAnimation: 'off',

    // Bracket pair colorization is purely cosmetic — disable for less render work.
    'bracketPairColorization.enabled': false,

    // formatOnPaste: true (default) passes pasted content through the language
    // formatter, which can silently corrupt indentation. Disabled here.
    formatOnPaste: false,

    // autoIndent 'advanced' runs expensive token-based analysis on every Enter
    // key press. 'brackets' is lightweight and handles 95% of real-world cases
    // (matching open/close brackets) without the overhead.
    autoIndent: 'brackets',

    // Glyph margin (left gutter for breakpoints, fold arrows etc.) allocates
    // a DOM element per visible line. Not needed without a debugger in Phase 1.
    glyphMargin: false,

    // Minimise the line-decoration column width; 5px provides visual separation
    // next to line numbers without spending DOM/paint budget.
    lineDecorationsWidth: 5,
  });

  registerLanguageProviders();

  // Initial layout — best-effort.
  applyLayout();
  requestAnimationFrame(applyLayout);

  // --- Content change (debounced) ---
  editor.onDidChangeModelContent(function () {
    if (!currentPath) return;
    clearTimeout(contentChangeTimer);
    contentChangeTimer = setTimeout(function () {
      postToNative({
        type: 'contentChanged',
        path: currentPath,
        content: editor.getValue(),
      });
    }, CONTENT_CHANGE_DEBOUNCE_MS);
  });

  // --- Cursor position ---
  editor.onDidChangeCursorPosition(function (e) {
    postToNative({
      type: 'cursorMoved',
      line:   e.position.lineNumber,
      column: e.position.column,
    });
  });

  // Keep the native toolbar in sync with Monaco's current range selection.
  editor.onDidChangeCursorSelection(function (e) {
    postToNative({
      type: 'selectionChanged',
      hasSelection: !e.selection.isEmpty(),
    });
  });

  // --- Scroll position report (debounced 500 ms) ---
  var scrollReportTimer = null;
  var SCROLL_REPORT_DEBOUNCE_MS = 500;
  editor.onDidScrollChange(function () {
    clearTimeout(scrollReportTimer);
    scrollReportTimer = setTimeout(function () {
      postToNative({ type: 'scrollPositionReport', scrollTop: editor.getScrollTop() });
    }, SCROLL_REPORT_DEBOUNCE_MS);
  });

  // --- Keyboard shortcut: Ctrl+S / Cmd+S ---
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, function () {
    if (!currentPath) return;
    postToNative({ type: 'fileSaved', path: currentPath });
  });

  // Focus is intentionally controlled by the native WebView touch listener.
  // JavaScript click/touchend focus calls used to run after Android long-press
  // selection handles appeared and collapse the selection.

  // Hide loading indicator and signal readiness to Kotlin
  document.getElementById('loading').classList.add('hidden');
  postToNative({ type: 'ready' });
});

// ---------------------------------------------------------------------------
// Public API — called by Kotlin via evaluateJavascript
// ---------------------------------------------------------------------------

function insertPairedText(opening, closing) {
  if (!editor || !opening || !closing) return;
  var model = editor.getModel();
  var selection = editor.getSelection();
  if (!model || !selection) return;

  var start = selection.getStartPosition();
  var startOffset = model.getOffsetAt(start);
  var selectedText = model.getValueInRange(selection);
  if (!selection.isEmpty()) {
    editor.executeEdits('androidide-pair', [{
      range: selection,
      text: opening + selectedText + closing,
      forceMoveMarkers: true,
    }]);
    var innerStart = model.getPositionAt(startOffset + opening.length);
    var innerEnd = model.getPositionAt(startOffset + opening.length + selectedText.length);
    editor.setSelection(new monaco.Selection(innerStart.lineNumber, innerStart.column, innerEnd.lineNumber, innerEnd.column));
  } else {
    var position = editor.getPosition();
    var line = model.getLineContent(position.lineNumber);
    var followingText = line.slice(position.column - 1, position.column - 1 + closing.length);
    var text = opening + (followingText === closing ? '' : closing);
    editor.executeEdits('androidide-pair', [{
      range: new monaco.Range(position.lineNumber, position.column, position.lineNumber, position.column),
      text: text,
      forceMoveMarkers: true,
    }]);
    editor.setPosition(model.getPositionAt(startOffset + opening.length));
  }
  editor.focus();
}

function insertEditorSnippet(trigger, snippet) {
  if (!editor || !snippet) return;
  var model = editor.getModel();
  var selection = editor.getSelection();
  if (!model || !selection) return;

  if (selection.isEmpty() && trigger) {
    var position = editor.getPosition();
    var beforeCursor = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
    var currentWord = beforeCursor.match(/[\w$.-]+$/);
    if (currentWord && trigger.toLowerCase().startsWith(currentWord[0].toLowerCase())) {
      var wordStart = position.column - currentWord[0].length;
      editor.setSelection(new monaco.Selection(position.lineNumber, wordStart, position.lineNumber, position.column));
    }
  }

  var snippetController = editor.getContribution('snippetController2');
  if (snippetController && typeof snippetController.insert === 'function') {
    snippetController.insert(snippet);
  } else {
    var range = editor.getSelection();
    var insertAt = model.getOffsetAt(range.getStartPosition());
    var marker = '\uE000';
    var text = snippet.replace(/\$0/, marker)
      .replace(/\$\{\d+:([^}]*)\}/g, '$1')
      .replace(/\$\{\d+\}/g, '')
      .replace(/\$\d+/g, '');
    var markerOffset = text.indexOf(marker);
    var caretOffset = markerOffset >= 0 ? markerOffset : text.length;
    text = text.replace(marker, '');
    editor.executeEdits('androidide-snippet', [{ range: range, text: text, forceMoveMarkers: true }]);
    editor.setPosition(model.getPositionAt(insertAt + caretOffset));
  }
  editor.focus();
}

window.androidIDE = {

  receiveMessage: function (msg) {
    if (typeof msg === 'string') {
      try { msg = JSON.parse(msg); } catch (e) { return; }
    }

    switch (msg.type) {

      case 'loadFile':
        loadFile(msg.path, msg.content, msg.language);
        break;

      case 'reloadFile':
        reloadFile(msg.path, msg.expectedCurrentContent, msg.content, msg.language);
        break;

      case 'clearActiveModel':
        flushPendingContentChange();
        if (editor) editor.setModel(null);
        currentPath = null;
        break;

      case 'setTheme':
        if (editor) {
          var theme = 'androidide-dark';
          if (msg.theme === 'light' || msg.theme === 'vs') {
            theme = 'androidide-light';
          } else if (msg.theme !== 'dark' && msg.theme !== 'vs-dark') {
            theme = msg.theme;
          }
          monaco.editor.setTheme(theme);
        }
        break;

      case 'setFontSize':
        if (editor) editor.updateOptions({ fontSize: msg.size });
        break;

      case 'setEditorOptions':
        if (editor) {
          var opts = {};
          var wrapChanged = false;
          if (msg.tabSize          != null) opts.tabSize          = msg.tabSize;
          if (msg.wordWrap         != null) { opts.wordWrap        = msg.wordWrap; wrapChanged = true; } // "on" / "off"
          if (msg.lineNumbers      != null) opts.lineNumbers       = msg.lineNumbers; // "on" / "off"
          if (msg.fontSize         != null) opts.fontSize          = msg.fontSize;
          if (msg.renderWhitespace != null) opts.renderWhitespace  = msg.renderWhitespace;
          if (msg.minimapEnabled         != null) opts.minimap              = { enabled: msg.minimapEnabled };
          if (msg.scrollBeyondLastLine   != null) opts.scrollBeyondLastLine = msg.scrollBeyondLastLine;
          if (msg.cursorStyle            != null) opts.cursorStyle          = msg.cursorStyle;
          if (msg.bracketPairColorization!= null) opts.bracketPairColorization = { enabled: msg.bracketPairColorization };
          if (msg.autoClosingBrackets    != null) opts.autoClosingBrackets  = msg.autoClosingBrackets;
          if (Object.keys(opts).length > 0) {
            editor.updateOptions(opts);
            // re-layout immediately or Monaco displays broken wrapped lines.
            if (wrapChanged) { applyLayout(); requestAnimationFrame(applyLayout); }
          }
        }
        break;

      case 'requestSave':
        if (editor && currentPath) {
          postToNative({ type: 'fileSaved', path: currentPath });
        }
        break;

      case 'closeTab':
        if (msg.path) {
          if (msg.path === currentPath) flushPendingContentChange();
          var safeSegment = encodeURIComponent(msg.path);
          var modelUri    = monaco.Uri.parse('androidide:///files/' + safeSegment);
          var model       = monaco.editor.getModel(modelUri);
          if (model) {
            if (msg.path === currentPath) {
              editor.setModel(null);
              currentPath = null;
            }
            model.dispose();
          } else if (msg.path === currentPath) {
            editor.setValue('');
            currentPath = null;
          }
        }
        break;

      // from the previous project cannot leak into the new one.
      case 'closeAllModels':
        flushPendingContentChange();
        lspPending = Object.create(null);
        monaco.editor.getModels().forEach(function (m) { m.dispose(); });
        if (editor) editor.setModel(null);
        currentPath = null;
        break;

      case 'forceLayout':
        applyLayout();
        requestAnimationFrame(applyLayout);
        break;

      case 'executeCommand':
        if (!editor || !msg.command) break;
        if (msg.command === 'focusEditor') {
          editor.focus();
          break;
        }
        if (msg.command === 'blurEditor') {
          window.androidIDE.blurEditor();
          break;
        }
        if (msg.command === 'closeSearch') {
          var toolbarFindController = editor.getContribution('editor.contrib.findController');
          if (toolbarFindController && toolbarFindController.closeFindWidget) toolbarFindController.closeFindWidget();
          editor.focus();
          break;
        }
        if (msg.command === 'requestCopy') {
          var copyModel = editor.getModel();
          var copySel   = editor.getSelection();
          if (copyModel && copySel && !copySel.isEmpty()) {
            var copyText = copyModel.getValueInRange(copySel);
            if (copyText) postToNative({ type: 'textCopied', text: copyText, isCut: false });
          }
          break;
        }
        if (msg.command === 'requestCut') {
          var cutModel = editor.getModel();
          var cutSel   = editor.getSelection();
          if (cutModel && cutSel && !cutSel.isEmpty()) {
            var cutText = cutModel.getValueInRange(cutSel);
            if (cutText) {
              postToNative({ type: 'textCopied', text: cutText, isCut: true });
              editor.executeEdits('cut', [{ range: cutSel, text: '', forceMoveMarkers: true }]);
            }
          }
          break;
        }
        // indent selected lines when a selection exists.
        if (msg.command === 'smartIndent') {
          var sel = editor.getSelection();
          if (sel && !sel.isEmpty()) {
            var indentAction = editor.getAction('editor.action.indentLines');
            if (indentAction) {
              indentAction.run().catch(function (e) {
                console.warn('[androidIDE] indentLines failed:', e);
              });
            }
          } else {
            var tabSize = editor.getModel()
              ? editor.getModel().getOptions().tabSize
              : 4;
            var spaces = '';
            for (var i = 0; i < tabSize; i++) spaces += ' ';
            var pos = editor.getPosition();
            editor.executeEdits('smartIndent', [{
              range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
              text: spaces,
              forceMoveMarkers: true,
            }]);
          }
          break;
        }
        // remove tab-width spaces behind cursor when no selection.
        if (msg.command === 'smartOutdent') {
          var sel2 = editor.getSelection();
          if (sel2 && !sel2.isEmpty()) {
            var outdentAction = editor.getAction('editor.action.outdentLines');
            if (outdentAction) {
              outdentAction.run().catch(function (e) {
                console.warn('[androidIDE] outdentLines failed:', e);
              });
            }
          } else {
            editor.trigger('keyboard', 'outdent', null);
          }
          break;
        }
        var action = editor.getAction(msg.command);
        if (action) {
          action.run().catch(function (e) {
            console.warn('[androidIDE] executeCommand action failed:', msg.command, e);
          });
        } else {
          editor.trigger('keyboard', msg.command, null);
        }
        break;

      case 'insertText':
        if (editor && msg.text) {
          // Use executeEdits for correct undo/redo behaviour and proper
          // indentation handling after multi-line pastes. This is more
          // correct than editor.trigger('keyboard', 'type', ...) which
          // bypasses Monaco's paste normalisation.
          var sel = editor.getSelection();
          editor.executeEdits('paste', [{
            range: sel,
            text:  msg.text,
            forceMoveMarkers: true,
          }]);
          // Reveal the cursor after paste so the viewport follows the insertion.
          requestAnimationFrame(function () {
            editor.revealPositionInCenterIfOutsideViewport(editor.getPosition());
          });
        }
        break;

      case 'insertPair':
        insertPairedText(msg.opening, msg.closing);
        break;

      case 'insertSnippet':
        insertEditorSnippet(msg.trigger || '', msg.snippet || '');
        break;

      case 'showFind':
        if (editor) {
          var findAction = editor.getAction('actions.find');
          if (findAction) findAction.run();
        }
        break;

      case 'showReplace':
        if (editor) {
          var replaceAction = editor.getAction('editor.action.startFindReplaceAction');
          if (replaceAction) replaceAction.run();
        }
        break;

      case 'closeSearch':
        if (editor) {
          var findController = editor.getContribution('editor.contrib.findController');
          if (findController && findController.closeFindWidget) findController.closeFindWidget();
          editor.focus();
        }
        break;

      case 'setCursorPosition':
        if (editor && msg.line != null && msg.column != null) {
          editor.setPosition({ lineNumber: msg.line, column: msg.column });
          editor.revealPositionInCenter({ lineNumber: msg.line, column: msg.column });
        }
        break;

      case 'selectMatch':
        if (editor) {
          var matchModel = editor.getModel();
          if (!matchModel) break;
          var matchLine = Number(msg.line);
          var matchColumn = Number(msg.column);
          var matchLength = Number(msg.length);
          if (!isFinite(matchLine) || matchLine < 1) matchLine = 1;
          if (!isFinite(matchColumn) || matchColumn < 1) matchColumn = 1;
          if (!isFinite(matchLength) || matchLength < 0) matchLength = 0;
          matchLine = Math.min(Math.floor(matchLine), matchModel.getLineCount());
          var maxColumn = matchModel.getLineMaxColumn(matchLine);
          matchColumn = Math.min(Math.floor(matchColumn), maxColumn);
          var matchEndColumn = Math.min(matchColumn + Math.floor(matchLength), maxColumn);
          var matchRange = new monaco.Range(matchLine, matchColumn, matchLine, matchEndColumn);
          editor.setSelection(matchRange);
          editor.revealRangeInCenter(matchRange);
          editor.focus();
        }
        break;

      case 'setScrollPosition':
        if (editor && msg.scrollTop != null) {
          editor.setScrollPosition({ scrollTop: msg.scrollTop });
        }
        break;

      case 'languageServerMessage':
        handleLanguageServerMessage(msg.message);
        window.dispatchEvent(new CustomEvent('androidide-language-server-message', {
          detail: {
            projectId: msg.projectId,
            serverId: msg.serverId,
            message: msg.message,
          },
        }));
        break;

      default:
        console.warn('[androidIDE] Unknown message type:', msg.type);
    }
  },

  /**
   * Blur the Monaco textarea to dismiss the soft keyboard on Android.
   */
  blurEditor: function () {
    if (editor) {
      var domNode = editor.getDomNode();
      if (domNode) {
        var ta = domNode.querySelector('textarea');
        if (ta) ta.blur();
      }
    }
  },
};

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------

/**
 * Load a file into the Monaco editor.
 *
 * @param {string} path     - Original SAF content:// URI.
 * @param {string} content  - Full file text.
 * @param {string} language - Monaco language ID (e.g. "kotlin", "java").
 */
function loadFile(path, content, language) {
  if (!editor) return;
  if (currentPath !== path) flushPendingContentChange();
  currentPath = path;
  if (editor.getModel()) monaco.editor.setModelMarkers(editor.getModel(), 'androidide-lsp', []);

  var safeSegment = encodeURIComponent(path);
  var uri         = monaco.Uri.parse('androidide:///files/' + safeSegment);
  var model       = monaco.editor.getModel(uri);

  if (model) {
    // An existing model is the authoritative in-memory document. Kotlin's
    // draft event is debounced, so replacing it here with the last disk value
    // during a rotation/rebind can erase keystrokes that Monaco already owns.
    // Recovery explicitly sends closeTab first when it must replace a model.
    if (model.getLanguageId() !== language) {
      monaco.editor.setModelLanguage(model, language);
    }
  } else {
    model = monaco.editor.createModel(content, language, uri);
  }

  editor.setModel(model);
  editor.setScrollPosition({ scrollTop: 0, scrollLeft: 0 });

  // Apply layout using window.innerWidth/innerHeight, then schedule a
  // follow-up pass after Blink's next paint cycle.
  applyLayout();
  requestAnimationFrame(applyLayout);

  // Do not focus during file initialization. Native touch handling controls
  // focus and IME visibility; focusing here opens the keyboard while files load.
}

/** Reload only a model whose current text still matches the native clean snapshot. */
function reloadFile(path, expectedCurrentContent, content, language) {
  if (!editor || !path) return;
  var wasActive = currentPath === path;
  if (wasActive) flushPendingContentChange();
  var uri = monaco.Uri.parse('androidide:///files/' + encodeURIComponent(path));
  var oldModel = monaco.editor.getModel(uri);
  if (oldModel && expectedCurrentContent != null && oldModel.getValue() !== expectedCurrentContent) {
    if (wasActive) {
      postToNative({ type: 'contentChanged', path: path, content: oldModel.getValue() });
    }
    return;
  }
  if (oldModel) oldModel.dispose();
  var model = monaco.editor.createModel(content, language, uri);
  if (wasActive) {
    currentPath = path;
    editor.setModel(model);
    editor.setScrollPosition({ scrollTop: 0, scrollLeft: 0 });
    applyLayout();
    requestAnimationFrame(applyLayout);
  }
}
