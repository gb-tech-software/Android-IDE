# Android IDE Application Architecture Contracts

**Status:** Accepted architecture contract set for the `dev` branch
**Primary responsibility:** Define the ownership and provider boundaries that application services must preserve.
**Scope:** Contracts and decisions for the application domains; implementation claims are limited to the explicitly recorded source-level foundation and editor-intelligence work.

## 1. Goal and active references

The goal of this contract set is to remove ownership, storage, navigation, lifecycle, provider, and identity ambiguity before expanding feature code. The active references are the approved product definition, approved provider research, approved implementation roadmap, status tracker, and Termux filesystem concept report.

## 2. State ownership matrix

| State | Authoritative owner | Readers | Explicit exclusions |
|---|---|---|---|
| User project files, generated files, `.git`, manifests | Selected `ProjectLocation` | Project storage adapter, editor, terminal, Git, language servers, previews | No app-private shadow project copy |
| Portable project metadata | Project metadata directory at the project root | Project registry, workspace restoration, project details | No secrets, PTYs, process handles, browser authentication, or caches |
| Project-associated runtime state | App-private runtime storage | Runtime and lifecycle services | No authoritative project files or global credentials |
| Global application data and preferences | App-private global storage | Application shell and domain services | No project file contents or project-owned tabs |
| Surface and transient UI state | Owning domain/application state | Compose surfaces | Not an independent source of project, session, or repository truth |
| Credentials and secure material | Keystore-backed encrypted vault | Credential and provider adapters | Never project metadata, source, logs, URLs, or events |

A screen may query another owner through an adapter or state contract, but it must not write directly into another owner’s storage or establish a competing authority.

## 3. Project-location and storage authority

Android IDE treats every persistent file provider as the same product-level project-location type. Supported examples include shared device storage, removable storage, cloud/document providers that satisfy the live-edit contract, and the Android IDE filesystem provider.

The Android IDE filesystem provider is a user-visible `DocumentsProvider` root backed by a durable filesystem with stronger development capabilities than ordinary emulated shared storage. It is selectable through SAF and can be browsed or edited by other applications after the user grants URI access. It is not a private project class, hidden workspace, shadow copy, or automatic fallback.

The provider has two deliberately separate areas:

1. **User-files root:** persistent project files exposed through SAF and usable by the editor, terminal, language servers, previews, and other applications with user-granted access.
2. **Protected runtime/package root:** the Termux bootstrap, package database, shell libraries, runtime state, PTYs, and process/session infrastructure. This root is not exposed as a general document-provider root.

In-memory editor buffers and application state are transient state, not a storage provider and never an authoritative project location.

Cloud-backed or remote document providers are live project locations only when they satisfy the required read, write, mutation, and observation contract; otherwise they are import/export sources. A location that can be selected but cannot support the required operations is rejected with an actionable result. The application must never silently change provider, overwrite, merge, rename, migrate, or redirect a requested destination.

Every acquisition or relocation operation follows: validate input; inspect destination and capabilities; review the exact operation; execute; verify returned identity and state; report complete, partial, blocked, interrupted, failed, or cancelled outcome; offer supported recovery or cleanup.

## 4. Navigation and restoration contract

The application opens at **Home**. Home is an entry point, not a dashboard and not an owner of project, terminal, browser, or Git state. Initial surfaces are Home, Projects, Project Details, Editor, Terminal, Browser, Git, and Settings.

The contextual sidebar is an integrated mobile surface. Hiding a surface changes visibility and focus but does not close its children or terminate owned work. Leaving a project is distinct from closing a terminal session, browser tab, editor tab, or other child resource.

Back handling is ordered as follows:

1. dismiss the highest-priority transient control;
2. close or hide a child control according to its domain contract;
3. return from details to the previous domain surface;
4. leave the selected project only after dirty work and active tasks are handled;
5. return to Home or Projects without destroying global sessions.

