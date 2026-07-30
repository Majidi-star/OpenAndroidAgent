@file:Suppress("DEPRECATION")
package com.example.androidagent.agent


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.androidagent.agent.model.AccessibilityNode
import com.example.androidagent.agent.model.NodeBounds

data class ParseResult(
    val serializedNodes: List<AccessibilityNode>,
    val rawNodeInfos: List<AccessibilityNodeInfo>
)

object TreeParser {

    /**
     * Traverses the active window's accessibility tree and returns:
     * 1. A list of simplified serialized nodes ready to be sent as JSON to the LLM.
     * 2. A list of matching raw AccessibilityNodeInfo objects, where list index matches node index.
     *
     * IMPORTANT: Call recycleNodeInfos on the rawNodeInfos list after the turn is complete
     * to avoid leaking accessibility framework resources.
     */
    fun parseTree(root: AccessibilityNodeInfo?): ParseResult {
        val serializedNodes = mutableListOf<AccessibilityNode>()
        val rawNodeInfos = mutableListOf<AccessibilityNodeInfo>()

        if (root == null) {
            return ParseResult(serializedNodes, rawNodeInfos)
        }

        var currentIndex = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            // 1. Check if the element is visible to the user.
            val isVisible = node.isVisibleToUser
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // 2. Filter out offscreen, zero-size, or invisible elements.
            if (!isVisible || bounds.isEmpty) {
                // Even if parent is not visible, traverse children because some containers 
                // report invisible bounds while their children are visible.
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    traverse(child)
                }
                return
            }

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            // viewIdResourceName gets the XML resource ID (e.g., "com.android.settings:id/search_action_bar")
            val resourceId = node.viewIdResourceName

            // 3. Determine if the node is "interesting" or interactive.
            // This is key to keeping the JSON payload size minimal for the LLM call.
            val isInteresting = !text.isNullOrEmpty() || 
                                !desc.isNullOrEmpty() || 
                                isClickable || 
                                isEditable || 
                                isScrollable

            if (isInteresting) {
                val nodeBounds = NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
                val className = node.className?.toString() ?: "unknown"

                val isPassword = node.isPassword
                serializedNodes.add(
                    AccessibilityNode(
                        index = currentIndex,
                        className = className,
                        resourceId = resourceId,
                        text = text,
                        contentDescription = desc,
                        clickable = isClickable,
                        editable = isEditable,
                        scrollable = isScrollable,
                        bounds = nodeBounds,
                        isPassword = isPassword
                    )
                )

                // AccessibilityNodeInfo objects are recycled by the framework.
                // obtain(node) returns a clone/copy that we can store in memory for action execution.
                rawNodeInfos.add(AccessibilityNodeInfo.obtain(node))
                currentIndex++
            }

            // Traverse children recursively.
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                traverse(child)
            }
        }

        traverse(root)
        return ParseResult(serializedNodes, rawNodeInfos)
    }

    /**
     * Safety clean-up. Releases system-allocated clones of AccessibilityNodeInfo.
     */
    fun recycleNodeInfos(infos: List<AccessibilityNodeInfo>) {
        for (info in infos) {
            try {
                info.recycle()
            } catch (e: Exception) {
                // Suppress errors during recycle (e.g., if already recycled)
            }
        }
    }
}
