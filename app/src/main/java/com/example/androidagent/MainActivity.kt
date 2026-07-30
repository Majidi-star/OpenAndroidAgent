package com.example.androidagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.androidagent.agent.AgentController
import com.example.androidagent.service.AgentAccessibilityService
import com.example.androidagent.llm.ApiFormat
import com.example.androidagent.ui.theme.AndroidAgentTheme

class MainActivity : ComponentActivity() {

    // Keep track of the service running state reactively
    private var isServiceRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check initial state
        isServiceRunning = AgentAccessibilityService.isServiceRunning()

        setContent {
            AndroidAgentTheme(darkTheme = true) { // Force beautiful dark theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF12131C) // Deep space indigo background
                ) {
                    AgentDashboard(
                        isServiceRunning = isServiceRunning,
                        onOpenSettings = {
                            // Intent to launch the system Accessibility Settings screen
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        },
                        context = this
                    )
                }
            }
        }
    }

    /**
     * Activity lifecycle callback: refreshes the accessibility service running status
     * when returning from system Settings.
     */
    override fun onResume() {
        super.onResume()
        isServiceRunning = AgentAccessibilityService.isServiceRunning()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDashboard(
    isServiceRunning: Boolean,
    onOpenSettings: () -> Unit,
    context: Context
) {
    // 1. SharedPreferences configuration to persist user settings locally
    val sharedPrefs = remember { context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE) }
    
    var apiFormatStr by remember {
        mutableStateOf(sharedPrefs.getString("api_format", "GEMINI_NATIVE") ?: "GEMINI_NATIVE")
    }
    val apiFormat = remember(apiFormatStr) {
        try { ApiFormat.valueOf(apiFormatStr) } catch(e: Exception) { ApiFormat.GEMINI_NATIVE }
    }
    
    var baseUrl by remember {
        mutableStateOf(sharedPrefs.getString("base_url", "https://generativelanguage.googleapis.com/") ?: "https://generativelanguage.googleapis.com/")
    }

    var apiKey by remember { 
        mutableStateOf(sharedPrefs.getString("api_key", "") ?: "") 
    }
    var modelName by remember { 
        mutableStateOf(sharedPrefs.getString("model_name", "gemini-1.5-flash") ?: "gemini-1.5-flash") 
    }
    var goal by remember { 
        mutableStateOf(sharedPrefs.getString("goal", "Open Settings and turn on Wi-Fi") ?: "Open Settings and turn on Wi-Fi") 
    }
    var rpmStr by remember {
        mutableStateOf(sharedPrefs.getString("rpm", "10") ?: "10")
    }
    val rpm = remember(rpmStr) {
        rpmStr.toIntOrNull() ?: 10
    }
    var useVision by remember {
        mutableStateOf(sharedPrefs.getBoolean("use_vision", false))
    }
    var useFallback by remember {
        mutableStateOf(sharedPrefs.getBoolean("use_fallback", false))
    }
    var fallbackFormatStr by remember {
        mutableStateOf(sharedPrefs.getString("fallback_format", "GEMINI_NATIVE") ?: "GEMINI_NATIVE")
    }
    var fallbackKey by remember {
        mutableStateOf(sharedPrefs.getString("fallback_key", "") ?: "")
    }
    var fallbackBaseUrl by remember {
        mutableStateOf(sharedPrefs.getString("fallback_base_url", "https://generativelanguage.googleapis.com/") ?: "https://generativelanguage.googleapis.com/")
    }
    var fallbackModelName by remember {
        mutableStateOf(sharedPrefs.getString("fallback_model_name", "gemini-1.5-flash") ?: "gemini-1.5-flash")
    }
    var tipsList by remember {
        mutableStateOf(sharedPrefs.getStringSet("tips_memory", emptySet())?.toList() ?: emptyList())
    }
    var usePrivacyMasking by remember {
        mutableStateOf(sharedPrefs.getBoolean("use_privacy_masking", true))
    }

    // Save preferences on modification
    LaunchedEffect(apiFormatStr, apiKey, baseUrl, modelName, rpmStr, useVision, useFallback, fallbackFormatStr, fallbackKey, fallbackBaseUrl, fallbackModelName, usePrivacyMasking, goal) {
        sharedPrefs.edit().apply {
            putString("api_format", apiFormatStr)
            putString("api_key", apiKey)
            putString("base_url", baseUrl)
            putString("model_name", modelName)
            putString("rpm", rpmStr)
            putBoolean("use_vision", useVision)
            putBoolean("use_fallback", useFallback)
            putString("fallback_format", fallbackFormatStr)
            putString("fallback_key", fallbackKey)
            putString("fallback_base_url", fallbackBaseUrl)
            putString("fallback_model_name", fallbackModelName)
            putBoolean("use_privacy_masking", usePrivacyMasking)
            putString("goal", goal)
            apply()
        }
    }

    // 2. State management for running task
    var agentState by remember { mutableStateOf(AgentController.State.IDLE) }
    val logs = remember { mutableStateListOf<String>() }
    var activeController by remember { mutableStateOf<AgentController?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    // Auto-scroll the log console to the bottom when new logs are added
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            lazyListState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Antigravity",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC084FC) // Glowing purple accent
                )
                Text(
                    text = "Autonomous GUI Agent",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            
            // Running Status Tag
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (agentState) {
                        AgentController.State.RUNNING -> Color(0xFF1E3A24)
                        AgentController.State.COMPLETED -> Color(0xFF132A3A)
                        AgentController.State.FAILED -> Color(0xFF3F1919)
                        AgentController.State.ERROR -> Color(0xFF3F1919)
                        else -> Color(0xFF1F202E)
                    }
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = agentState.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (agentState) {
                        AgentController.State.RUNNING -> Color(0xFF4ADE80)
                        AgentController.State.COMPLETED -> Color(0xFF60A5FA)
                        AgentController.State.FAILED -> Color(0xFFF87171)
                        AgentController.State.ERROR -> Color(0xFFF87171)
                        else -> Color.Gray
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 3. Accessibility Service Status Banner
        if (!isServiceRunning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1E1E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF7F1D1D), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "⚠",
                        color = Color(0xFFEF4444),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accessibility Service Disabled",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFCA5A5),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Enable 'Agent Accessibility Service' in Settings to execute actions.",
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Enable", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF14241F)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF065F46), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF34D399), RoundedCornerShape(4.dp))
                    )
                    Text(
                        text = "Service connected & ready to perceive screen.",
                        color = Color(0xFFA7F3D0),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 3.5 Safety Confirmation Dialog
        if (agentState == AgentController.State.PAUSED_FOR_SAFETY) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3F1919)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "⚠️ HIGH-STAKES ACTION BLOCKED",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFCA5A5),
                        fontSize = 16.sp
                    )
                    Text(
                        text = "The agent requested to perform a potentially irreversible or sensitive action. Please review and confirm below:",
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                activeController?.approveSafetyGate()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Approve & Run", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        
                        Button(
                            onClick = {
                                activeController?.rejectSafetyGate()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reject & Halt", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 4. Configuration Inputs
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2030)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Configuration",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )

                // API Format Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val formats = listOf(
                        "Gemini Native" to "GEMINI_NATIVE",
                        "OpenAI Compatible" to "OPENAI_COMPATIBLE"
                    )
                    formats.forEach { (label, value) ->
                        val isSelected = apiFormatStr == value
                        Button(
                            onClick = {
                                apiFormatStr = value
                                if (value == "GEMINI_NATIVE") {
                                    baseUrl = "https://generativelanguage.googleapis.com/"
                                } else if (baseUrl == "https://generativelanguage.googleapis.com/") {
                                    baseUrl = "https://integrate.api.nvidia.com/v1/"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFC084FC) else Color(0xFF2C2E3E),
                                contentColor = if (isSelected) Color.Black else Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (apiFormatStr == "OPENAI_COMPATIBLE") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFC084FC),
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color(0xFFC084FC)
                        )
                    )
                }

                // API Key Input
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (apiFormatStr == "GEMINI_NATIVE") "Gemini API Key" else "API Key / Token (Optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFC084FC),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color(0xFFC084FC)
                    )
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Model Selection
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Model") },
                        modifier = Modifier.weight(2f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFC084FC),
                            unfocusedBorderColor = Color.Gray
                        )
                    )

                    // RPM Selection
                    OutlinedTextField(
                        value = rpmStr,
                        onValueChange = { rpmStr = it },
                        label = { Text("RPM Limit") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFC084FC),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = useVision,
                        onCheckedChange = { useVision = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFC084FC),
                            checkedTrackColor = Color(0xFFE8D5FD)
                        )
                    )
                    Text(
                        text = "Use Vision (Set-of-Marks Fallback)",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = usePrivacyMasking,
                        onCheckedChange = { usePrivacyMasking = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFC084FC),
                            checkedTrackColor = Color(0xFFE8D5FD)
                        )
                    )
                    Text(
                        text = "Privacy Masking (Blur sensitive inputs)",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = useFallback,
                        onCheckedChange = { useFallback = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFC084FC),
                            checkedTrackColor = Color(0xFFE8D5FD)
                        )
                    )
                    Text(
                        text = "Use Two-Tier Fallback Model",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }

                if (useFallback) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Fallback Configuration",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color(0xFFC084FC)
                        )

                        // Fallback API Format Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val formats = listOf(
                                "Gemini Native" to "GEMINI_NATIVE",
                                "OpenAI Compatible" to "OPENAI_COMPATIBLE"
                            )
                            formats.forEach { (label, value) ->
                                val isSelected = fallbackFormatStr == value
                                Button(
                                    onClick = {
                                        fallbackFormatStr = value
                                        if (value == "GEMINI_NATIVE") {
                                            fallbackBaseUrl = "https://generativelanguage.googleapis.com/"
                                        } else if (fallbackBaseUrl == "https://generativelanguage.googleapis.com/") {
                                            fallbackBaseUrl = "https://integrate.api.nvidia.com/v1/"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) Color(0xFFC084FC) else Color(0xFF2C2E3E),
                                        contentColor = if (isSelected) Color.Black else Color.White
                                    ),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 2.dp)
                                ) {
                                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (fallbackFormatStr == "OPENAI_COMPATIBLE") {
                            OutlinedTextField(
                                value = fallbackBaseUrl,
                                onValueChange = { fallbackBaseUrl = it },
                                label = { Text("Fallback Base URL") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFC084FC),
                                    unfocusedBorderColor = Color.Gray,
                                    focusedLabelColor = Color(0xFFC084FC)
                                )
                            )
                        }

                        OutlinedTextField(
                            value = fallbackKey,
                            onValueChange = { fallbackKey = it },
                            label = { Text(if (fallbackFormatStr == "GEMINI_NATIVE") "Fallback Gemini Key" else "Fallback API Key / Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFC084FC),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color(0xFFC084FC)
                            )
                        )

                        OutlinedTextField(
                            value = fallbackModelName,
                            onValueChange = { fallbackModelName = it },
                            label = { Text("Fallback Model Name") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFC084FC),
                                unfocusedBorderColor = Color.Gray,
                                focusedLabelColor = Color(0xFFC084FC)
                            )
                        )
                    }
                }

                // Goal Input
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("Task Goal") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFC084FC),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color(0xFFC084FC)
                    )
                )
            }
        }

        // 4.5. Tips & Shortcuts Memory Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2030)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tips & Shortcuts Memory (${tipsList.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFFC084FC)
                    )
                    if (tipsList.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                tipsList = emptyList()
                                sharedPrefs.edit().putStringSet("tips_memory", emptySet()).apply()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Text("Clear Memory", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (tipsList.isEmpty()) {
                    Text(
                        text = "No learned tips or shortcuts in memory yet. The agent will adapt and learn tips here if actions fail during execution.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                } else {
                    tipsList.forEach { tip ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("💡", fontSize = 12.sp)
                            Text(
                                text = tip,
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // 5. Execution Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    if (apiFormat == ApiFormat.GEMINI_NATIVE && apiKey.isBlank()) {
                        logs.add("[Warning] Please provide a valid Gemini API Key.")
                        return@Button
                    }
                    if (goal.isBlank()) {
                        logs.add("[Warning] Goal cannot be empty.")
                        return@Button
                    }
                    logs.clear()
                    val fallbackFormat = if (fallbackFormatStr == "GEMINI_NATIVE") ApiFormat.GEMINI_NATIVE else ApiFormat.OPENAI_COMPATIBLE
                    val controller = AgentController(
                        apiFormat = apiFormat,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        modelName = modelName,
                        rpm = rpm,
                        useVision = useVision,
                        useFallback = useFallback,
                        fallbackFormat = fallbackFormat,
                        fallbackKey = fallbackKey,
                        fallbackBaseUrl = fallbackBaseUrl,
                        fallbackModelName = fallbackModelName,
                        usePrivacyMasking = usePrivacyMasking,
                        initialTips = tipsList,
                        onTipLearned = { newTip ->
                            val updated = tipsList.toMutableList()
                            if (newTip !in updated) {
                                updated.add(newTip)
                                tipsList = updated
                                sharedPrefs.edit().putStringSet("tips_memory", updated.toSet()).apply()
                            }
                        },
                        goal = goal,
                        onLog = { msg -> logs.add(msg) },
                        onStateChange = { newState -> agentState = newState }
                    )
                    activeController = controller
                    controller.start(coroutineScope)
                },
                modifier = Modifier.weight(1f),
                enabled = isServiceRunning && (agentState == AgentController.State.IDLE || agentState == AgentController.State.COMPLETED || agentState == AgentController.State.FAILED || agentState == AgentController.State.ERROR),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC084FC))
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Start Agent")
            }

            Button(
                onClick = {
                    activeController?.stop()
                    activeController = null
                    agentState = AgentController.State.IDLE
                },
                modifier = Modifier.weight(1f),
                enabled = agentState == AgentController.State.RUNNING || agentState == AgentController.State.PAUSED_FOR_SAFETY,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
            ) {
                Text("Halt Agent")
            }
        }

        // 6. Terminal Console Output Log
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Console Output Log",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Agent Logs", logs.joinToString("\n"))
                        clipboard.setPrimaryClip(clip)
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Copy", fontSize = 11.sp, color = Color(0xFFC084FC))
                }
                TextButton(
                    onClick = { logs.clear() },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("Clear", fontSize = 11.sp, color = Color(0xFFEF4444))
                }
            }
        }
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0E14)), // Classic terminal background
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color(0xFF1F222F), RoundedCornerShape(12.dp))
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Terminal idle. Tap Start Agent to execute the loop.",
                        color = Color.DarkGray,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = when {
                                log.startsWith("[Error]") -> Color(0xFFEF4444)
                                log.startsWith("[Warning]") -> Color(0xFFFBBF24)
                                log.startsWith("SUCCESS:") -> Color(0xFF34D399)
                                log.startsWith("FAILURE:") -> Color(0xFFF87171)
                                log.startsWith("Reasoning:") -> Color(0xFFC084FC)
                                log.startsWith("Decision:") -> Color(0xFF60A5FA)
                                log.startsWith("---") -> Color(0xFF818CF8)
                                else -> Color(0xFFE2E8F0)
                            }
                        )
                    }
                }
            }
        }
    }
}