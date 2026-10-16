package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator
import com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarState
import com.danielealbano.androidremotecontrolmcp.services.controlbar.OverlayLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.ui.components.controlbar.ControlBarCallbacks
import com.danielealbano.androidremotecontrolmcp.ui.components.controlbar.ControlBarOverlay
import com.danielealbano.androidremotecontrolmcp.ui.components.controlbar.EdgeGlow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

@Suppress("TooManyFunctions")
class McpAccessibilityService : AccessibilityService() {
    // AccessibilityNodeCache/ControlBarCoordinator are in the same package — no import needed
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NodeCacheEntryPoint {
        fun nodeCache(): AccessibilityNodeCache

        fun controlBarCoordinator(): ControlBarCoordinator

        fun settingsRepository(): SettingsRepository
    }

    private var serviceScope: CoroutineScope? = null

    private var nodeCache: AccessibilityNodeCache? = null

    private var cacheInvalidationDebouncer: CacheInvalidationDebouncer? = null

    @Volatile
    private var currentPackageName: String? = null

    @Volatile
    private var currentActivityName: String? = null

    /** Set once both overlay views are added (see [setupControlBarOverlay]); null before that and
     *  after [teardownControlBarOverlay]. [setOverlayHidden] is a safe no-op while null. */
    @Volatile
    private var overlayViews: List<android.view.View>? = null

    // All five @Volatile for the same reason as overlayViews above: written from the Main-
    // dispatched serviceScope collector (ensureOverlayWindows/removeOverlayWindows), which now
    // cycles these null<->non-null on every Hidden<->non-Hidden transition (round 2) instead of
    // writing them once at service-connect - and read from setBarTouchable, which StepDispatcher
    // invokes from an IO-dispatched coroutine (found in code review, round 2).
    @Volatile
    private var barView: ComposeView? = null

    @Volatile
    private var glowView: ComposeView? = null

    @Volatile
    private var barLifecycleOwner: OverlayLifecycleOwner? = null

    @Volatile
    private var glowLifecycleOwner: OverlayLifecycleOwner? = null

    @Volatile
    private var barLayoutParams: WindowManager.LayoutParams? = null

    /** Hides (or restores) DroidThumb's own floating control bar/glow overlay around a
     *  whole-display screenshot capture on API levels below the per-window capture API (plan 71,
     *  D-39) — a whole-display capture would otherwise include them. A no-op when no overlay views
     *  exist yet (nothing to hide).
     *
     *  `suspend` + `withContext(Dispatchers.Main)` (round 3 phone-feedback regression, found via
     *  code audit): `View.visibility`'s setter is a real Android View operation that requires the
     *  thread that created the view hierarchy, same as [setBarTouchable]'s own
     *  `WindowManager.updateViewLayout` call - and this one's caller,
     *  `ScreenCaptureProviderImpl.captureExcludingOverlay`, runs on the step-dispatch path's own
     *  background dispatcher too. Returning only once the change has actually applied (not
     *  fire-and-forget) matters here too: the caller takes the screenshot immediately after,
     *  which must never race the overlay actually being hidden. */
    suspend fun setOverlayHidden(hidden: Boolean) {
        withContext(Dispatchers.Main) {
            overlayViews?.forEach { it.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE }
        }
    }

    /** Toggles the bar window's `FLAG_NOT_TOUCHABLE` bit - registered with
     *  [ControlBarCoordinator.registerBarTouchToggle] in [setupControlBarOverlay], called by
     *  [com.danielealbano.androidremotecontrolmcp.wireprotocol.StepDispatcher] around a
     *  raw-coordinate gesture dispatch (plan 71's touch-passthrough decision). */
    @Suppress("ReturnCount")
    private fun setBarTouchable(touchable: Boolean) {
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val view = barView ?: return
        val params = barLayoutParams ?: return
        params.flags =
            if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        windowManager.updateViewLayout(view, params)
    }