Restoration is staged and tolerant of partial failure: initialize global state; validate the last project and permission; open or mark it unavailable; initialize portable metadata; load workspace descriptors; validate editor, terminal, browser, and task descriptors independently; restore accessible children; mark inaccessible children unavailable; restore surface and selection where possible; otherwise fall back to Home. Missing or stale child state must not delete unrelated state or substitute a different project.

## 5. Lifecycle and session contract

Long-running work belongs to a runtime/service boundary, never to a composable screen or screen-scoped coroutine. The UI observes state. Android IDE reports the limits of process survivability and never promises an unkillable process.

Every session or task has a stable identity, owner scope, backend identity where available, working directory, creation time, availability state, and termination reason. PTYs, process identifiers, and logs are app-private runtime state; durable descriptors are stored in the appropriate global or project-associated layer.

Availability states are:

- **Available:** the backend can reconnect or report the session as usable.
- **Unavailable:** the durable record exists but the backend cannot currently reconnect or use it.
- **Explicitly closed:** the user intentionally terminated it.
- **Invalidated:** an unavoidable Android, device, permission, or backend event ended it.

Changing screens, returning Home, hiding Terminal, or switching sessions does not close a session. Explicit close terminates the session and its owned child processes.

## 6. Provider adapter boundaries

Higher-level domains depend on these logical contracts, not on SAF, Termux, WebView, Monaco, Git implementation classes, or provider-specific process details:

| Adapter | Responsibility |
|---|---|
| `ProjectStorageAdapter` | Project-relative listing, reads, writes, mutations, metadata, capability checks, and change observation |
| `ProjectRegistryAdapter` | Registered project records, recent ordering, availability, and removal without deleting user files |
| `ProjectMetadataAdapter` | Portable identity and workspace descriptors, with explicit migration and no secret storage |
| `RuntimeWorkspaceAdapter` | Private workspace initialization, root identity, and project working directories |
| `TerminalRuntimeAdapter` | Sessions, PTY I/O, resize, child tracking, termination, and availability |
| `EditorDocumentAdapter` | Stable project-relative document identity, load/save, external-change reporting, and recovery separation |
| `LanguageServerAdapter` | Settings-gated LSP process lifecycle, on-demand pinned package setup, provider-backed workspace, framed JSON-RPC transport, and enabled-only availability reporting |
| `GitAdapter` | Canonical runtime Git status, mutations, configuration, output, and errors |
| `BrowserPreviewAdapter` | Global browser/preview state and failure isolation from project and runtime ownership |
| `CredentialVaultAdapter` | Secure credential lifecycle, redaction, revocation, and provider access without exposing secrets |
| `LifecycleCoordinator` | Restoration, foreground work, runtime availability, invalidation, and explicit exit semantics |

The initial Kotlin contract types live in `dev.android.ide.contracts`. Implementations may be added by later phases without changing the ownership vocabulary.

Language intelligence is an editor consumer of the selected project location, not a second project authority. A language server may run only when the terminal/runtime can access the selected provider-backed directory. The editor synchronizes open documents through LSP `didOpen`, `didChange`, `didSave`, and `didClose`; Monaco requests and server responses cross the existing editor bridge; diagnostics and supported language features are capability- and availability-gated. A server that cannot initialize is reported unavailable and must not receive a silently substituted project path.

## 7. Identity and metadata decision

The approved product direction names application identity `dev.android.ide` and metadata directory `.dev-android-ide`; the pre-Phase-0 source used `dev.androidide` and `.androidide`.

For this bootstrap, the decision is explicit:

- `dev.android.ide` and `.dev-android-ide` are the **authoritative identity** for the application architecture.
- Existing `dev.androidide` source/package names and `.androidide` metadata are **legacy inputs**, not new authoritative names.
- The identity migration is implemented at the application boundary. The application ID, Kotlin namespace/package references, manifest references, generated metadata, visibility rules, and documentation use the authoritative identity.
- Metadata initialization always checks `.dev-android-ide` first. If it does not exist, it is created, legacy files are migrated into it, and `.androidide` is deleted only after migration succeeds. Reads and writes after acquisition use only `.dev-android-ide`; the legacy directory is never preserved alongside it.
- Credentials and runtime handles remain excluded from both metadata names.

