## 2024-05-19 - [Avoid Kotlin Higher-Order Functions in Hot Paths]
**Learning:** In high-frequency paths (like URL interception in `MainActivity.kt`), using Kotlin higher-order collection functions such as `.any {}`, `.filter {}`, and `.map {}` instantiates `Iterator` objects and allocates closures, causing excessive GC churn.
**Action:** Replace them with zero-allocation indexed `for` loops (e.g., `for (i in 0 until list.size)`) when working with lists in performance-sensitive methods.
## $(date +%Y-%m-%d) - [Optimize High-Frequency Collection Allocations]
**Learning:** Found that methods like `getMediaControlWebView` and `handleMediaStateChanged` in `DualWebViewGroup.kt` were unnecessarily utilizing higher-order collection functions like `.any {}`, `.filterValues {}`, `.keys`, and `.maxByOrNull {}` on a map structure `mediaStateByWindowId` inside frequently accessed loops (like URL interception and touch handling), causing GC churn.
**Action:** Always prefer zero-allocation indexed `for` loops (`for (i in 0 until list.size)`) when iterating collections in Android views, avoiding standard higher-order functional mappings or iterative map structures within high-frequency paths.
