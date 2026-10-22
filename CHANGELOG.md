# Changelog

## 1.2.2 — 2026-10-22

- **Fixed: misleading "native notifications aren't available" message.** When macOS refuses the permission request (UNErrorDomain 1 — it happens to apps built and ad-hoc signed on your own Mac after a rebuild, or when the app wasn't opened from Finder/Dock), Task Baba now says so and explains the fix: quit, open it from Finder or the Dock, and press Allow again.
- Reminders no longer disappear when native notifications are refused: they fall back to simple macOS banners (without buttons) and still appear in the app.

## 1.2.1 — 2026-10-22

- Notes are now two sidebar sections: **Notes** for simple notes and **Secure Notes** for Keychain-protected logins and secrets (View menu → Secure Notes, too).

## 1.2.0 — 2026-10-22

- **Notes**: a new sidebar section for quick notes — search, pin, optional link.
- **Secure notes** for logins and credentials: username, password and secret notes are stored only in the macOS Keychain (the database holds just the title and website). Viewing or editing needs Touch ID or your Mac password (`babakit auth`, LocalAuthentication) and re-locks after 2 minutes; copied passwords are cleared from the clipboard after 30 s; strong-password generator.
- Settings → Reset all data now also deletes notes and their Keychain entries.
- Database migration 1 → 2 (adds the `note` table) runs automatically; existing data is kept.
- 13 new tests, including one that inspects the raw SQLite file to prove secrets never land there, and UI tests for creating, locking and unlocking notes.

## 1.1.1 — 2026-10-22

- **Fixed: "New task" didn't work on the Today screen.** The editor opened inside a scrolling column and crashed during layout, so no task could be added from it. The editor now has its own bounded area.
- The Add task / Save / Cancel buttons are pinned below the form, so they're always visible; pressing Enter in the title saves.
- Added UI tests that click through adding a task (Today, All Tasks, quick add, Enter to save, empty-title error).

## 1.1.0 — 2026-10-21

- **Animated Baba**: a new poseable rig (head, eyes, brows, mouth, IK arms, props, effects) and 40 keyframed animations, each triggered by its matching event — water, eye, stretch, walk, meal and rest breaks; task created, chosen, done, due soon, overdue and postponed; greetings, day summaries and returning from a break.
- Idle life: breathing, random blinks, lip-sync for new lines, looking around, mala counting, humming; yawning and dozing late at night.
- Settings: smoothness 30 / 40 / 60 fps (default 40) and a gallery to preview every move. Reduced motion shows still key poses.
- Battery-aware pacing: full frame rate only while something moves, ~5 fps while breathing, paused in an unfocused main window. Measured on an M1: ~1 % of one core in the background (was 22–50 % in the first prototype).
- 11 new tests and a rendered contact sheet of every move.

## 1.0.0 — 2026-10-21

First release.

- Tasks with priorities, importance, deadlines, estimates, notes, recurrence, MIT and transparent ranking
- Health reminders with active hours, weekdays, quiet hours, snooze, pause and user-confirmed tracking
- Friendly / Savage / Adaptive Baba with 170+ original lines
- Menu bar companion, floating Baba, native notifications with actions, launch at login
- Daily review, streaks and history
- Optional Colab-hosted AI (model stored in Google Drive), Drive metadata verification
- Optional Jira, Slack and Microsoft 365 smart inbox

### Verified in this release
- `./gradlew test` (136 tests) and proxy tests pass; `scripts/build-app.sh` builds and signs the app on macOS 15.6 / Apple Silicon.
- The packaged app launches, creates its database, schedules reminders, persists tasks across restart without duplicate schedules, and holds alerts during quiet hours.
- Keychain round-trip and launch-at-login register/unregister through `babakit`.

### Not yet verified with real services
- Clicking notification action buttons on a real delivered banner (needs a human).
- A real Colab session, real Google OAuth/Drive, real Jira/Slack/Microsoft accounts (all covered by mocked tests only).
