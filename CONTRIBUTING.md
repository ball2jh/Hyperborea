# Contributing to Hyperborea

Thanks for your interest in improving Hyperborea.

## Getting set up

1. Install the Android SDK and a JDK (17+).
2. Create `local.properties` in the repo root with `sdk.dir=/path/to/Android/Sdk` (copy
   `local.properties.example` for the optional settings).
3. Build and test:

   ```bash
   ./gradlew :app:assembleStandardDebug
   ./gradlew test
   ./gradlew lint
   ```

   Always use the `standard` product flavor. If you change `AndroidManifest.xml` or resources, run
   `./gradlew clean lint` rather than just `lint` (AGP lint can otherwise analyze stale merged
   manifests).

## Conventions

- The `:core` module is pure Kotlin with **no Android dependencies** — keep it that way. Feature
  modules depend only on `:core`, never on each other; `:app` is the only composition root.
- Target is API 25 (Android 7.1.2): use `startService()` + `startForeground()` (not the API-26
  variant), the pre-API-31 Bluetooth permission model, etc.
- Each class uses a `TAG` companion constant and the injected `AppLogger`; logcat tags are
  prefixed `Hyperborea.`.
- See [`AGENTS.md`](AGENTS.md) for the full architecture notes and design patterns. Investigate the
  existing interfaces and patterns before adding new ones.

## Pull requests

- Keep changes focused; include tests where it makes sense (`./gradlew test` must pass).
- Match the style of the surrounding code.
- By contributing, you agree your contributions are licensed under the project's [MIT
  License](LICENSE).

## Reporting issues

Please include: the equipment model and console firmware version, what you expected vs. what
happened, and relevant logs (`adb logcat -d | grep "Hyperborea\."`, or the in-app "Export logs"
action).
