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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
import kotlinx.coroutines.suspendCancellableCoroutine
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

    private var barView: ComposeView? = null
    private var glowView: ComposeView? = null
    private var barLifecycleOwner: OverlayLifecycleOwner? = null
    private var glowLifecycleOwner: OverlayLifecycleOwner? = null
    private var barLayoutParams: WindowManager.LayoutParams? = null

    /** Hides (or restores) DroidThumb's own floating control bar/glow overlay around a
     *  whole-display screenshot capture on API levels below the per-window capture API (plan 71,
     *  D-39) — a whole-display capture would otherwise include them. A no-op when no overlay views
     *  exist yet (nothing to hide). */
    fun setOverlayHidden(hidden: Boolean) {
        overlayViews?.forEach { it.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE }
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

    /** Adds the glow (behind, full-screen, always pass-through) and bar (in front, draggable)
     *  overlay windows via [WindowManager]/`TYPE_ACCESSIBILITY_OVERLAY` — no "display over other
     *  apps" permission needed, since this is restricted to accessibility services (plan 71,
     *  D-39). Both views' content reactively follows [ControlBarCoordinator.state] via plain
     *  Compose `collectAsState()` - no manual per-emission `setContent` calls needed. */
    private fun setupControlBarOverlay(controlBarCoordinator: ControlBarCoordinator) {
        val windowManager = getSystemService(WindowManager::class.java) ?: return

        val glowOwner = OverlayLifecycleOwner().also { it.onAttach() }
        val glow = createGlowView(glowOwner, controlBarCoordinator)
        windowManager.addView(glow, glowLayoutParams())

        val barOwner = OverlayLifecycleOwner().also { it.onAttach() }
        val bar = createBarView(barOwner, controlBarCoordinator, windowManager)
        val barParams = barLayoutParams()
        windowManager.addView(bar, barParams)

        glowView = glow
        barView = bar
        glowLifecycleOwner = glowOwner
        barLifecycleOwner = barOwner
        this.barLayoutParams = barParams
        overlayViews = listOf(glow, bar)
        controlBarCoordinator.registerBarTouchToggle(::setBarTouchable)
    }

    private fun createGlowView(
        owner: OverlayLifecycleOwner,
        controlBarCoordinator: ControlBarCoordinator,
    ): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                when (controlBarCoordinator.state.collectAsState().value) {
                    is ControlBarState.Running -> EdgeGlow(dim = false)
                    is ControlBarState.Paused -> EdgeGlow(dim = true)
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
        windowManager: WindowManager,
    ): ComposeView =
        ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state by controlBarCoordinator.state.collectAsState()
                ControlBarOverlay(
                    state = state,
                    callbacks = barCallbacks(controlBarCoordinator, state, windowManager),
                )
            }
        }

    private fun barCallbacks(
        controlBarCoordinator: ControlBarCoordinator,
        state: ControlBarState,
        windowManager: WindowManager,
    ) = ControlBarCallbacks(
        onCollapseToggle = { collapsed -> controlBarCoordinator.collapseToggle(collapsed) },
        onStop = controlBarCoordinator::stop,
        onPauseOrResume = {
            if (state is ControlBarState.Paused) controlBarCoordinator.resume() else controlBarCoordinator.pause()
        },
        onBackToApp = {
            backToForegroundApp(state)
            // Ended has nothing left to resume - dismiss the bar once "Back to" is used (plan
            // 71's own decision); Paused keeps it up, since Resume is still meaningful there.
            if (state is ControlBarState.Ended) controlBarCoordinator.close()
        },
        onClose = controlBarCoordinator::close,
        onDrag = { dx, dy -> moveBarBy(windowManager, dx, dy) },
    )

    private fun barLayoutParams() =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                y = BAR_INITIAL_BOTTOM_MARGIN_PX
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
            startActivity(launchIntent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Could not return to $pkg: ${e.message}")
        }
    }

    private fun moveBarBy(
        windowManager: WindowManager,
        dxPx: Float,
        dyPx: Float,
    ) {
        val view = barView ?: return
        val params = barLayoutParams ?: return
        val screen = getScreenInfo()
        // gravity is BOTTOM|CENTER_HORIZONTAL, so x is an offset from horizontal center - the
        // view's own half-width must be subtracted from the half-screen bound on both sides, or
        // dragging to either extreme pushes roughly half the bar off-screen (found in review).
        val maxX = ((screen.width - view.width) / 2).coerceAtLeast(0)
        params.x = (params.x + dxPx.toInt()).coerceIn(-maxX, maxX)
        params.y = (params.y - dyPx.toInt()).coerceIn(0, screen.height - view.height)
        windowManager.updateViewLayout(view, params)
    }

    private fun teardownControlBarOverlay() {
        val windowManager = getSystemService(WindowManager::class.java)
        barView?.let { windowManager?.removeView(it) }
        glowView?.let { windowManager?.removeView(it) }
        barLifecycleOwner?.onDetach()
        glowLifecycleOwner?.onDetach()
        barView = null
        glowView = null
        barLifecycleOwner = null
        glowLifecycleOwner = null
        barLayoutParams = null
        overlayViews = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope
        nodeCache = resolveNodeCache()
        resolveControlBarCoordinator()?.let { setupControlBarOverlay(it) }
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
                event.packageName?.toString()?.let { packageName ->
                    currentPackageName = packageName
                }
                event.className?.toString()?.let { className ->
                    currentActivityName = className
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

        /** Initial resting position of the floating control bar (plan 71, D-39): bottom-center,
         *  with this much clearance above the screen's bottom edge/gesture-nav area. */
        private const val BAR_INITIAL_BOTTOM_MARGIN_PX = 140

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
