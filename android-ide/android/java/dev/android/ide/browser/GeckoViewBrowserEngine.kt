package dev.android.ide.browser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.atomic.AtomicReference

/** Current implementation of the engine-neutral browser contract. */
class GeckoViewBrowserEngine(
    private val runtime: GeckoRuntime,
    private val context: Context,
) : BrowserEngine {
    override val capabilities: Set<BrowserEngineCapability> = setOf(
        BrowserEngineCapability.HTTP,
        BrowserEngineCapability.HTTPS,
        BrowserEngineCapability.LOCAL_FILE,
        BrowserEngineCapability.CONTENT_URI,
        BrowserEngineCapability.DESKTOP_USER_AGENT,
        BrowserEngineCapability.VIEWPORT_WIDTH,
        BrowserEngineCapability.DOWNLOADS,
        BrowserEngineCapability.PERMISSIONS,
        BrowserEngineCapability.SITE_DATA_CLEARING,
    )

    override fun createSession(
        tabId: String,
        settings: BrowserSessionSettings,
        sink: BrowserEngineEventSink,
    ) : BrowserEngineSession = GeckoBrowserEngineSession(runtime, context, tabId, settings, sink)

    override fun clearSiteData(onComplete: (Result<Unit>) -> Unit) {
        val clearRequest = try {
            runtime.storageController.clearData(StorageController.ClearFlags.SITE_DATA)
        } catch (error: Exception) {
            onComplete(Result.failure(error))
            return
        }
        clearRequest.accept(
            { onComplete(Result.success(Unit)) },
            { error -> onComplete(Result.failure(error ?: IllegalStateException("GeckoView could not clear site data"))) },
        )
    }
}

/** UI-side host wrapper. GeckoView remains confined to the adapter boundary. */
class GeckoViewBrowserSurfaceHost(internal val view: GeckoView) : BrowserSurfaceHost

