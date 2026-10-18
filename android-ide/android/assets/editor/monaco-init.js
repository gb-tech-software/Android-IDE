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
var editorReadOnly = false;
var monacoReady = false;
var pendingNativeMessages = [];
var contentChangeTimer = null;
var CONTENT_CHANGE_DEBOUNCE_MS = 150;   // faster dirty-marking (was 300)
var contentRevision = 0;
var lspRequestId = 1;
var lspPending = Object.create(null);
var lspProviderDisposables = [];
var localLanguageProvidersRegistered = false;
var languageProvidersRegistered = false;
var languageFeaturesRequested = false;
var lspDiagnosticsByPath = Object.create(null);

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
  postToNative({ type: 'contentChanged', path: currentPath, content: editor.getValue(), revision: ++contentRevision });
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

function lspLocationToMonaco(location, fallbackUri) {
  if (!location) return null;
  var uri = location.uri || location.targetUri;
  var range = location.range || location.targetSelectionRange || location.targetRange;
  if (!range) return null;
  return { uri: uri ? monaco.Uri.parse(uri) : fallbackUri, range: lspRange(range) };
}

/** Convert LSP WorkspaceEdit changes into Monaco's mobile-safe edit shape. */
function lspWorkspaceEditToMonaco(edit) {
  if (!edit) return { edits: [] };
  var result = [];
  Object.keys(edit.changes || {}).forEach(function (uri) {
    (edit.changes[uri] || []).forEach(function (change) {
      if (!change || !change.range || change.newText == null) return;
      result.push({ resource: monaco.Uri.parse(uri), textEdit: { range: lspRange(change.range), text: change.newText } });
    });
  });
  (edit.documentChanges || []).forEach(function (change) {
    var document = change && change.textDocument;
    if (!document || !Array.isArray(change.edits)) return;
    change.edits.forEach(function (entry) {
      var textEdit = entry && (entry.edit || entry);
      if (!textEdit || !textEdit.range || textEdit.newText == null) return;
      result.push({ resource: monaco.Uri.parse(document.uri), textEdit: { range: lspRange(textEdit.range), text: textEdit.newText } });
    });
  });
  return { edits: result };
}

function lspDocumentSymbolToMonaco(symbol) {
  return {
    name: symbol.name,
    detail: symbol.detail,
    kind: symbol.kind || monaco.languages.SymbolKind.Namespace,
    range: lspRange(symbol.range),
    selectionRange: lspRange(symbol.selectionRange || symbol.range),
    children: (symbol.children || []).filter(function (child) { return child && child.name && child.range; }).map(lspDocumentSymbolToMonaco),
  };
}

function modelUriForDocument(path) {
  return monaco.Uri.parse('androidide:///files/' + encodeURIComponent(path));
}

function lspDiagnosticsToMarkers(diagnostics) {
  return diagnostics.map(function (diagnostic) {
    var range = diagnostic.range || { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } };
    return {
      severity: diagnostic.severity === 1 ? monaco.MarkerSeverity.Error
        : diagnostic.severity === 2 ? monaco.MarkerSeverity.Warning
        : diagnostic.severity === 3 ? monaco.MarkerSeverity.Info
        : monaco.MarkerSeverity.Hint,
      message: diagnostic.message || '',
      source: diagnostic.source,
      startLineNumber: monacoPosition(range.start).lineNumber,
      startColumn: monacoPosition(range.start).column,
      endLineNumber: monacoPosition(range.end).lineNumber,
      endColumn: monacoPosition(range.end).column,
    };
  });
}

function applyLspDiagnostics(path) {
  var model = monaco.editor.getModel(modelUriForDocument(path));
  if (model) monaco.editor.setModelMarkers(model, 'androidide-lsp', lspDiagnosticsToMarkers(lspDiagnosticsByPath[path] || []));
}

