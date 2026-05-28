## 2024-05-07 - Avoid invalidate() in onDraw()
**Learning:** Calling `invalidate()` from within `onDraw()` in Android custom views causes infinite redraw cycles, heavily impacting CPU usage and battery performance.
**Action:** Never call `invalidate()` or trigger view invalidations during `onDraw()`. Trigger redrawing only in response to state changes (e.g., touch events or animation callbacks).
## 2026-05-06 - [Infinite Re-render Loop in Android UI Component]
**Learning:** Overriding `onDraw()` in an Android custom view just to call `invalidate()` on child components triggers an infinite loop of render passes, severely impacting CPU and battery life. `invalidate()` schedules a redraw, so calling it inside the redraw logic creates a cycle.
**Action:** Never call `invalidate()` or trigger layout passes from within an `onDraw` method. Rely on state changes outside of the draw cycle to call `invalidate()`.
## 2025-05-08 - Infinite Re-render Loop in `DualWebViewGroup.kt`
**Learning:** `dispatchDraw` was overridden solely to call `invalidate()` on child views. Since `dispatchDraw` is part of the drawing pass, calling `invalidate()` from it queues another drawing pass, causing an infinite loop. This severe anti-pattern saturated the UI thread and consumed unnecessary CPU/battery.
**Action:** Removed the overridden `dispatchDraw`. Invalidations should only be triggered by external state or layout changes, never from within the rendering pipeline itself (`onDraw`, `dispatchDraw`).
## 2025-05-09 - Avoid Local Object Allocations in Hover/Touch Event Loops
**Learning:** Allocating collections like `listOf(...)` or lambdas inside high-frequency touch event or hover loops (like `updateButtonHoverStates`) causes high memory churn. This forces the Android Garbage Collector to run frequently, leading to skipped frames and UI jank.
**Action:** Extract constant data structures and object arrays (e.g., arrays of Resource IDs, lists of Triples/lambdas) out of the method into class-level or instance-level properties. Reuse them to completely eliminate allocations on the hot path.
## 2026-05-12 - Pre-allocate measurement objects in UI loops
**Learning:** Instantiating  or using  inside high-frequency touch loops (like checking ) causes heavy garbage collection churn. Destructuring lists inline is also an invalid construct for list of integers in Kotlin.
**Action:** Extract UI calculation objects like  and simple data structures to class-level properties. Use standard  loops instead of closures or unsupported destructuring when iterating.
## 2026-05-12 - Pre-allocate measurement objects in UI loops
**Learning:** Instantiating Rect or using .forEach inside high-frequency touch loops causes heavy garbage collection churn.
**Action:** Extract UI calculation objects like Rect to class-level properties. Use standard for loops instead of closures when iterating.
## 2025-05-14 - Prevent Object Allocation in Touch Methods
**Learning:** Frequent object allocation in high-frequency methods like `dispatchChatTouchEvent`, `isPointInChat`, `dispatchKeyboardTap`, `isPointInKeyboard`, `computeAnchoredKeyboardCoordinates`, etc. causes garbage collection and UI stutter. Using `IntArray(2)` and `Rect()` allocations creates excessive churn during cursor hover loops.
**Action:** Preallocate single instance objects like `IntArray(2)` and `android.graphics.Rect()` as class members to reduce object allocation.
## 2024-05-15 - Prevent Object Allocation in Touch Methods
**Learning:** Frequent object allocation in high-frequency methods like `updateCursorPosition`, `refreshHoverAtCurrentCursor`, `updateHoverLocal` causes garbage collection and UI stutter. Using `IntArray(2)` allocations creates excessive churn during cursor hover loops.
**Action:** Preallocate single instance objects like `IntArray(2)` as class members to reduce object allocation.
## 2024-05-14 - Mutable Shared State in Utility Functions
**Learning:** Returning a pre-allocated object (like `reusableRect`) directly from a helper function (like `getHitRect`) can create brittle code if the calling functions hold onto those references. Even if it works sequentially, it is an anti-pattern that can lead to shared mutable state bugs later.
**Action:** When pre-allocating objects for performance, functions should either return boolean results after doing calculations internally, copy values into caller-provided references (e.g. `outRect`), or restrict usage to immediately reading primitives before the next allocation.
## 2024-05-19 - [Fix Missing Permission in TAP Bluetooth Controller Log]
**Learning:** Adding a try catch inside TapLinkBluetoothControllerServer.kt caught a lint Missing Permission error for the bluetooth socket.remoteDevice.name log.
**Action:** When working on bluetooth code wrap bluetooth actions that are only logs into try catch to bypass unneeded permission issues.
## 2025-05-18 - [Avoid Iterator Allocations in High-Frequency Hover Loops]
**Learning:** In Android, iterating over collections using `.forEach` or `for (item in list)` implicitly creates an Iterator object. When this happens inside a high-frequency method like `updateButtonHoverStates` (which is called on every touch or hover event), it creates immense object churn. This leads to garbage collection pauses and dropped frames. Also, using `linkedSetOf` causes node allocation for each entry added, which further worsens memory fragmentation during hover state updates.
**Action:** Replace `linkedSetOf` with `ArrayList` and iterate through lists using indexed `for` loops (`for (i in 0 until list.size) { val item = list[i] }`) in high-frequency methods. Convert map values into cached lists to avoid allocating a new collection of values on every pass.
