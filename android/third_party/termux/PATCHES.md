# Vendored Termux code (spec Q1)

Source: https://github.com/termux/termux-app tag `v0.118.3`,
commit `5b657c6adf4304e5198951ce815fe0205dcac29c` (the same code the M1 spike used from JitPack).

| Module here | Upstream path | Licence |
|---|---|---|
| `terminal-emulator/` | `terminal-emulator/src` (Java, `jni/termux.c`, tests) | Apache-2.0 (`LICENSE-termux-app.md`) |
| `terminal-view/` | `terminal-view/src` | Apache-2.0 (`LICENSE-termux-app.md`) |
| `extrakeys/` | `termux-shared/src/main/java/com/termux/shared/terminal/io/{extrakeys/*,TerminalExtraKeys.java}` | GPLv3 only (`extrakeys/LICENSE.md`) |

Upstream `build.gradle` files are replaced by `build.gradle.kts` (namespace, minSdk 29, Java 17,
ndk-build of `libtermux.so` for the app's ABIs). Termux's own unit tests run with
`./gradlew :terminal-emulator:testDebugUnitTest`.

## Patches

Every change to upstream files is listed here, newest last, so an update can re-apply them.

1. `terminal-emulator/src/main/AndroidManifest.xml`, `terminal-view/src/main/AndroidManifest.xml`:
   `package="…"` removed (AGP 9 takes the namespace from Gradle and rejects the attribute).
