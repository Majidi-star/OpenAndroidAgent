@file:Suppress("DEPRECATION")
package com.example.androidagent.agent


import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.androidagent.agent.model.AccessibilityNode
import com.example.androidagent.llm.LlmClient
import com.example.androidagent.llm.ApiFormat
import com.example.androidagent.llm.LlmActionResponse
import com.example.androidagent.service.AgentAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentController(
    private val apiFormat: ApiFormat,
    private val apiKey: String,
    private val baseUrl: String,
    private val modelName: String,
    private val rpm: Int,
    private val useVision: Boolean,
    private val useFallback: Boolean,
    private val fallbackFormat: ApiFormat,
    private val fallbackKey: String,
    private val fallbackBaseUrl: String,
    private val fallbackModelName: String,
    private val usePrivacyMasking: Boolean,
    private val initialTips: List<String>,
    private val onTipLearned: (String) -> Unit,
    private val goal: String,
    private val onLog: (String) -> Unit,
    private val onStateChange: (State) -> Unit
) {

    enum class State {
        IDLE, RUNNING, PAUSED_FOR_SAFETY, COMPLETED, FAILED, ERROR
    }

    private var job: kotlinx.coroutines.Job? = null
    private val activeTips = initialTips.toMutableList()
    private val llmClient = LlmClient(
        apiFormat = apiFormat,
        apiKey = apiKey,
        baseUrl = baseUrl,
        modelName = modelName,
        rpm = rpm,
        useFallback = useFallback,
        fallbackFormat = fallbackFormat,
        fallbackKey = fallbackKey,
        fallbackBaseUrl = fallbackBaseUrl,
        fallbackModelName = fallbackModelName,
        usePrivacyMasking = usePrivacyMasking
    )

    @Volatile
    private var safetyDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    
    private var lastScreenHash: String? = null
    private var lastActionType: String? = null
    private var lastTargetIndex: Int? = null
    private var lastInputText: String? = null
    private var consecutiveRepeatCount = 0
    private var consecutiveWaitSkips = 0

    fun approveSafetyGate() {
        safetyDeferred?.complete(true)
    }

    fun rejectSafetyGate() {
        safetyDeferred?.complete(false)
    }

    /**
     * Start the autonomous run loop.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        
        onStateChange(State.RUNNING)
        onLog("Starting agent loop. Goal: \"$goal\"")

        job = scope.launch(Dispatchers.Main) {
            runLoop()
        }
    }

    /**
     * Terminate the loop.
     */
    fun stop() {
        if (job?.isActive == true) {
            job?.cancel()
            onLog("Agent execution stopped by user.")
            onStateChange(State.IDLE)
        }
    }

    private suspend fun runLoop() {
        var step = 1
        val maxSteps = 20 // Guardrail budget limit per run

        val service = AgentAccessibilityService.getInstance()
        if (service == null) {
            onLog("[Error] Accessibility Service is not running. Please enable it in Android Settings.")
            onStateChange(State.ERROR)
            return
        }

        onLog("Perceiving initial screen to generate plan...")
        val planRoot = service.getRootNode()
        var initialNodes = emptyList<AccessibilityNode>()
        if (planRoot != null) {
            val parse = TreeParser.parseTree(planRoot)
            initialNodes = parse.serializedNodes
            planRoot.recycle()
            TreeParser.recycleNodeInfos(parse.rawNodeInfos)
        }

        onLog("Thinking (generating step-by-step plan)...")
        var currentPlan = try {
            llmClient.generatePlan(goal, initialNodes, onLog).planString
        } catch (e: Exception) {
            onLog("[Warning] Plan generation failed: ${e.localizedMessage}. Running with default ad-hoc execution.")
            "Ad-hoc execution matching goal: $goal"
        }
        onLog("\n📋 GENERATED INITIAL PLAN:\n$currentPlan\n")

        while (step <= maxSteps) {
            onLog("\n--- Step $step ---")

            // 2. Perceive: Read root window and parse tree
            onLog("Perceiving screen...")
            val root = service.getRootNode()
            if (root == null) {
                onLog("Root window node is null. Waiting for screen to load...")
                delay(2000)
                continue
            }

            val parseResult = TreeParser.parseTree(root)
            var nodes = parseResult.serializedNodes
            var rawInfos = parseResult.rawNodeInfos

            // Safely release the root node reference as required by Android framework
            root.recycle()

            var currentScreenHash = computeScreenHash(nodes)

            // Local Screen Diffing / Retry Cycle
            if (lastScreenHash != null && currentScreenHash == lastScreenHash) {
                var matches = true
                var retries = 0
                while (matches && retries < 3) {
                    onLog("Screen hash unchanged. Sleeping 500ms and re-parsing (retry ${retries + 1}/3)...")
                    delay(500)
                    val retryRoot = service.getRootNode()
                    if (retryRoot != null) {
                        TreeParser.recycleNodeInfos(rawInfos) // Recycle previous elements
                        val retryParse = TreeParser.parseTree(retryRoot)
                        nodes = retryParse.serializedNodes
                        rawInfos = retryParse.rawNodeInfos
                        retryRoot.recycle()
                        currentScreenHash = computeScreenHash(nodes)
                        if (currentScreenHash != lastScreenHash) {
                            matches = false
                            onLog("Screen updated successfully after retry wait.")
                        }
                    }
                    retries++
                }
            }

            // Cost Control: Bypassing LLM if screen still unchanged and last action was WAIT
            if (lastScreenHash != null && currentScreenHash == lastScreenHash && lastActionType == "WAIT") {
                if (consecutiveWaitSkips < 3) {
                    consecutiveWaitSkips++
                    onLog("[Cost Control] Bypassing LLM call. Repeating WAIT action locally (${consecutiveWaitSkips}/3 skips).")
                    TreeParser.recycleNodeInfos(rawInfos)
                    delay(2000)
                    step++
                    continue
                } else {
                    onLog("[Cost Control] Maximum consecutive WAIT skips reached. Querying LLM.")
                    consecutiveWaitSkips = 0
                }
            } else {
                consecutiveWaitSkips = 0
            }

            if (nodes.isEmpty()) {
                onLog("No visible interactive elements detected. Waiting...")
                TreeParser.recycleNodeInfos(rawInfos)
                delay(2000)
                continue
            }

            onLog("Parsed ${nodes.size} elements on current screen.")

            // 3. Decide: Call LLM
            var screenshotBase64: String? = null
            if (useVision) {
                onLog("Capturing screen for visual analysis...")
                val bitmap = service.takeScreenshotAsync()
                if (bitmap != null) {
                    val workingBitmap = if (usePrivacyMasking) {
                        val sensitiveCount = nodes.count { it.isSensitive }
                        if (sensitiveCount > 0) {
                            onLog("[Privacy] Masked $sensitiveCount sensitive fields on screenshot.")
                        }
                        VisualOverlayHelper.maskSensitiveRegions(bitmap, nodes)
                    } else {
                        bitmap
                    }
                    onLog("Overlaying Set-of-Marks layout...")
                    val overlaidBitmap = VisualOverlayHelper.drawSetOfMarks(workingBitmap, nodes)
                    screenshotBase64 = VisualOverlayHelper.bitmapToBase64(overlaidBitmap)
                    overlaidBitmap.recycle() // Recycle to avoid memory leaks
                } else {
                    onLog("[Warning] Failed to capture screen. Falling back to text-only mode.")
                }
            }

            onLog("Thinking (sending request to LLM)...")
            val startTime = System.currentTimeMillis()
            val tipsText = activeTips.joinToString(separator = "\n") { "- $it" }
            val llmResponse = try {
                llmClient.getNextAction(goal, nodes, screenshotBase64, currentPlan, tipsText, onLog)
            } catch (e: CancellationException) {
                TreeParser.recycleNodeInfos(rawInfos)
                throw e
            } catch (e: Exception) {
                onLog("[Error] LLM request failed: ${e.localizedMessage}")
                onStateChange(State.ERROR)
                TreeParser.recycleNodeInfos(rawInfos)
                return
            }
            val durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0
            onLog("LLM response received in ${durationSeconds}s")

            onLog("Reasoning: ${llmResponse.reasoning}")
            onLog("Decision: Action=${llmResponse.action}, TargetIndex=${llmResponse.index}, InputText=${llmResponse.text}")

            // Stuck Loop Detection Check
            currentScreenHash = computeScreenHash(nodes)
            if (currentScreenHash == lastScreenHash &&
                llmResponse.action == lastActionType &&
                llmResponse.index == lastTargetIndex &&
                llmResponse.text == lastInputText
            ) {
                consecutiveRepeatCount++
                if (consecutiveRepeatCount >= 2) {
                    onLog("FAILURE: [Loop Detection] Stuck loop detected: action ${llmResponse.action} on index ${llmResponse.index} repeated 3 times on the same screen state. Aborting execution.")
                    onStateChange(State.FAILED)
                    TreeParser.recycleNodeInfos(rawInfos)
                    return
                }
            } else {
                consecutiveRepeatCount = 0
                lastScreenHash = currentScreenHash
                lastActionType = llmResponse.action
                lastTargetIndex = llmResponse.index
                lastInputText = llmResponse.text
            }

            // Safety Gate Check
            val isHighStakes = checkIsHighStakes(llmResponse, nodes)
            if (isHighStakes) {
                onLog("[Safety Gate] HIGH-STAKES ACTION DETECTED! Action: ${llmResponse.action}, Target Index: ${llmResponse.index}, Text: ${llmResponse.text ?: ""}. Pausing for user confirmation...")
                onStateChange(State.PAUSED_FOR_SAFETY)

                val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                safetyDeferred = deferred
                val approved = deferred.await()
                safetyDeferred = null

                if (!approved) {
                    onLog("[Safety Gate] High-stakes action REJECTED by user. Halting loop.")
                    onStateChange(State.FAILED)
                    TreeParser.recycleNodeInfos(rawInfos)
                    return
                }

                onLog("[Safety Gate] High-stakes action APPROVED by user. Executing...")
                onStateChange(State.RUNNING)
            }

            // Handle terminal states
            val finalAction = llmResponse.action ?: "INVALID"
            when (finalAction) {
                "COMPLETED" -> {
                    onLog("SUCCESS: Agent completed the goal.")
                    onStateChange(State.COMPLETED)
                    TreeParser.recycleNodeInfos(rawInfos)
                    return
                }
                "FAILED" -> {
                    onLog("FAILURE: Agent declared the goal unreachable.")
                    onStateChange(State.FAILED)
                    TreeParser.recycleNodeInfos(rawInfos)
                    return
                }
            }

            // 4. Act: Execute the decision
            val beforeNodes = nodes
            try {
                executeAction(service, llmResponse, nodes, rawInfos)
            } catch (e: Exception) {
                onLog("[Error] Execution failed: ${e.localizedMessage}")
            }

            // 5. Cooldown wait (event-driven or timeout fallback)
            onLog("Waiting for layout change or transition...")
            val changed = service.awaitScreenChange(2500)
            if (changed) {
                onLog("Accessibility event triggered. Transitioning immediately.")
            } else {
                onLog("Transition timeout reached. Moving to next step.")
            }

            // Now capture screen state AFTER action to perform Reflection
            val postRoot = service.getRootNode()
            var afterNodes = emptyList<AccessibilityNode>()
            if (postRoot != null) {
                val parse = TreeParser.parseTree(postRoot)
                afterNodes = parse.serializedNodes
                postRoot.recycle()
                TreeParser.recycleNodeInfos(parse.rawNodeInfos)
            }

            val actionTypeStr = llmResponse.action ?: "INVALID"
            if (actionTypeStr != "WAIT" && actionTypeStr != "COMPLETED" && actionTypeStr != "FAILED" && actionTypeStr != "INVALID") {
                onLog("Reflecting on action success...")
                try {
                    val reflection = llmClient.reflectOnAction(
                        goal = goal,
                        actionDescription = "Action=${llmResponse.action}, TargetIndex=${llmResponse.index}, InputText=${llmResponse.text}",
                        beforeNodes = beforeNodes,
                        afterNodes = afterNodes,
                        onLog = onLog
                    )
                    onLog("[Reflection] Result: Success=${reflection.success}. Reasoning: ${reflection.reasoning}")
                    if (!reflection.success) {
                        onLog("[Reflection] Action execution marked as FAILED.")
                        reflection.suggestedTip?.let { newTip ->
                            if (newTip.isNotBlank() && newTip !in activeTips) {
                                onLog("💡 LEARNED NEW TIP: $newTip")
                                activeTips.add(newTip)
                                onTipLearned(newTip)
                            }
                        }

                        // Re-plan
                        onLog("Re-evaluating plan based on failure...")
                        try {
                            currentPlan = llmClient.generatePlan(
                                goal = "Adjust plan based on failure of: ${llmResponse.action} at index ${llmResponse.index}. Global goal is: $goal",
                                nodes = afterNodes,
                                onLog = onLog
                            ).planString
                            onLog("Revised Plan: $currentPlan")
                        } catch (pe: Exception) {
                            onLog("[Warning] Failed to revise plan: ${pe.localizedMessage}")
                        }
                    }
                } catch (re: Exception) {
                    onLog("[Warning] Reflection check failed: ${re.localizedMessage}")
                }
            }

            // Clean raw cloned accessibility nodes
            TreeParser.recycleNodeInfos(rawInfos)
            step++
        }

        onLog("Budget Limit Reached: Stopped agent after $maxSteps steps.")
        onStateChange(State.FAILED)
    }

    private suspend fun executeAction(
        service: AgentAccessibilityService,
        response: LlmActionResponse,
        nodes: List<AccessibilityNode>,
        rawInfos: List<AccessibilityNodeInfo>
    ) {
        val actionType = response.action ?: "INVALID"
        val targetIndex = response.index

        when (actionType) {
            "CLICK" -> {
                if (targetIndex == null || targetIndex !in nodes.indices) {
                    onLog("Warning: Invalid node index $targetIndex for CLICK.")
                    return
                }
                val serializedNode = nodes[targetIndex]
                val rawNode = rawInfos[targetIndex]
                onLog("Clicking index $targetIndex (${serializedNode.className}) text: \"${serializedNode.text ?: ""}\"")

                // Try resolution-independent accessibility action click first
                var success = service.performNodeAction(rawNode, AccessibilityNodeInfo.ACTION_CLICK)
                
                // If it fails, fall back to coordinate-based gesture click
                if (!success) {
                    onLog("Direct performAction(CLICK) returned false. Falling back to coordinate gesture tap.")
                    val cx = serializedNode.bounds.centerX
                    val cy = serializedNode.bounds.centerY
                    success = service.clickAt(cx, cy)
                    onLog("Gesture tap at ($cx, $cy) success result: $success")
                } else {
                    onLog("Direct performAction(CLICK) succeeded.")
                }
            }
            "INPUT_TEXT" -> {
                if (targetIndex == null || targetIndex !in nodes.indices) {
                    onLog("Warning: Invalid node index $targetIndex for INPUT_TEXT.")
                    return
                }
                val serializedNode = nodes[targetIndex]
                val rawNode = rawInfos[targetIndex]
                val textToInput = response.text ?: ""
                onLog("Inputting \"$textToInput\" to index $targetIndex")

                // Make sure the view is focused before typing
                rawNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // Try direct set text action
                var success = service.performNodeAction(rawNode, AccessibilityNodeInfo.ACTION_SET_TEXT, textToInput)
                
                if (!success) {
                    onLog("Direct performAction(SET_TEXT) failed. Falling back to gesture-clicking to focus.")
                    // Click first to focus, then wait, then try setting text
                    service.clickAt(serializedNode.bounds.centerX, serializedNode.bounds.centerY)
                    delay(500)
                    success = service.performNodeAction(rawNode, AccessibilityNodeInfo.ACTION_SET_TEXT, textToInput)
                    onLog("Post-click performAction(SET_TEXT) success result: $success")
                } else {
                    onLog("Direct performAction(SET_TEXT) succeeded.")
                }
            }
            "SCROLL_FORWARD" -> {
                // If index is valid scrollable container, use it. Otherwise do a global swipe.
                if (targetIndex != null && targetIndex in nodes.indices && nodes[targetIndex].scrollable) {
                    val rawNode = rawInfos[targetIndex]
                    onLog("Scrolling forward index $targetIndex")
                    val success = service.performNodeAction(rawNode, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    onLog("Direct scroll forward success: $success")
                } else {
                    onLog("Performing global swipe scroll forward (down).")
                    // Default drag upward to scroll downward
                    val success = service.swipe(500f, 1600f, 500f, 600f, duration = 400)
                    onLog("Global swipe scroll forward success: $success")
                }
            }
            "SCROLL_BACKWARD" -> {
                if (targetIndex != null && targetIndex in nodes.indices && nodes[targetIndex].scrollable) {
                    val rawNode = rawInfos[targetIndex]
                    onLog("Scrolling backward index $targetIndex")
                    val success = service.performNodeAction(rawNode, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    onLog("Direct scroll backward success: $success")
                } else {
                    onLog("Performing global swipe scroll backward (up).")
                    // Default drag downward to scroll upward
                    val success = service.swipe(500f, 600f, 500f, 1600f, duration = 400)
                    onLog("Global swipe scroll backward success: $success")
                }
            }
            "PRESS_BACK" -> {
                onLog("Executing Back action.")
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                onLog("Global Back success: $success")
            }
            "PRESS_HOME" -> {
                onLog("Executing Home action.")
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                onLog("Global Home success: $success")
            }
            "WAIT" -> {
                onLog("Waiting 2 seconds as requested by LLM.")
                delay(2000)
            }
            else -> {
                onLog("Unknown action: $actionType")
            }
        }
    }

    private fun computeScreenHash(nodes: List<AccessibilityNode>): String {
        val inputString = nodes.joinToString(separator = "|") {
            "${it.index}:${it.className}:${it.resourceId ?: ""}:${it.text ?: ""}:${it.contentDescription ?: ""}"
        }
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(inputString.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            inputString.hashCode().toString()
        }
    }

    private fun checkIsHighStakes(response: LlmActionResponse, nodes: List<AccessibilityNode>): Boolean {
        if (response.is_irreversible_or_high_stakes == true) return true

        val index = response.index
        if (index != null && index in nodes.indices) {
            val node = nodes[index]
            val keywords = listOf("delete", "remove", "pay", "transfer", "confirm purchase", "uninstall", "factory reset")
            val text = (node.text ?: "").lowercase()
            val desc = (node.contentDescription ?: "").lowercase()
            for (keyword in keywords) {
                if (text.contains(keyword) || desc.contains(keyword)) {
                    return true
                }
            }
        }
        return false
    }
}