This decision makes migration a one-way acquisition/opening step and prevents feature work from creating an accidental mixed identity.

## 8. Shared event vocabulary

Events describe facts and identify entities; they are not screen-specific commands and never carry secrets.

| Event family | Examples |
|---|---|
| Project | registered, opened, unavailable, relocated, exported, removed, deleted |
| Storage | permission granted, lost, restored, rejected |
| Project file | created, changed, moved, renamed, deleted, externally changed |
| Runtime/session | session created, available, unavailable, explicitly closed, invalidated |
| Child process | started, exited, failed, terminated with owner |
| Browser | tab created, restored, unavailable, closed |
| Git | repository changed externally, refreshed |
| Lifecycle | hidden, foregrounded, process recreated, service stopped, app exited |

## 9. Error and operation-result vocabulary

All user-data mutations use the same operation lifecycle: validation, conflict/capability preflight, reviewed operation, execution, verification, and an explicit result with recovery or cleanup where supported.

Stable error categories are: permission loss; unsupported provider capability; destination conflict; invalid archive; unavailable runtime; package failure; process loss; malformed metadata; external file change; credential failure; and user cancellation.

Stable operation outcomes are: **complete**, **partial**, **blocked**, **interrupted**, **failed**, and **cancelled**. A conflict stops before creation, replacement, merge, or deletion. Providers must not silently rename or substitute a target.

## 10. Application foundation entry checklist and exact scope

Application foundation may begin. The smallest Phase 1 scope is:

1. establish Home, top-level navigation, contextual sidebar, and Back/Hide/Close/Leave/Exit contracts;
2. establish the four storage layers and project registry boundary;
3. persist storage permission and location identity;
4. provide capability inspection and unavailable-state reporting;
5. add lifecycle coordination and restoration records;
6. expose the Android IDE user-files root through SAF without exposing the runtime/package root;
7. verify that no hidden project copy is created and no project is silently migrated.

Application foundation must not prematurely implement terminal runtime, editor feature expansion, browser, Git, language intelligence, credentials, or extensions.

## 11. Acceptance and limitations

Architecture contracts are accepted when the contract record and Kotlin contract types agree, active documents point to the approved references, legacy documents are clearly archived, identity migration is isolated and visible, and targeted checks find no active claim that cloud storage is live-editable, sessions are project-owned by default, credentials belong in project metadata, desktop mode is required, or processes are unkillable.

This contract set does not claim that every provider integration or runtime feature is implemented. Android builds and device validation are separate acceptance activities and are intentionally not run as part of this lightweight contract verification.

## 12. Implementation audit and clarified correction

### 12.1 What is currently wrong with the implementation or behavior

The source-level implementation has the following cross-domain defects and misleading behaviors:

