## 2026-05-06 - [Infinite Re-render Loop in Android UI Component]
**Learning:** Overriding `onDraw()` in an Android custom view just to call `invalidate()` on child components triggers an infinite loop of render passes, severely impacting CPU and battery life. `invalidate()` schedules a redraw, so calling it inside the redraw logic creates a cycle.
**Action:** Never call `invalidate()` or trigger layout passes from within an `onDraw` method. Rely on state changes outside of the draw cycle to call `invalidate()`.