@OptIn(ExperimentalCoroutinesApi::class)
private class GeckoBrowserEngineSession(
    private val runtime: GeckoRuntime,
    private val context: Context,
    private val tabId: String,
    settings: BrowserSessionSettings,
    private val sink: BrowserEngineEventSink,
    ) : BrowserEngineSession {
    internal val geckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(if (settings.desktopSite) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (settings.desktopSite) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build(),
    )
    private var serializedState: BrowserSerializedState? = null
    private var canGoBack = false
    private var canGoForward = false
    private var pendingHistoryAddress: String? = null
    private var lastKnownUrl: String? = null
    private val promptHandler = Handler(Looper.getMainLooper())

    init {
        geckoSession.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                val failedAddress = displayErrorAddress(uri?.takeIf(String::isNotBlank) ?: lastKnownUrl.orEmpty())
                val (title, message) = navigationErrorMessage(error)
                sink.onNavigationFailure(BrowserNavigationFailure(tabId, failedAddress, title, message))
                // GeckoView's default implementation returns null and aborts the failed load.
                // The app displays a retryable error dialog while leaving the requested URL intact.
                return null
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (hasUserGesture && pendingHistoryAddress != null) pendingHistoryAddress = null
                lastKnownUrl = url
                sink.onNavigationChanged(BrowserNavigationChanged(tabId, url.orEmpty()))
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoBrowserEngineSession.canGoBack = canGoBack
                sink.onNavigationCapabilitiesChanged(BrowserNavigationCapabilitiesChanged(tabId, this@GeckoBrowserEngineSession.canGoBack, this@GeckoBrowserEngineSession.canGoForward))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                this@GeckoBrowserEngineSession.canGoForward = canGoForward
                sink.onNavigationCapabilitiesChanged(BrowserNavigationCapabilitiesChanged(tabId, this@GeckoBrowserEngineSession.canGoBack, this@GeckoBrowserEngineSession.canGoForward))
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Gecko owns the initial navigation; uri is informational. Return a fresh,
                // unopened session. Blocked unsolicited popups reach here only after approval;
                // user-initiated new windows can reach this callback without PopupPrompt.
                val engineSession = sink.onNewTabRequested(uri) as? GeckoBrowserEngineSession
                return GeckoResult.fromValue(engineSession?.geckoSession)
            }
        })
        geckoSession.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                lastKnownUrl = url
                sink.onNavigationChanged(BrowserNavigationChanged(tabId, url))
                sink.onProgressChanged(BrowserProgressChanged(tabId, 0))
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                sink.onProgressChanged(BrowserProgressChanged(tabId, progress))
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                sink.onProgressChanged(BrowserProgressChanged(tabId, 100))
                // Only explicit navigations carry a history address. Reloads,
                // restore, tab activation, and back/forward must not fabricate
                // new global history entries from the current URL.
                val historyAddress = pendingHistoryAddress
                pendingHistoryAddress = null
                sink.onPageLoadCompleted(BrowserPageLoadCompleted(tabId, success, historyAddress))
            }

            override fun onSessionStateChange(session: GeckoSession, state: GeckoSession.SessionState) {
                serializedState = BrowserSerializedState(state.toString())
                sink.onSerializedStateChanged(tabId, serializedState!!)
            }
        })
        geckoSession.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                sink.onTitleChanged(BrowserTitleChanged(tabId, title.orEmpty()))
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val link = element.linkUri?.takeIf(String::isNotBlank)
                val media = element.srcUri?.takeIf(String::isNotBlank)
                val kind = when {
                    link != null && media != null -> BrowserContextKind.LINKED_MEDIA
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> BrowserContextKind.IMAGE
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO -> BrowserContextKind.VIDEO
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO -> BrowserContextKind.AUDIO
                    link != null -> BrowserContextKind.LINK
                    else -> return
                }
                sink.onContextMenuRequested(
                    BrowserContextMenuRequest(tabId, kind, link, media, element.title, element.altText, element.textContent),
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                sink.onDownloadRequested(
                    BrowserDownloadRequest(
                        tabId = tabId,
                        url = response.uri,
                        mimeType = response.headers["Content-Type"] ?: "application/octet-stream",
                        fileName = null,
                        contentDisposition = response.headers.entries
                            .firstOrNull { it.key.equals("Content-Disposition", ignoreCase = true) }
                            ?.value,
                        body = response.body,
                        totalBytes = response.headers["Content-Length"]?.toLongOrNull() ?: -1L,
                    ),
                )
            }
        })
        geckoSession.setPermissionDelegate(object : PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: PermissionDelegate.Callback,
            ) {
                AndroidPermissionBroker.request(context, permissions, callback)
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val decision = CompletableDeferred<Boolean>()
                sink.onPermissionRequested(
                    BrowserPermissionRequest(
                        id = "$tabId-${System.nanoTime()}",
                        tabId = tabId,
                        origin = displayPermissionOrigin(permission.uri),
                        permission = permissionName(permission.permission),
                        decision = decision,
                    ),
                )
                val result = GeckoResult<Int>()
                decision.invokeOnCompletion { cause ->
                    val allowed = cause == null && runCatching { decision.getCompleted() }.getOrDefault(false)
                    result.complete(
                        if (allowed) PermissionDelegate.ContentPermission.VALUE_ALLOW
                        else PermissionDelegate.ContentPermission.VALUE_DENY,
                    )
                }
                return result
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<PermissionDelegate.MediaSource>?,
                audio: Array<PermissionDelegate.MediaSource>?,
                callback: PermissionDelegate.MediaCallback,
            ) {
                val permission = when {
                    video != null && audio != null -> "camera and microphone"
                    video != null -> "camera"
                    audio != null -> "microphone"
                    else -> {
                        callback.reject()
                        return
                    }
                }
                val decision = CompletableDeferred<Boolean>()
                sink.onPermissionRequested(
                    BrowserPermissionRequest(
                        id = "$tabId-${System.nanoTime()}",
                        tabId = tabId,
                        origin = displayPermissionOrigin(uri),
                        permission = permission,
                        decision = decision,
                    ),
                )
                decision.invokeOnCompletion { cause ->
                    val allowed = cause == null && runCatching { decision.getCompleted() }.getOrDefault(false)
                    if (allowed) {
                        callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    } else {
                        callback.reject()
                    }
                }
            }
        })
        geckoSession.setPromptDelegate(object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.ALERT,
                title = prompt.title,
                message = prompt.message,
                basePrompt = prompt,
                // GeckoView AlertPrompt has no confirm response; dismissing it is the OK action.
                resolve = { prompt.dismiss() },
                cancel = { prompt.dismiss() },
            )

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.CONFIRM,
                title = prompt.title,
                message = prompt.message,
                basePrompt = prompt,
                resolve = { answer ->
                    prompt.confirm(
                        if (answer.accepted) GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
                        else GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE,
                    )
                },
                cancel = { prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE) },
            )

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.TEXT,
                title = prompt.title,
                message = prompt.message,
                defaultValue = prompt.defaultValue,
                basePrompt = prompt,
                resolve = { answer -> if (answer.accepted) prompt.confirm(answer.value.orEmpty()) else prompt.dismiss() },
                cancel = { prompt.dismiss() },
            )

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.BEFORE_UNLOAD,
                title = "Leave site?",
                message = "Changes you made may not be saved.",
                basePrompt = prompt,
                resolve = { answer -> prompt.confirm(if (answer.accepted) AllowOrDeny.ALLOW else AllowOrDeny.DENY) },
                cancel = { prompt.confirm(AllowOrDeny.DENY) },
            )

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.REPOST,
                title = "Confirm Form Resubmission",
                message = "The page that you're looking for used information that you entered. Returning to that page might cause any action you took to be repeated. Do you want to continue?",
                basePrompt = prompt,
                resolve = { answer -> prompt.confirm(if (answer.accepted) AllowOrDeny.ALLOW else AllowOrDeny.DENY) },
                cancel = { prompt.confirm(AllowOrDeny.DENY) },
            )

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.AUTH,
                title = prompt.title ?: "Sign in",
                message = prompt.message ?: "${prompt.authOptions.uri?.let(::displayOrigin) ?: "This site"} requires a username and password.",
                origin = prompt.authOptions.uri,
                defaultUsername = prompt.authOptions.username,
                defaultPassword = prompt.authOptions.password,
                passwordOnly = prompt.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0,
                basePrompt = prompt,
                resolve = { answer ->
                    if (!answer.accepted) prompt.dismiss()
                    else if (prompt.authOptions.flags and GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0) {
                        prompt.confirm(answer.password.orEmpty())
                    } else {
                        prompt.confirm(answer.username.orEmpty(), answer.password.orEmpty())
                    }
                },
                cancel = { prompt.dismiss() },
            )

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.POPUP,
                title = "Allow pop-up?",
                message = "This page wants to open ${prompt.targetUri ?: "a new window"}.",
                targetUri = prompt.targetUri,
                basePrompt = prompt,
                resolve = { answer -> prompt.confirm(if (answer.accepted) AllowOrDeny.ALLOW else AllowOrDeny.DENY) },
                cancel = { prompt.confirm(AllowOrDeny.DENY) },
            )

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val activePrompt = AtomicReference(prompt)
                return requestWebsitePrompt(
                    kind = BrowserWebsitePromptKind.CHOICE,
                    title = prompt.title ?: "Choose an option",
                    message = prompt.message,
                    choices = flattenChoices(prompt.choices),
                    choiceMode = when (prompt.type) {
                        GeckoSession.PromptDelegate.ChoicePrompt.Type.MENU -> BrowserChoiceMode.MENU
                        GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE -> BrowserChoiceMode.MULTIPLE
                        else -> BrowserChoiceMode.SINGLE
                    },
                    multiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE,
                    basePrompt = prompt,
                    onPromptUpdate = { request, updated ->
                        val updatedChoice = updated as? GeckoSession.PromptDelegate.ChoicePrompt ?: return@requestWebsitePrompt request
                        activePrompt.set(updatedChoice)
                        request.copy(
                            title = updatedChoice.title ?: request.title,
                            message = updatedChoice.message,
                            choices = flattenChoices(updatedChoice.choices),
                            choiceMode = when (updatedChoice.type) {
                                GeckoSession.PromptDelegate.ChoicePrompt.Type.MENU -> BrowserChoiceMode.MENU
                                GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE -> BrowserChoiceMode.MULTIPLE
                                else -> BrowserChoiceMode.SINGLE
                            },
                            multiple = updatedChoice.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE,
                        )
                    },
                    resolve = { answer -> activePrompt.get().confirm(answer.selectedIds.toTypedArray()) },
                    cancel = { activePrompt.get().dismiss() },
                )
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.COLOR,
                title = prompt.title ?: "Choose a color",
                message = "Choose a color to share with this page.",
                defaultValue = prompt.defaultValue,
                predefinedValues = prompt.predefinedValues?.toList().orEmpty(),
                basePrompt = prompt,
                resolve = { answer -> if (answer.accepted) prompt.confirm(answer.value.orEmpty()) else prompt.dismiss() },
                cancel = { prompt.dismiss() },
            )

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.DATE_TIME,
                title = prompt.title ?: "Enter a value",
                message = "Choose a date or time.",
                defaultValue = prompt.defaultValue,
                inputType = when (prompt.type) {
                    GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE -> "date"
                    GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH -> "month"
                    GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK -> "week"
                    GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME -> "time"
                    else -> "datetime-local"
                },
                minValue = prompt.minValue,
                maxValue = prompt.maxValue,
                basePrompt = prompt,
                resolve = { answer -> if (answer.accepted) prompt.confirm(answer.value.orEmpty()) else prompt.dismiss() },
                cancel = { prompt.dismiss() },
            )

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> = requestWebsitePrompt(
                kind = BrowserWebsitePromptKind.FILE,
                title = prompt.title ?: "Choose file${if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) "s" else ""}",
                message = "Select a file to upload to this page.",
                multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                mimeTypes = prompt.mimeTypes?.toList().orEmpty(),
                basePrompt = prompt,
                resolve = { answer ->
                    if (answer.accepted && answer.selectedFileUris.isNotEmpty()) {
                        prompt.confirm(context, answer.selectedFileUris.map(Uri::parse).toTypedArray())
                    } else prompt.dismiss()
                },
                cancel = { prompt.dismiss() },
            )
        })
        geckoSession.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                sink.onScrollChanged(BrowserScrollChanged(tabId, scrollY.coerceAtLeast(0)))
            }
        })
    }

    override fun open() {
        geckoSession.open(runtime)
    }

    override fun attach(host: BrowserSurfaceHost) {
        val view = (host as? GeckoViewBrowserSurfaceHost)?.view ?: return
        if (view.session !== geckoSession) {
            view.session?.let { view.releaseSession() }
            view.setSession(geckoSession)
        }
    }

    override fun load(request: BrowserNavigation) {
        pendingHistoryAddress = request.historyAddress
        geckoSession.loadUri(request.value)
    }

    override fun downloadResource(url: String, referrer: String?): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank()) return false
        val requestBuilder = WebRequest.Builder(url)
        resourceReferrer(referrer, parsed)?.let(requestBuilder::referrer)
        return runCatching {
            GeckoWebExecutor(runtime).fetch(requestBuilder.build()).accept(
                { maybeResponse ->
                    val response = maybeResponse ?: run {
                        sink.onDownloadRequested(
                            BrowserDownloadRequest(
                                tabId = tabId,
                                url = url,
                                mimeType = "application/octet-stream",
                                fileName = null,
                            ),
                        )
                        return@accept
                    }
                    val headers = response.headers
                    val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" }
                    val contentDisposition = headers.entries.firstOrNull { it.key.equals("Content-Disposition", true) }?.value
                    val contentLength = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }
                        ?.value?.toLongOrNull() ?: -1L
                    val body = response.body?.takeIf { response.statusCode in 200..299 }
                    if (body == null) runCatching { response.body?.close() }
                    sink.onDownloadRequested(
                        BrowserDownloadRequest(
                            tabId = tabId,
                            url = response.uri.ifBlank { url },
                            mimeType = contentType,
                            fileName = null,
                            contentDisposition = contentDisposition,
                            body = body,
                            totalBytes = contentLength,
                        ),
                    )
                },
                {
                    sink.onDownloadRequested(
                        BrowserDownloadRequest(
                            tabId = tabId,
                            url = url,
                            mimeType = "application/octet-stream",
                            fileName = null,
                            body = null,
                        ),
                    )
                },
            )
            true
        }.getOrDefault(false)
    }

    /** Uses the platform default strict-origin-when-cross-origin referrer behavior. */
    private fun resourceReferrer(referrer: String?, target: Uri): String? {
        val source = referrer?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val sourceScheme = source.scheme?.lowercase() ?: return null
        val targetScheme = target.scheme?.lowercase() ?: return null
        if (sourceScheme !in setOf("http", "https") || source.host.isNullOrBlank()) return null
        if (sourceScheme == "https" && targetScheme == "http") return null
        val authority = buildString {
            append(source.host)
            if (source.port >= 0) append(':').append(source.port)
        }
        val sameOrigin = sourceScheme == targetScheme &&
            source.host.equals(target.host, ignoreCase = true) && source.port == target.port
        if (sameOrigin) return source.buildUpon().encodedAuthority(authority).fragment(null).build().toString()
        return "$sourceScheme://$authority/"
    }

    override fun goBack() = geckoSession.goBack()
    override fun goForward() = geckoSession.goForward()
    override fun reload() = geckoSession.reload()
    override fun stop() = geckoSession.stop()
    override fun clearHistory() = geckoSession.purgeHistory()
    override fun setDesktopSite(enabled: Boolean) {
        geckoSession.settings.setUserAgentMode(
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE,
        )
        geckoSession.settings.setViewportMode(
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE,
        )
    }

    override fun saveState(): BrowserSerializedState? = serializedState

    override fun restoreState(state: BrowserSerializedState) {
        val restored = GeckoSession.SessionState.fromString(state.value)
            ?: throw IllegalArgumentException("Persisted Gecko session state is invalid")
        geckoSession.restoreState(restored)
        serializedState = state
    }

    override fun close() {
        geckoSession.close()
    }

    private fun permissionName(permission: Int): String = when (permission) {
        PermissionDelegate.PERMISSION_GEOLOCATION -> "location"
        PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> "notifications"
        PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> "persistent storage"
        PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE, PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> "autoplay"
        else -> "additional site access"
    }

    private fun requestWebsitePrompt(
        kind: BrowserWebsitePromptKind,
        title: String?,
        message: String?,
        origin: String? = lastKnownUrl,
        defaultValue: String? = null,
        defaultUsername: String? = null,
        defaultPassword: String? = null,
        passwordOnly: Boolean = false,
        targetUri: String? = null,
        choices: List<BrowserWebsitePromptChoice> = emptyList(),
        choiceMode: BrowserChoiceMode? = null,
        multiple: Boolean = false,
        mimeTypes: List<String> = emptyList(),
        inputType: String? = null,
        minValue: String? = null,
        maxValue: String? = null,
        predefinedValues: List<String> = emptyList(),
        basePrompt: GeckoSession.PromptDelegate.BasePrompt? = null,
        onPromptUpdate: ((BrowserWebsitePromptRequest, GeckoSession.PromptDelegate.BasePrompt) -> BrowserWebsitePromptRequest)? = null,
        resolve: (BrowserWebsitePromptAnswer) -> GeckoSession.PromptDelegate.PromptResponse,
        cancel: () -> GeckoSession.PromptDelegate.PromptResponse,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val request = BrowserWebsitePromptRequest(
            id = "$tabId-${System.nanoTime()}",
            tabId = tabId,
            kind = kind,
            origin = origin?.let(::displayOrigin).orEmpty(),
            title = title,
            message = message,
            defaultValue = defaultValue,
            defaultUsername = defaultUsername,
            defaultPassword = defaultPassword,
            passwordOnly = passwordOnly,
            targetUri = targetUri,
            choices = choices,
            choiceMode = choiceMode,
            multiple = multiple,
            mimeTypes = mimeTypes,
            inputType = inputType,
            minValue = minValue,
            maxValue = maxValue,
            predefinedValues = predefinedValues,
            decision = CompletableDeferred(),
        )
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        var revision = request.revision
        basePrompt?.setDelegate(object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                sink.onWebsitePromptDismissed(tabId, request.id)
            }

            override fun onPromptUpdate(prompt: GeckoSession.PromptDelegate.BasePrompt) {
                val refreshed = onPromptUpdate?.invoke(request, prompt) ?: request
                revision += 1
                sink.onWebsitePromptUpdated(refreshed.copy(revision = revision))
            }
        })
        sink.onWebsitePromptRequested(request)
        request.decision.invokeOnCompletion { cause ->
            val answer = if (cause == null) {
                runCatching { request.decision.getCompleted() }.getOrDefault(BrowserWebsitePromptAnswer(false))
            } else BrowserWebsitePromptAnswer(false)
            promptHandler.post {
                result.complete(runCatching { resolve(answer) }.getOrElse { cancel() })
            }
        }
        return result
    }

    /** JS dialogs identify the site by host and port, without leaking its page path. */
    private fun displayOrigin(value: String): String = runCatching {
        val uri = Uri.parse(value)
        val host = uri.host?.takeIf(String::isNotBlank) ?: return value.substringBefore('?').substringBefore('#').take(128)
        if (uri.port >= 0) "$host:${uri.port}" else host
    }.getOrDefault(value.substringBefore('?').substringBefore('#').take(128))

    /** Site-permission copy includes the scheme, matching Chrome's permission prompts. */
    private fun displayPermissionOrigin(value: String): String = runCatching {
        val uri = Uri.parse(value)
        val host = uri.host?.takeIf(String::isNotBlank) ?: return value.substringBefore('?').substringBefore('#').take(128)
        val authority = if (uri.port >= 0) "$host:${uri.port}" else host
        "${uri.scheme ?: "https"}://$authority"
    }.getOrDefault(value.substringBefore('?').substringBefore('#').take(128))

    private fun displayErrorAddress(value: String): String = runCatching {
        val uri = Uri.parse(value)
        val host = uri.host
        if (host.isNullOrBlank()) value.substringBefore('?').substringBefore('#').take(256)
        else buildString {
            uri.scheme?.let { append(it).append("://") }
            append(host)
            if (uri.port >= 0) append(':').append(uri.port)
            val path = uri.path
            if (!path.isNullOrBlank()) append(path)
        }.take(256)
    }.getOrDefault(value.substringBefore('?').substringBefore('#').take(256))

    private fun flattenChoices(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
        depth: Int = 0,
    ): List<BrowserWebsitePromptChoice> = buildList {
        choices.forEach { choice ->
            if (choice.separator) {
                add(BrowserWebsitePromptChoice("separator-${size}", "", false, true, separator = true, depth = depth))
                return@forEach
            }
            val children = choice.items
            if (children.isNullOrEmpty()) {
                add(BrowserWebsitePromptChoice(choice.id, choice.label, choice.selected, choice.disabled, depth = depth))
            } else {
                add(BrowserWebsitePromptChoice("group-${choice.id}", choice.label, false, true, group = true, depth = depth))
                addAll(flattenChoices(children, depth + 1))
            }
        }
    }

    private fun navigationErrorMessage(error: WebRequestError): Pair<String, String> = when (error.code) {
        WebRequestError.ERROR_UNKNOWN_HOST -> "Can't find this site" to "The server address could not be found. Check the address or your internet connection."
        WebRequestError.ERROR_OFFLINE -> "You're offline" to "This device appears to have no internet connection. Reconnect and try again."
        WebRequestError.ERROR_NET_TIMEOUT -> "Connection timed out" to "The server took too long to respond."
        WebRequestError.ERROR_CONNECTION_REFUSED -> "Connection refused" to "The server refused the connection. It may be unavailable or not accepting requests."
        WebRequestError.ERROR_NET_RESET, WebRequestError.ERROR_NET_INTERRUPT -> "Connection interrupted" to "The connection was interrupted before the page finished loading."
        WebRequestError.ERROR_UNKNOWN_PROXY_HOST, WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "Proxy unavailable" to "The configured proxy could not be reached."
        WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_SECURITY_SSL, WebRequestError.ERROR_BAD_HSTS_CERT ->
            "Secure connection failed" to "The site's certificate or secure connection could not be verified. The page was not opened."
        WebRequestError.ERROR_MALFORMED_URI, WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "Invalid address" to "The address is malformed or uses a protocol this browser cannot open."
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI, WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI, WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI ->
            "Page blocked" to "GeckoView blocked this address as potentially harmful."
        else -> "Page couldn't load" to "Check your internet connection and the address, then try again."
    }
}

