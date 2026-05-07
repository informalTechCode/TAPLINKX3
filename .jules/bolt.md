## 2024-05-07 - Avoid invalidate() in onDraw()
**Learning:** Calling `invalidate()` from within `onDraw()` in Android custom views causes infinite redraw cycles, heavily impacting CPU usage and battery performance.
**Action:** Never call `invalidate()` or trigger view invalidations during `onDraw()`. Trigger redrawing only in response to state changes (e.g., touch events or animation callbacks).
## 2026-05-06 - [Infinite Re-render Loop in Android UI Component]
**Learning:** Overriding `onDraw()` in an Android custom view just to call `invalidate()` on child components triggers an infinite loop of render passes, severely impacting CPU and battery life. `invalidate()` schedules a redraw, so calling it inside the redraw logic creates a cycle.
**Action:** Never call `invalidate()` or trigger layout passes from within an `onDraw` method. Rely on state changes outside of the draw cycle to call `invalidate()`.
