# OpenAndroidAgent 🤖📱

An open-source, lightweight, local-first Android GUI agent powered by Large Language Models (LLMs). It interacts with any Android screen autonomously using accessibility node trees, coordinate gesture taps, and visual screenshots to complete user goals.

---

## ✨ Features

- **Multimodal (Vision) & Text-Only Modes**: Supports visual model execution (Set-of-Marks bounding box overlays) or lightweight text-only trees.
- **Compact Tree Serialization**: Compresses accessibility trees by **80%** (flat format) to fit large screen configurations into small local LLM context windows.
- **Planner-Operator-Reflector Loop**: 
  - **Planner**: Generates a high-level step-by-step plan before execution.
  - **Operator**: Resolves coordinates, selects actions, and performs taps/swipes.
  - **Reflector**: Inspects state transitions before/after each step to verify success, learn persistent tips, and dynamically revise plans.
- **Tips & Shortcuts Memory**: Learns from failed actions and persists tips across runs using local database storage, preventing repetitive errors.
- **🔒 Privacy Masking**: Automatically detects sensitive inputs (passwords, credentials, PINs, card numbers, API keys, tokens) using framework flags and keywords. Blurs/blackouts sensitive regions on screenshots and redacts text data to `[MASKED]` before transmitting to LLMs.
- **Safety Gate**: Pauses and intercepts high-stakes actions (or unreachable goal failures) for manual user review and approval.
- **Two-Tier Model Fallback**: Automatically redirects requests to a secondary model/key if the primary endpoint throws errors.
- **Latency & Cost Controls**: Event-driven accessibility state change hooks trigger immediate transitions (bypassing delay timers), and consecutive `WAIT` commands skip redundant LLM calls.

---

## 🛠️ Requirements

- **Android SDK / Studio**
- **Android Emulator or Physical Device** (USB Debugging enabled)
- **Model Server**:
  - **Local Ollama** (e.g. `http://10.0.2.2:11434/v1` for emulator access to host) running `qwen2.5vl:7b`/`qwen2.5vl:3b` (Multimodal) or `gemma3:12b` (Text-Only).
  - **Gemini Native API** (Google AI Studio key).

---

## 🚀 Getting Started

### 1. Build and Install the App
Clone the repository and install the application debug build:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Enable Accessibility Service
To allow the agent to read screen hierarchies and execute gestures:
1. Go to **Settings** -> **Accessibility** on your Android device.
2. Select **Antigravity** (or **OpenAndroidAgent**).
3. Toggle **Use service** to **ON**.

### 3. Start the Agent Loop
1. Open the application.
2. Configure your model (Base URL, API Key, Model Name, and Vision preferences). *Note: Scroll down on the configuration card to view all options.*
3. Enter your target task goal (e.g. `Search for Wi-Fi in Settings`).
4. Click **Start Agent**!
