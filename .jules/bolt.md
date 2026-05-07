## 2024-05-07 - Avoid invalidate() in onDraw()
**Learning:** Calling `invalidate()` from within `onDraw()` in Android custom views causes infinite redraw cycles, heavily impacting CPU usage and battery performance.
**Action:** Never call `invalidate()` or trigger view invalidations during `onDraw()`. Trigger redrawing only in response to state changes (e.g., touch events or animation callbacks).