function normalizeCompletionItems(result) {
  var items = Array.isArray(result) ? result : (result && result.items) || [];
  return items.map(function (item) {
    var textEdit = item.textEdit;
    var insertText = item.insertText || item.label || '';
    var range = textEdit && textEdit.range
      ? lspRange(textEdit.range)
      : textEdit && textEdit.insert
        ? lspRange(textEdit.insert)
        : undefined;
    if (textEdit && textEdit.newText != null) insertText = textEdit.newText;
    var documentation = item.documentation;
    if (documentation && typeof documentation === 'object' && documentation.value != null) {
      documentation = { value: documentation.value, isTrusted: false };
    }
    return {
      label: item.label || insertText,
      kind: item.kind || monaco.languages.CompletionItemKind.Text,
      detail: item.detail,
      documentation: documentation,
      filterText: item.filterText,
      sortText: item.sortText,
      preselect: !!item.preselect,
      commitCharacters: item.commitCharacters,
      insertText: insertText,
      range: range,
      additionalTextEdits: (item.additionalTextEdits || []).filter(function (edit) {
        return edit && edit.range && edit.newText != null;
      }).map(function (edit) {
        return { range: lspRange(edit.range), text: edit.newText };
      }),
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
    javascriptreact: [
      ['component', 'function ${1:Component}(${2:props}) {\n\treturn (\n\t\t<${3:div}>$0</${3:div}>\n\t);\n}', 'React component'],
      ['jsx', '<${1:Component} ${2:prop}={${3:value}}>$0</${1:Component}>', 'JSX element'],
      ['fragment', '<>\n\t$0\n</>', 'React fragment'],
    ],
    typescript: [
      ['fn', 'function ${1:name}(${2:args}): ${3:void} {\n\t$0\n}', 'Function'],
      ['iface', 'interface ${1:Name} {\n\t$0\n}', 'Interface'],
      ['type', 'type ${1:Name} = ${2:unknown};', 'Type alias'],
    ],
    typescriptreact: [
      ['component', 'function ${1:Component}(${2:props}: ${3:Props}) {\n\treturn (\n\t\t<${4:div}>$0</${4:div}>\n\t);\n}', 'Typed React component'],
      ['props', 'type ${1:Props} = {\n\t${2:property}: ${3:string};\n};', 'Component props'],
      ['jsx', '<${1:Component} ${2:prop}={${3:value}}>$0</${1:Component}>', 'TSX element'],
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
      ['image', '![${1:alt text}](${2:./image.png})', 'Image link'],
      ['code', '```' + '${1:language}' + '\n$0\n```', 'Code fence'],
    ],
    xml: [
      ['element', '<${1:element}>\n\t$0\n</$1>', 'Element'],
      ['attribute', '${1:name}="${2:value}"', 'Attribute'],
      ['declaration', '<?xml version="1.0" encoding="UTF-8"?>', 'XML declaration'],
      ['svg', '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${1:100} ${2:100}">\n\t$0\n</svg>', 'SVG root'],
      ['path', '<path d="${1:M 0 0}" fill="${2:none}" stroke="${3:currentColor}" />', 'SVG path'],
    ],
    yaml: [
      ['mapping', '${1:key}: ${2:value}', 'YAML mapping'],
      ['list', '- ${1:item}', 'YAML list item'],
      ['document', '---\n${1:key}: ${2:value}', 'YAML document'],
    ],
    jsonc: [
      ['property', '"${1:key}": ${2:value},', 'JSONC property'],
    ],
    sql: [
      ['select', 'SELECT ${1:*}\nFROM ${2:table}\nWHERE ${3:condition};', 'SELECT query'],
      ['join', 'SELECT ${1:columns}\nFROM ${2:table_a}\nJOIN ${3:table_b} ON ${4:condition};', 'JOIN query'],
      ['insert', 'INSERT INTO ${1:table} (${2:columns})\nVALUES (${3:values});', 'INSERT statement'],
      ['create', 'CREATE TABLE ${1:table} (\n\t${2:id} INTEGER PRIMARY KEY\n);', 'CREATE TABLE statement'],
      ['update', 'UPDATE ${1:table}\nSET ${2:column} = ${3:value}\nWHERE ${4:condition};', 'UPDATE statement'],
    ],
    shell: [
      ['if', 'if [ ${1:condition} ]; then\n\t$0\nfi', 'If statement'],
      ['for', 'for ${1:item} in ${2:items}; do\n\t$0\ndone', 'For loop'],
      ['case', 'case ${1:value} in\n\t${2:pattern})\n\t\t$0\n\t\t;;\nesac', 'Case statement'],
      ['fn', '${1:name}() {\n\t$0\n}', 'Shell function'],
    ],
  };
  snippets.scss = snippets.css;
  snippets.less = snippets.css;
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
    var params = message.params || {};
    var documentUri = params.uri || currentPath;
    var diagnostics = params.diagnostics || [];
    if (documentUri) {
      lspDiagnosticsByPath[documentUri] = diagnostics;
      applyLspDiagnostics(documentUri);
      postToNative({
        type: 'diagnosticsChanged',
        documentUri: documentUri,
        diagnostics: diagnostics.map(function (diagnostic) {
          var range = diagnostic.range || { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } };
          var start = monacoPosition(range.start);
          var end = monacoPosition(range.end);
          return {
            startLine: start.lineNumber,
            startColumn: start.column,
            endLine: end.lineNumber,
            endColumn: end.column,
            severity: diagnostic.severity || 4,
            message: diagnostic.message || '',
            source: diagnostic.source || '',
          };
        }),
      });
    }
  }
}

