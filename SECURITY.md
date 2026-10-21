# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private vulnerability reporting
(**Security → Report a vulnerability**) on this repository. We aim to acknowledge reports within 7 days.

## Scope and design

- Task data is stored locally in SQLite under `~/Library/Application Support/TaskBaba/`.
- Secrets (Colab API key, Google/Microsoft OAuth tokens, Slack/Jira tokens) are stored only in the macOS Keychain.
- TLS verification is never disabled. Cloud endpoints must use HTTPS (plain HTTP is allowed only for localhost).
- AI output is treated as untrusted display text: it is length-limited, filtered, and can never execute commands, modify data or change settings.
- The Colab proxy enforces an API key (constant-time comparison), request-size and generation limits, rate limiting, and does not log prompts.
- OAuth uses PKCE with a loopback redirect (Google) or the device-code flow (Microsoft) and requests least-privilege scopes.
- No credentials, tokens or private keys belong in this repository. If you find one, report it privately so it can be revoked.

## Supported versions

Only the latest release receives security fixes.
