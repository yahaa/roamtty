# Remote desktop gestures

How touches on the desktop page become mouse input on the host, after Apple's trackpad.
The engine is `DesktopGestures` (desktop-core/src/main/kotlin/io/itermux/desktop/DesktopGestures.kt), the same
engine as iOS's (roamtty-ios `docs/DESKTOP-GESTURES.md`), with positions in pixels and sizes in dp. Its unit tests
(`DesktopGesturesTest`) follow the tables below row by row, and `DesktopGestureLiveTest` (androidTest) makes every
gesture with real multi-touch against a live host (see "Acceptance" below).

## 手势速查

两种操作方式在 操作 › 操作方式 里切换：**鼠标指针**（触控板模式，手指像在触控板上推光标）和
**屏幕触控**（像用手机一样：点哪里就是哪里，单指滑动滚动内容）。

### 点击（手指数量 = 鼠标按键）

| 手势 | 鼠标指针 | 屏幕触控 |
|---|---|---|
| 单指轻点 | 在光标处左键单击，立即生效 | 在手指落点左键单击，立即生效 |
| 单指连点两下 | 双击 | 双击；第二下偏几个点也落在同一像素上 |
| 双指轻点 | 在光标处右键 | 在两指中间右键 |
| 三指轻点 | 中键 | 在三指中间中键 |

### 按住（圆环填满并震动一下：单指蓝色，双指红色）

| 手势 | 效果 |
|---|---|
| 单指按住，不动就松手 | 右键 |
| 单指按住，然后移动 | 左键拖动，松手放下（屏幕触控从手指落点开始拖） |
| 双指按住，然后移动 | 右键拖动 |

### 移动和滚动

| 手势 | 鼠标指针 | 屏幕触控 |
|---|---|---|
| 单指滑动 | 移动光标，越快走得越远 | 滚动手指下面的内容，快速一划后带惯性；放大后先平移画面，到边缘再滚动 |
| 双指滑动 | 滚动远端，快速一划后带惯性继续滚 | 同左；放大后也是滚动，不平移画面 |

### 画面和快捷操作

| 手势 | 效果 |
|---|---|
| 捏合 | 缩放画面（最大 5 倍），边捏边移动可平移；只在手机上，不发给电脑 |
| 双指连点两下 | 在当前位置放大 2.5 倍 / 恢复全屏 |
| 从画面边缘向内滑 | 光标送到电脑屏幕那一侧的边缘（Dock、任务栏、菜单栏）；从角落滑入则送到角落（触发角）。以画面的边为准，不是屏幕的边 |
| 三指上滑 | 所有窗口：调度中心 / 任务视图 / 活动概览 |
| 三指下滑 | 显示桌面 |
| 三指左 / 右滑 | 切换到右边 / 左边的桌面 |
| 四指及以上 | 不处理 |

放大后画面会自动跟随：鼠标指针模式下光标进入画面外圈 15% 时画面跟着移；屏幕触控模式下拖动到画面边缘时画面自动滑动。

返回：从屏幕左右边缘的**黑边**（画面外面的区域）向内滑即是系统返回，和其他页面一样；返回键同样一次就退出。
从画面上开始的边缘滑动留给远程桌面（把光标送到边缘）。竖屏时画面上下是黑边，横屏时画面左右是黑边；
画面放大铺满时没有黑边，用 操作 › 返回。Android 每条边最多让出 200 dp 给应用，画面更高时，
超出部分的边缘滑动仍是系统返回。

## Principles

1. **The number of fingers is the mouse button**: one finger left, two right, three middle.
2. **Taps act at once.** Nothing waits to find out whether a drag follows. The one exception is the
   two-finger tap: its right click waits 300 ms, because a second two-finger tap zooms instead.
3. **Holding picks the button up**, as touch-and-hold does on a phone: a ring fills under the finger,
   then turns solid with a haptic tap. Lift without moving for a right click; move to drag.
4. **The picture and the host are separate**: pinching zooms the picture only and never reaches the
   host; two fingers sliding always scroll the host, zoomed in or not.
5. **The phone answers first**: the cursor is the phone's own and moves before the host answers; holds and shortcuts
   have haptics and a ring.
6. **The picture owns its edges**: an edge swipe that starts on the picture is the desktop's (a system gesture
   exclusion over the picture only); from the black bars beside it, the system's back gesture works as on any
   page, with the bars hidden too (`BEHAVIOR_DEFAULT` on this page: hidden bars would otherwise take the first
   swipe). Android grants an app at most 200 dp of exclusion per edge. 操作 › 返回 always leaves.

## The gestures

### Clicks

| Gesture | Trackpad mode (鼠标指针) | Touch mode (屏幕触控) |
|---|---|---|
| One-finger tap | Left click where the cursor is | Left click where the finger landed |
| One-finger double tap | Double click | Double click; a second tap within 300 ms and 20 dp lands on the first one's pixel |
| Two-finger tap | Right click at the cursor | Right click between the fingers |
| Three-finger tap | Middle click | Middle click between the fingers |

### Holds (350 ms for one finger, 500 ms for two; blue ring for left, red for right)

