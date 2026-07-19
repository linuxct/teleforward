# TeleForward

Send the Android notifications you care about to a Telegram chat.

> **Vibe-coded project notice**
> This app was built entirely with AI assistance (Claude) from scratch — it is not a manually maintained codebase.

There's no server. The app *is* the bot: it keeps your bot token on the phone and talks to the
Telegram Bot API directly. Nothing to host, no account, no third party besides Telegram.

```
┌───────────────────────────────────────────┐
│ WhatsApp · Messages                       │
│ Alice                                     │
│ Are you free tonight?                     │
│ 21:04                                     │
├───────────────────────────────────────────┤
│ [ 💬 Reply ][ ✅ Mark read ][ 🗑 Dismiss ] │
└───────────────────────────────────────────┘
```

Those buttons act on your phone, from Telegram.

## Setup

1. Message [@BotFather](https://t.me/BotFather), send `/newbot`, copy the token it gives you.
2. Install TeleForward and grant it notification access when asked.
3. Paste the token. Open your new bot, press **Start**, then tap **Capture chat** in the app.
4. Open **Apps** and switch on what you want forwarded.

A bot can't message you until you've pressed Start at least once. If the first send fails, that's
usually why.

## What it does

**Picks what forwards.** Per app, per notification channel, or per individual chat. A chat rule beats
its channel, which beats the whole app. Channels and chats only appear in the list once you've
received something from them, because Android won't let one app enumerate another's.

**Acts on your phone from Telegram.** Every forward carries the buttons that notification actually
offers — reply, mark read, delete, media controls, whatever the app exposes — plus Dismiss. Reply
sends a real message from your phone. You can also just reply to the forwarded message.

**Rebuilds the real link** ✨. Android won't let the app read a notification's tap action, so
TeleForward reconstructs the link from what it *can* see and appends it:

| App | You get |
|---|---|
| YouTube | the video (live streams and premieres are exact) |
| Apple Music | the song |
| WhatsApp | the chat |

It's best-effort. If it can't be sure, it adds nothing rather than sending you to the wrong place.

**Media.** Music notifications forward with cover art and transport controls, and the chat keeps only
the current track — each new song replaces the last one and gets pinned so you can find it. Skipping
through an album gives you one message, not twelve.

**Also:** images, optional contact photos, Wi-Fi-only delivery, a global pause, a delivery log, and a
diagnostics dump for when something misbehaves. Spanish and English.

## Buttons: when they work

This is the one thing worth understanding, because it's a real constraint rather than a bug.

Telegram gives a bot a few seconds to respond to a button press. With no server, the app has to be
*listening at that moment* — it can't be woken up on demand. So:

- **Screen on** → presses work instantly. This covers pressing from your phone.
- **Just after a forward** → works, for a few minutes.
- **Always listening** (Settings) → always works, including from Telegram Desktop with your phone in
  your pocket. Costs a permanent notification.

You'll rarely meet a dead button anyway: when you dismiss a notification on the phone, its buttons
disappear from the chat straight away. That part needs nothing listening.

## Good to know

- Actions only work while the notification is still on your phone.
- **Reply** is only silent where the app supports inline reply (WhatsApp, Telegram X). Others open the
  app on your phone — those buttons are marked `↗`.
- **WhatsApp groups can't be linked.** WhatsApp Web has no URL that opens a group, so group messages
  forward without a link. The old-style group id starts with the *creator's* phone number, so linking
  it would open the wrong chat.
- Contact photos are off by default. Telegram renders every image at full bubble width, so a 64px
  avatar becomes a blurry slab.
- One recipient only.
- Doze and OEM battery managers can delay things. Excluding the app from battery optimisation helps.

## Privacy

Your token is encrypted on-device (Android Keystore) and the token/recipient screens block
screenshots. Nothing is read from a notification unless it matches a rule you set. Diagnostics dumps
leave out message text, reply text and contact details.

The one sensitive permission is Contacts, and only if you opt in: it turns a saved WhatsApp contact
into a `web.whatsapp.com` link. The number never leaves your phone except inside the link you forward
to yourself.

## Build

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # unit tests, no device needed
```

Android 8+ (Material You needs 12+). Kotlin, Compose, Hilt, Room, WorkManager, Retrofit, Coil.

## Nothing is forwarding?

In order:

1. Notification access still granted? Some OEMs revoke it after a force-stop.
2. Global pause off?
3. App **and** channel enabled? Remember a chat rule overrides its channel.
4. Did you press Start in the bot?

**Settings → Send test message** checks the Telegram half on its own, and the **Delivery log** shows
what happened to each item.
