package com.example.androidagent.llm

import com.example.androidagent.agent.model.AccessibilityNode
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class ApiFormat {
    GEMINI_NATIVE,
    OPENAI_COMPATIBLE
}

// ==========================================
// Gemini Native API Models
// ==========================================
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

data class GeminiGenerationConfig(
    val responseMimeType: String,
    val responseSchema: GeminiResponseSchema? = null
)

data class GeminiResponseSchema(
    val type: String,
    val properties: Map<String, GeminiSchemaProperty>,
    val required: List<String>
)

data class GeminiSchemaProperty(
    val type: String,
    val enum: List<String>? = null
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    val content: GeminiResponseContent?
)

data class GeminiResponseContent(
    val parts: List<GeminiResponsePart>?
)

data class GeminiResponsePart(
    val text: String?
)

// ==========================================
// OpenAI Compatible API Models
// ==========================================
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiRequestMessage>,
    val response_format: OpenAiResponseFormat? = null
)

data class OpenAiRequestMessage(
    val role: String,
    val content: Any // Can be String or List<OpenAiContentPart>
)

data class OpenAiContentPart(
    val type: String, // "text" or "image_url"
    val text: String? = null,
    val image_url: OpenAiImageUrl? = null
)

data class OpenAiImageUrl(
    val url: String
)

data class OpenAiResponseFormat(
    val type: String // e.g. "json_object"
)

data class OpenAiResponse(
    val choices: List<OpenAiChoice>?
)

data class OpenAiChoice(
    val message: OpenAiResponseMessage?
)

data class OpenAiResponseMessage(
    val role: String,
    val content: String?
)

// ==========================================
// Unified Output Models
// ==========================================
data class LlmActionResponse(
    val action: String, // CLICK, INPUT_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, PRESS_BACK, PRESS_HOME, WAIT, COMPLETED, FAILED
    val index: Int?,
    val text: String?,
    val reasoning: String,
    val is_irreversible_or_high_stakes: Boolean? = false
)

data class LlmPlanResponse(
    val plan: com.google.gson.JsonElement
) {
    val planString: String
        get() {
            return if (plan.isJsonArray) {
                val list = mutableListOf<String>()
                plan.asJsonArray.forEach { list.add(it.asString) }
                list.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
            } else {
                plan.asString
            }
        }
}

data class LlmReflectionResponse(
    val success: Boolean,
    val reasoning: String,
    val suggestedTip: String? = null
)

