# Environment Setup for TapLink X3

Follow these steps to ensure commands run from the correct working directory and the Gradle wrapper resolves paths properly.

1. **Move to the repo root**
   ```bash
   cd /workspace/TAPLINKX3
   pwd  # should output /workspace/TAPLINKX3
   ```
2. **Use the Gradle wrapper**
   ```bash
   ./gradlew --version
   ```
   The wrapper resolves project-relative paths; avoid invoking a system-wide `gradle` binary.
3. **Optional: export a helper alias**
   Add this to your shell profile if you frequently open new sessions:
   ```bash
   echo "alias taplinkx3='cd /workspace/TAPLINKX3'" >> ~/.bashrc
   source ~/.bashrc
   ```
   Then run `taplinkx3` to jump directly into the repository before running tasks.

With the working directory set to the repo root, environment setup should no longer mis-detect paths when running builds or tests.
