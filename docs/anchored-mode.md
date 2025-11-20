# Anchored keyboard mode

Anchored keyboard mode pins the on-screen keyboard relative to the viewport instead of the controller. The cursor is the only moving element; taps are projected from the cursor into the keyboard so users can "poke" at keys while the keyboard remains stationary.

## Gesture interception

When the keyboard is visible and the layout reports `isAnchored = true`, `DualWebViewGroup.onInterceptTouchEvent` checks whether the cursor projection falls inside the keyboard bounds. Any qualifying `ACTION_DOWN`, `ACTION_MOVE`, or `ACTION_UP` event is intercepted and marked as an anchored gesture. 【F:app/src/main/java/com/TapLink/app/DualWebViewGroup.kt†L1592-L1650】

`onTouchEvent` then consumes the matching `ACTION_UP` event, recalculates the cursor-aligned keyboard coordinates, and forwards the tap to `CustomKeyboardView.handleAnchoredTap`. Drag and cancel events simply keep the interception state in sync. 【F:app/src/main/java/com/TapLink/app/DualWebViewGroup.kt†L1729-L1803】

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Tracking : ACTION_DOWN within bounds
    Tracking --> Tracking : ACTION_MOVE within bounds
    Tracking --> Dispatch : ACTION_UP (within bounds)
    Tracking --> Idle : ACTION_CANCEL / ACTION_UP (out of bounds)
    Dispatch --> Idle
```

## Listener routing

Once `handleAnchoredTap` is invoked, `CustomKeyboardView` resolves the button under the projected coordinates and emits an `OnKeyboardActionListener` callback. `MainActivity` implements this interface to distribute the action to the active surface (bookmark editor, URL bar, or WebView). 【F:app/src/main/java/com/TapLink/app/CustomKeyboardView.kt†L173-L260】【F:app/src/main/java/com/TapLink/app/MainActivity.kt†L3848-L3940】

| Input listener | Anchored mode behavior | Relevant implementation |
| --- | --- | --- |
| `handleDrag` (`CustomKeyboardView`) | **Ignored** – returns immediately when `isAnchoredMode` is `true`. | 【F:app/src/main/java/com/TapLink/app/CustomKeyboardView.kt†L648-L716】 |
| `handleFlingEvent` (`CustomKeyboardView`) | **Ignored** – flings short-circuit before moving focus. | 【F:app/src/main/java/com/TapLink/app/CustomKeyboardView.kt†L648-L681】 |
| `performFocusedTap` (`CustomKeyboardView`) | **Unused** – taps are routed through cursor projection, not focus. | 【F:app/src/main/java/com/TapLink/app/DualWebViewGroup.kt†L1785-L1803】 |
| `handleAnchoredTap` (`CustomKeyboardView`) | **Active** – resolves the key and triggers `handleButtonClick`. | 【F:app/src/main/java/com/TapLink/app/CustomKeyboardView.kt†L173-L260】 |
| `OnKeyboardActionListener` (`MainActivity`) | **Active** – receives callbacks such as `onKeyPressed`, `onBackspacePressed`, and `onEnterPressed`. | 【F:app/src/main/java/com/TapLink/app/MainActivity.kt†L3848-L3940】 |

## Result delivery

Depending on the callback, `MainActivity` performs one of three actions:

1. **Bookmark navigation/editing** – when the bookmarks drawer is expanded, `onKeyPressed` and `onBackspacePressed` target `BookmarksView.handleKeyboardInput`. 【F:app/src/main/java/com/TapLink/app/MainActivity.kt†L3848-L3908】
2. **URL editing** – if the inline URL field is visible, characters are inserted directly into `DualWebViewGroup.urlEditText`. 【F:app/src/main/java/com/TapLink/app/MainActivity.kt†L3848-L3899】
3. **Web content input** – otherwise, characters and commands are sent to the embedded `WebView` via JavaScript bridges. 【F:app/src/main/java/com/TapLink/app/MainActivity.kt†L3870-L3940】

This routing ensures anchored taps behave consistently regardless of which overlay is active.