class LlmClient(
    private val apiFormat: ApiFormat,
    private val apiKey: String,
    private val baseUrl: String,
    private val modelName: String,
    rpm: Int,
    private val useFallback: Boolean = false,
    private val fallbackFormat: ApiFormat = ApiFormat.GEMINI_NATIVE,
    private val fallbackKey: String = "",
    private val fallbackBaseUrl: String = "",
    private val fallbackModelName: String = "",
    private val usePrivacyMasking: Boolean = false
) {

    private val rateLimiter = RateLimiter(rpm)
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun serializeTreeCompact(nodes: List<AccessibilityNode>): String {
        val sb = StringBuilder()
        for (node in nodes) {
            val type = node.className.substringAfterLast('.')
            val isSensitive = usePrivacyMasking && node.isSensitive
            val nodeText = if (isSensitive) "[MASKED]" else node.text
            val nodeDesc = if (isSensitive) "[MASKED]" else node.contentDescription

            // Skip layout nodes that have no text, desc, and are not interactive
            if (nodeText.isNullOrBlank() && nodeDesc.isNullOrBlank() && !node.clickable && !node.editable && !node.scrollable) {
                continue
            }
            
            sb.append("[${node.index}] type=$type")
            if (!nodeText.isNullOrBlank()) {
                val cleanedText = nodeText.replace("\n", " ").trim()
                if (cleanedText.isNotEmpty()) {
                    sb.append(" text=\"$cleanedText\"")
                }
            }
            if (!nodeDesc.isNullOrBlank()) {
                val cleanedDesc = nodeDesc.replace("\n", " ").trim()
                if (cleanedDesc.isNotEmpty()) {
                    sb.append(" desc=\"$cleanedDesc\"")
                }
            }
            val flags = mutableListOf<String>()
            if (node.clickable) flags.add("clickable")
            if (node.editable) flags.add("editable")
            if (node.scrollable) flags.add("scrollable")
            if (flags.isNotEmpty()) {
                sb.append(" flags=${flags.joinToString(",")}")
            }
            node.resourceId?.substringAfterLast('/')?.let { resId ->
                if (resId.isNotBlank()) {
                    sb.append(" id=$resId")
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Generates a high-level step-by-step plan to achieve the goal.
     */
    suspend fun generatePlan(
        goal: String,
        nodes: List<AccessibilityNode>,
        onLog: ((String) -> Unit)? = null
    ): LlmPlanResponse = withContext(Dispatchers.IO) {
        rateLimiter.acquire()
        val treeCompact = serializeTreeCompact(nodes)
        val prompt = """
            You are an autonomous Android GUI agent.
            Your global goal is: "$goal"
            
            Here is the current screen's active UI tree:
            $treeCompact
            
            Generate a high-level step-by-step plan (sub-goals) to achieve the goal.
            Respond ONLY with a single JSON object. Do NOT output any markdown blocks (like ```json), just output raw JSON text.
            
            Your output format:
            {
              "plan": "Detailed step-by-step plan to achieve the goal"
            }
        """.trimIndent()

        val schema = GeminiResponseSchema(
            type = "OBJECT",
            properties = mapOf(
                "plan" to GeminiSchemaProperty(type = "STRING")
            ),
            required = listOf("plan")
        )

        val rawJson = queryLlm(prompt, null, geminiSchema = schema)
        onLog?.invoke("[Planner] Raw JSON response:\n$rawJson")
        gson.fromJson(rawJson, LlmPlanResponse::class.java)
    }

    /**
     * Obtains the next action decision from the configured LLM provider.
     */
    suspend fun getNextAction(
        goal: String,
        nodes: List<AccessibilityNode>,
        screenshotBase64: String? = null,
        plan: String? = null,
        tips: String? = null,
        onLog: ((String) -> Unit)? = null
    ): LlmActionResponse = withContext(Dispatchers.IO) {
        rateLimiter.acquire()
        val treeCompact = serializeTreeCompact(nodes)
        
        val planSection = if (plan != null) "Current Plan:\n$plan\n" else ""
        val tipsSection = if (tips != null && tips.isNotBlank()) "Learned Tips & Guidance:\n$tips\n" else ""
        
        val prompt = """
            You are an autonomous Android GUI agent.
            Your global goal is: "$goal"
            
            $planSection
            $tipsSection
            Here is the current screen's active UI tree:
            $treeCompact
            
            Choose the single next action to get closer to the goal based on the plan and tips.
            Use the 'index' of the node to interact with elements.
            
            Actions you can choose:
            - CLICK: Click an interactive element (must provide a valid target index).
            - INPUT_TEXT: Input text on an editable element (must provide index and text content).
            - SCROLL_FORWARD: Scroll down/forward a scrollable view (provide index of scrollable view).
            - SCROLL_BACKWARD: Scroll up/backward a scrollable view (provide index of scrollable view).
            - PRESS_BACK: Simulate the hardware/global Back button (index can be null).
            - PRESS_HOME: Simulate the hardware/global Home button (index can be null).
            - WAIT: Idle for a brief period before re-checking (index can be null).
            - COMPLETED: The goal has been successfully reached.
            - FAILED: The goal cannot be reached, or you are stuck.
            
            CRITICAL RULES:
            1. You MUST respond ONLY with a single JSON object.
            2. Do NOT output any markdown blocks (like ```json), just output raw JSON text.
            3. Do NOT include any pre-text or post-text conversational explanations.
            
            Your output MUST adhere strictly to this JSON format:
            {
              "action": "CLICK" | "INPUT_TEXT" | "SCROLL_FORWARD" | "SCROLL_BACKWARD" | "PRESS_BACK" | "PRESS_HOME" | "WAIT" | "COMPLETED" | "FAILED",
              "index": integer or null,
              "text": string or null,
              "reasoning": "Detailed reasoning about why this action is chosen",
              "is_irreversible_or_high_stakes": boolean
            }

            ### FEW-SHOT EXAMPLES FOR GUIDANCE:
            
            Example 1: Click Action
            Goal: "Search for Wi-Fi settings"
            Active Tree:
            [0] type=FrameLayout
            [1] type=ImageButton desc="Search settings" flags=clickable id=search_button
            Output:
            {
              "action": "CLICK",
              "index": 1,
              "text": null,
              "reasoning": "Clicking the 'Search settings' icon (index 1) to open the search bar input.",
              "is_irreversible_or_high_stakes": false
            }
            
            Example 2: Input Text Action
            Goal: "Search for Wi-Fi settings"
            Active Tree:
            [3] type=EditText text="Search..." flags=clickable,editable id=search_src_text
            Output:
            {
              "action": "INPUT_TEXT",
              "index": 3,
              "text": "Wi-Fi",
              "reasoning": "Focusing on the active search field (index 3) and typing 'Wi-Fi' to execute search queries.",
              "is_irreversible_or_high_stakes": false
            }
        """.trimIndent()

        val schema = GeminiResponseSchema(
            type = "OBJECT",
            properties = mapOf(
                "action" to GeminiSchemaProperty(
                    type = "STRING",
                    enum = listOf("CLICK", "INPUT_TEXT", "SCROLL_FORWARD", "SCROLL_BACKWARD", "PRESS_BACK", "PRESS_HOME", "WAIT", "COMPLETED", "FAILED")
                ),
                "index" to GeminiSchemaProperty(type = "INTEGER"),
                "text" to GeminiSchemaProperty(type = "STRING"),
                "reasoning" to GeminiSchemaProperty(type = "STRING"),
                "is_irreversible_or_high_stakes" to GeminiSchemaProperty(type = "BOOLEAN")
            ),
            required = listOf("action", "reasoning")
        )

        val rawJson = queryLlm(prompt, screenshotBase64, geminiSchema = schema)
        onLog?.invoke("[Operator] Raw JSON response:\n$rawJson")
        gson.fromJson(rawJson, LlmActionResponse::class.java)
    }

    /**
     * Inspects and reflects on whether the executed action succeeded or failed.
     */
    suspend fun reflectOnAction(
        goal: String,
        actionDescription: String,
        beforeNodes: List<AccessibilityNode>,
        afterNodes: List<AccessibilityNode>,
        onLog: ((String) -> Unit)? = null
    ): LlmReflectionResponse = withContext(Dispatchers.IO) {
        rateLimiter.acquire()
        val beforeCompact = serializeTreeCompact(beforeNodes)
        val afterCompact = serializeTreeCompact(afterNodes)

        val prompt = """
            You are an independent inspector for an Android GUI agent.
            The agent is pursuing the goal: "$goal"
            The agent executed this action: "$actionDescription"
            
            Evaluate if the action succeeded or failed.
            Compare the screen state before and after the action:
            
            ### Screen State BEFORE:
            $beforeCompact
            
            ### Screen State AFTER:
            $afterCompact
            
            Determine:
            1. Did the screen change as expected? If yes, "success": true.
            2. If it did not change (or returned to the same state), or if it triggered an unexpected error/verification pop-up, "success": false.
            3. If failed, suggest a specific, short tip ("suggestedTip") to guide future actions (e.g. "Do not click index 12 because it is static text; click index 13 instead"). If successful, "suggestedTip" should be null.
            
            Respond ONLY with a single JSON object. Do NOT output any markdown blocks (like ```json), just output raw JSON text.
            
            Your output format:
            {
              "success": boolean,
              "reasoning": "Explanation of your comparison",
              "suggestedTip": string or null
            }
        """.trimIndent()

        val schema = GeminiResponseSchema(
            type = "OBJECT",
            properties = mapOf(
                "success" to GeminiSchemaProperty(type = "BOOLEAN"),
                "reasoning" to GeminiSchemaProperty(type = "STRING"),
                "suggestedTip" to GeminiSchemaProperty(type = "STRING")
            ),
            required = listOf("success", "reasoning")
        )

        val rawJson = queryLlm(prompt, null, geminiSchema = schema)
        onLog?.invoke("[Reflector] Raw JSON response:\n$rawJson")
        gson.fromJson(rawJson, LlmReflectionResponse::class.java)
    }

    /**
     * Unifies and triggers routing, retries, and fallback logic for all LLM calls.
     */
    private fun queryLlm(
        prompt: String,
        screenshotBase64: String?,
        currentKey: String = apiKey,
        currentBaseUrl: String = baseUrl,
        currentModel: String = modelName,
        currentFormat: ApiFormat = apiFormat,
        geminiSchema: GeminiResponseSchema? = null
    ): String {
        return try {
            when (currentFormat) {
                ApiFormat.GEMINI_NATIVE -> executeGeminiNativeRaw(prompt, screenshotBase64, currentKey, currentBaseUrl, currentModel, geminiSchema)
                ApiFormat.OPENAI_COMPATIBLE -> executeOpenAiCompatibleRaw(prompt, screenshotBase64, currentKey, currentBaseUrl, currentModel)
            }
        } catch (e: Exception) {
            if (useFallback) {
                System.err.println("[LlmClient] Primary LLM request failed: ${e.localizedMessage}. Triggering Fallback Model...")
                try {
                    when (fallbackFormat) {
                        ApiFormat.GEMINI_NATIVE -> executeGeminiNativeRaw(prompt, screenshotBase64, fallbackKey, fallbackBaseUrl, fallbackModelName, geminiSchema)
                        ApiFormat.OPENAI_COMPATIBLE -> executeOpenAiCompatibleRaw(prompt, screenshotBase64, fallbackKey, fallbackBaseUrl, fallbackModelName)
                    }
                } catch (fallbackEx: Exception) {
                    throw Exception("Primary LLM call failed: ${e.localizedMessage}. AND Fallback LLM call failed: ${fallbackEx.localizedMessage}", fallbackEx)
                }
            } else {
                throw e
            }
        }
    }

    private fun executeGeminiNativeRaw(
        prompt: String,
        screenshotBase64: String?,
        currentKey: String,
        currentBaseUrl: String,
        currentModel: String,
        schema: GeminiResponseSchema?
    ): String {
        val cleanedBase = if (currentBaseUrl.endsWith("/")) currentBaseUrl else "$currentBaseUrl/"
        val url = "${cleanedBase}v1beta/models/$currentModel:generateContent?key=$currentKey"

        val parts = mutableListOf<GeminiPart>()
        parts.add(GeminiPart(text = prompt))
        if (screenshotBase64 != null) {
            parts.add(GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = screenshotBase64)))
        }

        val config = schema?.let {
            GeminiGenerationConfig(
                responseMimeType = "application/json",
                responseSchema = it
            )
        }

        val payload = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            generationConfig = config
        )

        val responseBody = executePost(url, gson.toJson(payload), headers = emptyMap())
        
        val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
        val rawText = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("Could not parse text candidate from Gemini response envelope: $responseBody")

        return cleanJsonString(rawText)
    }

    private fun executeOpenAiCompatibleRaw(
        prompt: String,
        screenshotBase64: String?,
        currentKey: String,
        currentBaseUrl: String,
        currentModel: String
    ): String {
        val cleanedBase = if (currentBaseUrl.endsWith("/")) currentBaseUrl else "$currentBaseUrl/"
        val url = "${cleanedBase}chat/completions"

        val content = if (screenshotBase64 != null) {
            listOf(
                OpenAiContentPart(
                    type = "image_url",
                    image_url = OpenAiImageUrl(url = "data:image/jpeg;base64,$screenshotBase64")
                ),
                OpenAiContentPart(type = "text", text = prompt)
            )
        } else {
            prompt
        }

        val payload = OpenAiRequest(
            model = currentModel,
            messages = listOf(
                OpenAiRequestMessage(role = "user", content = content)
            ),
            response_format = OpenAiResponseFormat(type = "json_object")
        )

        val headers = mutableMapOf<String, String>()
        if (currentKey.isNotBlank()) {
            headers["Authorization"] = "Bearer $currentKey"
        }

        val responseBody = executePost(url, gson.toJson(payload), headers)
        
        val openAiResponse = gson.fromJson(responseBody, OpenAiResponse::class.java)
        val rawText = openAiResponse.choices?.firstOrNull()?.message?.content
            ?: throw Exception("Could not parse message content from OpenAI response envelope: $responseBody")

        return cleanJsonString(rawText)
    }

    private fun cleanJsonString(raw: String): String {
        var cleaned = raw.trim()
        
        // 1. If wrapped in markdown code blocks, extract contents
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            val lastTicks = cleaned.lastIndexOf("```")
            if (firstNewline != -1 && lastTicks != -1 && lastTicks > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastTicks).trim()
            }
        }
        
        // 2. Fallback: extract the outermost JSON object if conversational text is present
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1)
        }
        
        return cleaned
    }

    private fun executePost(url: String, jsonPayload: String, headers: Map<String, String>): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        for ((key, value) in headers) {
            requestBuilder.addHeader(key, value)
        }

        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorMsg = response.body?.string() ?: "Empty body"
            throw Exception("HTTP Request failed (${response.code}): $errorMsg")
        }

        return response.body?.string() ?: throw Exception("Empty response body received.")
    }
}
