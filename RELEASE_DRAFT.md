# TapLink Update: AI Chat & Enhanced Control

This release brings a major new capability to TapLink: **TapLink AI**, along with requested customization options and significant internal improvements.

## 🤖 New Feature: TapLink AI
TapLink now includes a dedicated, floating AI chat window powered by the **Groq API**.
-   **Context-Aware**: The AI knows it is running on RayNeo X3 Pro glasses and can assist with browser-specific tasks.
-   **Persistent Chat**: Easily toggle the chat window on/off without losing your conversation context.
-   **High Performance**: Powered by Groq's fast inference engine for near-instant responses.
-   **Access**: Tap the new **Chat** (comment bubble) icon in the bottom navigation bar.
-   *Note: Requires a Groq API Key (set in Settings).*

## 🎛️ New Setting: Cursor Sensitivity
You can now fine-tune the responsiveness of the cursor to match your preference.
-   **Adjustable Slider**: Found in the **Settings** menu.
-   **Reset Button**: Quickly revert to the default sensitivity if needed.

## ✨ Polished & Documented Features
This update also solidifies and officially documents several key features:
-   **Voice Control**: Use the microphone button to input text using your voice (powered by Groq).
-   **Netflix Support**: Automatic handling of User Agent strings to ensure Widevine DRM compatibility for Netflix playback.
-   **Anchor Smoothness**: Adjust the smoothing factor for anchored mode to balance stability and responsiveness.
-   **Manual Screen Positioning**: Re-center or offset the screen position when using a scaled-down UI.

## 🛠️ Under the Hood
-   **Major Refactoring**: Over 20,000 lines of code have been reviewed and refactored to improve application stability and performance.
-   **Cleanup**: Removed legacy experimental code and streamlined the codebase.
-   **Documentation**: Updated `HISTORY.md` and added detailed documentation for the new AI system.

---
*Check out the [User Guide](docs/USER_GUIDE.md) for more details on these features.*
