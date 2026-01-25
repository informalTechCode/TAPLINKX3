# TapLink X3

TapLink X3 is an Android-based browser shell designed for XR headsets that mirrors a dual-eye viewport, overlays a precision cursor, and exposes a custom radial keyboard that can be anchored to the viewport or controlled via spatial gestures. The application focuses on keeping input predictable when the user is navigating web content from a wearable controller. It was originally created by u/glxblt76.

<a href="https://www.buymeacoffee.com/informaltech" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## Highlights

- **Dual-eye rendering** that mirrors a single `WebView` into a left-eye clip with a cursor and a right-eye `SurfaceView` preview.
- **Custom keyboard** with anchored and focus-driven modes, supporting casing toggles, symbol layouts, and dynamic buttons.
- **Voice Control**: High-accuracy speech-to-text powered by the Groq API. Activate it by pressing the microphone key on the custom keyboard. An API key is required and can be entered via the settings menu.

## Gesture Reference

| Gesture | Action | Notes |
| --- | --- | --- |
| **Single Tap** | Click / Focus | Primary interaction. |
| **Double Tap** | Back | Navigation history previous. |
| **Triple Tap** | Re-center | Anchored Mode only. |

## Tips
*   **API Key Entry**: Use the `scrcpy` keyboard to paste your Groq API key into the setting dialog.
- **Persistent bookmarks** managed through `BookmarksView` with storage handled by `BookmarkManager`.
- **TapLink AI** powered by Groq, providing a dedicated chat interface for reasoning and real-time help.

## Useful Features

- **Anchored Mode (3DoF)**: Clicking the anchor icon toggles anchored mode on and off. Anchored mode provides 3 degrees of freedom with adjustable smoothness.
- **Temple Gestures (Anchored Mode)**:
  - **Double Tap (Right Temple)**: Go back.
  - **Triple Tap (Right Temple)**: Re-center the display.
- **Media Mode (Blank Screen)**: Toggle the "Eye" icon to black out the screen for audio-only consumption while keeping media controls accessible.
- **Scroll Mode**: Automatically hides UI elements for immersive reading; restore by tapping the transparent button in the bottom-right.
- **Enhanced Settings**: Centralized control for volume, brightness, smoothness, screen size, and typography.
- **Screen Drift**: If you run into a screen drift issue, reboot the glasses.
- **Brightness Limitation**: Due to a RayNeo limitation, we do not recommend running the glasses at max brightness while using TapLink X3.
- **Location Services**: To use location features in web apps, ensure the **RayNeo AR companion app** on your phone has **Location permission** granted. The glasses stream GPS data from the connected phone.

## Documentation

- [Setup and Build](docs/SETUP.md) - Instructions for environment setup, building, and installing.
- [User Guide](docs/USER_GUIDE.md) - **Recommended** for first-time users (gestures, modes, and tools).
- [Architecture Overview](docs/ARCHITECTURE.md) - Details on the app structure, data flow, and bookmark management.
- [Input Systems](docs/INPUT_SYSTEM.md) - Explanation of anchored, focus-driven, and voice input modes.
- [TapLink AI](docs/TAPLINK_AI.md) - Details on the dedicated chat window and Groq integration.
- [Project History](docs/HISTORY.md) - Release notes and credit history.

![TapLink X3 app icon](app/src/main/ic_launcher-playstore.png)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
