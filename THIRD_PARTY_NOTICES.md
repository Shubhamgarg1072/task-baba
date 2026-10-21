# Third-party notices

Task Baba's original code is MIT-licensed. It depends on, or documents the use of, the components below, each under its own licence. The MIT licence does not change any of them.

## Bundled / build dependencies

| Component | Licence |
|---|---|
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization (JetBrains) | Apache-2.0 |
| Compose Multiplatform, Skiko (JetBrains) and AndroidX Compose runtime/foundation/material3 | Apache-2.0 |
| Material Icons (Google, via `compose.materialIconsExtended`) | Apache-2.0 |
| SQLDelight (Cash App) | Apache-2.0 |
| sqlite-jdbc (Xerial) incl. SQLite | Apache-2.0 (SQLite itself is public domain) |
| Gradle wrapper | Apache-2.0 |
| Bundled Java runtime (jpackage image of the build JDK, e.g. Azul Zulu / OpenJDK 17) | GPL-2.0 with Classpath Exception |

## Optional, not bundled (used by the Colab notebook / server)

| Component | Licence |
|---|---|
| Qwen3-4B-GGUF / Qwen3-1.7B-GGUF model weights (Qwen team, Alibaba Cloud) | Apache-2.0 — see the model cards on Hugging Face. Weights are never committed to this repo or bundled in the app. |
| llama.cpp (ggml-org) | MIT |
| cloudflared (Cloudflare) | Apache-2.0 |
| Google Colab / Google Drive API, Microsoft Graph, Slack Web API, Jira Cloud REST API | Governed by each provider's terms of service |

## Artwork

The Baba character, app icon and menu-bar glyph are original artwork drawn from basic shapes in code (`scripts/icon/MonkIcon.swift`, `MonkCharacter.kt`). No third-party illustrations, fonts or sounds are included (the app uses system fonts and macOS default notification sounds).
