# AGENTS.md

Guidance for AI agents working in this repository.

## What Chainline is

A Kotlin Multiplatform app (Android + iOS) for cyclists. Three product pillars:

1. **Component wear tracking** — bikes and their parts (chain, cassette, chainrings, tyres, brake pads, bottom bracket, cables, suspension service intervals). Each component accrues distance/time from rides and has a service or replacement threshold; the app warns before things wear out.
2. **Route planning** — plan and inspect routes (distance, elevation, surface), keep a library of planned and ridden routes.
3. **Stats** — training and usage statistics over the activity history.

**Ride data is never entered by hand.** It is imported from the user's existing tracker account: **Strava** or **Garmin Connect**. The app is a consumer of those APIs, not a recording app — there is no GPS-tracking / activity-recording feature. Do not add one unless explicitly asked.

## Three decisions that are settled

**1. UI is always native. Never Compose Multiplatform.** SwiftUI on iOS, Jetpack Compose on Android, written separately against a shared Kotlin core. This is not open for re-litigation — do not add Compose Multiplatform iOS targets, do not propose sharing screens, do not "save duplication" by lifting a view into common code. Only non-UI code is shared.

**2. iOS ships first.** v1 is the iOS app. Android comes after. Until v1 ships, iOS is the only client that has to work — but `sharedLogic` must stay platform-neutral so Android is a UI project later, not a rewrite. Concretely: no Android-only dependency creeps into `sharedLogic`, and anything platform-bound goes behind `expect`/`actual` from the start even while only the iOS `actual` is exercised.

**3. Local-first persistence, SQLDelight, no backend data tier.** The app owns a local database and there is no server holding user data. The one server-side piece is a token-exchange endpoint for Strava OAuth (see Integrations). Do not propose a backend, a user-accounts system, or cloud sync for v1.

## Current state

This is still the bare KMP template from the JetBrains wizard (`Greeting`, `Platform`, a "Click me!" screen). Almost nothing product-specific has been written yet. Commit history: `init: KMP app template`. Treat every file under `sharedLogic`/`androidApp`/`iosApp` as scaffolding to be replaced, not as established architecture.

The template shipped a `sharedUI` Compose Multiplatform module. **It contradicts the native-UI decision and is slated for deletion**, along with the Compose plugins and dependencies in `gradle/libs.versions.toml` and the root build. Don't build on it, don't extend it, and don't wire it into iOS. `androidApp` currently gets its `App()` composable from it; when Android work starts, that screen gets rewritten as Jetpack Compose inside `androidApp`.

## Repository layout

```
sharedLogic/     KMP library — domain, data, API clients, persistence. No UI. The only shared code.
                 Targets: android, iosArm64, iosSimulatorArm64.
                 Exported to iOS as a static framework named `SharedLogic`.
iosApp/          SwiftUI application. The v1 target. Imports `SharedLogic`.
androidApp/      Jetpack Compose application (`eu.mstrlc.chainline`). Post-v1.
sharedUI/        Leftover Compose Multiplatform template module — to be deleted, see above.
gradle/libs.versions.toml   Single source of truth for all versions.
```

Package root everywhere: `eu.mstrlc.chainline`.

### Where the seam sits

`sharedLogic` owns everything down to and including presentation state: domain models, API clients, sync, persistence, wear calculation, and the view-model-equivalent state holders. The platform layer owns rendering and navigation only.

That seam is what makes writing the UI twice cheap. If a piece of logic is being written in Swift, it is probably in the wrong place — the test is whether Android would have to reimplement it.

### Directory structure

`sharedLogic/src/commonMain/kotlin/eu/mstrlc/chainline/` is laid out by layer. Put new files in the right one rather than inventing a parallel scheme:

```
domain/         Models, repository interfaces, use cases. Pure Kotlin, no framework types.
data/           SQLDelight database, Ktor clients, repository implementations.
presentation/   ViewModels / StateFlow holders.
di/             Koin modules.
```