    /** Adds/removes the glow (behind, full-screen, always pass-through) and bar (in front) overlay
     *  windows via [WindowManager]/`TYPE_ACCESSIBILITY_OVERLAY` — no "display over other apps"
     *  permission needed, since this is restricted to accessibility services (plan 71, D-39). The
     *  windows are only attached while [ControlBarCoordinator.state] is NOT [ControlBarState.Hidden]
     *  - torn down and recreated fresh on every Hidden<->non-Hidden transition, driven by a plain
     *  coroutine collecting [ControlBarCoordinator.state] in this service's own [serviceScope].
     *
     *  Thread-safety audit (round 3 phone-feedback regression - [setBarTouchable]/
     *  [setOverlayHidden] needed an explicit `withContext(Dispatchers.Main)` fix because their
     *  callers run on a background dispatcher; this collector does NOT, and deliberately has no
     *  such wrapping): [serviceScope] is created with `Dispatchers.Main`, so every emission this
     *  `collect` block processes - and therefore every [ensureOverlayWindows]/[removeOverlayWindows]
     *  call below - already runs on the main thread, regardless of which thread's call to
     *  [com.danielealbano.androidremotecontrolmcp.services.controlbar.ControlBarCoordinator.beginStep]/
     *  `taskDone`/`stop` etc. caused the emission - Kotlin's structured concurrency confines a
     *  collecting coroutine's resumptions to its OWN dispatcher, not the emitting thread's.
     *  [teardownControlBarOverlay]'s own call to [removeOverlayWindows] is likewise safe without
     *  wrapping: it only ever runs from `onDestroy()`, which Android itself guarantees is called on
     *  the main thread for every `Service`/`AccessibilityService` lifecycle callback.
     *
     *  Found live on a real device (redroid, round 2): leaving the windows permanently attached and
     *  relying on Compose recomposition alone to render "nothing" for Hidden left the Done bar's
     *  LAST rendered frame stuck on screen forever, even though the coordinator's own state had
     *  genuinely moved on to Hidden (confirmed via logging - the underlying StateFlow transitioned
     *  correctly; only the window's displayed pixels never caught up). Growing content (e.g.
     *  Hidden -> Ended) recomposed and displayed correctly; shrinking back to empty did not -
     *  consistent with the WindowManager window's last-submitted buffer simply never being asked to
     *  redraw once its measured content shrank to nothing. Removing the window outright on Hidden
     *  makes that failure mode impossible: there is no buffer left to go stale. The bar is fixed at
     *  the top of the screen, below the status bar - no collapse, drag or record button (removed in
     *  round 2 per founder phone-test feedback: "simplify for now", visual design is a later pass).
     */
    private fun setupControlBarOverlay(controlBarCoordinator: ControlBarCoordinator) {
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        controlBarCoordinator.registerBarTouchToggle(::setBarTouchable)

        serviceScope?.launch {
            controlBarCoordinator.state.collect { newState ->
                controlBarUiState = newState
                if (shouldShowOverlayWindows(newState)) {
                    ensureOverlayWindows(windowManager, controlBarCoordinator)
                } else {
                    removeOverlayWindows(windowManager)
                }
            }
        }
    }

    /** Adds the glow/bar windows if not already present. Found in code review (round 2): a stale
     *  or invalid window token (app backgrounded mid-transition, display reconfiguration) can make
     *  [WindowManager.addView] throw even here, same as any other overlay window add. Caught so
     *  one bad add doesn't crash the service or leave an orphaned glow window added-but-untracked
     *  (which [removeOverlayWindows] could then never find again) - the fields are only populated,
     *  and the glow window only kept, once BOTH adds succeed. */
    private fun ensureOverlayWindows(
        windowManager: WindowManager,
        controlBarCoordinator: ControlBarCoordinator,
    ) {
        if (barView != null) return

        val glowOwner = OverlayLifecycleOwner().also { it.onAttach() }
        val glow = createGlowView(glowOwner)
        val barOwner = OverlayLifecycleOwner().also { it.onAttach() }
        val bar = createBarView(barOwner, controlBarCoordinator)
        val barParams = barLayoutParams()

        var glowAdded = false
        try {
            windowManager.addView(glow, glowLayoutParams())
            glowAdded = true
            windowManager.addView(bar, barParams)
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Failed to add control bar overlay windows: ${e.message}")
            if (glowAdded) runCatching { windowManager.removeView(glow) }
            glowOwner.onDetach()
            barOwner.onDetach()
            return
        }

        glowView = glow
        barView = bar
        glowLifecycleOwner = glowOwner
        barLifecycleOwner = barOwner
        this.barLayoutParams = barParams
        overlayViews = listOf(glow, bar)
    }

