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

## Features

| | What it does | Default |
|---|---|---|
| **Selective forwarding** | Choose what forwards per app, per notification channel, and per individual conversation, with INCLUDE/EXCLUDE precedence and a global pause. | — |
| **Images & contact photos** | Forwards attached images; the sender's avatar / app logo is a separate opt-in. | images on, photos off |
| **Magic links ✨** | Rebuilds the URL a notification is *about* — the YouTube video, the song playing, the WhatsApp or Signal chat, the Discord DM, the Telegram message, the GitHub issue, the Bluesky post — and appends it as a `Link:` line, since Android won't let the app read the notification's own tap action. | on per supported app |
| **Remote actions 🎛️** | Inline buttons under each forwarded message that act on the phone: dismiss, mark read, reply, or any button the source app itself offers. You can also just reply to the forwarded message. | **on** |
| **Now playing 🎵** | One live message per media app with album art, transport buttons, and a 🔗 universal song link for the current track — a remote control for whatever is playing. | off |
| **Always listening** | Keeps a permanent connection so button presses act instantly, instead of only during a short window after each forward. | off |
| **Diagnostics** | On-demand forensic dump for troubleshooting, redacted of message and reply text. | off |

Everything above is local to your phone and your one paired chat. There is no account, no
telemetry, and no third party in the path other than Telegram itself.

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

**The return path.** Remote actions make the flow two-way, and having no server means there is
nothing for Telegram to call back into — so the app asks. It long-polls `getUpdates` to learn that
you pressed a button or replied, then re-finds the notification on the device and fires the real
action. By default it only listens for a few minutes after each forward (the window in which you'd
plausibly press something); *Always listening* upgrades that to a foreground service. This is the
one part of TeleForward that isn't purely reactive, which is why both its cost and its scheduling
are described in *Remote actions* below.

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
5. **What you'll get** — introduces the two things that are already switched on: *magic links*
   (the real link added to forwarded messages) and *action buttons* (act on the phone from
   Telegram). Action buttons can be switched off right here; both are changeable later.
6. **Allow notifications** (Android 13+) — optional `POST_NOTIFICATIONS` grant so the app can
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
send a test, global Pause, include images, **include contact photos**, skip ongoing notifications,
Wi-Fi-only delivery, outbox expiry window, clear the delivery log, a listener-health indicator, and
a shortcut to the battery-optimization settings. A **Remote actions** section holds the action
buttons, now playing and always-listening switches (see *Remote actions* below).
The **Delivery log** lists recent outbox rows with status chips and a retry action for failed
or expired items.

**Include contact photos** is separate from *include images* and off by default: it forwards the
sender's avatar or the app's logo, and Telegram lays every photo out at full bubble width — so a
128px avatar gets upscaled until it dominates the message. Real content images (a photo someone
actually sent) are governed by *include images* and are unaffected by this switch.

## Magic links ✨

Some notifications are *about* something with a shareable URL — a new YouTube video, the song
that's playing, a chat, a Discord DM, a GitHub issue, a Bluesky post — but the notification text
almost never contains that URL. The
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
| **YouTube** | video id (live/premiere), else channel id + video title | `youtube.com/watch?v=…` | live/premieres resolve **directly**; uploads use the public feed, with a search fallback |
| **Apple Music** | track + artist (now-playing) | `music.apple.com/…` (the song) | Apple's public iTunes Search API |
| **WhatsApp** | the chat's phone number | `web.whatsapp.com/send/…` (opens the chat) | phone from the chat identity, or a saved contact (opt-in) |
| **Discord** | the channel id (conversation shortcut) + the message id | `discord.com/channels/@me/…` (opens the DM, on the message) | **direct messages only** — a server channel's link needs a guild id the notification never exposes |
| **Telegram** | the chat + message id, from the Wear `dismissalId` | `t.me/c/…` (the message) | **groups/supergroups only** — Telegram publishes no shareable per-message link for private chats, and secret chats are never linked |
| **GitHub** | the `owner/repo#123` reference in the text | `github.com/owner/repo/issues/…` | pure text parse, no lookup — GitHub redirects `/issues/` to `/pull/` for pull requests |
| **Signal** | the sender's saved contact | `signal.me/#p/+…` (opens the chat) | saved contact only (opt-in Contacts) — Signal's own ids are device-local and carry no number |