Dependencies point inward: `presentation` depends on `domain`, `data` implements `domain` interfaces, and `domain` depends on neither. A Ktor or SQLDelight type appearing in `domain` or `presentation` is a layering bug.

### State holders

State holders live in `presentation/` and use **JetBrains' KMP `lifecycle-viewmodel`** (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`) — `ViewModel` subclasses exposing `StateFlow`, with work launched in `viewModelScope`.

Do not introduce a third-party MVI framework — MVIKotlin, Decompose, Orbit, Ballast — unless a concrete problem proves the plain approach insufficient, and say so explicitly if you propose one. They add a large conceptual surface and their own interop quirks for benefits this app has not yet earned.

### Kotlin/Native interop

What `sharedLogic` exposes is what Swift has to consume, and the defaults are unpleasant from Swift: sealed hierarchies flatten, `Flow` and coroutines don't map to `async`/`await` or Combine on their own, and generics degrade. Design the public surface for Swift ergonomics — suspend functions and `Flow`s wrapped in something callable, plain types at the boundary — rather than exposing idiomatic Kotlin and making the iOS side suffer.

**Never throw across the Swift boundary.** A Kotlin exception crossing into Swift becomes an unhandled `NSError` or kills the process outright — Swift cannot `catch` it the way Kotlin callers can, and `@Throws` only covers checked-style declarations. Every domain and data layer operation that can fail returns an explicit `Outcome` instead:

```kotlin
sealed class Outcome<out D> {
    data class Success<D>(val data: D) : Outcome<D>()
    data class Failure(val error: DomainError) : Outcome<Nothing>()
}

sealed class DomainError { /* Network, RateLimited, Unauthorized, NotFound, Storage, … */ }
```

Named `Outcome`, not `Result`, so it does not shadow `kotlin.Result` on every unqualified use in `commonMain`.

Failure is a value, not an exception. `DomainError` is a sealed hierarchy so the Swift side can handle every case, and adding a case surfaces as a compile-time gap rather than a runtime surprise. Catch exceptions at the data layer boundary — Ktor and SQLDelight both throw — and map them into `DomainError` there. Nothing above `data/` should contain a `try`/`catch`.

**This rule depends on SKIE.** Kotlin sealed classes flatten to plain ObjC classes, so out of the box Swift gets no exhaustive `switch` — only `if case let` chains with a mandatory `default`, which silently swallows new error cases and defeats the point. SKIE restores exhaustive `switch` over sealed hierarchies, and maps `suspend` to `async`/`await` and `Flow` to `AsyncSequence` besides. Add it when the first sealed type crosses the boundary.

### Dependency injection

**Koin.** It is the KMP-native choice and works in `commonMain` without code generation or compiler plugins.

- Every layer declares its own Koin module in `di/` — `domainModule`, `dataModule`, `presentationModule` — rather than one module listing everything. New dependencies register in the module for their layer.
- **`sharedLogic` must expose a `KoinHelper.kt`** in `di/` that initializes the graph and can be called from Swift. iOS has no equivalent of Android's `Application.onCreate`, so the graph is started explicitly from the SwiftUI app entry point (`iOSApp.init`) via a plain function like `initKoin()`. Koin's own `startKoin` builder does not cross the ObjC boundary cleanly, so wrap it.
- `KoinHelper.kt` also exposes the accessors Swift needs to resolve state holders. Swift should never reach into Koin's internals — it calls named factory functions.
- No constructor injection into SwiftUI views. Views receive an already-resolved state holder.

**Owning a KMP ViewModel from SwiftUI.** A Kotlin `ViewModel` cannot conform to `ObservableObject`, so SwiftUI never holds one directly. Wrap it in a small Swift observer class that owns the KMP instance, resolves it from `KoinHelper` in its initializer, and republishes the `StateFlow` as observable Swift state:

