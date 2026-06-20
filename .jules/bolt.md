## 2024-05-18 - Optimize list allocations in focus functions
**Learning:** `CustomKeyboardView` methods like `getFocusedKey`, `moveFocusRight`, etc., were continuously generating sequences and collections using `.children.filter { it.visibility == View.VISIBLE }.toList()` during high-frequency interaction. This caused significant iterator churn and GC pressure.
**Action:** Avoid inline sequence generation and collection extraction in hot UI loops. Replace filter/toList operations on view children with indexed `for` loop traversal over `row.childCount`, performing in-place visibility checks.
