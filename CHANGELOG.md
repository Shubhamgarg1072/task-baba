# Changelog

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
