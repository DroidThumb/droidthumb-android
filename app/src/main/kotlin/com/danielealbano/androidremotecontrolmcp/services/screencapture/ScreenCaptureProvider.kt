package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.graphics.Bitmap
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData

/**
 * Abstracts access to screenshot capture functionality.
 *
 * Production implementation uses AccessibilityService.takeScreenshot() (Android 11+).
 * Tools use this interface to enable JVM-based testing with mock implementations.
 */
interface ScreenCaptureProvider {
    companion object {
        const val DEFAULT_QUALITY = 80
    }

    suspend fun captureScreenshot(
        quality: Int = DEFAULT_QUALITY,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): Result<ScreenshotData>

    /**
     * Captures a screenshot and returns the resized [Bitmap] without JPEG encoding.
     *
     * The caller is responsible for calling [Bitmap.recycle] on the returned bitmap
     * when it is no longer needed.
     *
     * @param maxWidth Maximum width in pixels, or null.
     * @param maxHeight Maximum height in pixels, or null.
     * @param windowId The focused window's id, when known (plan 71, D-39) — used to exclude
     *   DroidThumb's own floating control bar/glow overlay from the capture: a per-window capture
     *   on API levels that support it, or the existing overlay hidden-and-restored around a
     *   whole-display capture otherwise. `null` (e.g. degraded multi-window mode) always falls
     *   back to whole-display capture.
     * @return A [Result] containing the resized [Bitmap].
     */
    suspend fun captureScreenshotBitmap(
        maxWidth: Int? = null,
        maxHeight: Int? = null,
        windowId: Int? = null,
    ): Result<Bitmap>

    fun isScreenCaptureAvailable(): Boolean
}
