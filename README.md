# TeleForward

Mirror selected Android notifications to a Telegram chat you control.

> **Vibe-coded project notice**  
> This app was built entirely with AI assistance (Claude) from scratch — it is not a manually maintained codebase.


TeleForward is a **single Android app with no server**. The app *is* the bot: it holds
your Telegram bot token, calls the [Telegram Bot API](https://core.telegram.org/bots/api)
(`https://api.telegram.org/bot<token>/…`) directly, and forwards the notifications you pick
to one paired chat. There is nothing to self-host — no Python service, no Docker, no public
endpoint. `api.telegram.org` is always reachable from the phone, and the bot token is the
only credential.

## What it is / how it works

A `NotificationListenerService` observes incoming notifications. Each one is checked against
your per-app, per-channel, and per-conversation filters *before* any content is read. Matches
are written to a local **outbox** (Room), and a WorkManager drain worker builds the message and
delivers it to Telegram — retrying with backoff until it succeeds, expires, or hits a terminal
error.

```
Notification posted
      │
      ▼
NotificationListenerService  ── cheap reject (own app, ongoing, group summary…)
      │
      ▼
FilterEngine   ── conversation rule → channel rule → whole-app rule + global pause
      │  (FORWARD only; non-matches never have their content read)
      ▼
Extract title / body / images  ──►  Outbox (Room)  ──►  WorkManager drain worker
                                                              │
                                                              ▼
                                            Telegram Bot API: sendMessage /
                                            sendPhoto / sendMediaGroup
                                                              │
                                                              ▼
                                                     Your paired chat
```

Send method is chosen from the payload: text only → `sendMessage`; one image → `sendPhoto`
(caption if the text fits, otherwise a separate message); 2–10 images → `sendMediaGroup`;
more are batched in groups of ten.

**Conversation detection** is best-effort. A notification is tied to a specific chat via its
conversation shortcut id (`conversationShortcutInfo` / `notification.shortcutId`) or a
conversation-specific channel id. For message notifications that publish none of these —
`MessagingStyle` or `CATEGORY_MESSAGE`, as some WhatsApp builds do — TeleForward falls back to
the stable per-chat notification tag (or, failing that, the chat title) so the same chat keys
consistently across notifications. An app that provides none of these signals simply degrades
to channel-level filtering.

## Requirements

- **Android 8.0 (API 26)** or newer.
- **Material You dynamic color** requires **Android 12 (API 31)+**; on older releases the app
  uses a built-in static Material 3 Expressive theme.
- A Telegram account and a bot token (see below).

## Build

The project is a flattened Android Studio / Gradle project with a single module, `:app`.

First, point Gradle at your Android SDK. Create `local.properties` in the repo root with:

```properties
sdk.dir=/path/to/your/Android/Sdk
```

`local.properties` is git-ignored and must not be committed.

Then, from the repo root:

```bash
# Build the debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Or build and install onto a connected device / emulator
./gradlew installDebug

# Run the JVM unit tests
./gradlew :app:testDebugUnitTest
```

Toolchain: AGP 9.2.1, Kotlin 2.3.21, KSP, JDK 17, `compileSdk 37` / `targetSdk 36` /
`minSdk 26`. UI is Jetpack Compose with Material 3 Expressive
(`androidx.compose.material3:material3:1.5.0-alpha23`, an alpha track). Persistence and
plumbing use Hilt, Room, Preferences DataStore, WorkManager, Retrofit + kotlinx-serialization,
and Coil 3.

## Create a Telegram bot

1. In Telegram, open a chat with [@BotFather](https://t.me/BotFather).
2. Send `/newbot` and follow the prompts (name, then a username ending in `bot`).
3. BotFather replies with an **HTTP API token** like `123456789:AA…`. Copy it — you'll paste
   it during onboarding.

## First-run setup (onboarding)

The app walks you through a short wizard:

1. **Welcome** — a brief privacy note.
2. **Grant notification access** — opens the system special-access screen; enable TeleForward
   there and return. The screen detects the grant automatically when you come back.
3. **Bot token** — paste the BotFather token. The app validates it with `getMe` and shows the
   resolved `@bot` username. The token is stored encrypted on-device.
4. **Pair your chat** — open your bot (there's a `t.me/<bot>` link) and press **Start**, then
   tap **Capture chat**. The app runs a one-time `getUpdates` poll to read your `chat_id` from
   the message you just sent. Alternatively, enter a numeric `chat_id` manually (e.g. from
   [@userinfobot](https://t.me/userinfobot)). Use **Send test message** to confirm delivery
   end to end.
5. **Allow notifications** (Android 13+) — optional `POST_NOTIFICATIONS` grant so the app can
   post its own status notification; you can skip it.

After finishing, open **Apps** in the main screen to choose what forwards.

> A bot can't message you until you've pressed **Start** (or otherwise messaged it) at least
> once. A manually entered recipient that hasn't done so will fail the first send with a clear
> "open the bot and press Start" error.

## Choosing what forwards

- **Per-app toggle** — enable forwarding for a whole app.
- **Per-channel toggles** — drill into an app to enable or disable individual notification
  channels. A channel only appears **after you've received at least one notification on it** —
  Android doesn't let one app enumerate another app's channels ahead of time, so the list is
  built from observed notifications.
- **Per-conversation toggles** — opening an app also shows a **Conversations** section below the
  channels, with a switch for each individual chat (e.g. a single WhatsApp conversation). This is
  best-effort (see *Conversation detection* above): a conversation only appears **after at least
  one notification has been received from that chat** — like channels, Android doesn't allow
  enumerating another app's conversations up front.
- **Precedence** — highest first: a per-conversation rule wins over its channel rule, which wins
  over the whole-app rule; with none of them, the notification is dropped. INCLUDE/EXCLUDE apply
  at every level, so you can, for example, forward a whole app (or channel) but **exclude** one
  chat, or forward a single chat out of a channel that's otherwise off. A **global Pause** stops
  all forwarding regardless of rules.

> **Upgrades preserve your selections.** When conversation support was added the notification
> database migrated in place (v1 → v2), so existing app- and channel-level rules survive an app
> update untouched.

Other controls live in **Settings**: replace the bot token, re-pair or change the recipient and
send a test, global Pause, include images, Wi-Fi-only delivery, outbox expiry window, clear the
delivery log, a listener-health indicator, and a shortcut to the battery-optimization settings.
The **Delivery log** lists recent outbox rows with status chips and a retry action for failed
or expired items.

## Magic links ✨

Some notifications are *about* something with a shareable URL — a new YouTube video, the song
that's playing, a WhatsApp chat — but the notification text almost never contains that URL. The
link lives inside the notification's tap action (a `PendingIntent`), which Android does **not**
let another app read.

**Magic links** reconstruct that URL from the identifying scraps a notification *does* expose (a
channel id, a track + artist, a chat identity) and append it to the forwarded message as a final
`Link: …` line — so a forward you read on your desktop is one click away from the real thing.

It is **best-effort and heuristic by design.** When a link can't be reconstructed *confidently*,
the item is forwarded normally, just without the extra line. A magic link is never a *wrong*
link: TeleForward would rather add nothing than send you to the wrong video or chat.

### Supported services

| Service | Read from the notification | Reconstructed link | How |
|---|---|---|---|
| **YouTube** | channel id + video title | `youtube.com/watch?v=…` | public uploads feed, with a search fallback |
| **Apple Music** | track + artist (now-playing) | `music.apple.com/…` (the song) | Apple's public iTunes Search API |
| **WhatsApp** | the chat's phone number | `web.whatsapp.com/send/…` (opens the chat) | phone from the chat identity, or a saved contact (opt-in) |

Packages covered: YouTube (`com.google.android.youtube` plus common re-packaged clients), Apple
Music (`com.apple.android.music`), and WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`).

### What to expect

- **Per-app toggle, on by default.** Open a supported app under **Apps** and you'll see a
  **Reconstruct magic link** switch. Turn it off to opt that app out; the choice is remembered.
- **It won't always land.** YouTube's feed and search results lag for busy channels; Apple Music
  needs an exact catalogue match; WhatsApp needs a resolvable phone number. A miss simply means
  no `Link:` line — never a broken one.
- **YouTube self-heals.** If the first attempt (made as the message is sent) misses, the item
  still forwards immediately, and a background worker keeps re-checking for up to about an hour,
  then **edits the already-sent Telegram message** to add the link once it resolves. (Apple Music
  and WhatsApp resolve instantly from a single lookup, so they don't need this.)

### WhatsApp specifics

WhatsApp Web can only open a chat by **phone number** — there is no URL that addresses a chat by
its internal id. Modern WhatsApp hides the number behind a privacy identifier (`…@lid`), so:

- **Unsaved contacts / older WhatsApp** expose the number directly (a `+…` title, or a
  `…@s.whatsapp.net` identity), and are linked with **no extra permission**.
- **Saved contacts on current WhatsApp** hide it; the only way back to the number is the sender's
  address-book entry. This is strictly **opt-in**: a **Resolve saved contacts** card on the
  WhatsApp settings page requests **Contacts** access — but only when you tap **Grant**, never
  automatically. With it granted, saved-contact chats become `web.whatsapp.com/send` links. The
  number **never leaves your device** except inside the link you forward to your own chat, and it
  is never written to the diagnostics logs.

## Security & privacy

- The **bot token** is encrypted at rest with a non-exportable AES-256-GCM key held in the
  Android Keystore (`SecretStore`); the key never leaves the keystore.
- Token/recipient screens set `FLAG_SECURE`, blocking screenshots, screen recording, and the
  recents preview while they're visible.
- The token is injected into requests by a path interceptor, so it never appears in URLs, logs,
  or annotations, and rotates cleanly.
- The app **only ever sends to your one paired chat** — a leaked token can't redirect forwards.
- **No analytics, no telemetry.** All traffic is TLS-only to `api.telegram.org` (cleartext is
  disabled via the network security config). Notification content is never logged, and content
  or images are never extracted for non-matching or paused notifications.
- `getUpdates` polling is used **only during pairing** (bounded); after pairing the app only
  ever sends.
- **Contacts access is optional and opt-in.** The one sensitive permission the app can request is
  `READ_CONTACTS`, used *solely* to turn a saved WhatsApp contact into a `web.whatsapp.com/send`
  link (see *Magic links*). It's requested only when you tap **Grant** on the WhatsApp settings
  card, checked live at send time, and the resolved number never leaves the device except inside
  the link you forward to your own chat.

## Known limitations

- **Single recipient.** One paired chat; no multiple destinations.
- **Per-conversation filtering is best-effort.** It relies on signals apps may or may not
  publish (conversation shortcut id, conversation channel, or a stable message-notification tag);
  an app that provides none of them degrades to channel-level, so individual chats won't be
  separable. As with channels, a conversation is only listed **after a notification has been
  seen** from that chat.
- A bot **cannot** message you until you press **Start** in it once.
- **Images are best-effort:** they're downscaled (~2048 px, JPEG) and subject to Telegram's
  limits (photo uploads up to ~10 MB, media groups of 2–10 items).
- **Background timing:** delivery may lag while the device is in Doze; the drain worker retries
  with exponential backoff (min 30 s) until it succeeds or the item expires.
- **Network:** in regions that block `api.telegram.org` you'll need a system-level VPN/proxy —
  the app has no built-in proxy.
- **OEM battery managers** may kill the notification listener. TeleForward requests a rebind,
  but disabling battery optimization for the app is recommended for reliable forwarding.
- Delivery dedup is local only; a network drop after Telegram accepts a message but before the
  app sees the response can rarely double-post.
- **Magic links are best-effort** (see *Magic links* above). They're reconstructed from
  notification metadata via public feeds/APIs, so they can miss — a lagging YouTube feed, no
  Apple Music catalogue match, or an unresolvable WhatsApp number — and simply add no link when
  they do. Only YouTube, Apple Music, and WhatsApp are supported.

## Project layout

Flattened Gradle project (Android Studio project at the repo root), single module `:app`.

```
teleforward/
├── settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml   # version catalog
├── local.properties                                                   # sdk.dir (git-ignored)
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                         # themes, strings, network security config, icons
        └── kotlin/space/linuxct/teleforward/
            ├── TeleForwardApp.kt  MainActivity.kt
            ├── designsystem/            # Compose theme, color, type, shared components
            ├── domain/                  # RawNotification, FilterEngine, models (pure Kotlin)
            ├── data/
            │   ├── db/                  # Room entities, DAOs, database
            │   ├── link/                # magic-link reconstruction (YouTube, Apple Music, WhatsApp)
            │   ├── settings/            # Preferences DataStore
            │   ├── secret/              # Keystore-backed SecretStore (bot token)
            │   ├── telegram/            # API client, DTOs, message builder, sender, pairing
            │   └── repo/                # repositories
            ├── service/                 # NotificationListenerService + mapper
            ├── work/                    # DeliveryWorker, LinkResolveRetryWorker (magic-link edit)
            ├── util/                    # notification-access helpers
            ├── di/                      # Hilt modules
            └── ui/                      # onboarding, apps, channels, settings, log, navigation
```

## Troubleshooting

**Nothing is forwarding.** Check, in order:

1. **Notification access** is still granted (Settings → Notification access → TeleForward). Some
   OEMs revoke it after the app is force-stopped or battery-optimized.
2. **Global Pause** is off (Settings).
3. The **app** is enabled, and the specific **channel** (or **conversation**) is enabled —
   remember a per-conversation switch overrides its channel, which overrides the whole-app
   setting. Channels and conversations only show up after at least one notification has been seen
   from them, and per-conversation detection is best-effort (some apps only expose channel-level).
4. You **pressed Start** in the bot — a bot can't deliver to a chat that hasn't messaged it.

Use **Settings → Send test message** to confirm the Telegram side works, and check the
**Delivery log** for per-item status and error messages. If items sit as failed with a network
error, verify connectivity (and the Wi-Fi-only setting) and that `api.telegram.org` is reachable.
