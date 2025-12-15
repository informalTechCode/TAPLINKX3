# TapLink X3

TapLink X3 is an Android-based browser shell designed for XR headsets that mirrors a dual-eye viewport, overlays a precision cursor, and exposes a custom radial keyboard that can be anchored to the viewport or controlled via spatial gestures. The application focuses on keeping input predictable when the user is navigating web content from a wearable controller. It was originally created by u/glxblt76.

<a href="https://www.buymeacoffee.com/informaltech" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## Highlights

- **Dual-eye rendering** that mirrors a single `WebView` into a left-eye clip with a cursor and a right-eye `SurfaceView` preview.
- **Custom keyboard** with anchored and focus-driven modes, supporting casing toggles, symbol layouts, and dynamic buttons.
- **Persistent bookmarks** managed through `BookmarksView` with storage handled by `BookmarkManager`.

## Useful Features

- **Anchored Mode (3DoF)**: Clicking the anchor icon toggles anchored mode on and off. Anchored mode provides 3 degrees of freedom.
- **Temple Gestures (Anchored Mode)**:
  - **Double Tap (Right Temple)**: Go back.
  - **Triple Tap (Right Temple)**: Re-center the display.
- **Screen Drift**: If you run into a screen drift issue, reboot the glasses.
- **Brightness Limitation**: Due to a RayNeo limitation, we do not recommend running the glasses at max brightness while using TapLink X3.

## Documentation

- [Setup and Build](docs/SETUP.md) - Instructions for environment setup, building, and installing.
- [Architecture Overview](docs/ARCHITECTURE.md) - Details on the app structure, data flow, and bookmark management.
- [Input Systems](docs/INPUT_SYSTEM.md) - Explanation of anchored and focus-driven keyboard modes.
- [Project History](docs/HISTORY.md) - Release notes and credit history.

![TapLink X3 app icon](app/src/main/ic_launcher-playstore.png)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
