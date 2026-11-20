# Findings and decisions (2026-11-20)

What we learned while building and testing the app with two phones. Read this before changing the
push, update or power design.

## Phones and accounts
- Two phones: John's S21 Ultra (Android 15) and Violeta's S23 (Android 17). Both run the GitHub release build.
- The doorbell keeps **one entry per phone**, keyed by Android ID, which stays the same when the app is
  reinstalled (if the signing key is the same). The FCM token changes on every reinstall, so keying by token
  produced duplicate entries. A token Google reports as UNREGISTERED is dropped automatically.
- Owner's rule: **Decline ends the call for everyone** and hangs up on the visitor ("there's a reason").
  Do not change it to "silence only this phone".

## Ringing path
- The doorbell sends a high-priority FCM data push `ring`. PushService starts `RingService`, a `phoneCall`
  foreground service that lives only while it rings. It plays the ringtone itself, so the app can have its own
  ringtone and volume (a notification channel's sound can't be changed after creation), and shows a CallStyle
  full-screen notification.
- When a call ends, every phone gets one `call` summary push, and Recent is built only from those. So both
  phones show the same history ("Violeta talked 0:12, door opened").
- Measured: push sent in ~1.4 s from the ESP, phone connected ~1 s after the push arrives.

## Push reliability: the open question
- Twice on 2026-11-20 the phone's Google push connection (GMS/MCS, mtalk.google.com:5228) was **silently
  dead**. Every push to every app (doorbell, Discord) waited until the phone's next heartbeat found the dead
  link and reconnected. A ring only lives 40 s, so those rings were lost.
- Evidence: `adb shell dumpsys activity service com.google.android.gms/.gcm.GcmService`. The dead periods were
  01:09→01:25 and 01:38→01:59, each ending in `Sent Client HB` → `Close err:28` → reconnect → queued messages
  arriving at once. The heartbeat interval on this Wi-Fi was 1250 s (21 min); after the failure GMS lowered it
  to 950 s.
- **Not the router's NAT timeout:** `tools/nat_timeout.py` (idle TCP to imap.gmail.com) found connections
  still alive after 2, 4, 6, 8, 10, 15 and 20 min. (tcpbin.com is useless for this: it closes idle
  connections after 15 s itself.)
- After the 950 s heartbeat, the link stayed up 45+ min, survived a 21-min quiet gap, and every ring arrived
  (including a 12-min-idle test by the owner). The cause of the two dead periods is still unknown.
- Owner: "if it doesn't do reliable, what's the point". **Reliability has to be proven with numbers.**
- Planned and not built: an overnight silent test. The ESP sends a `ping` push every 10 min, the app reports
  receipt over the LAN, and the test page shows "N of N arrived, worst delay X s".
- Fallback idea if pushes stay flaky: while the phone is on home Wi-Fi, also hear the doorbell directly over
  the LAN, using a minimum-importance notification channel (no lock screen text, no status bar icon). The
  owner hated the old visible "Connected…" notification, so it must stay invisible.

## Self-update: one tap, by decision
- GitHub Releases + `Updater.kt` (a check twice a day via WorkManager, or Settings → Check now) works:
  0.4.0 → 0.4.4 all installed this way.
- **It can't be silent on these Samsungs without Google Play.** Even with `UPDATE_PACKAGES_WITHOUT_USER_ACTION`
  and the app as its own installer of record, Play Protect intercepts updates from outside Play (it rejected
  one session with `INSTALL_FAILED_VERIFICATION_FAILURE`) and Android still shows its confirmation. Samsung
  also turned "Install unknown apps" off again once.
- Owner accepted: an update = one notification tap + confirm ("not like we're pushing updates daily").
  Google Play was considered and **rejected** (listing work, policy fights over full-screen intent, battery
  exemption and self-update). Play internal testing would be the only route to zero-tap updates.
- WorkManager won't run a periodic job early, even with `cmd jobscheduler run -f`.

## Power and heat
- The ESP32-C6 SuperMini idles at about 50 °C ("can hold it between fingers"). Cause: Wi-Fi power save is off
  (`WIFI_PS_NONE`), so the radio listens continuously, at ~100 mA through the board's small LDO.
- Planned (not built): power save on while idle and off during a call; chip temperature on the test page.
  This matters for the final build: closed inside the handset, possibly powered from the line.

## Next session
Plan the hardware: the ESP inside the УКП-СМД-11 (see NETLIST.md and the schematic), its power source, the
line interface for ring detect, off-hook, door button and audio, and what to order.
