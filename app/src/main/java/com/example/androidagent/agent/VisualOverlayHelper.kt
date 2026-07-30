package com.example.androidagent.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.example.androidagent.agent.model.AccessibilityNode
import java.io.ByteArrayOutputStream

object VisualOverlayHelper {

    /**
     * Draws Set-of-Marks (numbered bounding boxes) on the screen bitmap.
     * Highlights each parsed interactive element with its flat index label.
     */
    fun drawSetOfMarks(bitmap: Bitmap, nodes: List<AccessibilityNode>): Bitmap {
        val canvas = Canvas(bitmap)

        // Paint configuration for element bounds border
        val borderPaint = Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        // Paint configuration for background color of the number labels
        val badgePaint = Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Paint configuration for text inside number labels
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        for (node in nodes) {
            val bounds = node.bounds
            val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)

            // 1. Draw boundary overlay around elements
            canvas.drawRect(rect, borderPaint)

            // 2. Draw label index tag overlay at top-left corner
            val label = "[${node.index}]"
            val textBounds = Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val badgeWidth = textBounds.width() + 8
            val badgeHeight = textBounds.height() + 8

            val badgeRect = Rect(
                rect.left,
                rect.top,
                rect.left + badgeWidth,
                rect.top + badgeHeight
            )
            canvas.drawRect(badgeRect, badgePaint)

            // Render text tag centered
            canvas.drawText(
                label,
                rect.left.toFloat() + 4f,
                rect.top.toFloat() + badgeHeight - 4f,
                textPaint
            )
        }

        return bitmap
    }

    /**
     * Masks (draws a solid color over) sensitive regions of the bitmap.
     */
    fun maskSensitiveRegions(bitmap: Bitmap, nodes: List<AccessibilityNode>): Bitmap {
        val canvas = Canvas(bitmap)
        val maskPaint = Paint().apply {
            color = Color.BLACK // Solid black cover for complete mathematical privacy
            style = Paint.Style.FILL
        }

        val textPaint = Paint().apply {
            color = Color.RED
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        for (node in nodes) {
            if (node.isSensitive) {
                val bounds = node.bounds
                val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                // Draw a solid black box over the sensitive bounds
                canvas.drawRect(rect, maskPaint)
                // Overlay text tag centered vertically
                val textHeight = bounds.bottom - bounds.top
                canvas.drawText(
                    "[MASKED]",
                    bounds.left.toFloat() + 8f,
                    bounds.top.toFloat() + (textHeight / 2f) + 6f,
                    textPaint
                )
            }
        }
        return bitmap
    }

    /**
     * Compresses the Bitmap to JPEG format and converts it to a clean base64 string.
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
    }
}