function registerLocalLanguageProviders() {
  if (localLanguageProvidersRegistered) return;
  localLanguageProvidersRegistered = true;
  var available = Object.create(null);
  monaco.languages.getLanguages().forEach(function (entry) { available[entry.id] = true; });
  ['javascript', 'javascriptreact', 'typescript', 'typescriptreact', 'python', 'html', 'css', 'scss', 'less', 'json', 'jsonc', 'kotlin', 'java', 'xml', 'yaml', 'sql', 'markdown', 'shell']
    .filter(function (language) { return available[language] && localSnippetItems(language).length > 0; })
    .forEach(function (language) {
      lspProviderDisposables.push(monaco.languages.registerCompletionItemProvider(language, {
        triggerCharacters: 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_'.split(''),
        provideCompletionItems: function (model, position) {
          var prefix = model.getWordUntilPosition(position).word || '';
          var local = localSnippetItems(language).filter(function (item) {
            return !prefix || item.label.toLowerCase().indexOf(prefix.toLowerCase()) === 0;
          });
          return Promise.resolve({ suggestions: local });
        },
      }));
    });
}

function registerLanguageProviders() {
  if (languageProvidersRegistered) return;
  languageProvidersRegistered = true;
  var available = Object.create(null);
  monaco.languages.getLanguages().forEach(function (entry) { available[entry.id] = true; });
  // These IDs correspond to LanguageServerRegistry and the installed, pinned
  // server packages. XML/SVG keeps Monaco's local XML grammar/snippets only.
  var languages = ['javascript', 'javascriptreact', 'typescript', 'typescriptreact', 'python', 'html', 'css', 'scss', 'less', 'json', 'jsonc', 'markdown']
    .filter(function (language) { return available[language]; });
  var lspCompletionLanguages = ['javascript', 'javascriptreact', 'typescript', 'typescriptreact', 'python', 'html', 'css', 'scss', 'less', 'json', 'jsonc', 'markdown'];
  var lspCompletionTriggers = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_'.split('').concat(['.', ':', '/', '<', '"', "'"]);
  languages.forEach(function (language) {
    if (lspCompletionLanguages.indexOf(language) !== -1) {
      lspProviderDisposables.push(monaco.languages.registerCompletionItemProvider(language, {
        triggerCharacters: lspCompletionTriggers,
        provideCompletionItems: function (model, position, context) {
        return requestLsp('textDocument/completion', {
          textDocument: { uri: model.uri.toString() },
          position: lspPosition(position),
          context: {
            triggerKind: context && context.triggerKind || 1,
            triggerCharacter: context && context.triggerCharacter,
          },
        }, 1500).then(function (result) {
          return { suggestions: normalizeCompletionItems(result) };
        }).catch(function () {
          return { suggestions: [] };
        });
        },
      }));
      lspProviderDisposables.push(monaco.languages.registerSignatureHelpProvider(language, {
        signatureHelpTriggerCharacters: ['(', ','],
        signatureHelpRetriggerCharacters: [','],
        provideSignatureHelp: function (model, position, token, context) {
          return requestLsp('textDocument/signatureHelp', {
            textDocument: { uri: model.uri.toString() },
            position: lspPosition(position),
            context: {
              triggerKind: context && context.triggerKind || 1,
              triggerCharacter: context && context.triggerCharacter,
              isRetrigger: !!(context && context.isRetrigger),
            },
          }, 600).then(function (result) {
            if (!result || !Array.isArray(result.signatures) || !result.signatures.length) return null;
            function documentation(value) {
              if (!value) return undefined;
              if (typeof value === 'string') return value;
              return { value: value.value || '' };
            }
            return {
              value: {
                signatures: result.signatures.map(function (signature) {
                  return {
                    label: signature.label || '',
                    documentation: documentation(signature.documentation),
                    parameters: (signature.parameters || []).map(function (parameter) {
                      return {
                        label: parameter.label || '',
                        documentation: documentation(parameter.documentation),
                      };
                    }),
                  };
                }),
                activeSignature: Math.max(0, result.activeSignature || 0),
                activeParameter: Math.max(0, result.activeParameter || 0),
              },
              dispose: function () {},
            };
          }).catch(function () { return null; });
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
    lspProviderDisposables.push(monaco.languages.registerDeclarationProvider(language, {
      provideDeclaration: function (model, position) {
        return requestLsp('textDocument/declaration', {
          textDocument: { uri: model.uri.toString() }, position: lspPosition(position),
        }).then(function (result) {
          var locations = Array.isArray(result) ? result : (result ? [result] : []);
          return locations.map(function (location) { return lspLocationToMonaco(location, model.uri); }).filter(Boolean);
        }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerTypeDefinitionProvider(language, {
      provideTypeDefinition: function (model, position) {
        return requestLsp('textDocument/typeDefinition', {
          textDocument: { uri: model.uri.toString() }, position: lspPosition(position),
        }).then(function (result) {
          var locations = Array.isArray(result) ? result : (result ? [result] : []);
          return locations.map(function (location) { return lspLocationToMonaco(location, model.uri); }).filter(Boolean);
        }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerImplementationProvider(language, {
      provideImplementation: function (model, position) {
        return requestLsp('textDocument/implementation', {
          textDocument: { uri: model.uri.toString() }, position: lspPosition(position),
        }).then(function (result) {
          var locations = Array.isArray(result) ? result : (result ? [result] : []);
          return locations.map(function (location) { return lspLocationToMonaco(location, model.uri); }).filter(Boolean);
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
    lspProviderDisposables.push(monaco.languages.registerDocumentSymbolProvider(language, {
      provideDocumentSymbols: function (model) {
        return requestLsp('textDocument/documentSymbol', { textDocument: { uri: model.uri.toString() } })
          .then(function (result) {
            return (result || []).filter(function (symbol) { return symbol && symbol.name && symbol.range; }).map(lspDocumentSymbolToMonaco);
          }).catch(function () { return []; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerFoldingRangeProvider(language, {
      provideFoldingRanges: function (model) {
        return requestLsp('textDocument/foldingRange', { textDocument: { uri: model.uri.toString() } })
          .then(function (result) {
            return (result || []).filter(function (range) { return range && range.startLine != null && range.endLine != null; }).map(function (range) {
              return { start: range.startLine + 1, end: range.endLine + 1, kind: range.kind };
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
            return { title: action.title, kind: action.kind, isPreferred: action.isPreferred, edit: lspWorkspaceEditToMonaco(action.edit) };
          }), dispose: function () {} };
        }).catch(function () { return { actions: [], dispose: function () {} }; });
      },
    }));
    lspProviderDisposables.push(monaco.languages.registerRenameProvider(language, {
      provideRenameEdits: function (model, position, newName) {
        return requestLsp('textDocument/rename', {
          textDocument: { uri: model.uri.toString() }, position: lspPosition(position), newName: newName,
        }).then(lspWorkspaceEditToMonaco).catch(function () { return { edits: [] }; });
      },
    }));
  });
}

require.config({ paths: { vs: 'vs' } });

// Core editor load remains independent from the language bundle. The aggregate
// contribution registers Monaco's bundled languages and lazily loads their
// tokenizers/configurations. If it is unavailable, the editor still becomes
// usable and logs the reason rather than hanging ready.
require(['vs/editor/editor.main'], function () {
  require(['vs/basic-languages/monaco.contribution'], function () {
    initializeMonacoEditor();
  }, function (error) {
    console.warn('[androidIDE] Monaco basic-language contributions unavailable:', error);
    initializeMonacoEditor();
  });
});

function initializeMonacoEditor() {

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

    // Local completions work even when optional LSP packages are not installed.
    // Language-specific providers are registered only for actual Monaco IDs.
    quickSuggestions: { other: true, comments: false, strings: true },
    suggestOnTriggerCharacters: true,
    acceptSuggestionOnCommitCharacter: true,
    acceptSuggestionOnEnter: 'smart',
    snippetSuggestions: 'inline',
    parameterHints: { enabled: false },

    folding: false,

    // Link detection runs a regex over every visible line on every edit.
    links: false,

    renderValidationDecorations: 'off',

    // Smooth caret animation adds GPU compositing overhead on Android.
    cursorSmoothCaretAnimation: 'off',

    // Bracket pair colorization is purely cosmetic — disable for less render work.
    'bracketPairColorization.enabled': false,

    // Formatting is effective only where Monaco or a registered LSP provides a
    // formatter. Full indentation uses each loaded language's rules for Enter,
    // closing brackets/tags, and outdent.
    formatOnPaste: true,
    autoIndent: 'full',
    detectIndentation: true,

    // Glyph margin (left gutter for breakpoints, fold arrows etc.) allocates
    // a DOM element per visible line. Not needed without a debugger in Phase 1.
    glyphMargin: false,

    // Minimise the line-decoration column width; 5px provides visual separation
    // next to line numbers without spending DOM/paint budget.
    lineDecorationsWidth: 5,
  });
  registerLocalLanguageProviders();

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
        revision: ++contentRevision,
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
    postToNative({ type: 'fileSaved', path: currentPath, content: editor.getValue() });
  });

  // Focus is intentionally controlled by the native WebView touch listener.
  // JavaScript click/touchend focus calls used to run after Android long-press
  // selection handles appeared and collapse the selection.

  // Flush commands that arrived while Monaco was loading, then signal
  // readiness. Nothing related to LSP or suggestions is allowed to delay this
  // point: opening a file must first produce an editable Monaco surface.
  monacoReady = true;
  var queuedMessages = pendingNativeMessages;
  pendingNativeMessages = [];
  queuedMessages.forEach(function (message) { window.androidIDE.receiveMessage(message); });
  postToNative({ type: 'ready' });

  // Optional LSP requests remain behind the user's setting. Local snippets,
  // Monaco language rules, auto-indent and available formatters are already live.
  window.androidIDE.enableLanguageFeatures = function () {
    setTimeout(function () {
      registerLanguageProviders();
      editor.updateOptions({
        quickSuggestions: { other: true, comments: false, strings: true },
        suggestOnTriggerCharacters: true,
        acceptSuggestionOnCommitCharacter: true,
        acceptSuggestionOnEnter: 'smart',
        snippetSuggestions: 'inline',
        parameterHints: { enabled: true },
        renderValidationDecorations: 'on',
      });
    }, 0);
  };
  if (languageFeaturesRequested) window.androidIDE.enableLanguageFeatures();
}

// ---------------------------------------------------------------------------
// Public API — called by Kotlin via evaluateJavascript
// ---------------------------------------------------------------------------

function insertPairedText(opening, closing) {
  if (!editor || editorReadOnly || !opening || !closing) return;
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
  if (!editor || editorReadOnly || !snippet) return;
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

    if (!monacoReady || !editor) {
      pendingNativeMessages.push(msg);
      return;
    }

    switch (msg.type) {

      case 'enableLanguageFeatures':
        if (window.androidIDE.enableLanguageFeatures) window.androidIDE.enableLanguageFeatures();
        else languageFeaturesRequested = true;
        break;

      case 'loadFile':
        loadFile(msg.requestId, msg.path, msg.content, msg.language, msg.readOnly === true);
        break;

      case 'reloadFile':
        reloadFile(msg.path, msg.expectedCurrentContent, msg.content, msg.language);
        break;

      case 'clearActiveModel':
        flushPendingContentChange();
        if (editor) editor.setModel(null);
        currentPath = null;
        postToNative({ type: 'activeModelCleared', requestId: msg.requestId });
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
          document.getElementById('editor-root').style.visibility = 'visible';
          document.getElementById('loading').classList.add('hidden');
        }
        break;

      case 'setFontSize':
        if (editor) editor.updateOptions({ fontSize: msg.size });
        break;

      case 'setEditorOptions':
        if (editor) {
          var opts = {};
          var wrapChanged = false;
          if (msg.readOnly != null) editorReadOnly = msg.readOnly === true;
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
          if (msg.readOnly              != null) opts.readOnly              = msg.readOnly;
          if (Object.keys(opts).length > 0) {
            editor.updateOptions(opts);
            // re-layout immediately or Monaco displays broken wrapped lines.
            if (wrapChanged) { applyLayout(); requestAnimationFrame(applyLayout); }
          }
        }
        break;

      case 'requestSave':
        if (editor && currentPath) {
          postToNative({ type: 'fileSaved', path: currentPath, content: editor.getValue() });
        }
        break;

      case 'closeTab':
        if (msg.path) {
          if (msg.path === currentPath) flushPendingContentChange();
          delete lspDiagnosticsByPath[msg.path];
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
        lspDiagnosticsByPath = Object.create(null);
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
              postToNative({ type: 'textCopied', text: cutText, isCut: !editorReadOnly });
              if (!editorReadOnly) editor.executeEdits('cut', [{ range: cutSel, text: '', forceMoveMarkers: true }]);
            }
          }
          break;
        }
        if (editorReadOnly && !msg.command.startsWith('cursor') &&
            msg.command !== 'selectAll' && msg.command !== 'editor.action.selectAll' &&
            msg.command !== 'editor.action.clipboardCopyAction' && msg.command !== 'actions.find') break;
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
        if (editor && !editorReadOnly && msg.text) {
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
        if (editor && !editorReadOnly) {
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
function loadFile(requestId, path, content, language, readOnly) {
  if (!editor) {
    postToNative({ type: 'fileLoadFailed', requestId: requestId, path: path, reason: 'Monaco editor is not initialized' });
    return;
  }
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
  applyLspDiagnostics(path);
  editorReadOnly = readOnly === true;
  editor.updateOptions({ readOnly: readOnly === true });
  editor.setScrollPosition({ scrollTop: 0, scrollLeft: 0 });

  // Apply layout using window.innerWidth/innerHeight, then schedule a
  // follow-up pass after Blink's next paint cycle.
  applyLayout();
  requestAnimationFrame(applyLayout);
  postToNative({ type: 'fileLoaded', requestId: requestId, path: path });

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
