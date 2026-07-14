## 2024-05-19 - [Avoid Kotlin Higher-Order Functions in Hot Paths]
**Learning:** In high-frequency paths (like URL interception in `MainActivity.kt`), using Kotlin higher-order collection functions such as `.any {}`, `.filter {}`, and `.map {}` instantiates `Iterator` objects and allocates closures, causing excessive GC churn.
**Action:** Replace them with zero-allocation indexed `for` loops (e.g., `for (i in 0 until list.size)`) when working with lists in performance-sensitive methods.