- Hold that wrapper with **`@StateObject`** (or `@State` with `@Observable` on iOS 17+), never `@ObservedObject`. `@StateObject` instantiates once and survives redraws; `@ObservedObject` re-resolves on every re-render, spawning a new ViewModel and leaking the old one's `viewModelScope`.
- Resolve inside the wrapper, not in the view body — a view body can run arbitrarily often.
- **Tear the ViewModel down when the wrapper deinitializes.** iOS has no lifecycle owner to do it for you, so without this `viewModelScope` is never cancelled and in-flight coroutines outlive the screen. This is the actual leak to worry about.

  Note that **`ViewModel.clear()` is `internal`** in `lifecycle-viewmodel` and is not callable from Swift — nor from your own Kotlin code. Teardown goes through one of these instead:

  - **Preferred: own a `ViewModelStore` per screen.** `ViewModelStore.clear()` *is* public. The Swift wrapper holds a store, resolves the ViewModel through it, and calls `store.clear()` on deinit. This runs the real androidx path, so `onCleared()` fires and `viewModelScope` is cancelled properly.
  - **Alternative: a project `BaseViewModel`** in `presentation/` that constructs its own scope via the `ViewModel(viewModelScope:)` constructor and exposes a public `dispose()` cancelling that scope. Simpler to bridge, but you own the lifecycle contract rather than inheriting it.

  Pick one and apply it to every screen — a codebase with both is how one of them silently stops being called.
- Collect the `StateFlow` in a single `Task` started on appear and cancelled on disappear; with SKIE it is an `AsyncSequence` and reads as a plain `for await` loop.

## Commands

```bash
./gradlew :sharedLogic:iosSimulatorArm64Test        # iOS-side tests — the ones that matter for v1
./gradlew :sharedLogic:testAndroidHostTest          # JVM-side tests of the same common code
./gradlew :sharedLogic:linkDebugFrameworkIosSimulatorArm64   # build the Swift-facing framework
./gradlew :androidApp:assembleDebug                 # build Android (post-v1)
```

iOS app: open `iosApp/` in Xcode and run. The Gradle framework task is wired into the Xcode build phase — after changing Kotlin, rebuild in Xcode rather than only running Gradle.

## Conventions

- **Versions go in `gradle/libs.versions.toml`.** Never hardcode a version in a `build.gradle.kts`, and never add a dependency without also adding the catalog entry.
- **Business logic belongs in `sharedLogic/src/commonMain`.** Use `expect`/`actual` (see `Platform.kt`) only for genuinely platform-bound things — keychain/keystore, secure storage, system browser for OAuth.
- Prefer `kotlinx.*` (coroutines, serialization, datetime), Ktor, and SQLDelight for anything new in `sharedLogic`; they are the KMP-native choices and keep iOS working.
- **No persistence in Swift.** No SwiftData, no Core Data, no `UserDefaults` for app data. The database is SQLDelight in `sharedLogic`. `UserDefaults`/Keychain are for tokens and UI preferences only.
- Swift code is idiomatic SwiftUI and stays thin: views, navigation, and adapting `SharedLogic` state into `@Observable`/`ObservableObject`. No business rules, no networking, no persistence in Swift.
- **All timestamps are UTC `kotlinx.datetime.Instant`**, in SQLDelight columns, domain models, and every function signature. No local times, no naive date strings, no platform date types below the UI layer. Store as epoch milliseconds (`INTEGER`) in SQLite with an `Instant` column adapter. Converting to the user's timezone and formatting for display is **strictly the platform UI layer's job** — `Date`/`DateFormatter` on iOS, never in `sharedLogic`. Ride data crosses timezones; this is not a detail to get casual about.
- JVM target is 11, `minSdk` 24. Keep new code compatible.
- Don't commit `local.properties`, `.idea/`, `build/`, `.gradle/`, or `.DS_Store`.

## Persistence

