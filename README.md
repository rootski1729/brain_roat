# 🧠 Brain Rot

A brain pet that lives on top of your Android screen. Every reel you watch and every hour of
scrolling makes it more restless and rotten. You can feed it and give it water, but what really
heals it is putting the phone down. Set limits and it will physically stand in your way.

| You asked for | What it does |
|---|---|
| Count reels on YouTube / Instagram | An accessibility service counts swipes in YouTube Shorts, Instagram Reels, TikTok and Snapchat Spotlight. |
| A character on top of the phone, like the desktop break-reminder cats | A floating brain sits above every app. You can drag it and it snaps to the edge. Tap it for a care panel (stats, feed, water, hide for 1 h, open the app). Long-press opens the app. There is also a home-screen widget. |
| Reel count and total phone time, with permission | Usage access gives per-app time and total screen time; the accessibility service gives reels. Both are shown on the Brain, Stats and widget screens. |
| Interactive: feed it, give it water | Walnut, blueberries, salmon and water, each with a cooldown and a "full" state. The brain chews, drinks and talks. |
| The more reels and screen time, the worse it gets | `rot` rises with reels, Shorts time and screen time. It goes through five stages (Fresh → Foggy → Restless → Fried → Rotten), changing colour, eyes, mouth, jitter, sweat, steam and flies. |
| Gets better with rest | Every minute with the screen off heals it. A night's sleep resets it. |
| Limits per app, reels, or whole phone; it moves around and won't let you use the app; reset only in the app | When Restless or worse, the brain wanders around the screen. When a limit is hit, a full-screen guard covers the app: the brain bounces around angrily, Back is captured, and the only ways out are leaving the app or unlocking inside Brain Rot after a breathing countdown that doubles each time (15 s, 30 s, 60 s … up to 5 min). |

