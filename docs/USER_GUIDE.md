# User Guide

This guide covers the core features, gestures, and settings of TapLink X3 for the RayNeo X3 Pro glasses, with step-by-step browser how-tos and exact button locations.

## Layout and Button Map

### Bottom Navigation Bar (horizontal bar along the bottom edge)
Buttons left-to-right:
1. **Back** (left arrow) - go to the previous page.
2. **Forward** (right arrow) - go to the next page.
3. **Home** (house) - open your home bookmark.
4. **URL** (link/chain) - open the URL field.
5. **Chat** (comment bubble) - open/close TapLink AI.
6. **Settings** (gear) - open the settings panel.
7. **Refresh** (rotate arrow) - reload the current page.
8. **Scroll Mode** (expand/fullscreen) - hide the UI for reading.
9. **Quit** (X) - close the app.

### Left Toggle Bar (vertical bar along the left edge)
Buttons top-to-bottom:
1. **Mode Toggle** (phone/desktop icon) - switch between mobile and desktop browsing mode.
2. **Dashboard** (glasses icon) - open the built-in AR dashboard page.
3. **Bookmarks** (bookmark icon) - open/close the bookmarks panel.
4. **Windows** (window icon) - open/close the windows overview (appears under Bookmarks when enabled).
5. **Zoom Out** (magnifying glass -) - decrease webpage zoom.
6. **Zoom In** (magnifying glass +) - increase webpage zoom.
7. **Blank Screen** (eye slash / eye) - toggle blank screen mode.
8. **Anchored Mode** (anchor / crossed anchor) - toggle anchored vs head-locked mode.

### Scroll Mode Restore
When Scroll Mode is active, a small transparent **Show** button appears in the bottom-right corner. Tap it to restore the bars.

## Touch Gestures
- **Single Tap**: Click links, buttons, and focus input fields.
- **Double Tap**: Navigation "Back" (same as the Back button).
- **Triple Tap** (Anchored Mode only): Re-center the display in front of your current view.

## Core Browser How-Tos

### Open a URL
1. Tap the **URL** (link) button on the bottom navigation bar.
2. The URL field appears at the top of the view (to the right of the left toggle bar).
3. Use the on-screen keyboard to type the URL.
4. Press **Enter** on the keyboard to navigate.

### Go Back / Forward
- Tap **Back** (left arrow) or **Forward** (right arrow) on the bottom navigation bar.

### Reload the Page
- Tap **Refresh** (rotate arrow) on the bottom navigation bar.

### Return Home
- Tap **Home** (house) on the bottom navigation bar to open your home bookmark.

### Quit the App
- Tap **Quit** (X) on the bottom navigation bar.

## Display Modes

### Anchored Mode (Anchor icon, left toggle bar)
1. Tap **Anchor** on the left toggle bar to enable Anchored Mode (icon shows an anchor).
2. The screen stays fixed in space; the cursor moves with your head.
3. **Re-center**: Triple tap anywhere to re-center the display.
4. **Adjust smoothness**: Open **Settings** and move the **Anchor Smoothness** slider.

### Non-Anchored Mode (Crossed Anchor icon)
1. Tap **Anchor** again to disable Anchored Mode (icon shows a crossed anchor).
2. The screen follows your head movement.
3. If **Screen Size** is below 100 percent, **Position** sliders appear in Settings to shift the display.

### Scroll Mode (Fullscreen/Expand icon, bottom navigation bar)
1. Tap **Scroll Mode** (expand icon) on the bottom navigation bar.
2. The UI hides for an immersive reading view.
3. To restore, tap the **Show** button in the bottom-right corner.

### Blank Screen Mode (Eye icon, left toggle bar)
1. Tap **Blank Screen** (eye slash) on the left toggle bar.
2. The display turns black while audio keeps playing.
3. **Unmask**: Tap the **Eye** button in the bottom-right corner.
4. **Media controls** (bottom center) appear when media is playing: previous track, 10s back, play/pause, 10s forward, next track.
5. Note: Anchored Mode is automatically disabled during blank screen mode.

## Reading and Zoom

### Webpage Zoom
1. Tap **Zoom In** or **Zoom Out** on the left toggle bar.
2. To reset zoom, open **Settings** and tap **Reset Webpage Zoom**.

### Scrollbars (non-anchored mode)
- When a page can scroll, thin scrollbars appear on the right or bottom edges.
- Tap or drag the scrollbar thumb to move through the page.

## Bookmarks

### Open the Bookmarks Panel
- Tap **Bookmarks** (bookmark icon) on the left toggle bar.

### Open a Bookmark
1. In the bookmarks panel, tap a bookmark URL row to open it.

### Set a Home Bookmark
1. Tap the **home** icon on the left side of a bookmark row.
2. The selected bookmark becomes the Home page.

### Add a Bookmark
1. Tap **+ Add** in the footer of the bookmarks panel.
2. An edit field appears. Type the URL.
3. Press **Enter** on the keyboard to save.

### Edit a Bookmark
1. Double tap the bookmark URL row you want to edit.
2. Edit the URL in the field that appears.
3. Press **Enter** to save changes.

### Delete a Bookmark
1. Tap the **X** button on the right side of a bookmark row (non-home rows only).

### Navigate Bookmark Pages
- Use **< Prev** and **Next >** in the footer to move between pages.

### Close Bookmarks
- Tap the **X** in the bookmarks header or tap the **Bookmarks** button again.

## Windows (Tabs)

### Open Windows Overview
- Tap the **Windows** (window icon) on the left toggle bar.

### Create a New Tab
1. In the windows overview, tap **Open New Tab** at the top.

### Switch Tabs
1. Tap a window thumbnail in the grid to switch to it.

### Close a Tab
1. Tap the **X** in the top-right of a window thumbnail.

### Exit Windows Overview
- Tap the **Windows** button again.

## Settings

### Open and Close Settings
- Tap **Settings** (gear) on the bottom navigation bar.
- In the settings header, use **Help** (question mark icon) or **Close** (X) on the right.
- The Help dialog includes a TapLink AI page with quick tips and requirements.

### Audio and Display
- **Volume** and **Brightness** sliders are in the left column.
- **Anchor Smoothness** controls anchored tracking stiffness.
- **Screen Size** changes the UI scale; use **Reset 100%** to restore.

### Text Appearance
- **Font Size** slider adjusts webpage text size.
- **Text Color** wheel lets you pick a text color for web content.
- Use **Reset Font Size** or **Reset Color** to revert.

### Position Controls (Non-Anchored, Screen Size below 100 percent)
- **Horizontal** and **Vertical** sliders appear in the right column.
- Use **Reset** to return to center.

### Groq API Key
1. Tap **Enter Groq API Key** in Settings.
2. Paste your key (using `scrcpy` makes this easier).
3. Confirm to enable TapLink AI and voice features.

## Voice Control and AI
- **Speech-to-Text**: Tap the **Mic** key on the on-screen keyboard to dictate into the active field.
- **TapLink AI**: Tap **Chat** (comment icon) on the bottom navigation bar to open the AI window. Tap again to close it.
- **Summarize**: Use the **Summarize** button in TapLink AI to recap the active webpage (dashboard/about:blank are not supported).