    /** Removes the glow/bar windows if present. Mirrors [ensureOverlayWindows]'s own defensive
     *  handling (round 3 phone-feedback regression, found via code audit): an uncaught exception
     *  from either `removeView` call here would propagate out of the state collector's `collect`
     *  block in [setupControlBarOverlay] and kill that coroutine permanently - after which NO
     *  further overlay state change would ever be applied again for the rest of the service's
     *  life, matching exactly the "the bar/glow sometimes never dismisses" symptom reported this
     *  round. Each view is removed independently so a failure removing one (e.g. a view already
     *  detached by some other racing caller) never prevents the other from being cleaned up, and
     *  never kills the collector - same `runCatching` best-effort pattern as [ensureOverlayWindows]'s
     *  own cleanup-on-failure path. */
    private fun removeOverlayWindows(windowManager: WindowManager) {
        barView?.let { runCatching { windowManager.removeView(it) } }
        glowView?.let { runCatching { windowManager.removeView(it) } }
        barLifecycleOwner?.onDetach()
        glowLifecycleOwner?.onDetach()
        barView = null
        glowView = null
        barLifecycleOwner = null
        glowLifecycleOwner = null
        barLayoutParams = null
        overlayViews = null
    }

    /** Written only from the [setupControlBarOverlay] collector; read by both overlay views'
     *  composables. See that function's doc comment for why this indirection exists. */
    private var controlBarUiState by mutableStateOf<ControlBarState>(ControlBarState.Hidden)