Android only. iOS doesn't let apps draw over other apps, read other apps' screen time
(beyond Apple's own Screen Time UI) or detect reels, so none of these features can be built there.

## Build and run

Requirements: Android Studio Ladybug or newer (or JDK 17 + Android SDK 35).

```bash
./gradlew :core:test           # brain engine, limits, usage maths, reel counting, storage format
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug
```

On first launch, onboarding walks through the permissions:

| Permission | Why | Needed |
|---|---|---|
| Usage access | Per-app and total screen time | Yes |
| Display over other apps | The floating brain and the block screen | Yes |
| Accessibility (reel counting) | Counting reels, and seeing app switches instantly | Recommended. Without it there is no reel count, and blocking falls back to polling every 1.5 s. |
| Notifications | Status notification, and nudges when the overlay is off | Optional |
| Unrestricted battery | Stops aggressive OEMs (Xiaomi, Samsung, …) from killing the service | Optional |

**Sideloaded APKs (Android 13+):** Android greys out the accessibility switch for apps not
installed from a store. Open App info → ⋮ → *Allow restricted settings*, then try again. The
disclosure dialog has a button for this.

Release signing reads `keystore.properties` (ignored by git) from the repo root, with the keys
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Architecture

```
core/   pure Kotlin, no Android, fully unit-tested
  BrainEngine       vitals (rot, food, water, energy), stages, mood, care actions, tuning
  LimitEvaluator    which limit (if any) blocks the foreground app; nudges; unlock policy
  UsageAggregator   rebuilds per-app foreground time from raw usage events
  ReelCounter       turns surface/scroll signals into "one more reel"
  ShortVideoApps    view ids of each app's reel player
  AppData(+Codec)   the single persisted document, with day rollover and sanitising
  DetectionHealth   warns when reel detection has probably broken

app/
  BrainRotApp/AppGraph      process-wide singletons
  data/BrainStore           StateFlow<AppData>, written with AtomicFile, debounced 1 s
  data/UsageReader          UsageStatsManager → core events (+ foreground poller fallback)
  runtime/BrainRuntime      live state, reel/care/unlock actions, events for the overlay
  service/BrainService      foreground "heartbeat": engine ticks, screen on/off, limits, notification, widget
  service/ReelAccessibilityService   reels + instant app-switch detection; HOME/BACK on request
  overlay/                  BrainRenderer (procedural Canvas character), BrainView, OverlayController, BlockScreenView
  widget/                   home-screen widget (rendered bitmap of the brain)
  ui/                       Compose screens: Brain, Limits, Stats, Settings, Onboarding
```

The data flow is one-way. The accessibility service, UsageStats and user actions update
`BrainStore` (persisted) and `BrainRuntime.live` (in memory). `BrainService` combines the two
flows, evaluates limits and renders the overlay. The Compose UI watches the same flows.

### The brain model (`core/BrainEngine.kt`, rates in `BrainTuning`)

| Input | Effect |
|---|---|
| each reel | +0.6 rot |
| a minute inside Shorts/Reels | +0.45 rot (on top of screen time) |
| a minute of screen on | +0.12 rot, −0.15 energy |
| a minute of screen off (periods of 2 min or more) | −0.35 rot, +0.5 energy |
| food / water under 20 | rot builds 25 % faster |
| walnut / blueberries / salmon | +35 / +20 / +45 food, −1 / −3 / −2 rot, 30 min cooldown |
| water | +30 water, −1.5 rot, 10 min cooldown |

At these rates, 100 reels in about 40 minutes makes the brain Rotten, and 8 hours of sleep fully
heals it. The brain gets sick but never dies, so there's no shame spiral.

### Reel detection

`ReelAccessibilityService` reads only view ids, visibility and bounds. It never reads text.
- **The player is on screen** when a known id (for example `reel_recycler`, `clips_viewer_view_pager`)
  is visible and covers at least half the screen height. Entering it counts the first reel. Coming
  back within 30 s (from comments or the share sheet) does not count again.
- **Next reel:** a scroll event from the pager (or one of its 4 nearest parents), or from any
  scrollable view covering almost the whole screen.
  - If the app reports an adapter position, a reel counts only when the position passes the
    highest one seen so far. Swiping back to re-watch, or a cancelled swipe, never counts.
  - Otherwise a burst of scroll events counts once, with at least 800 ms between counts.
- **When an app update renames its ids:** the Brain screen shows a warning (`DetectionHealth`) if
  a short-video app was used for a while with zero reels counted. To refresh the ids, open the
  reels player, run `adb shell uiautomator dump`, find the full-screen pager and add its id to
  `core/.../ShortVideoApps.kt`. Keep the old ids.

The approach was informed by DetoxDroid, Scrolless, curbox and Mindful (all GPL), plus VPet,
Shimeji and ANeko for pet behaviour. **No code was copied**; everything here is an original
implementation.

### Limits and blocking

- **Reel limits** block only while the Shorts/Reels player is on screen, so normal YouTube and
  Instagram DMs keep working. A limit of N lets you finish reel N.
  - TikTok opens straight into its feed, so a TikTok reel limit blocks the whole app.
- **Per-app time limits** block the whole app. The **whole-phone screen-time limit** blocks every
  app except the launcher, phone/dialer, Settings, the permission controller and Brain Rot itself.
- **Leaving a block:**
  - "Take me out of here" goes Home.
  - "Leave Shorts / Reels" presses Back in the app, for reel limits.
  - "Unlock in Brain Rot…" opens the mindful wait, which grants +10 min or +5 reels for today.
- **Changing limits:** tightening is instant. Loosening (raising, removing or switching off
  enforcement) goes through the same wait. Grants and nudges expire at midnight.
- **Switching off reel counting** while a reel limit is set costs the same wait, since reel limits
  stop applying without it.
- **Without the overlay permission,** a breach still tries to leave the app and posts one
  notification explaining why. Leaving is reliable only with the accessibility service on, because
  Android may ignore a background HOME intent.
- **Calls are never covered:** the dialer and in-call screens (including Samsung's separate in-call
  package) are exempt from blocking. The foreground app is decided by the accessibility *window
  type*, so keyboards, System UI and our own overlays never count as switching apps.

## Observability and troubleshooting

- `adb logcat -s BrainRot` gives structured `event key=value` lines: `service_started`,
  `limit_block`, `limit_nudge`, `unlock_granted`, `catch_up`, `accessibility_connected`/`_disconnected`,
  `overlay_add_failed`, `store_load_failed`, …
  - No user text or screen content is ever logged.
  - Debug-level lines (such as each `reel_counted`) are stripped from release builds.
- **Settings → Diagnostics** shows:
  - whether the heartbeat service is running;
  - whether the accessibility service is connected;
  - the age of the last accessibility event, brain update and usage refresh.
- **Warning cards on the Brain screen:** missing permissions, a stopped service (with a Restart
  button), and suspected broken reel detection.
- **A corrupt state file** is kept as `brain_state.json.corrupt-<ts>` and the app starts fresh
  instead of crashing.

## Privacy

Everything stays on the device:
- The app has **no INTERNET permission**.
- It stores counts and settings only, in `files/brain_state.json`.
- It sees other installed apps only through `<queries>` for launcher and home intents, without
  `QUERY_ALL_PACKAGES`.

## Publishing on Google Play

- **Accessibility:** the service is not an accessibility tool (`isAccessibilityTool=false`).
  - Fill in the Play Console accessibility declaration.
  - Record the required video showing the in-app prominent disclosure (Onboarding → Reel
    counting), both accept and decline, and the reel counting itself.
- **Foreground service:** declare the `specialUse` service type in Play Console. The
  justification text is in the manifest property.
- **Battery:** the app opens the battery-optimisation *settings list*. It does not request
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Play restricts.

## Device QA checklist

Run this on a real device before release (CI can't do it):
1. Onboarding grants all permissions. The bubble appears and can be dragged, snaps to the edge,
   and keeps its position after a restart.
2. Tapping the bubble opens the panel. Feed and water make the brain chew and drink. Tapping
   outside closes the panel. "Hide 1h" hides the bubble.
3. Open YouTube Shorts and swipe 5 times: the count goes up by 5. Swiping back doesn't count.
   Opening comments and closing them doesn't count. Repeat for Instagram Reels.
4. Set a 3-reel limit. Reel 4 shows the block screen. Back is captured. "Leave Shorts" returns to
   YouTube home. The unlock wait runs, and the next unlock's wait doubles.
5. Set a 1-minute limit for some app. It is blocked after 1 minute, including when reopened from
   Recents.
6. Watch the stages and screen-off healing. Leave the screen off for 30 minutes: the brain comes
   back healthier and says so.
7. Reboot: the service comes back (`service_started` in logcat).
8. Dark mode, font scale 200 %, TalkBack on the main screens.

## Known limitations

- **Reel ids** depend on other apps' layouts and can break after their updates (see
  *Reel detection* for how to notice and fix it). Facebook Reels has no stable ids and is not
  counted.
- **Starting the service from the background** (from the accessibility service, after an update,
  or a sticky restart on Android 15) can be refused on Android 12+. The refusal is logged
  (`service_start_denied` / `service_foreground_failed`), the next app launch or boot starts it,
  and Diagnostics shows the state. Unrestricted battery makes this much rarer.
- **Clock changes:** a forward clock jump while the screen is on counts as at most 45 s of use,
  not hours.
- **Screen-off healing** is tracked live by the service. If the system kills the process, the gap
  is rebuilt from UsageStats when the service restarts (`catch_up`).
- **Android 17's Advanced Protection mode** disables non-tool accessibility services. Reel
  counting then stops, but time limits keep working through UsageStats polling.
