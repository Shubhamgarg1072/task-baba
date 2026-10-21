# Contributing to Task Baba

Thank you for helping Baba help people! 🙏

## Getting started

1. macOS 13+, JDK 17+, and a Swift toolchain (Xcode or Command Line Tools).
2. `./gradlew test` must pass before and after your change.
3. `scripts/run-dev.sh` runs the app with a separate dev data folder.

## Guidelines

- **Keep business logic in `core/`** with unit tests; Compose code in `app/` should stay thin.
- **Every control must do something real.** No placeholder buttons, fake statistics or simulated integrations.
- **Privacy first.** Don't add analytics. Don't send task, health or notification data anywhere without explicit, documented consent. Secrets go in the Keychain via `SecretStore`, never in settings, logs or source.
- **AI is optional and advisory.** AI output is display text only; it must never trigger actions.
- **Monk lines** must be original, kind-hearted and respectful of Hindu traditions — tease procrastination, never people, health, or faith. Add lines to `MessageLibrary.kt`; ids are generated, and tests check coverage.
- Match the surrounding code style (Kotlin official style, 4-space indent).
- Never commit databases, model weights, tokens, `.env` files or personal data. CI runs secret scanning.

## Pull requests

- One focused change per PR, with tests.
- Describe user-visible behaviour and how you verified it (tests, screenshots via `ScreenshotRenderer`).
- Ordinary PRs must not require private credentials; external services are mocked in tests.

## Releasing

See [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md).