**SQLDelight in `sharedLogic`.** This is the project's answer to "what do we use instead of SwiftData" — it is the KMP-native equivalent: schema and queries written as SQL in `.sq` files, typed Kotlin generated from them, mature on Kotlin/Native. Room's KMP support also works, but SQLDelight is the more proven iOS-first choice and is what this project uses.

Drivers are per-platform behind `expect`/`actual`: **`app.cash.sqldelight:native-driver`** (`NativeSqliteDriver`) for iOS and **`app.cash.sqldelight:android-driver`** (`AndroidSqliteDriver`) for Android. Only the iOS `actual` needs to work for v1, but write both signatures now.

SwiftData is not an option and neither is Core Data. Persistence sits below the UI seam, in shared Kotlin, because the code that reads and writes it — sync, wear accrual, stats — is the code that must not be written twice. If Swift is touching a database, something is in the wrong layer.

### Why local-first

The app is mostly a cache of Strava plus the component data the user owns. Strava's rate limits make a persistent local store mandatory regardless of whether a server exists, and v1 is single-user and single-device, so a backend data tier would earn nothing while doubling the sync work.

### Migration-safety rules — follow these from the first table

A backend may arrive later (multi-device sync, webhook-driven updates). What makes that migration expensive is not the database, it's four details that are cheap now and cannot be backfilled once real user data exists. They are not optional:

- **Client-generated UUID primary keys.** Never `AUTOINCREMENT` integers — sequential local IDs across two devices is the migration that goes wrong. Use **`kotlin.uuid.Uuid`** from the standard library (`Uuid.random()` for v4), stored as `TEXT`. No third-party UUID dependency.

  `Uuid` is **stable** on this project's Kotlin 2.4.20 — it carries `@SinceKotlin("2.4")` and `@WasExperimental(ExperimentalUuidApi::class)`, and `Uuid.random()` is stable too. **Do not add `@OptIn(ExperimentalUuidApi::class)`** or the `-opt-in` compiler flag for it. That opt-in was required on Kotlin 2.0.20–2.3.x and is stale advice here; adding it now produces a pointless annotation. Some `Uuid` companion members added in 2.3 are still experimental, so opt in narrowly at the call site if you touch one — never project-wide.
- **`createdAt` and `updatedAt` on every row**, UTC, written on every insert and update.
- **Soft deletes** — a nullable `deletedAt` column, never a bare `DELETE`. A deletion that leaves no trace cannot be synced.
- **Domain models are not table rows.** Keep the SQLDelight-generated types inside the data layer and map to domain models at its boundary, so the wire format can diverge from the schema later.

## Domain model sketch

Nothing is implemented yet; this is the intended shape, useful for naming and for keeping work consistent:

- `Athlete` — the connected user, one per provider connection.
- `Activity` — an imported ride: id, provider, start time, distance, moving time, elevation, and the gear it was ridden on.
- `Bike` (provider "gear") — maps to Strava gear / Garmin gear where available.
- `Component` — belongs to a bike, has `installedAt` (date + odometer offset), accumulated distance/time, and a wear target.
- `ServiceRecord` — a replacement or service event that resets a component's accumulation.
- `Route` — planned or ridden geometry plus derived metrics.

Wear accrual is derived from activities, not stored as a running counter that mutates in place. Keep it recomputable: if an activity is edited or deleted upstream, or a component's install date is corrected, wear must be recalculable from the activity history. This is the single most important modelling constraint in the app.

## Integrations

Both providers are OAuth 2.0, and **no client secret may ship in the app binary.**

Strava does not support PKCE — its token exchange and every refresh require `client_secret`. So the app needs **one server-side endpoint** that holds the secret and proxies the exchange. That is the entire backend: a single function, no database, no user accounts. Shipping the secret in the binary is not the fallback — it is extractable, and Strava's rate limits are per-application, so a leaked secret means anyone can exhaust the app's quota for every user.

Do not stash the secret in `local.properties` and call it done, and do not let this one endpoint grow into a data tier.

### Sync constraints

