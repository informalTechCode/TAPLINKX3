# Setup and Build

## Environment Setup

Follow these steps to ensure commands run from the correct working directory and the Gradle wrapper resolves paths properly.

1. **Move to the repo root**
   ```bash
   cd /workspace/TAPLINKX3
   pwd  # should output /workspace/TAPLINKX3
   ```
2. **Use the Gradle wrapper (with API 34 installed)**
   ```bash
   ./gradlew --version
   ```
   The wrapper resolves project-relative paths; avoid invoking a system-wide `gradle` binary. If you see SDK warnings, install Android API 34 to match the module's `compileSdk`/`targetSdk`.
3. **Optional: export a helper alias**
   Add this to your shell profile if you frequently open new sessions:
   ```bash
   echo "alias taplinkx3='cd /workspace/TAPLINKX3'" >> ~/.bashrc
   source ~/.bashrc
   ```
   Then run `taplinkx3` to jump directly into the repository before running tasks.

## Build & run

1. Install Android Studio (Giraffe or newer) with Android SDK 34.
2. Import the repository as a Gradle project.
3. Select the `app` run configuration and deploy to an Android 10 (API 29) or newer device/headset.
4. The default start URL is derived from the bookmark home entry; the keyboard opens via the navigation bar or triple-click menu.

Gradle wrapper commands are available for CI:

```bash
./gradlew assembleDebug
./gradlew test
```

## Installation & sideloading

- To install without Android Studio, build or download the APK and sideload it onto your headset/phone. A step-by-step walkthrough is available here.
-   [![TapLink X3 sideloading walkthrough](https://img.youtube.com/vi/l3wu7x14LKY/maxresdefault.jpg)](https://www.youtube.com/watch?v=l3wu7x14LKY)
- When sideloading via ADB, enable developer options on the target device and confirm that unknown sources are allowed in the headset settings before installing the APK.
