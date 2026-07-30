package com.example.androidagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AgentAccessibilityService connects to Android's Accessibility framework.
 * This service runs in the background and has system-level access to inspect the UI tree
 * and perform programmatic taps, scrolls, and text input on behalf of the user.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        // Volatile singleton reference so the MainActivity and AgentController can interact
        // with the running service instance.
        @Volatile
        private var instance: AgentAccessibilityService? = null

        fun getInstance(): AgentAccessibilityService? = instance
        fun isServiceRunning(): Boolean = instance != null
    }

    /**
     * Called by Android when the service is successfully bound and connected.
     * This is where we capture the service instance.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /**
     * Called when the service is stopped or disabled by the user in Settings.
     */
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    @Volatile
    private var screenChangeDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun triggerScreenChangeEvent() {
        screenChangeDeferred?.complete(Unit)
    }

    /**
     * Receives event notifications about UI changes (e.g. windows opening, scrolling).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            triggerScreenChangeEvent()
        }
    }

    override fun onInterrupt() {
        // Required method. Called when the system wants to interrupt accessibility feedback.
    }

    /**
     * Suspends execution until the next UI transition event is fired, or times out.
     */
    suspend fun awaitScreenChange(timeoutMs: Long): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
        screenChangeDeferred = deferred
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                deferred.await()
                true
            } ?: false
        } finally {
            if (screenChangeDeferred === deferred) {
                screenChangeDeferred = null
            }
        }
    }

    /**
     * Retrieves the root element of the currently active window.
     * AccessibilityNodeInfo is a representation of a UI element on screen.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        // rootInActiveWindow retrieves the window root. It must be recycled/managed properly.
        return rootInActiveWindow
    }

    /**
     * Performs a programmatic action on a specific node.
     * This is preferred over coordinate clicks because it's resolution-independent.
     */
    fun performNodeAction(node: AccessibilityNodeInfo, action: Int, textValue: String? = null): Boolean {
        return if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && textValue != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textValue)
            }
            node.performAction(action, arguments)
        } else {
            node.performAction(action)
        }
    }

    /**
     * Simulates a tap gesture at a specific (x, y) coordinate.
     * Wraps the async callback-based dispatchGesture API in a coroutine suspend function
     * using suspendCancellableCoroutine so we can write clean linear code in our AgentController.
     */
    suspend fun clickAt(x: Float, y: Float): Boolean = suspendCancellableCoroutine { continuation ->
        // 1. Define the touch path (a single point tap is a path that starts and ends at the same point)
        val path = Path().apply {
            moveTo(x, y)
        }

        // 2. StrokeDescription: Path, start delay (0ms), duration (80ms for a quick tap)
        val stroke = StrokeDescription(path, 0, 80)

        // 3. Build the gesture description
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        // 4. Dispatch the gesture on Android's UI thread and resume the coroutine when complete
        val success = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (continuation.isActive) continuation.resume(false)
            }
        }, null)

        // If dispatchGesture returns false immediately, the system rejected the gesture (e.g. screen off)
        if (!success) {
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Simulates a swipe gesture from start coordinates to end coordinates.
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 400): Boolean = 
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val stroke = StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val success = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)

            if (!success) {
                if (continuation.isActive) continuation.resume(false)
            }
        }

    /**
     * Captures a screenshot from the accessibility service on Android 11+.
     * Converts the HardwareBuffer into a software-backed mutable Bitmap.
     */
    suspend fun takeScreenshotAsync(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        val colorSpace = result.colorSpace
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close() // Close native buffer resource to avoid memory leak

                        if (hardwareBitmap != null) {
                            // Copy the read-only hardware bitmap into a software-backed mutable bitmap so we can draw on it using Canvas
                            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            hardwareBitmap.recycle()
                            continuation.resume(softwareBitmap)
                        } else {
                            continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            continuation.resume(null)
        }
    }
}