Packages covered: YouTube (`com.google.android.youtube` plus common re-packaged clients), Apple
Music (`com.apple.android.music`), WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`), Discord
(`com.discord`), Telegram (`org.telegram.messenger` plus the common forks), GitHub
(`com.github.android`), and Signal (`org.thoughtcrime.securesms`).

A `t.me/c/` link opens only for **members** of that chat and has no web preview — it's a link back to
your own conversation, not something a stranger can open.

**A note on the two phone-number links.** WhatsApp and Signal links contain the peer's number, which
means forwarding one writes that number into a Telegram cloud chat. That's the same disclosure for
both, and it's the peer's number *you already have saved* — but Signal's users are the likeliest to
care, so if that bothers you, turn magic links off for Signal (or WhatsApp) under **Apps**. Signal
also only ever resolves a **saved contact**: it exposes no other identifier, so an unsaved sender is
simply never linkable.

**Parcel tracking, any app.** A tracking number sitting in a notification's text — an SMS, a shop
app, an email — is turned into its carrier tracking link and forwarded alongside any real links.
This is app-agnostic, because tracking numbers don't come from one app.

Only **UPS**, **USPS** and **international post (UPU S10** — e.g. `RR287043775IN`, what Correos and
most postal operators use**)** are detected, on purpose. A check digit is only a *10× filter*, so it
is the number's **structure** that makes detection safe: UPS is anchored by its `1Z` prefix, USPS by
its unusual 20/22/26-digit length, S10 by its letter/digit layout plus a real service indicator and a
real ISO country. FedEx (a bare 12-digit number) and DHL Express (a bare 10-digit number, i.e. the
shape of a phone number) have no such anchor — `123456789012` is a valid FedEx checksum and
`2125551234` a valid DHL one — so they are excluded rather than risk turning an order id or a phone
number into a wrong link.

⚠️ **One caveat on S10.** UPS and USPS links go to the carrier itself. S10 has no universal official
tracking page, so its link goes to a **third-party aggregator** (17track), which therefore sees the
tracking number. If you'd rather that never happen, the whole feature is inert unless a notification
actually contains a tracking number — but there is currently no separate switch for it.

**Now playing, any player.** The *Now playing* control (see below) adds a `🔗` link to its card for
**every** media player — Spotify, YouTube Music, Deezer, Tidal, an offline player, whatever is
playing. Since only Apple Music exposes a keyless "song → url" lookup, the track + artist are
resolved through the iTunes Search API and wrapped in an Odesli **song.link** universal page, which
routes each recipient into *their own* service. Same best-effort rule: no confident catalogue match,
no link. Honours the per-app magic-link opt-out.

### What to expect

- **Per-app toggle, on by default.** Open a supported app under **Apps** and you'll see a
  **Reconstruct magic link** switch. Turn it off to opt that app out; the choice is remembered.
- **It won't always land**, and each service misses differently. YouTube's feed and search lag for
  busy channels; Apple Music needs an exact catalogue match; WhatsApp and Signal need a resolvable
  phone number; Discord links only direct messages; Telegram only groups; GitHub skips anything that
  mentions a discussion; Bluesky links posts but not follows or chats. A miss simply means no `Link:`
  line — never a broken one.
- **YouTube live streams and premieres are exact.** Those notifications identify themselves by the
  *video* id rather than the channel, so the link is built directly — instantly, with no lookup and
  no chance of picking the wrong video. Only ordinary uploads need the feed/search route.
- **YouTube uploads self-heal.** If the first attempt (made as the message is sent) misses, the item
  still forwards immediately, and a background worker keeps re-checking for up to about an hour,
  then **edits the already-sent Telegram message** to add the link once it resolves. (Only YouTube
  needs this. Apple Music resolves from a single lookup, and Discord, Telegram, GitHub, Signal and
  Bluesky need no lookup at all — they read an id the notification already carries.)

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
- **Group chats are never linked.** The group's id *is* in the notification, but WhatsApp Web has no
  URL that opens a chat by id — the only group URL is an admin-generated `chat.whatsapp.com` invite,
  which a notification never carries. Group messages therefore forward without a `Link:` line. (The
  older group-id format begins with the group *creator's* phone number; linking that would silently
  open a private chat with the wrong person, so TeleForward deliberately refuses it.)

## Remote actions (action buttons)

Forwarded messages can carry **buttons underneath them** — Telegram inline buttons, attached to that
one message — so you can act on the notification from Telegram without touching the phone:

```
┌──────────────────────────────────────────────┐
│ WhatsApp · Messages                          │
│ Alice                                        │
│ Are you free tonight?                        │
│ 21:04                                        │
├──────────────────────────────────────────────┤
│ [ 💬 Reply ] [ ✅ Mark read ] [ 🗑 Dismiss ] │
└──────────────────────────────────────────────┘
```

Every forward keeps **its own** buttons, indefinitely — scroll back and an older message's buttons
still target the right notification.

The buttons **mirror whatever that notification actually offers**, using the app's own wording — so
K-9 Mail gets `Mark Read` / `Delete`, a music notification gets its transport controls, WhatsApp gets
`Reply` / `Mark as read` / `Mute`. Plus:

- **🗑 Dismiss** — always available; removes the notification from the phone, exactly as if you'd
  swiped it away. It goes through the notification listener, not the source app.
- **💬 Reply** — offered when the app supports inline reply. Pressing it opens your keyboard focused
  on a reply box; what you type is delivered through the app's own quick-reply, so it's sent as a
  real message from your phone. You can also just **reply to the forwarded message** directly.
- **`↗` suffix** — this action *opens the app on your phone* instead of acting silently in the
  background (e.g. K-9's `Reply`, WhatsApp's `Mute`). Everything without the marker happens quietly
  in the background, which is what makes remote control actually useful.

How it works: Android blocks *reading* another app's notification tap-action, but it does **not**
block *firing* one, and a notification listener may dismiss notifications outright. So TeleForward
re-finds the live notification by its key and triggers the real action — no accessibility service.

Action buttons are **on by default** (Settings → Remote actions) — including for existing installs
that update into the feature. Switch them off and they stay off through every future update; the
default is only used for someone who has never touched the toggle.

A press only lands while the app is listening (see *The return path* above), and there are two modes:

- **Burst (default):** after each forward it listens for a few minutes — the window in which you'd
  realistically press a button — then checks back a handful of times over the next couple of hours, so
  a later press is still collected and answered rather than vanishing. No permanent notification,
  negligible battery.
- **Always listening (optional):** a foreground service holds the connection open so presses act
  instantly, at the cost of a **permanent notification**.

A maximum of six of an app's own actions are shown, so a media notification's nine controls don't
become an unusable wall of buttons.

### Now playing control

Media notifications are normally skipped — they're ongoing, and they re-post on every track change and
every play/pause, so forwarding them would mean a message per tick. Turn on **Now playing control**
(Settings → Remote actions) and each media app instead keeps **one** message in the chat, carrying the
**album art** and that app's own transport buttons:

```
┌──────────────────────────────────┐
│ [ album art ]                    │
│ 🎵 Apple Music                   │
│ Some Track Title                 │
│ Some Artist                      │
├──────────────────────────────────┤
│ [ Previous ] [ ⏯ Play/Pause ]    │
│ [ Next ] [ Stop ]                │
└──────────────────────────────────┘
```

- **A new track replaces the message.** The old one is deleted and a fresh one posted, so Telegram
  actually notifies you that something else is playing — an edit would have been silent. Only one
  control per app is ever live, so an old message can't sit there driving the player.
- **It's a remote, not a status display.** Pressing Play/Pause acts on the phone but doesn't redraw
  the message: the button reads `⏯ Play/Pause` in both states by design, so there's nothing to
  refresh. A label showing the *current* state would be a lie the instant you used it — you press
  "Pause", playback pauses, and the button still says "Pause" until an edit catches up.
- **Shuffle / Repeat show no ON/OFF.** The notification simply doesn't carry that state — those
  buttons are identical whether the mode is on or off — so TeleForward shows the player's own wording
  rather than guessing. Pressing them still works.
- **Playback ending** turns the message into "⏹ Playback ended" and removes its buttons.
- These notifications are `NO_CLEAR` — Android refuses to let any listener dismiss them — so **no
  Dismiss button is offered** here; the app's own `Stop` is the real equivalent.

Off by default, and deliberately so: unlike a chat notification (a single event you may act on once),
a media notification re-posts for your whole listening session, so the control keeps waking the
inbound poller as tracks change. That's a real battery cost, so it's yours to opt into.

> **Prefer a notification per song instead?** Leave this off and turn off **Skip ongoing** in
> Settings. Media notifications then forward as ordinary messages — one per track, with cover art and
> buttons — and Telegram pings you for each one. The chat still keeps only the **latest**: each new
> track deletes the previous message, per player. Playing the same song twice forwards twice; only
> genuine duplicate re-posts are collapsed.
>
> Either way, the message for whatever is playing is **pinned**, so you can reach it however much
> arrives underneath. TeleForward deletes the "pinned a message" notice Telegram posts for its own
> pins, so the chat doesn't fill with them.

**Skipping doesn't spam you.** Track changes are held for a second, and only the track you actually
land on is forwarded — skip through half an album and you get one message, not twelve. This is why a
deliberate track change takes about a second to appear. It also keeps TeleForward well inside
Telegram's rate limits: each track costs several API calls (send, unpin, delete the previous, pin,
tidy the pin notice), and hitting a limit would throttle the bot token for *everything*, ordinary
notification forwards included.

### Limits worth knowing

- **The notification must still be on the phone.** Once it's gone (read elsewhere, swiped, auto-cleared)
  the action can't fire and TeleForward answers "no longer available" rather than pretending.
- **A late press is answered late, and says so.** Telegram stops accepting an answer for a press
  within seconds, so one made outside a listening window can never light up the button itself. The app
  checks back a few times over the following couple of hours, performs the action then, and posts a
  message explaining what happened and why the button appeared to hang. *Always listening* is what
  makes presses act immediately.
- **Reply only works where the app supports inline reply** with a modifiable action — WhatsApp and
  Telegram X do. Apps that only open a compose screen (e.g. K-9 Mail) show that action as `Reply ↗`
  instead, which opens the app on the phone.
- If something misbehaves, enable **Diagnostics** and dump the logs: every button attach, poll,
  press and reply is recorded (`remoteActionTrace`) — without message text, reply text or contact
  details.
- Media-group forwards can't carry buttons (a Telegram limitation), so the buttons ride the
  accompanying text message instead.
- Doze and OEM battery managers can delay polling.
- While remote actions are on, the poller consumes Telegram updates — if you need to **re-pair**, turn
  it off first so the pairing capture can see your message. Re-pairing no longer swallows a button
  press you'd made but not yet had answered; the capture stops short of any pending press rather than
  acknowledging the whole backlog.

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
- **Contact photos are off by default.** Telegram lays every photo out at full bubble width and the
  Bot API has no way to send one smaller or inline (inline images would need a public URL, i.e. a
  server). A small avatar therefore gets upscaled into a blurry block that dwarfs the message, so
  the notification's large icon is skipped — chat notifications forward as clean text. Real content
  images (the photo someone sent you) are unaffected. Re-enable it with **Settings → Include contact
  photos**.
- **Background timing:** delivery may lag while the device is in Doze; the drain worker retries
  with exponential backoff (min 30 s) until it succeeds or the item expires.
- **Network:** in regions that block `api.telegram.org` you'll need a system-level VPN/proxy —
  the app has no built-in proxy.
- **OEM battery managers** may kill the notification listener. TeleForward requests a rebind,
  but disabling battery optimization for the app is recommended for reliable forwarding.
- Delivery dedup is local only; a network drop after Telegram accepts a message but before the
  app sees the response can rarely double-post.
- **Remote actions are best-effort** (see *Remote actions* above): they need the notification to
  still exist on the phone, and in the default burst mode a late press may not land until the next
  forward. Reply is only silent where the app exposes an inline-reply action that accepts injected
  text; elsewhere it opens the app on the phone (marked `↗`).
- **Now playing is off by default** and costs battery when on, because a media notification keeps the
  inbound poller waking for the length of a listening session.
- **Magic links are best-effort** (see *Magic links* above). They're reconstructed from
  notification metadata via public feeds/APIs, so they can miss — a lagging YouTube feed, no
  Apple Music catalogue match, or an unresolvable WhatsApp number — and simply add no link when
  they do. Per-app `Link:` lines cover YouTube, Apple Music, and WhatsApp; the *Now playing* card
  adds a universal song link for any other media player.

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
            │   ├── telegram/            # API client, DTOs, message builder, sender, pairing,
            │   │                        #   inbound poller, action keyboards/dispatcher, now playing
            │   └── repo/                # repositories
            ├── service/                 # NotificationListenerService + mapper, action gateway
            │                            #   (fires/dismisses on device), always-listening service
            ├── work/                    # DeliveryWorker, LinkResolveRetryWorker (magic-link edit),
            │                            #   TelegramPollWorker (burst poll for button presses)
            ├── util/                    # notification-access helpers, bounded caches
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