/** Owns the bundled browser DevTools extension; user exposure is controlled separately. */
class GeckoBrowserDeveloperToolsService(
    private val runtime: GeckoRuntime,
) : BrowserDeveloperToolsService {
    private val handler = Handler(Looper.getMainLooper())
    private val ports = linkedSetOf<WebExtension.Port>()
    private var enabled = true
    private var opened = false
    private var installed = false
    private var installing = false

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        installIfNeeded()
        postVisibility(opened && enabled)
    }

    override fun open() {
        if (!enabled) return
        opened = true
        installIfNeeded()
        postVisibility(true)
    }

    override fun close() {
        opened = false
        postVisibility(false)
    }

    private fun installIfNeeded() {
        if (installed || installing) return
        installing = true
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/devtools/",
            "android-ide-devtools@android-ide",
        ).accept({ extension ->
            handler.post {
                installing = false
                if (extension == null) return@post
                installed = true
                extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        if (port.name != "android_ide") return
                        ports += port
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) = Unit

                            override fun onDisconnect(port: WebExtension.Port) {
                                ports -= port
                            }
                        })
                        postVisibility(opened, port)
                    }
                }, "android_ide")
            }
        }, {
            handler.post { installing = false; installed = false }
        })
    }

    private fun postVisibility(open: Boolean, only: WebExtension.Port? = null) {
        val message = JSONObject()
            .put("type", "developer-tools")
            .put("enabled", enabled)
            .put("open", open && enabled)
        (only?.let(::listOf) ?: ports.toList()).forEach { port -> runCatching { port.postMessage(message) } }
    }
}
