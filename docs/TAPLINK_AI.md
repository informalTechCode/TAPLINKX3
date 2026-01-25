# TapLink AI

TapLink X3 includes a dedicated, floating TapLink AI window powered by the **Groq API**.

## How to Use TapLink AI

1. Tap the **Chat** (comment bubble) button on the **bottom navigation bar** to open the AI window.
2. The chat window appears centered on the screen with a message list and an input field at the bottom.
3. Type your question in the input field.
4. Tap the **Send** button (arrow) or press **Enter** on the keyboard.
5. Tap the **Chat** button again to hide the AI window.

### Entering the Groq API Key
1. Tap **Settings** (gear) on the bottom navigation bar.
2. In the settings panel, tap **Enter Groq API Key**.
3. Paste or type your key, then confirm.
4. If you are connected to a PC, using `scrcpy` makes pasting easier.

## Features

*   **Dedicated UI**: A clean, dark-themed floating window accessible via the "Comment" icon in the navigation bar.
*   **Context-Aware**: The AI is aware it is running on the RayNeo X3 Pro glasses and TapLink browser.
*   **High Performance**: Uses the `groq/compound` model for fast inference and advanced reasoning.
*   **Persistent History**: Chat history is maintained during the session.

## Architecture

The AI system consists of three main components:

1.  **ChatView (`ChatView.kt`)**:
    *   A custom `LinearLayout` that hosts a `WebView`.
    *   Manages the window lifecycle (show/hide).
    *   Injects the `GroqBridge` JavaScript interface.

2.  **Web Interface (`clean_chat.html`)**:
    *   A lightweight HTML/JS interface stored in `assets/`.
    *   Handles message rendering and user input.
    *   Communicates with the Android app via `GroqBridge`.
    *   Maps "assistant" roles to visual styles.

3.  **Groq Interface (`GroqInterface.kt`)**:
    *   Handles network requests to the Groq API.
    *   Manages API keys and model configuration (`groq/compound`).
    *   Injects a detailed system prompt with context about Informal Tech and the hardware.

## Configuration

The TapLink AI requires a valid Groq API Key to be set in the app settings (accessible via the Settings menu).

### System Prompt
The system automatically injects context about:
*   **Platform**: RayNeo X3 Pro glasses.
*   **Software**: TapLink X3 browser.
*   **Creator**: Informal Tech ([YouTube](https://youtube.com/@informal-tech)).
*   **Docs**: [GitHub](https://github.com/informalTechCode/TAPLINKX3/tree/main/docs).