1. **Filesystem identity is fragmented.** Project storage, the terminal, the language server, and the editor independently interpret `content://`, `file://`, and provider-specific paths. A project can therefore be editable through SAF while its terminal or language server cannot resolve the same location. Capability reporting is consequently broader than actual runtime support.
2. **The Android IDE provider is not yet an application-wide filesystem bridge.** The DocumentsProvider exposes the Android IDE user-files root, but external storage, removable storage, and other SAF providers are still consumed as raw provider URIs instead of as nodes in one navigable Android IDE filesystem model.
3. **Project terminal access can silently substitute a different directory.** A project session can fall back to the Termux home when its requested working directory is unavailable. That hides a project-access failure and can cause commands to run against the wrong location.
4. **Session cleanup is too aggressive for temporary process loss.** Runtime initialization and stale-session handling can clear descriptors that should be restorable after Android temporarily kills the application process, while the intended product behavior is to discard state only on a true new launch, explicit close, or device restart/exit boundary.
5. **LSP routing is not fully document-safe.** Diagnostics and navigation results are not consistently routed by their returned document URI, and native lifecycle request IDs can overlap with Monaco request IDs. Provider URI conversion, server readiness, and workspace-edit handling are incomplete.
6. **Toolbar customization is not authoritative.** Saved orders can be repopulated with defaults, so disabled actions may return. Settings do not consistently show each action's icon, description, and current position, making command identity and ordering difficult to understand.
7. **Git is in an inconsistent intermediate state.** Some Git placeholders remain interactive while other paths imply that a Git UI is implemented or remove the entry point. The intended behavior is a visible placeholder that directs users to the terminal without pretending to provide Git controls.
8. **Existing-folder acquisition asks for too much and mixes concerns.** Import should inspect and register the selected folder, not act as the project rename/description editor. Managed Android IDE metadata is intentional, but user files must never be overwritten and optional initial files must not be recreated after the user deletes them.

These findings are source-level findings. Android lint, compilation, and device behavior remain unverified because those checks are intentionally not run in this task.

### 12.2 Clarification: the permanent-fix direction

The correction is to make Android IDE's filesystem the common application boundary rather than passing raw provider URLs between domains:

1. **Build one Android IDE filesystem tree.** Treat Android IDE storage, Android shared storage, removable storage, and user-granted document providers as child locations in a provider-backed filesystem. Expose stable virtual nodes/paths and resolve them to the underlying SAF or local provider only inside the filesystem adapter. Keep permissions explicit; the bridge does not bypass SAF grants.
2. **Use the same resolver everywhere.** Project management, file browsing, editor documents, terminal working directories, language-server workspaces, Git placeholders, and browser/preview integrations must resolve the selected node through the same filesystem contract. A virtual filesystem identity is not a second copy of project data.
3. **Report capabilities per resolved node.** File editing can be available through SAF even when a terminal path is unavailable. Terminal and LSP are available only when the selected node can be resolved to a directory accessible by the Termux runtime. No project operation may silently substitute the runtime home or another provider.
4. **Separate new terminal sessions from project sessions.** A newly created terminal session may inherit the previous directory or use the runtime home. Opening a project/folder in Terminal must validate and use that project's resolved directory, or show a failure in the originating modal/surface.
5. **Preserve recoverable state only within the application lifecycle boundary.** Temporary Android process loss while the app remains restorable should preserve descriptors and UI state. True exit, device restart, explicit close, or invalidation should not resurrect obsolete PTYs. Unavailable descriptors should report their reason rather than being silently erased.
6. **Keep Git placeholders interactive.** Home and the Git surface remain navigable. The Git surface explains that no Git UI is implemented and offers navigation to Terminal; the sidebar explains the same limitation but does not offer the Terminal control. Git operations remain terminal operations.
7. **Treat project metadata as portable managed state.** Import may initialize the Android IDE metadata directory and may create a missing initial root README without overwriting an existing one. Opening later repairs only managed metadata and never recreates an optional user-deleted README. Rename and description changes belong to project details and context actions.
8. **Make toolbar customization explicit.** Keep the flat Monaco action catalog and fixed five-action pages. Persist the user's enabled ordered list as authoritative. Each settings row shows its distinct icon, label, description, current position, and enabled state. LSP-dependent actions are marked as potentially unavailable.
9. **Complete LSP on top of the filesystem boundary.** Route diagnostics, definitions, references, and workspace edits by stable document identity; separate native and Monaco request IDs; queue document events until initialization; and report unavailable runtime access without substituting a different path.

The first implementation step after this clarification is the shared filesystem resolution contract and adapter. Later terminal, LSP, project-acquisition, Git-placeholder, and toolbar corrections must consume that boundary instead of adding more URI-specific logic.
