# TapLink X3

TapLink X3 is an Android-based browser shell designed for XR headsets that mirrors a dual-eye viewport, overlays a precision cursor, and exposes a custom radial keyboard that can be anchored to the viewport or controlled via spatial gestures. The application focuses on keeping input predictable when the user is navigating web content from a wearable controller. It was originally created by u/glxblt76.

<a href="https://www.buymeacoffee.com/informaltech" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

## Highlights

- **Dual-eye rendering** that mirrors a single `WebView` into a left-eye clip with a cursor and a right-eye `SurfaceView` preview.
- **Custom keyboard** with anchored and focus-driven modes, supporting casing toggles, symbol layouts, and dynamic buttons.
- **Navigation and triple-click overlays** supplying quick actions, bookmarking, and anchor toggles.
- **Persistent bookmarks** managed through `BookmarksView` with storage handled by `BookmarkManager`.

## Documentation

- [Setup and Build](docs/SETUP.md) - Instructions for environment setup, building, and installing.
- [Architecture Overview](docs/ARCHITECTURE.md) - Details on the app structure, data flow, and bookmark management.
- [Input Systems](docs/INPUT_SYSTEM.md) - Explanation of anchored and focus-driven keyboard modes.
- [Project History](docs/HISTORY.md) - Release notes and credit history.
