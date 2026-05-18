# MercurySDK API Reference
This document describes the public APIs of **MercurySDK** for Android applications, including the interfaces actually called in the Demo App. It is applicable for developing binocular mirrored UI and touch interaction on the RayNeo X3 AR glasses platform.
---
## Table of Contents
1. [Overview](#1-overview)
2. [Quick Start](#2-quick-start)
3. [Core APIs](#3-core-apis)
4. [Binocular Mirrored Display UI](#4-binocular-mirrored-display-ui)
5. [Focus & Touch](#5-focus--touch)
6. [Dialog & Toast](#6-dialog--toast)
7. [RecyclerView Support](#7-recyclerview-support)
8. [Extensions & Utilities](#8-extensions--utilities)
9. [Global Constraint Checklist](#9-global-constraint-checklist)
## 1. Overview
MercurySDK provides the following capabilities for RayNeo AR glasses:
- **Binocular mirrored layout**: Symmetric and synchronous update of left and right eye views, with support for 3D parallax effects.
- **Temple touch control**: Maps temple touch events to unified gestures (single click, double click, slide, etc.) for easy operation on touchless devices.
- **Focus management**: Switches focus via temple sliding/clicking, and handles focus and tracking in lists and pop-ups.

All **package names** in this document are rooted at `com.ffalcon.mercury.android.sdk`.

### 1.1 Class & Function Signature Summary (Reference Index)
The table below is for quick location of commonly used types and entry points, following the style of Android official Reference with "class overview + core signatures".

| Type                           | Package Path          | Core Signatures (Excerpts)                          | Function                                          |
|--------------------------------|-----------------------|-----------------------------------------------------|---------------------------------------------------|
| `MercurySDK`                   | `...sdk`              | `init(application: Application)`                    | SDK initialization entry point                    |
| `MobileState`                  | `...sdk.api`          | `isMobileConnected(): Flow<Boolean>`                | Mobile phone connection state monitoring          |
| `BindingPair<B>`               | `...sdk.core`         | `updateView { }` / `setLeft { }` / `checkIsLeft(...)`| Left-right layout mapping and synchronous update  |
| `make3DEffect`                 | `...sdk.core`         | `make3DEffect(leftView, rightView, enable, parallax)`| Binocular 3D parallax configuration               |
| `make3DEffectForSide`          | `...sdk.core`         | `make3DEffectForSide(view, isLeft, enable, parallax)`| Monocular 3D parallax configuration               |
| `BaseMirrorActivity<B>`        | `...sdk.ui.activity`  | `abstract class BaseMirrorActivity<B : ViewBinding>`| Activity-level binocular base class               |
| `FToast`                       | `...sdk.ui.toast`     | `show(...)` / `showCustom(...)`                     | Binocular Toast                                   |
| `FDialog.Builder<T>`           | `...sdk.ui.dialog`    | `setContentView(...)` / `setEventHandler(...)`      | Binocular Dialog builder                          |
| `TempleAction`                 | `...sdk.touch`        | `sealed class TempleAction`                         | Gesture semantic model                            |
| `TempleActionViewModel`        | `...sdk.touch`        | `state: SharedFlow<TempleAction>`                   | Gesture event stream distribution                 |
| `FocusHolder` / `FocusInfo`    | `...sdk.ui.util`      | `addFocusTarget(...)` / `currentFocus(...)`         | Universal focus item management                   |
| `FixPosFocusTracker`           | `...sdk.ui.util`      | `handleFocusTargetEvent(action)`                    | Fixed focus item switching logic                  |
| `RecyclerViewFocusTracker`     | `...sdk.ui.util`      | `handleActionEvent(it, block)`                      | Moving focus item list tracking                   |
| `RecyclerViewSlidingTracker`   | `...sdk.ui.util`      | `observeOriginMotionEventStream(...)`               | Fixed focus + follow-touch scrolling              |
| `StartSnapHelper`              | `...sdk.util`         | `StartSnapHelper(offset2Start)`                     | List start snap                                   |
| `DeviceUtil`                   | `...sdk.util`         | `isX3Device(): Boolean`                             | RayNeo X3 device judgment                         |
| `FLogger`                      | `...sdk.util`         | `d(...)` / `i(...)` / `e(...)`                      | Unified SDK logging                               |
---
## 2. Quick Start
### 2.1 Prerequisites (From RayNeo X3 ARSDK Document)
To ensure the normal operation of the APIs in this document, it is recommended to meet the following basic integration requirements first:
1. Enable ViewBinding (the SDK is encapsulated based on ViewBinding):
```groovy
buildFeatures {
    viewBinding = true
}
```
2. Add the following to the `application` node in the main module's `AndroidManifest.xml`:
```xml
<meta-data
    android:name="com.rayneo.mercury.app"
    android:value="true" />
```
The application may not be displayed in the glasses Launcher if this configuration is missing.

### 2.2 Initialize the SDK
Call the initialization once in `Application#onCreate`:
```kotlin
// In custom Application subclass
override fun onCreate() {
    super.onCreate()
    MercurySDK.init(this)
}
```
**Class**: `com.ffalcon.mercury.android.sdk.MercurySDK`

| Method                               | Description                                                                 |
|--------------------------------------|-----------------------------------------------------------------------------|
| `init(application: Application)`     | Initializes the SDK with the current Application, must be called before using other SDK capabilities. |
| `mApplication: Application`          | Read-only. Get the Application instance through this property after initialization. |

**Notes (Since / Threading / Lifecycle)**
- **Since**: Corresponding MercurySDK version line of the current repository (`v0.2.4` series).
- **Threading**: `init()` is recommended to be called only once in the main thread during the application startup phase.
- **Lifecycle**: It is recommended to complete initialization in `Application#onCreate`; repeated initialization will not report an error, but frequent calls during runtime are not recommended.

### 2.3 Recommended Page Interaction Conventions (From RayNeo X3 ARSDK Document)
- Focus switching: Usually use forward/backward slide.
- Trigger current focus action: Usually use single click.
- Page return: It is recommended to exit the current page with double click (consistent with Demo and SDK examples).
---
## 3. Core APIs
### 3.1 MobileState
**Package**: `com.ffalcon.mercury.android.sdk.api`
Used to observe the connection state with the RayNeo AR mobile App.

**Notes (Since / Threading / Lifecycle)**
- **Since**: `MobileState` provides connection state observation capability in X3 feature integration.
- **Threading**: The `Flow` can be collected in any coroutine context; switch back to the main thread for UI updates (or handle in the main thread environment of `lifecycleScope`).
- **Lifecycle**: Internally registers `ContentObserver` in `onStart` and automatically unregisters it in `onCompletion`; avoid long-term hanging collection in global coroutines without lifecycle constraints.

| Member                            | Type              | Description                                                              |
|-----------------------------------|-------------------|--------------------------------------------------------------------------|
| `METHOD_MOBILE_CONNECT_STATE`     | `String`          | ContentProvider method name constant: `"mobileConnectState"`.            |
| `isMobileConnected()`             | `Flow<Boolean>`   | Returns a Flow of the current mobile connection state, pushed automatically when the connection changes. |

**Example: Update UI based on BLE connection state**
```kotlin
MobileState.isMobileConnected()
    .onEach { connected ->
        mBindingPair.updateView {
            tvBleStatus.text = if (connected) "connect" else "disconnect"
        }
    }
    .launchIn(lifecycleScope)
```
---
### 3.2 DeviceUtil
**Package**: `com.ffalcon.mercury.android.sdk.util`
Device model judgment utility.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Added in the X3 adaptation phase to distinguish X2/X3 branch logic.
- **Threading**: `isX3Device()` is a lightweight synchronous call and can be invoked in any thread.
- **Lifecycle**: No binding to component lifecycle; it is recommended to read and cache the result in the page initialization phase for UI branch processing.

| Method                      | Description                     |
|-----------------------------|---------------------------------|
| `isX3Device(): Boolean`     | Determines if the current device is RayNeo X3. |

**Example**
```kotlin
if (DeviceUtil.isX3Device()) {
    tvDevicesType.text = "RayNeo X3"
    // Display BLE status, etc. only for X3
} else {
    tvDevicesType.text = "RayNeo X2"
}
```
---
## 4. Binocular Mirrored Display UI
### 4.1 BindingPair&lt;B : ViewBinding&gt;
**Package**: `com.ffalcon.mercury.android.sdk.core`
A pair of left and right ViewBindings based on ViewBinding, where operations on the left layout can be synchronously mapped to the right, applicable for binocular mirrored pages.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Serves as the basic capability for mirrored rendering, used across Activity/Fragment/View-level components.
- **Threading**: `update/updateView/setLeft` are UI operations and should be executed in the main thread.
- **Lifecycle**: `BindingPair` shares the same lifecycle with the left and right Bindings it holds; do not continue to reference internal Views after the page is destroyed.

| Member         | Type      | Description                                          |
|----------------|-----------|------------------------------------------------------|
| `left`         | `B`       | Left ViewBinding.                                    |
| `right`        | `B`       | Right ViewBinding.                                   |
| `PARALLAX`     | `Float`   | Default parallax value (3f) for 3D effects.          |

**Methods**
| Method                                                   | Description                                                                 |
|----------------------------------------------------------|-----------------------------------------------------------------------------|
| `update(block: T.() -> Unit)`                            | Executes `block` on both `left` and `right` for synchronous update of left and right views. |
| `updateView(block: T.() -> Unit)`                        | Same as `update`, semantically emphasizing "view update".                    |
| `setLeft(block: T.() -> Unit)`                           | Executes `block` only on the left (e.g., register left-side events only).    |
| `checkIsLeft(t: T): Boolean`                             | Determines if the current ViewBinding is the left one.                       |
| `enable3DEffect(vararg leftViews, enable, parallax)`     | Enables/disables 3D parallax in batches for a set of left Views; only pass **left** Views, otherwise a null pointer may occur. |

**Note**: Do not call `enable3DEffect` for the "right View" inside the block of `updateView` / `update`. The internal logic uses the left View as the key to find the right one, and passing the right View will return null.

---
### 4.2 make3DEffect (Top-Level Function)
**Package**: `com.ffalcon.mercury.android.sdk.core`
Sets or cancels 3D parallax (horizontal translation) for a pair of left and right Views.
```kotlin
@JvmOverloads
fun make3DEffect(
    leftView: View,
    rightView: View,
    enable: Boolean = true,
    parallax: Float = BindingPair.PARALLAX
)
```
| Parameter      | Description                                 |
|---------------|---------------------------------------------|
| `leftView`    | Corresponding View for the left eye.        |
| `rightView`   | Corresponding View for the right eye.       |
| `enable`      | `true` to apply parallax, `false` to reset to 0. |
| `parallax`    | Offset: left View moves right, right View moves left. |

---
### 4.3 make3DEffectForSide (Top-Level Function)
**Package**: `com.ffalcon.mercury.android.sdk.core`
Sets 3D parallax for a single View on one side (left or right), commonly used for single-side views such as list items and buttons.
```kotlin
@JvmOverloads
fun make3DEffectForSide(
    view: View,
    isLeft: Boolean,
    enable: Boolean = true,
    parallax: Float = BindingPair.PARALLAX
)
```
| Parameter     | Description                                          |
|--------------|------------------------------------------------------|
| `view`       | The View to set parallax for.                        |
| `isLeft`     | `true` indicates the View belongs to the left layout. |
| `enable`     | Whether to enable parallax.                          |
| `parallax`   | Offset value.                                        |

**Example: Focus highlight + 3D for list items or buttons**
```kotlin
pair.updateView {
    val isLeft = pair.checkIsLeft(this)
    triggerFocus(hasFocus, btnOk, isLeft)  // e.g., change background color
}
// Inside custom triggerFocus:
make3DEffectForSide(view, isLeft, hasFocus)
```
---
### 4.4 ViewPair&lt;T : View&gt;
**Package**: `com.ffalcon.mercury.android.sdk.core`
Encapsulation of a pair of left and right Views of the same type, inherited from `BaseMirrorAction<T>`, providing `update`, `setLeft`, `checkIsLeft`, etc. Commonly used for synchronous operations of two left and right RecyclerViews.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Applicable for general scenarios of paired operations on left and right Views.
- **Threading**: Calls involving View updates should be made in the main thread.
- **Lifecycle**: Holds strong references to Views; it is recommended to hold only during the page's active period and avoid caching in static singletons.

**Construction**
```kotlin
ViewPair(mBindingPair.left.recyclerView, mBindingPair.right.recyclerView)
```
---
### 4.5 BaseMirrorActivity&lt;B : ViewBinding&gt;
**Package**: `com.ffalcon.mercury.android.sdk.ui.activity`
An Activity base class with left and right mirrored layout: automatically generates two left and right Bindings according to the generic type `B` and places them in a horizontally equally divided root layout; it also inherits temple touch control and gesture distribution (see [BaseEventActivity / Touch](#5-focus--touch)).

**Notes (Since / Threading / Lifecycle)**
- **Since**: Standard base class for binocular mirrored Activities.
- **Threading**: Gesture collection and UI updates are executed in the main thread in accordance with Android component conventions.
- **Lifecycle**: It is recommended to perform UI initialization in `onCreate`, start collecting events in `onStart/onResume`, and release resources such as players/sensors in `onStop/onDestroy`.

| Member                      | Type                      | Description                                                                 |
|-----------------------------|---------------------------|-----------------------------------------------------------------------------|
| `mBindingPair`              | `BindingPair<B>`          | Pair of left and right ViewBindings, used for `updateView`, `enable3DEffect`, `checkIsLeft`, etc. |
| `templeActionViewModel`     | `TempleActionViewModel`   | ViewModel for temple gestures, collect gestures through the `state` Flow.    |

**Usage**
1. Inherit and specify the ViewBinding type, e.g., `BaseMirrorActivity<ActivityApiBinding>()`.
2. Update the left and right UI in `onCreate` via `mBindingPair.updateView { ... }`.
3. Collect and handle single clicks, double clicks, slides, etc. in `lifecycleScope` with `templeActionViewModel.state.collect { ... }`.

**Note**: The root layout intercepts touch events and delivers them to the Activity's `onTouchEvent` to ensure unified processing of temple gestures.

---
### 4.6 MirrorContainerView
**Package**: `com.ffalcon.mercury.android.sdk.ui.wiget`
A non-abstract, directly usable left and right mirrored container View (LinearLayout with left and right columns arranged horizontally). It does not depend on generics and dynamically binds the ViewBinding type via `bindTo`.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Used for quick integration of View-level binocular mirrored scenarios.
- **Threading**: `bindTo()` and subsequent View updates should be executed in the main thread.
- **Lifecycle**: `bindTo()` adds left and right subtrees to the container; it is usually recommended to call once per instance; avoid repeated stacking on repeated calls by yourself.

| Method                                             | Description                                                               |
|----------------------------------------------------|---------------------------------------------------------------------------|
| `bindTo(bindingClz: Class<B>): BindingPair<B>`     | Generates two left and right layouts using the specified ViewBinding class, adds them to the container, and returns `BindingPair<B>`. |

**Example (Inside FToast, FDialog, etc.)**
```kotlin
val toastView = MirrorContainerView(context).apply {
    val pair = bindTo(FfalconToastBinding::class.java)
    pair.updateView { tvToast.text = msg }
    make3DEffect(left.tvToast, right.tvToast, true, 15f)
}
```
---
### 4.7 BaseMirrorContainerView<B : ViewBinding>
**Package**: `com.ffalcon.mercury.android.sdk.ui.wiget`
A mirrored container base class that supports inheritance, specifies the ViewBinding via generics, and requires subclasses to implement `onInit()` for initialization. It holds `mBindingPair: BindingPair<B>` internally, with usage similar to `mBindingPair` in `BaseMirrorActivity`.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Used for custom View inheritance systems of binocular mirrored containers.
- **Threading**: Initialization and view updates must be performed in the main thread.
- **Lifecycle**: Initialization is triggered in the construction phase; avoid accessing unready external dependencies early in the construction (e.g., size information before attaching to the Window).

---
### 4.8 BaseMirrorFragment<B, H>
**Package**: `com.ffalcon.mercury.android.sdk.ui.fragment`
A Fragment-level mirrored base class that uses `HolderPair<B, H>` and `BindingPair<B>` to manage left and right Holders and Bindings. Suitable for implementing left and right symmetric UI and event synchronization in Fragments, with usage similar to Activity mirroring.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Used for binocular mirrored encapsulation at the Fragment granularity.
- **Threading**: Fragment UI callbacks and `mBindingPair` updates should be executed in the main thread.
- **Lifecycle**: It is recommended to follow the pattern of creation in `onCreateView` and releasing View references in `onDestroyView` to avoid accessing Bindings beyond the View lifecycle.
---
## 5. Focus & Touch
### 5.1 IFocusable
**Package**: `com.ffalcon.mercury.android.sdk.focus`
A focus capability interface for custom focus objects (e.g., `focusObj` of FixPosFocusTracker, trackers of RecyclerView, etc.).

**Notes (Since / Threading / Lifecycle)**
- **Since**: Abstract interface for the unified focus system.
- **Threading**: It is recommended to read and write `hasFocus` in the main thread to ensure consistency with the UI state.
- **Lifecycle**: `focusParent` is recommended to point to a reachable object on the current page; disconnect cross-page focus reference chains when the page is destroyed.

| Property       | Type            | Description                                                              |
|----------------|-----------------|--------------------------------------------------------------------------|
| `hasFocus`     | `Boolean`       | Whether the current object has focus.                                    |
| `focusParent`  | `IFocusable?`   | Parent focus object; the focus can be returned to the parent object when releasing focus. |

---
### 5.2 reqFocus / releaseFocus (Extension Functions)
**Package**: `com.ffalcon.mercury.android.sdk.focus`
```kotlin
fun IFocusable.reqFocus(parent: IFocusable? = null)
fun IFocusable.releaseFocus()
```
- `reqFocus(parent)`: Sets the current object as focused; if `parent` is passed, `focusParent` will be set.
- `releaseFocus()`: Cancels the current focus and calls `focusParent?.reqFocus()` to return the focus to the parent object.

---
### 5.3 FocusHolder
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
A fixed-order focus list manager: maintains a set of `FocusInfo`, supports `next()` / `previous()` for focus switching, and allows looping.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Core management class for focus list switching.
- **Threading**: Internal state is not thread-safe; it is recommended to call only in the main thread.
- **Lifecycle**: List items are usually consistent with the page View lifecycle; it is recommended to synchronously verify the validity of the current focus item after dynamically deleting items.

| Constructor Parameter        | Description                                   |
|-----------------------------|-----------------------------------------------|
| `loop: Boolean = false`     | Whether to switch in a loop (wrap around after reaching the start/end). |

**Methods**
| Method                                                | Description                                                                 |
|-------------------------------------------------------|-----------------------------------------------------------------------------|
| `addFocusTarget(vararg focusInfoList: FocusInfo)`     | Adds items that can participate in focus switching.                         |
| `removeFocusTarget(target: Any)`                      | Removes the focus item associated with `target`; switches to the next item if the current focus is exactly this item. |
| `currentFocus(target: Any)`                           | Sets the current focus to the item corresponding to the specified `target` (commonly used to set the default focus). |
| `next()`                                              | Switches to the next item.                                                   |
| `previous()`                                          | Switches to the previous item.                                               |

**Properties**
| Property           | Type          | Description                  |
|--------------------|---------------|------------------------------|
| `currentFocusItem` | `FocusInfo`   | The item with current focus. |

---
### 5.4 FocusInfo
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
Represents a focus item that can be managed by FocusHolder.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Basic unit of the focus model.
- **Threading**: `eventHandler` and `focusChangeHandler` are triggered by the gesture distribution thread by default; it is recommended to only perform main thread UI operations in actual use.
- **Lifecycle**: `target` is often a View; ensure it is still attached to a valid page before executing focus changes.

**Construction**
```kotlin
FocusInfo(
    target: Any,                              // Arbitrary object for identification (e.g., View)
    eventHandler: (TempleAction) -> Unit,       // Temple gesture callback
    focusChangeHandler: (hasFocus: Boolean) -> Unit  // Callback when focus is gained/lost
)
```
| Method               | Description                                 |
|----------------------|---------------------------------------------|
| `fetchFocus()`       | Internally calls `focusChangeHandler(true)`, indicating focus gain. |
| `releaseFocus()`     | Internally calls `focusChangeHandler(false)`, indicating focus loss. |

**Example**
```kotlin
FocusInfo(
    btnAdd,
    eventHandler = { action ->
        when (action) {
            is TempleAction.Click -> addDynamicFocus()
            else -> Unit
        }
    },
    focusChangeHandler = { hasFocus ->
        mBindingPair.updateView {
            triggerFocus(hasFocus, btnAdd, mBindingPair.checkIsLeft(this))
        }
    }
)
```
---
### 5.5 FixPosFocusTracker
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
Fixed position focus switching tracker: Switches focus in the FocusHolder's list according to temple sliding (or single-step up/down/left/right), and can pass unhandled gestures to the `eventHandler` of the current focus item.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Used for gesture-driven switching of fixed focus items (or fixed areas).
- **Threading**: `handleFocusTargetEvent()` needs to be in the same thread as UI focus updates (main thread recommended).
- **Lifecycle**: Responds to events only when `focusObj.hasFocus == true`; switch this state in a timely manner when the page is switched or a pop-up seizes the focus.

**Construction**
```kotlin
FixPosFocusTracker(
    focusHolder: FocusHolder,
    continuous: Boolean = false,   // Whether to switch according to continuous sliding distance
    isVertical: Boolean = true,    // true for vertical sliding switch, false for horizontal
    ignoreDelta: Int = IGNORE_DELTA
)
```
| Member                                             | Type                              | Description                                                                 |
|----------------------------------------------------|-----------------------------------|-----------------------------------------------------------------------------|
| `focusHolder`                                      | `FocusHolder`                     | Managed focus list.                                                         |
| `focusObj`                                         | `IFocusable`                      | Used to indicate "whether the current tracker has focus", can be bound to the interface (e.g., for RecyclerView or the entire page). |
| `onFocusChangeListener`                            | `OnTrackerFocusChangeListener?`   | Focus change callback.                                                      |
| `handleFocusTargetEvent(action: TempleAction)`     | Method                            | Executes `next`/`previous` or forwards to the current `FocusInfo.eventHandler` according to `TempleAction` when focus is gained. |

**Constant**: `IGNORE_DELTA = 50`, the switch is triggered only when the distance exceeds this value in continuous sliding mode.

**Example**
```kotlin
val focusHolder = FocusHolder(true)
focusHolder.addFocusTarget(focusInfo1, focusInfo2)
focusHolder.currentFocus(btnAdd)
fixPosFocusTracker = FixPosFocusTracker(focusHolder).apply {
    focusObj.hasFocus = true
}
// In the collect of templeActionViewModel.state:
fixPosFocusTracker?.handleFocusTargetEvent(it)
```
---
### 5.6 addFocusView (BindingPair Extension)
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
Dynamically adds a pair of left and right focus Views to an existing mirrored layout, adds them to the specified `FocusHolder`, and automatically creates `FocusInfo`, synchronizes left and right views and 3D effects.
```kotlin
fun <T : ViewBinding, V : View> BindingPair<T>.addFocusView(
    parent: ViewGroup,
    viewFactory: () -> V,
    focusHolder: FocusHolder,
    focusConfig: FocusConfig<V>.() -> Unit = {}
): FocusViewHandle<V>
```
| Parameter      | Description                                                                 |
|---------------|-----------------------------------------------------------------------------|
| `parent`      | Parent container in the left layout (only pass the left one); the corresponding parent container on the right will be automatically found through Binding mapping. |
| `viewFactory` | Factory for creating a single View (called twice to generate left and right respectively). |
| `focusHolder` | The FocusHolder to add to.                                                  |
| `focusConfig` | Configuration for `FocusConfig<V>`.                                         |

**FocusConfig&lt;V&gt;** Optional Configuration:
| Property                | Type                                  | Description                                                              |
|-------------------------|---------------------------------------|--------------------------------------------------------------------------|
| `eventHandler`          | `((TempleAction) -> Unit)?`           | Gesture processing for this focus item.                                  |
| `onFocusChange`         | `((V, Boolean, Boolean) -> Unit)?`    | (view, hasFocus, isLeft) Update appearance when focus changes.           |
| `autoRequestFocus`      | `Boolean`                             | Whether to request focus immediately after addition.                     |
| `layoutParamsFactory`   | `((ViewGroup, V) -> LayoutParams)?`   | Custom LayoutParams for left and right sub Views.                        |

**Return Value**: `FocusViewHandle<V>`, which can be used to remove or request focus later.

---
### 5.7 FocusViewHandle<V : View>
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
Returned by `addFocusView`, used to manage dynamically added focus Views.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Matching handle for dynamic focus item capabilities.
- **Threading**: `clearFocusView()/updateView()` involve View tree modifications and should be executed in the main thread.
- **Lifecycle**: It is recommended to clean up unused dynamic focus items before the page is destroyed to prevent residual invalid references.

| Method                                | Description                                                                 |
|---------------------------------------|-----------------------------------------------------------------------------|
| `clearFocusView()`                    | Removes the left and right Views from the parent container and removes the focus item from the FocusHolder. |
| `requestFocus()`                      | Makes the FocusHolder set the current focus to this View.                   |
| `updateView(block: V.() -> Unit)`     | Executes `block` on both left and right Views simultaneously for synchronous update. |

---
### 5.8 TempleAction (Sealed Class)
**Package**: `com.ffalcon.mercury.android.sdk.touch`
Represents various gestures generated by temple touch control, received in `templeActionViewModel.state` or callbacks of Dialog/RecyclerView.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Unified semantic model for gesture streams of BaseEventActivity.
- **Threading**: Actions are data objects and can be passed across threads; however, the main thread must be used for UI updates after consumption.
- **Lifecycle**: It is recommended to collect in combination with `repeatOnLifecycle` to avoid continuing to consume gesture events when the page is invisible.

| Subtype                                           | Description                     |
|---------------------------------------------------|---------------------------------|
| `Idle`                                            | No operation                    |
| `Click`                                           | Single click                    |
| `LongClick`                                       | Long click                      |
| `DoubleClick`                                     | Double click                    |
| `TripleClick`                                     | Triple click                    |
| `SlideBackward(args)`                             | Slide backward (e.g., right slide) |
| `SlideForward(args)`                              | Slide forward (e.g., left slide) |
| `SlideUpwards(args)`                              | Slide upwards                   |
| `SlideDownwards(args)`                            | Slide downwards                 |
| `SlideContinuous(delta, longClick, vertical)`     | Continuous slide, `delta` is the difference from the press point |
| `MoveUp(isLongClick)`                             | Finger lift                     |
| `ActionUp`                                        | End of event sequence starting from DOWN |
| `ActionDown`                                      | Press down                      |
| `DoubleFingerClick` / `DoubleFingerLongClick`     | Double-finger click / long click |

All Actions in the form of data classes have `consumed: Boolean`, which can be marked as consumed in the business to avoid repeated processing.

**Slide Direction Semantic Description (Supplemented by X3 Document)**
The actual trigger directions of `SlideForward` and `SlideBackward` are affected by the system's "natural mode/non-natural mode" settings.
It is recommended that business logic does not hardcode these two as fixed "left/right slides", but designs interactions according to the "forward/backward" semantics, or provides user-configurable mapping in the settings page.

### 5.9 CommonTouchCallback (Compatibility Note)
**Package**: `com.ffalcon.mercury.android.sdk.touch`
Although the App does not directly implement this class, it is the underlying callback interface for event distribution of `BaseTouchActivity`. According to the X3 document and source code, the following capabilities can be noted:

**Notes (Since / Threading / Lifecycle)**
- **Since**: X3 supplements vertical sliding, double-finger and axis filtering capabilities.
- **Threading**: Callbacks are usually in the input event processing chain; it is recommended to keep them lightweight and return as soon as possible.
- **Lifecycle**: If the callback object is held by a custom component by itself, the association must be released when the component is destroyed to avoid continuing to receive events.

- New vertical sliding: `onTPSlideUpwards`, `onTPSlideDownwards`
- New double-finger events: `onTPDoubleFingerClick`, `onTPDoubleFingerLongClick`
- `onTPSlideContinuous(delta, longClick, vertical)` adds the `vertical` parameter
- `filterMode` (`NoFilter` / `OnlyX` / `OnlyY`) takes effect only on X3 and can filter continuous sliding axis data

> For the app calling surface covered in this document, these capabilities will eventually be mapped to the corresponding subtypes of `TempleAction` and uniformly consumed by `TempleActionViewModel.state`.

---
### 5.10 TempleActionViewModel
**Package**: `com.ffalcon.mercury.android.sdk.touch`
ViewModel for receiving and distributing temple gestures in Activity.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Cooperates with `BaseEventActivity` to provide gesture event stream distribution.
- **Threading**: Uses `viewModelScope` and Flow internally; the subscription side switches threads as needed, and the main thread is recommended for UI subscriptions.
- **Lifecycle**: The ViewModel lifecycle follows the host component; it is recommended to collect `state` in the `RESUMED` or `STARTED` state.

| Member             | Type                         | Description                                                         |
|--------------------|------------------------------|---------------------------------------------------------------------|
| `userTempleAction` | `Channel<TempleAction>`      | Channel for sending gestures (generally sent internally by BaseEventActivity). |
| `state`            | `SharedFlow<TempleAction>`   | Subscribe to this Flow to handle gestures.                          |

**Example**
```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        templeActionViewModel.state.collect { action ->
            when (action) {
                is TempleAction.DoubleClick -> finish()
                is TempleAction.Click -> showDialog()
                else -> Unit
            }
        }
    }
}
```
---
### 5.11 actionName (MotionEvent Extension)
**Package**: `com.ffalcon.mercury.android.sdk.ui.activity` (in the same file as `BaseTouchActivity`)
```kotlin
fun MotionEvent.actionName(): String
```
Converts `MotionEvent.action` into a human-readable string (e.g., `"ACTION_DOWN"`, `"ACTION_UP"`), convenient for logging or debugging.
---
## 6. Dialog & Toast
### 6.1 FToast
**Package**: `com.ffalcon.mercury.android.sdk.ui.toast`
Toast with support for binocular mirroring and 3D effects.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Universal binocular mirrored prompt component of the SDK.
- **Threading**: `show()` is recommended to be called in the main thread.
- **Lifecycle**: Depends on `MercurySDK.mApplication`; `MercurySDK.init()` must be completed first; Toast is a short-lived transient UI and should not carry critical business processes.

| Method                                                                      | Description                          |
|-----------------------------------------------------------------------------|--------------------------------------|
| `show(msg: String, short: Boolean = true, yOffset: Int = 200.dp)`           | Displays text Toast.                 |
| `show(msgResId: Int, short: Boolean = true, yOffset: Int = 200.dp)`         | Uses string resource ID.             |
| `showCustom(msg, short, yOffset, bindingClz, initViewBlock)`                | Uses custom ViewBinding layout and 3D initialization. |

**Example**
```kotlin
FToast.show("Click Confirm")
FToast.show(R.string.message, short = false, yOffset = 300.dp)
```
---
### 6.2 FDialog
**Package**: `com.ffalcon.mercury.android.sdk.ui.dialog`
Dialog with support for binocular mirroring, temple touch control and focus switching.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Universal binocular mirrored pop-up component of the SDK.
- **Threading**: Builder configuration, `show()/dismiss()` and view updates must all be executed in the main thread.
- **Lifecycle**: `dismiss()` cancels the internal coroutine scope; it is recommended to ensure the pop-up is closed and related resources are released before the host's `onDestroy`.

**Construction / Usage**: Configured in a chain via `FDialog.Builder<T : ViewBinding>`.

| Builder Method                                                              | Description                                                                 |
|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `setContentView(bindingClz, initViewBlock, params)`                         | Sets the content layout (ViewBinding class), initialization block and optional LayoutParams. |
| `setFocusTracker(focusTracker: FocusTracker)`                               | Sets the focus switching logic inside the dialog.                            |
| `setCancelable(cancelable: Boolean)`                                        | Whether the dialog can be canceled via the back key.                        |
| `setCanceledOnTouchOutside(cancel: Boolean)`                                | Whether the dialog can be canceled by clicking outside.                     |
| `setOnDismissListener(onDismiss)`                                           | Dismiss callback.                                                           |
| `setOnShowListener(onShow)`                                                 | Show callback.                                                               |
| `setEventHandler(handler: (TempleAction, DialogInterface) -> Unit)`         | Global gesture processing (e.g., double click to close); button-level events are processed in the TrackInfo of FocusTracker. |
| `build(): FDialog`                                                          | Builds the FDialog.                                                          |

**Builder Properties** (available after `setContentView`):
- `mPair: BindingPair<T>`: Left and right Bindings of the dialog content.
- `mFocusTracker: FocusTracker?`: The set focus tracker.

**Example**
```kotlin
FDialog.Builder<DialogTestBinding>(this)
    .setCancelable(true)
    .setCanceledOnTouchOutside(true)
    .setContentView(DialogTestBinding::class.java) { pair, dialog ->
        // Initialize pair.left / pair.right
    }
    .apply {
        val tracker = FocusTracker(true).apply {
            addFocusTarget(btnOkTrackInfo, btnCancelTrackInfo)
        }
        tracker.currentFocus(pair.left.btnCancel)
        setFocusTracker(tracker)
    }
    .setEventHandler { action, dialog ->
        if (action is TempleAction.DoubleClick) dialog.dismiss()
    }
    .build()
    .show()
```
---
### 6.3 FocusTracker (for Dialog)
**Package**: `com.ffalcon.mercury.android.sdk.ui.dialog`
Similar to `FocusHolder`, but used inside FDialog: manages a list of `TrackInfo`, supports `next()` / `previous()` and looping.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Specialized management class for the Dialog focus system.
- **Threading**: Focus switching and UI feedback should be executed in the main thread.
- **Lifecycle**: Should be used with the same lifecycle as a single Dialog instance; reusing the same instance across Dialogs is not recommended.

| Construction                | Description                  |
|-----------------------------|------------------------------|
| `FocusTracker(loop: Boolean)`| Focus loops when `loop == true`. |

| Method                                                | Description                  |
|-------------------------------------------------------|------------------------------|
| `addFocusTarget(vararg trackInfoList: TrackInfo)`     | Adds items for focus switching. |
| `currentFocus(target: Any)`                           | Sets the current focus item. |
| `next()` / `previous()`                               | Switches focus.              |

---
### 6.4 TrackInfo
**Package**: `com.ffalcon.mercury.android.sdk.ui.dialog`
Configuration of a single focusable item inside FDialog.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Focus item model for Dialog.
- **Threading**: Ensure the main thread is used for UI or dismiss operations in `eventHandler`.
- **Lifecycle**: `target` is recommended to be bound to the current content view of the Dialog; this configuration should be considered invalid after the Dialog is destroyed.

**Construction**
```kotlin
TrackInfo(
    target: Any,
    eventHandler: (action: TempleAction, dialog: DialogInterface) -> Unit,
    focusChangeHandler: (hasFocus: Boolean) -> Unit,
    isSelected: Boolean = false
)
```
- `eventHandler`: Processing when the control receives temple gestures (e.g., Click to confirm/cancel and dismiss).
- `focusChangeHandler`: Update UI when focus is gained/lost (need to use `pair.updateView` + `make3DEffectForSide` internally to synchronize left and right).
---
## 7. RecyclerView Support
### 7.1 RecyclerViewFocusTracker
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
RecyclerView focus and scroll tracker based on **continuous sliding distance**: Switches the currently selected item and scrolls according to the `delta` of `SlideContinuous`, suitable for lists with "focus following sliding".

**Notes (Since / Threading / Lifecycle)**
- **Since**: Core tracker for moving focus item lists.
- **Threading**: All RecyclerView and Adapter operations must be performed in the main thread.
- **Lifecycle**: It is recommended to process `handleActionEvent()` while the page is visible; set `focusObj.hasFocus` to `false` when the page is invisible or out of focus.

**Construction**
```kotlin
RecyclerViewFocusTracker(
    mPair: ViewPair<RecyclerView>,
    ignoreDelta: Int = IGNORE_DELTA,
    loop: Boolean = false
)
```
| Member                  | Type                              | Description                                                               |
|-------------------------|-----------------------------------|---------------------------------------------------------------------------|
| `mPair`                 | `ViewPair<RecyclerView>`          | Two left and right RecyclerViews.                                         |
| `focusObj`              | `IFocusable`                      | Whether to have focus (e.g., the list only responds when the entire page gains focus). |
| `currentSelectPos`      | `Int`                             | Current selected item position (writable via `setCurrentSelectPos`).      |
| `onItemFocusListener`   | `OnItemFocusListener?`            | Callback for selected item changes.                                       |
| `onFocusChangeListener` | `OnTrackerFocusChangeListener?`   | Callback for focus gain/loss.                                             |
| `refreshListener`       | `PullToRefreshListener?`          | Triggers "pull down to refresh", etc. when sliding forward continuously at the first item. |

**Methods**
| Method                                                                   | Description                                                                 |
|--------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `setCurrentSelectPos(index: Int)`                                        | Sets the current selected index.                                            |
| `handleActionEvent(it: TempleAction, block: (TempleAction) -> Unit)`     | Processes gestures when focused; switches items on continuous sliding, and forwards Click/DoubleClick, etc. to `block`. |
| `checkedSelectPos(): Int`                                                | Returns the current selected position when focused, otherwise -1.           |
| `checkPosSelected(pos: Int): Boolean`                                    | Determines whether a position is currently selected and has focus.          |
| `notifyDataSetChanged()`                                                 | Calls `notifyDataSetChanged()` for the adapters of both left and right RecyclerViews. |

**Example**
```kotlin
favoriteTracker = RecyclerViewFocusTracker(
    ViewPair(mBindingPair.left.recyclerView, mBindingPair.right.recyclerView),
    ignoreDelta = 70
)
favoriteTracker.focusObj.hasFocus = true
// In state.collect:
favoriteTracker.handleActionEvent(it) { action ->
    when (action) {
        is TempleAction.Click -> { /* Click the current item */ }
        is TempleAction.DoubleClick -> finish()
        else -> {}
    }
}
```
---
### 7.2 RecyclerViewSlidingTracker
**Package**: `com.ffalcon.mercury.android.sdk.ui.util`
RecyclerView tracker based on **slide snap**: Relies on `SnapHelper` to determine the currently selected item when scrolling stops, and can subscribe to original touch events to achieve "follow-touch" scrolling.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Used for fixed focus + follow-touch scrolling scenarios.
- **Threading**: Event conversion, `dispatchTouchEvent`, and Adapter refresh need to be performed in the main thread.
- **Lifecycle**: The original event stream should be automatically started and stopped with the page focus state after registration; ensure no more events are received and forwarded before leaving the page.

**Construction**
```kotlin
RecyclerViewSlidingTracker(mPair: ViewPair<RecyclerView>)
```
**Methods**
| Method                                                           | Description                                                                 |
|------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `observeOriginMotionEventStream(dispatcher, eventTransform)`     | Registers to `MotionEventDispatcher`, converts temple events into RecyclerView touch events to achieve follow-touch scrolling. |
| `setCurrentSelectPos(index: Int)`                                | Sets the current selected index.                                            |
| `handleActionEvent(it, block)`                                   | Processes gestures; forwards Click/DoubleClick to `block`, and `SlideBackward` at the first item can trigger `refreshListener`. |
| `smoothScrollToPosition(smoothScroll)`                           | Scrolls to `currentSelectPos`.                                              |
| `notifyItemChanged(pos)` / `notifyDataSetChanged()`              | Refreshes the specified item or the entire list.                            |

**Note**: It is necessary to set a `SnapHelper` (e.g., `StartSnapHelper`) for the RecyclerView, and set it via `setTag(R.id.tag_snap_helper, snapHelper)` to ensure `findSelectedPosition` works correctly. `R.id.tag_snap_helper` in the SDK is defined in MercurySDK's `res/values/id.xml`.

**Example**
```kotlin
favoriteTracker = RecyclerViewSlidingTracker(
    ViewPair(mBindingPair.left.recyclerView, mBindingPair.right.recyclerView)
)
favoriteTracker.observeOriginMotionEventStream(motionEventDispatcher) { event ->
    MotionEvent.obtain(
        event.downTime, event.eventTime, event.action,
        320f, event.x, event.metaState
    )
}
recyclerView.apply {
    val snapHelper = StartSnapHelper(41.dp)
    snapHelper.attachToRecyclerView(this)
    setTag(com.ffalcon.mercury.android.sdk.R.id.tag_snap_helper, snapHelper)
}
```
---
### 7.3 StartSnapHelper
**Package**: `com.ffalcon.mercury.android.sdk.util`
Inherits from `LinearSnapHelper`, makes the RecyclerView snap to the first visible item in the **start direction** (or with an offset), suitable for lists with "fixed focus items".

**Notes (Since / Threading / Lifecycle)**
- **Since**: Used for list start snap scenarios on glasses.
- **Threading**: As a RecyclerView UI component, it is only used in the main thread.
- **Lifecycle**: It is recommended to create and destroy it synchronously with the RecyclerView; it can be reattached as needed after replacing the Adapter/LayoutManager.

**Construction**
```kotlin
StartSnapHelper(offset2Start: Int)
```
- `offset2Start`: Offset relative to the start edge (e.g., 41.dp), used to align the snap point to the center of the first item, etc.

**Usage**: `attachToRecyclerView(recyclerView)`, and set `setTag(R.id.tag_snap_helper, snapHelper)` when used with `RecyclerViewSlidingTracker`.

---
### 7.4 BaseBindingHolder<T : ViewBinding> / SimpleBindingAdapter<B : ViewBinding>
**Package**: `com.ffalcon.mercury.android.sdk.ext`
- **BaseBindingHolder**: A `RecyclerView.ViewHolder` that holds a ViewBinding, access the layout via `binding`.
- **SimpleBindingAdapter**: An Adapter based on generic ViewBinding, inflates the corresponding Binding via reflection in `onCreateViewHolder`, and returns `BaseBindingHolder<B>`.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Basic encapsulation of RecyclerView + ViewBinding.
- **Threading**: Adapter lifecycle callbacks run in the main thread; avoid heavy calculations in `onBindViewHolder`.
- **Lifecycle**: Reflective inflation depends on generic signatures; the generic structure should be kept correct during obfuscation/refactoring.

When list items need left and right synchronization, the 3D effect and selected state can be updated in the Adapter according to `BindingPair.checkIsLeft` or the tracker's `checkPosSelected`.
---
## 8. Extensions & Utilities
### 8.1 dp / sp (Extension Properties)
**Package**: `com.ffalcon.mercury.android.sdk.ext`
Converts values to density-independent px (based on `Resources.getSystem().displayMetrics`).
```kotlin
val Int.dp: Int
val Float.dp: Float
val Int.sp: Int
val Float.sp: Float
```
**Example**: `200.dp`, `41.dp`, `15f.dp`.

---
### 8.2 setViewVisible
**Package**: `com.ffalcon.mercury.android.sdk.ext`
```kotlin
fun setViewVisible(visible: Boolean, vararg views: View)
```
Unifiedly sets multiple Views to `View.VISIBLE` or `View.GONE`.

---
### 8.3 FLogger
**Package**: `com.ffalcon.mercury.android.sdk.util`
Unified logging utility with the TAG `"MercurySDK"`.

**Notes (Since / Threading / Lifecycle)**
- **Since**: Built-in unified logging facade of the SDK.
- **Threading**: The interface can be called in any thread; long logs will be output in segments.
- **Lifecycle**: `isDebug` can be updated at runtime; it is recommended to call `updateLogSwitch()` to synchronize the state after debugging switch actions.

| Method                                     | Description                                                              |
|--------------------------------------------|--------------------------------------------------------------------------|
| `updateLogSwitch()`                        | Updates `isDebug` according to BuildConfig and `log.tag.MercurySDK`.     |
| `v(tag, msg)` / `v(msg)`                   | Verbose log level.                                                       |
| `d(tag, msg)` / `d(msg)`                   | Debug log level.                                                         |
| `i(tag, msg)` / `i(msg)`                   | Info log level.                                                          |
| `w(tag, msg)` / `w(msg)`                   | Warn log level.                                                          |
| `e(tag, msg, t?)` / `e(msg)` / `e(t?)`     | Error log level (supports throwable).                                    |
| `printStack()`                             | Prints the current call stack.                                           |

Log content is accompanied by the call location (class name, method name, file name, line number), convenient for jumping in Logcat.

---
### 8.4 Development & Debugging Suggestions (From RayNeo X3 ARSDK Document)
- It is recommended to set the theme `windowBackground` to pure black (`#FF000000`) for a more natural perspective effect.
- Release high-frequency capabilities such as sensors, Camera, and GPS in `onPause`/`onDestroy` in a timely manner to avoid background power consumption and resource occupation.
- For monocular screen projection debugging, refer to the `scrcpy --crop` solution in the SDK document.
---
## 9. Global Constraint Checklist
This section abstracts the various `Since / Threading / Lifecycle` constraints in the preceding text into a pre-release checklist for easy review, testing and external release.

### 9.1 Threading Constraint Checklist
| Check Item                      | Applicable Objects                                                                                      | Mandatory Requirements                                                               |
|--------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| SDK initialized once in main thread | `MercurySDK`                                                                                            | Call `init()` in `Application#onCreate`, avoid repeated initialization during runtime. |
| All View/Binding updates in main thread | `BindingPair`, `BaseMirrorActivity`, `MirrorContainerView`, `BaseMirrorContainerView`, `BaseMirrorFragment` | `updateView`, `setLeft`, `bindTo`, focus style updates, etc. all in the main thread.  |
| Gesture event stream consumption and UI modification in the same thread | `TempleActionViewModel`, `TempleAction`, `CommonTouchCallback`                                            | Events can be passed across threads, but the main thread must be used for UI modifications after consumption. |
| RecyclerView operations in main thread | `RecyclerViewFocusTracker`, `RecyclerViewSlidingTracker`, `StartSnapHelper`, `SimpleBindingAdapter`        | Including `notifyDataSetChanged`, scrolling, `dispatchTouchEvent` forwarding, `attachToRecyclerView`. |
| Dialog and Toast called only in main thread | `FDialog`, `FToast`                                                                                      | `show()` / `dismiss()` / Builder configuration and view updates all in the main thread. |
| Logging allowed across threads, avoid heavy logging in hot paths | `FLogger`                                                                                               | Reduce log volume on demand for high-frequency paths to avoid affecting input response. |

### 9.2 Lifecycle Constraint Checklist
| Check Item                  | Applicable Objects                                                           | Mandatory Requirements                                                          |
|----------------------------|------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| Event collection bound to visible lifecycle | `TempleActionViewModel` (consumer side)                                      | Use `repeatOnLifecycle(STARTED/RESUMED)` to stop consumption when the page is invisible. |
| Automatic release of connection state monitoring | `MobileState`                                                              | Collect via controlled coroutines; ensure the internal `ContentObserver` is unregistered after the Flow completes. |
| Correct transfer of focus state during page switching | `IFocusable`, `FocusHolder`, `FixPosFocusTracker`, `FocusTracker`             | Switch `hasFocus` in a timely manner when pages/pop-ups are switched to avoid "invisible pages still responding to gestures". |
| Cleanup of dynamic focus items before destruction | `addFocusView`, `FocusViewHandle`                                           | Call `clearFocusView()` when no longer in use to avoid residual Views and references. |
| Follow-touch event stream started/stopped with focus | `RecyclerViewSlidingTracker`                                               | Stop forwarding original events when leaving the page or losing focus.            |
| Component resources released according to Android lifecycle | Camera/Sensor/GPS/Player scenarios                                           | Perform corresponding release in `onPause` / `onStop` / `onDestroy` to avoid power consumption and leaks. |

### 9.3 Release Review Checklist
```markdown
## Release Review Checklist
### Threading
- [ ] SDK initialized once in the main thread (call `MercurySDK.init()` in `Application#onCreate`)
- [ ] All View/Binding updates executed in the main thread (`updateView` / `setLeft` / `bindTo`)
- [ ] UI modifications after gesture event stream consumption executed in the main thread
- [ ] RecyclerView-related operations in the main thread (scrolling, refresh, event forwarding, SnapHelper attachment)
- [ ] Dialog and Toast called only in the main thread (`show` / `dismiss` / Builder configuration)
- [ ] Log volume reduced for high-frequency paths to avoid affecting input response

### Lifecycle
- [ ] Gesture event collection bound to visible lifecycle (`repeatOnLifecycle(STARTED/RESUMED)`)
- [ ] `MobileState` connection monitoring automatically released after page leave (no unbounded hanging collection)
- [ ] Focus state correctly transferred during page/pop-up switching (avoid invisible pages responding to gestures)
- [ ] Dynamic focus items cleaned up before destruction (call `clearFocusView()`)
- [ ] Follow-touch event stream started/stopped with focus (stop forwarding after leaving the page or losing focus)
- [ ] Camera/Sensor/GPS/Player resources correctly released in `onPause/onStop/onDestroy`
```
---