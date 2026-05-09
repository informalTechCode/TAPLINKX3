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
