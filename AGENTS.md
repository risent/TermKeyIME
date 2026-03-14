# Repository Guidelines

## Project Structure & Module Organization

`app/` contains the Android application module. Kotlin sources live in `app/src/main/java/com/termkey/ime/`, including the IME service, Shuangpin engine, lexicon access, voice client, and settings screens. UI resources are under `app/src/main/res/` with layouts in `layout/`, preferences in `xml/`, and shared values in `values/`. Offline data ships from `app/src/main/assets/`, notably `chinese_lexicon.db`. Unit tests live in `app/src/test/java/com/termkey/ime/`. Product docs and Play Store assets are in `docs/`, `play-store/`, and `res/`.

## Build, Test, and Development Commands

- `./gradlew installDebug`: build and install the debug APK on a connected device or emulator.
- `./gradlew assembleDebug`: compile a local debug APK without installing it.
- `./gradlew testDebugUnitTest`: run JVM unit tests in `app/src/test/`.
- `./gradlew clean`: clear Gradle build outputs when builds become stale.

Use JDK 17. The project targets Android SDK 34 and minSdk 26.

## Coding Style & Naming Conventions

Follow idiomatic Kotlin with 4-space indentation and trailing commas where the surrounding code already uses them. Use `PascalCase` for classes and activities (`SettingsActivity`), `camelCase` for methods and properties (`lookupCandidates`), and lowercase underscore resource names (`activity_setup.xml`, `bg_key_enter.xml`). Keep package names under `com.termkey.ime`. Prefer small, focused classes and keep UI wiring in activities/services while engine logic stays in dedicated Kotlin files.

No formatter or linter is currently configured in Gradle, so match the existing style closely and keep diffs tidy.

## Testing Guidelines

Unit tests use JUnit 4. Add tests beside the existing suite in `app/src/test/java/com/termkey/ime/`, and name files `*Test.kt`. Prefer descriptive test names such as `spaceCommitOnlyWhenCandidateConsumesWholeCode()`. Add or update tests for Shuangpin decoding, candidate ranking, and other engine behavior when logic changes.

## Commit & Pull Request Guidelines

Recent commits use short imperative subjects such as `Hide compact zh number row` and `Improve shuangpin candidate display`. Keep commit titles concise, capitalized, and action-oriented.

PRs should explain user-visible changes, note any settings or data migrations, and link the related issue when applicable. Include screenshots for keyboard, setup, settings, or Play Store asset changes, and list the Gradle commands you ran to verify the change.

## Security & Configuration Tips

Do not commit real signing credentials or voice service secrets. Keep local signing data in `keystore.properties`, use `keystore.properties.example` as the template, and verify any credential-related changes before opening a PR.