| Gesture | Effect |
|---|---|
| Hold one finger, lift without moving | Right click |
| Hold one finger, then move | Left-button drag; lifting drops. Touch mode presses where the finger landed |
| Hold two fingers, then move | Right-button drag |

### Moving and scrolling

| Gesture | Trackpad mode | Touch mode |
|---|---|---|
| One finger slides | Moves the cursor, faster the faster the finger | Scrolls what is under the finger, with inertia, as on a phone (the cursor goes there first, since the host scrolls what is under its cursor). Zoomed in, it moves the picture first, as a zoomed photo, and scrolls the host only past the picture's edge |
| Two fingers slide | Scrolls the host, with inertia after a flick | Same, zoomed in too; the cursor goes between the fingers first |

### The picture and shortcuts (on the phone, not sent as mouse input)

| Gesture | Effect |
|---|---|
| Pinch | Zooms the picture (up to 5×); moving while pinching moves it |
| Two-finger double tap | Zooms in 2.5× on that spot, or back out to the whole screen |
| Swipe in from an edge of the picture | Puts the cursor on the host's edge there (Dock, taskbar, menu bar); from a corner, in the corner (hot corners). The picture's edge, not the view's: from a black bar at the screen's edge, it is the system's back |
| Three fingers up | Every window: Mission Control / Task View / Activities |
| Three fingers down | Show the desktop |
| Three fingers left / right | The desktop on the right / left: ⌃→ on macOS, Ctrl+Win+→ on Windows, Ctrl+Alt+→ on Linux |
| Four or more fingers | Nothing |

Zoomed in, the picture follows: in trackpad mode it keeps the cursor out of the outer 15% of the
view; in touch mode, a drag held near the picture's edge slides it along (up to 600 dp/s).

## Parameters

| | Value |
|---|---|
| Movement before a touch stops being a tap or hold | 10 dp |
| Hold | 350 ms (one finger), 500 ms (two: they often rest before scrolling) |
| Double tap | 300 ms, 20 dp |
| Multi-finger tap | lifted within 350 ms, no finger moved 10 dp |
| Scroll / pinch decided by | centroid moving 12 dp / span changing 24 dp |
| Edge zone | within 16 dp of the picture's edge (as shown in the view), moving inward |
| Three-finger swipe | centroid moving 40 dp |
| Pointer gain | clamp(0.8 × (v / 150 dp/s)^0.6, 0.5, 4), in picture pixels per finger pixel |
| Wheel | 2.5 / scale (dp per native pixel) units (1/120 notch) per dp, 2…12; sent three or more at a time |
| Inertia | a scroll view's normal deceleration, 0.998 per ms; none after a pause over 60 ms or below 50 dp/s |
| Haptics | hold and pickup: `LONG_PRESS`; right / middle click, shortcuts, 显示桌面 / 所有窗口: `KEYBOARD_TAP`; switching mode: `VIRTUAL_KEY` |

## The cursor

Moves go out as absolute positions (`INPUT abs`), from a cursor the phone keeps. The host adds no
acceleration of its own (Windows and X11 would to relative moves), so the cursor the phone draws is
exactly where the host's is, with no round trip. The host's own position (`CURSOR_POS`) is taken
whenever the phone has not moved the cursor for 500 ms: an app warped it, or someone used the
host's mouse. The phone draws the host's cursor image (`CURSOR_SHAPE`) at its hot spot, at the
picture's scale, kept between 15 and 24 dp; the video carries no cursor. The hold ring appears only after
200 ms, so a tap shows none.

## The host side

On macOS the plugin must set the click count (`kCGMouseEventClickState`) on presses and releases,
or no double click ever registers and window buttons ignore clicks (roamtty-desktop
`fix/mac-click-count`). Wheel units below a notch are smooth on macOS and Windows and add up to whole
notches on X11 and Wayland.

## Acceptance

`DesktopGestureLiveTest` runs the tables against a real host with real multi-touch, injected through
UiAutomation on the real clock, and reads what the page sent from `DesktopInputProbe`; screenshots of the host's
picture show each effect (a menu, a scroll, the overview, the desktop). Arguments: `gHost`, `gPort`, `gUser`,
`gPassword`, `gOs` (LINUX / WINDOWS / MACOS), `gKeyType` and `gKey` (the host key, as in `ssh-keyscan`).

```
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class io.itermux.app.desktop.DesktopGestureLiveTest \
  -e gHost … -e gUser … -e gPassword … -e gOs LINUX -e gKeyType ssh-ed25519 -e gKey … \
  com.roamtty.app.test/io.itermux.app.ItermuxTestRunner
```

Tests: `touchModeGestures`, `trackpadModeGestures`, `showDesktopButtonEveryTap` (显示桌面 six times: sent at once,
the picture changes each time) and `swipeBackFromOutsideThePicture` (portrait and landscape). The system's back
gesture ignores touches injected through UiAutomation, so the screen-edge swipes there go through `input swipe`.
The page's timers (holds, the waiting right click, inertia) run on a main-looper `Handler` on the uptime clock, not
a coroutine delay, whose clock a Compose test holds still.
