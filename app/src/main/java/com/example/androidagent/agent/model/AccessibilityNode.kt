package com.example.androidagent.agent.model

/**
 * NodeBounds represents the screen coordinates of a UI element.
 */
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Float
        get() = (left + right) / 2f
    val centerY: Float
        get() = (top + bottom) / 2f
}

/**
 * AccessibilityNode is a clean, serialized representation of a UI element.
 * It removes bulky framework elements, leaving only what is useful for an LLM to decide on actions.
 */
data class AccessibilityNode(
    val index: Int,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val bounds: NodeBounds,
    val isPassword: Boolean = false
) {
    val isSensitive: Boolean
        get() {
            if (isPassword) return true
            val classNameLower = className.lowercase()
            if (classNameLower.contains("password") || classNameLower.contains("pin")) return true
            
            val textLower = (text ?: "").lowercase()
            val descLower = (contentDescription ?: "").lowercase()
            val resIdLower = (resourceId ?: "").lowercase()

            val sensitiveKeywords = listOf(
                "password", "passphrase", "secret", "cardnumber", "creditcard", "cvv", 
                "social security", "ssn", "national id", "passcode", "credit_card",
                "card_number", "security_code", "bank_account", "iban", "account_number",
                "key", "token", "credential"
            )

            for (keyword in sensitiveKeywords) {
                if (textLower.contains(keyword) || descLower.contains(keyword) || resIdLower.contains(keyword)) {
                    return true
                }
            }
            return false
        }
}
