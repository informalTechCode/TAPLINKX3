1. **Analyze:** The `isOver` function in `DualWebViewGroup.kt` repeatedly calls `getGlobalVisibleRect` during touch/hover events, which is very CPU intensive and causes jank.
2. **Optimize:** Replace `getGlobalVisibleRect` with manual coordinate calculation traversing up to the `DualWebViewGroup` container (`this`). We accumulate `.x` and `.y` properties up the tree, apply the container's scroll offsets, and use `getLocationOnScreen` on the container to get the absolute screen position.
3. **Verify:** Run lint and tests.
4. **Submit:** Commit the changes.