    private fun createGlowView(owner: OverlayLifecycleOwner): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                when (controlBarUiState) {
                    is ControlBarState.Running -> EdgeGlow(paused = false)
                    is ControlBarState.Paused -> EdgeGlow(paused = true)
                    else -> Unit
                }
            }
        }

    private fun glowLayoutParams() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        )

    private fun createBarView(
        owner: OverlayLifecycleOwner,
        controlBarCoordinator: ControlBarCoordinator,
    ): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state = controlBarUiState
                ControlBarOverlay(state = state, callbacks = barCallbacks(controlBarCoordinator, state))
            }
        }

    private fun barCallbacks(
        controlBarCoordinator: ControlBarCoordinator,
        state: ControlBarState,
    ) = ControlBarCallbacks(
        onStop = controlBarCoordinator::stop,
        onPauseOrResume = {
            if (state is ControlBarState.Paused) controlBarCoordinator.resume() else controlBarCoordinator.pause()
        },
        onBackToApp = {
            backToForegroundApp(state)
            // Ended has nothing left to resume - dismiss the bar once "Back to" is used (plan
            // 71's own decision); Paused no longer offers "Back to" at all (round 2 simplification).
            if (state is ControlBarState.Ended) controlBarCoordinator.close()
        },
        onClose = controlBarCoordinator::close,
    )

    /** A small, explicit, density-aware pixel width - deliberately NEITHER `WRAP_CONTENT` NOR
     *  `MATCH_PARENT`. Found live (redroid, round 2): a `WRAP_CONTENT` window hosting this Compose
     *  content measured wider than the screen for a long "Back to <app>" label, running the text
     *  off the right edge with no way to see or tap it. Switching to `MATCH_PARENT` "fixed" that
     *  clipping but turned the ENTIRE top strip of the screen into an invisible touch-dead-zone:
     *  Android routes a whole touch gesture to the topmost non-`FLAG_NOT_TOUCHABLE` window under
     *  the down-point regardless of what's actually drawn there, so empty margin pixels at the
     *  very top of the screen silently swallowed taps meant for real app/system UI underneath
     *  (e.g. pulling down the notification shade) - found in code review, round 2, not caught by
     *  manual testing since it has no visible symptom. A small explicit pixel width, capped to the
     *  screen's own width as a safety net, keeps the window's real (and therefore touchable)
     *  footprint tight around the visible pill - what `WRAP_CONTENT` was meant to do, without its
     *  measurement ambiguity - while `ControlBarOverlay`'s "Back to <app>" label now also
     *  truncates with an ellipsis instead of relying on window size alone to avoid clipping. */
    private fun barLayoutParams(): WindowManager.LayoutParams {
        val widthPx = minOf(dpToPx(BAR_MAX_WIDTH_DP), resources.displayMetrics.widthPixels)
        return WindowManager
            .LayoutParams(
                widthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                y = statusBarHeightPx() + dpToPx(TOP_BAR_CLEARANCE_DP)
            }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Classic cross-OEM resource lookup for the status bar's height, with a safe fallback if a
     *  ROM doesn't define it (this overlay window isn't a normal app window, so it isn't
     *  automatically inset-aware the way an Activity's decor view is). Good enough for "below the
     *  status bar" as a fixed position - a later visual-design pass can replace this with a
     *  real `WindowInsets` listener if a specific OEM proves this wrong. */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dpToPx(FALLBACK_STATUS_BAR_HEIGHT_DP)
    }

    @Suppress("ReturnCount")
    private fun backToForegroundApp(state: ControlBarState) {
        val target =
            when (state) {
                is ControlBarState.Paused -> state.returnTarget
                is ControlBarState.Ended -> state.returnTarget
                else -> null
            } ?: return
        val pkg = target.packageName
        if (pkg == null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            // A Service (this is one, not an Activity) MUST set FLAG_ACTIVITY_NEW_TASK or
            // startActivity() either throws or silently no-ops depending on the OEM's framework
            // build - this was the actual cause of "Back to <app> does nothing" (founder
            // phone-test feedback round 2, reproduced on a real OnePlus/OxygenOS device).
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Could not return to $pkg: ${e.message}")
        }
    }

    private fun teardownControlBarOverlay() {
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        removeOverlayWindows(windowManager)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope
        nodeCache = resolveNodeCache()
        // Kill-switch (plan 71 round 3): found live on a real OnePlus that every tap/type_text
        // step failed with "Only the original thread that created a view hierarchy can touch its
        // views" - the bar's touch-passthrough toggle ran off the main thread. That root cause is
        // now fixed (ControlBarCoordinator.setBarTouchable/McpAccessibilityService.setOverlayHidden
        // both dispatch onto Main), verified live on redroid, and the setting defaults back to
        // enabled (ControlBarSettingsImpl) - this check, and the setting itself, stay as the
        // escape hatch for the same class of regression in the future. With it off, the overlay is
        // never set up at all - zero behavior difference from a build that never had the bar.
        scope.launch {
            if (resolveSettingsRepository()?.getControlBarEnabled() == true) {
                resolveControlBarCoordinator()?.let { setupControlBarOverlay(it) }
            }
        }
        cacheInvalidationDebouncer =
            CacheInvalidationDebouncer(
                scope = scope,
                debounceMillis = CACHE_INVALIDATION_DEBOUNCE_MS,
                onSettled = { invalidateCache(nodeCache) },
            )

        configureServiceInfo()

        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // See isForeignWindowStateChange's doc comment: DroidThumb's own overlay windows
                // and non-TYPE_APPLICATION windows (IME, status bar, recents, system dialogs)
                // fire this same event type and must never be tracked as "the foreground app"
                // themselves. An unresolvable (null) window type counts as foreign: the window
                // lookup most often fails exactly when a newly launched app's first window-state
                // event fires, and A5's launch_app wait depends on the package being recorded.
                val windowType = getAccessibilityWindows().firstOrNull { it.id == event.windowId }?.type
                if (isForeignWindowStateChange(event.packageName?.toString(), BuildConfig.APPLICATION_ID, windowType)) {
                    // Package-level backstop on top of the window-type check: even when the event
                    // looks like a real application window, our own package, System UI and every
                    // enabled input method must never overwrite the tracked foreground package -
                    // the control bar captures its "Back to <app>" return target from it, and an
                    // IME package has no launcher activity (16 Oct phone test: "Back to Gboard").
                    val recordedPackage = event.packageName?.toString()
                    if (recordedPackage != null &&
                        !isBackToTargetPackageForbidden(
                            recordedPackage,
                            BuildConfig.APPLICATION_ID,
                            enabledInputMethodPackages(),
                        )
                    ) {
                        currentPackageName = recordedPackage
                        event.className?.toString()?.let { className -> currentActivityName = className }
                    }
                }
                Log.d(
                    TAG,
                    "Window state changed: package=$currentPackageName, " +
                        "activity=$currentActivityName",
                )
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Soft-keyboard show/hide and other window add/remove/bounds changes arrive here.
                Log.d(TAG, "Windows changed (e.g. soft-keyboard show/hide)")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "Window content changed: package=${event.packageName}")
            }

            else -> {
                // Ignored event types
            }
        }

        // A structural window change (keyboard show/hide, rotation, activity/dialog transition)
        // shifts element bounds. Because cached node ids are derived from bounds, and which
        // elements are present/actionable also changes, the cached id->node entries become stale.
        // Schedule a debounced invalidation so the cache is dropped once — and only once — AFTER
        // the transition settles, so the next node lookup resolves against the live tree.
        scheduleCacheInvalidationIfNeeded(event.eventType, cacheInvalidationDebouncer)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroying")

        teardownControlBarOverlay()

        // Stop any pending debounced invalidation before tearing down the scope it runs on.
        cacheInvalidationDebouncer?.cancel()
        cacheInvalidationDebouncer = null

        // Flush the node cache — all AccessibilityNodeInfo references become invalid.
        nodeCache?.clear()
        nodeCache = null

        serviceScope?.cancel()
        serviceScope = null
        currentPackageName = null
        currentActivityName = null
        inputMethodInstance = null
        instance = null

        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                else -> "UNKNOWN($level)"
            }
        Log.w(TAG, "Trim memory: level=$levelName")
    }

    /**
     * Returns the root [AccessibilityNodeInfo] of the currently active window,
     * or null if no window is available.
     */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Returns all on-screen windows via [AccessibilityService.getWindows].
     * Requires [AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS].
     *
     * @return List of [AccessibilityWindowInfo], or empty list if unavailable.
     */
    fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
        try {
            windows ?: emptyList()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "getWindows() failed: ${e.message}")
            emptyList()
        }

    /**
     * Returns the package name of the currently focused application,
     * or null if unknown.
     */
    fun getCurrentPackageName(): String? = currentPackageName

    /** Packages of every enabled input method, read at check time (the list changes rarely; no
     *  caching needed). Empty when the input method manager is unavailable, so the package check
     *  degrades to the own-package/System-UI exclusions only. */
    private fun enabledInputMethodPackages(): List<String> =
        getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            .orEmpty()

    /**
     * Returns the class name (activity name) of the currently focused window,
     * or null if unknown.
     */
    fun getCurrentActivityName(): String? = currentActivityName

    /**
     * Returns true if the service is connected and ready to process requests.
     * Does NOT check for an active window — multi-window support handles
     * window availability at tree-parsing time.
     */
    fun isReady(): Boolean = instance != null

    /**
     * Returns the [CoroutineScope] for this service, or null if not connected.
     */
    fun getServiceScope(): CoroutineScope? = serviceScope

    /**
     * Returns the current screen dimensions, density, and orientation.
     *
     * @return [ScreenInfo] with width, height, densityDpi, and orientation.
     */
    fun getScreenInfo(): ScreenInfo {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val width = bounds.width()
        val height = bounds.height()

        val displayMetrics = resources.displayMetrics
        val densityDpi = displayMetrics.densityDpi

        val orientation =
            when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ScreenInfo.ORIENTATION_LANDSCAPE
                else -> ScreenInfo.ORIENTATION_PORTRAIT
            }

        return ScreenInfo(
            width = width,
            height = height,
            densityDpi = densityDpi,
            orientation = orientation,
        )
    }

    override fun onCreateInputMethod(): InputMethod {
        val method = McpInputMethod(this)
        inputMethodInstance = method
        return method
    }

    private fun configureServiceInfo() {
        serviceInfo =
            serviceInfo?.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
            }
        if (serviceInfo == null) {
            Log.w(TAG, "serviceInfo is null, cannot configure accessibility service settings")
        }
    }

    /**
     * Resolves the singleton [AccessibilityNodeCache] via Hilt's application entry point.
     * Returns null (and logs) if Hilt is not initialized, in which case cache invalidation
     * becomes a no-op rather than crashing the service.
     */
    private fun resolveNodeCache(): AccessibilityNodeCache? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .nodeCache()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve node cache", e)
            null
        }

    private fun resolveControlBarCoordinator(): ControlBarCoordinator? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .controlBarCoordinator()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve control bar coordinator", e)
            null
        }

    private fun resolveSettingsRepository(): SettingsRepository? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .settingsRepository()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve settings repository", e)
            null
        }

    /**
     * Takes a screenshot using AccessibilityService.takeScreenshot() API.
     * Does NOT require user consent.
     *
     * @param timeoutMs Maximum time to wait for screenshot capture.
     * @return Bitmap of the screenshot, or null if capture failed or timed out.
     */
    suspend fun takeScreenshotBitmap(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, Executor { it.run() }, screenshotCallback(continuation))
            }
        }

    /**
     * Captures just [windowId]'s content (API 34+ only) — never this app's own overlay windows,
     * which are different windows entirely (plan 71, D-39). Falls back to `null` on any failure
     * (including pre-34 API levels, where [android.accessibilityservice.AccessibilityService]
     * has no `takeScreenshotOfWindow` overload at all) so the caller can fall back to whole-display
     * capture rather than fail the screenshot outright.
     */
    @SuppressLint("NewApi")
    @Suppress("TooGenericExceptionCaught")
    suspend fun takeScreenshotOfWindowBitmap(
        windowId: Int,
        timeoutMs: Long = SCREENSHOT_TIMEOUT_MS,
    ): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    takeScreenshotOfWindow(windowId, Executor { it.run() }, screenshotCallback(continuation))
                } catch (e: Exception) {
                    Log.w(TAG, "takeScreenshotOfWindow failed: ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun screenshotCallback(continuation: CancellableContinuation<Bitmap?>) =
        object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close()
                if (continuation.isActive) continuation.resume(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                Log.e(TAG, "Screenshot failed with error code: $errorCode")
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /**
     * Returns true if screenshot capability is available. Always true on minSdk 33+.
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun canTakeScreenshot(): Boolean = true

    /**
     * Drops the framework's accessibility node cache for this service via [clearCache] (public
     * since API 33; minSdk is 33). See [AccessibilityServiceProvider.clearFrameworkNodeCache] for
     * why this is needed to defeat stale WebView reads after JavaScript DOM changes.
     *
     * This is distinct from [invalidateCache], which flushes our own id→node [nodeCache]; this
     * clears the framework-side cache that backs [rootInActiveWindow]/[getWindows] traversal.
     */
    fun clearFrameworkNodeCache() {
        clearCache()
    }

    class McpInputMethod(
        service: AccessibilityService,
    ) : InputMethod(service)

    companion object {
        private const val TAG = "MCP:AccessibilityService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L

        /**
         * Length of the QUIET GAP (no further window-structure events) that must elapse before the
         * node cache is invalidated. The debounce timer resets on every event during a transition,
         * so this value is the silence required *after the last event*, not the total transition
         * duration. 250ms is an empirically chosen heuristic — long enough that a settling
         * keyboard/rotation has stopped emitting events, short enough to keep the post-transition
         * cache fresh quickly. Single tunable constant; validate/adjust against real-device traces.
         */
        private const val CACHE_INVALIDATION_DEBOUNCE_MS = 250L

        /** Fixed position of the floating control bar (plan 71, D-39, round 2): top-center, this
         *  much clearance below the status bar. Density-aware (dp, converted via [dpToPx]) -
         *  found in code review, round 2, that the round-2 original (`24` raw pixels) wasn't. */
        private const val TOP_BAR_CLEARANCE_DP = 8

        /** Used only if a ROM doesn't define the `status_bar_height` resource at all - a
         *  conservative, not device-accurate approximation. Expressed in dp (Android's own status
         *  bar height is a near-constant physical size across densities when expressed this way),
         *  converted via [dpToPx] - found in code review, round 2, that the round-2 original
         *  (`100` raw pixels, justified by a comment computing to ~63px at 420dpi) was neither
         *  density-aware nor consistent with its own justification. */
        private const val FALLBACK_STATUS_BAR_HEIGHT_DP = 24

        /** Caps the bar window's own width (see [barLayoutParams]) - wide enough for a real
         *  "Back to <app name>" label without the window spanning (and touch-blocking, see
         *  [barLayoutParams]'s own doc comment) the full screen width. */
        private const val BAR_MAX_WIDTH_DP = 360

        /**
         * Singleton instance of the accessibility service.
         * Set when the service connects, cleared when it is destroyed.
         * Access from other components to interact with the accessibility tree.
         */
        @Volatile
        var instance: McpAccessibilityService? = null
            private set

        @Volatile
        var inputMethodInstance: McpInputMethod? = null
            private set
    }
}

/**
 * Returns true if [eventType] is a structural window change after which cached node bounds may be
 * stale and the node cache should be invalidated: [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]
 * (rotation, activity/dialog transition) and [AccessibilityEvent.TYPE_WINDOWS_CHANGED]
 * (soft-keyboard show/hide, window add/remove).
 *
 * Deliberately EXCLUDES [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED], which fires far too
 * frequently (e.g. live text, progress bars) to drive cache invalidation without thrashing.
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun triggersCacheInvalidation(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

/**
 * Schedules a debounced cache invalidation iff [eventType] is a structural window change (see
 * [triggersCacheInvalidation]). No-op when [debouncer] is null (service not fully connected).
 *
 * Top-level and `internal` so the event-to-schedule wiring can be unit-tested without
 * instantiating the service.
 */
internal fun scheduleCacheInvalidationIfNeeded(
    eventType: Int,
    debouncer: CacheInvalidationDebouncer?,
) {
    if (triggersCacheInvalidation(eventType)) {
        debouncer?.schedule()
    }
}

/**
 * Clears [cache] if present. The whole-cache drop is the invalidation applied after a settled
 * window transition; a null [cache] (Hilt entry point unavailable) is a safe no-op.
 *
 * Top-level and `internal` so the invalidation behavior can be unit-tested without instantiating
 * the service.
 */
internal fun invalidateCache(cache: AccessibilityNodeCache?) {
    cache?.clear()
}

/**
 * Returns true if a [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] event naming [eventPackageName]
 * (from a window of [windowType]) should update the tracked foreground package/activity.
 *
 * Two independent exclusions, found live on redroid against this build (round 4 phone-feedback
 * regression - "Back to <app>" did nothing for a real app, recorded as "Back to Android Keyboard
 * (AOSP)" instead):
 *  - [eventPackageName] is NOT [selfPackageName]. DroidThumb's own floating control bar/glow
 *    overlay windows (plan 71, D-39) fire this same event type when added; a real bug found live
 *    during phone-test round 2's redroid verification: "Back to <app>" showed "Back to
 *    DroidThumb" instead of the real foreground app, because the overlay's own appearance had
 *    overwritten the tracked foreground package with DroidThumb's own package right as a session
 *    started.
 *  - [windowType] is [AccessibilityWindowInfo.TYPE_APPLICATION]. A session's very first
 *    step is very often a tap into a text field followed by typing, which brings up the IME -
 *    itself a [AccessibilityWindowInfo.TYPE_INPUT_METHOD] window, not
 *    `TYPE_APPLICATION`, but one that still fires its OWN `TYPE_WINDOW_STATE_CHANGED` under the
 *    keyboard app's own package (e.g. `com.android.inputmethod.latin`). Without this check, that
 *    event could overwrite the tracked foreground package with the keyboard's right as
 *    [ControlBarCoordinator.beginStep] captures a session's return target, recording "Back to
 *    Android Keyboard" instead of the app actually being automated - and since an IME package has
 *    no launcher activity, tapping it later is a silent no-op
 *    ([McpAccessibilityService.backToForegroundApp]'s `getLaunchIntentForPackage(pkg) ?: return`).
 *    The same reasoning excludes every other non-[AccessibilityWindowInfo.TYPE_APPLICATION]
 *    window (status bar, quick settings, recents, system dialogs) by window *type* rather than by
 *    package name, so it holds across OEM System UI package names rather than special-casing
 *    `com.android.systemui`.
 *  - A null (unresolvable) [windowType] counts as foreign (pre-A3 behavior). The original A3 rule
 *    (null is NOT foreign) collided with A5's `launch_app` wait, which waits for
 *    [McpAccessibilityService.getCurrentPackageName] to equal the launched package: the window
 *    lookup most often fails exactly when a newly launched app's first window-state event fires
 *    (its window may not be in [McpAccessibilityService.getAccessibilityWindows] yet), so the
 *    stricter rule could make `launch_app` wait the full timeout and report failure for an app
 *    that did come to the front. The Gboard regression this check originally guarded is instead
 *    covered at the recording site by [isBackToTargetPackageForbidden], which excludes our own
 *    package, System UI and every enabled input method by package name - the "Back to Gboard"
 *    event's package WAS Gboard.
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun isForeignWindowStateChange(
    eventPackageName: String?,
    selfPackageName: String,
    windowType: Int? = null,
): Boolean =
    eventPackageName != selfPackageName &&
        (windowType == null || windowType == AccessibilityWindowInfo.TYPE_APPLICATION)

/**
 * Returns true if [packageName] must NEVER be recorded as the tracked foreground package and
 * therefore never surface as the control bar's "Back to <app>" return target, even when the
 * window type check in [isForeignWindowStateChange] passes.
 *
 * Belt-and-braces package-level exclusion alongside the window-type check above (16 Oct phone
 * test: the bar said "Back to Gboard" after typing in the Claude app, because a window whose
 * type could not be resolved passed the old null-tolerant check). Excludes DroidThumb's own
 * package (the overlay windows), `com.android.systemui` (status bar/recents/system dialogs),
 * and every enabled input method's package - an IME has no launcher activity, so tapping it as
 * a return target is a silent no-op. An IME can also legitimately present a
 * TYPE_APPLICATION-mode window on some OEM keyboards, which the type check alone would miss.
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun isBackToTargetPackageForbidden(
    packageName: String,
    ownPackage: String,
    imePackages: List<String>,
): Boolean =
    packageName == ownPackage ||
        packageName == SYSTEM_UI_PACKAGE ||
        packageName in imePackages

/** Stock Android's System UI package, excluded by name because it never is "the foreground app". */
internal const val SYSTEM_UI_PACKAGE = "com.android.systemui"

/**
 * Returns true if the floating control bar/glow overlay windows should be present for [state] -
 * i.e. [state] is anything other than [ControlBarState.Hidden]. Drives the add/remove decision in
 * [McpAccessibilityService.setupControlBarOverlay]'s state collector (plan 71, round 2): the
 * windows are only attached while there is something to show, torn down and recreated fresh on
 * every Hidden<->non-Hidden transition, rather than left permanently attached and relying on
 * Compose recomposition alone to represent "nothing" (round 2's own fix for the Done bar's stale
 * rendered frame getting stuck on screen forever - see that function's doc comment).
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun shouldShowOverlayWindows(state: ControlBarState): Boolean = state !is ControlBarState.Hidden