Sync must work not just from a foregrounded app but from an iOS `BGTaskScheduler` background task. That imposes hard requirements on everything in the sync path — Ktor calls and SQLDelight transactions alike:

- **Callable from any thread.** Nothing in the sync path may require the main thread or touch UI state. Expose `suspend` functions, do the work on a background dispatcher, and never assume a main-thread `Dispatchers.Main` context exists. Kotlin/Native's current memory model permits this freely; the constraint is discipline, not the runtime.
- **Fully cancellable.** Background tasks get killed on an expiration handler with little warning. Honour coroutine cancellation throughout, and make sure a cancelled sync leaves the database consistent — wrap multi-row writes in a SQLDelight transaction so a kill mid-sync cannot half-apply.
- **Resumable and incremental.** Persist the sync cursor after each successful page, so a sync interrupted at 80% resumes rather than restarting. This matters doubly given Strava's rate limits.
- **No UI dependencies of any kind.** No state holder, no navigation, no `presentation/` types in the sync path. A sync triggered from the background has no view alive to observe it; it writes to the database and the UI reacts when it next reads.
- Give the iOS side a single entry point — one `suspend` function taking no UI context — so the `BGTaskScheduler` handler is thin.

### Strava

- OAuth 2.0 with refresh tokens; short-lived access tokens (~6h) — implement refresh before it bites.
- Scopes needed: `activity:read_all` (rides, including private), `profile:read_all` (gear).
- Relevant endpoints: `/athlete`, `/athlete/activities`, `/activities/{id}`, `/gear/{id}`, `/routes`.
- Strava already tracks gear distance; treat that as a cross-check, not the source of truth — components are finer-grained than Strava gear.
- **Rate limits are strict and low** (per-15-minute and daily caps, read-specific limits on top). Design for incremental sync using `after`/`before` cursors, cache aggressively, and never re-pull the full history on every launch. Handle 429 with backoff.
- Webhooks exist for push updates, but they need a public endpoint. v1 polls on launch and on pull-to-refresh; revisit only if the token-exchange endpoint grows.
- Strava's API terms restrict storing and displaying data; check them before designing offline caching or any export feature.

### Garmin

- Garmin's Connect APIs (Health/Activity API) are **partner-program gated** — access requires an approved application, it is not self-serve like Strava. Confirm access exists before building against it.
- Consequence: Strava is the realistic first integration. Build the sync layer provider-agnostic (a `ActivityProvider` interface in `sharedLogic`) so Garmin slots in later without a rewrite.
- Do not scrape Garmin Connect's web endpoints or use an unofficial client — it breaks and violates their terms.

### Secrets

API credentials must not be committed. Read them from `local.properties` / environment at build time using **`com.codingfeline.buildkonfig`**, which generates a config object into `commonMain`.

**Android's `BuildConfig` does not exist in shared code.** It is generated by AGP for Android targets only, so referencing it from `commonMain` breaks the iOS build — which, since iOS is the v1 target, is the build that matters. BuildKonfig is the multiplatform equivalent and the only sanctioned way to get build-time constants into `sharedLogic`.

Runtime tokens are a separate matter: keep them in the platform keystore (iOS Keychain / Android Keystore) behind an `expect`/`actual` interface — never in plain `UserDefaults` or `SharedPreferences`.

## Working notes for agents

- Verify before claiming: build what you changed. Since iOS is the v1 target, `./gradlew :sharedLogic:linkDebugFrameworkIosSimulatorArm64` is the check that counts — Kotlin/Native errors never show up in the Android tasks.
- When adding a dependency, check it supports iosArm64 and iosSimulatorArm64, not just Android. A JVM-only library compiles fine on Android and breaks the iOS link step, which is the build you care about right now.
- Android lagging behind is expected and fine. Don't hold up iOS work to keep Android green, but don't write anything into `sharedLogic` that would block Android either.
- Keep the template's `Greeting`/`Platform`/`GreetingUtil` files only until real code replaces them; delete them then rather than building around them.
