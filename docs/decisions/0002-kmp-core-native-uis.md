# 0002: Kotlin Multiplatform core with native UIs

- **Status:** accepted
- **Date:** 2026-10-14

## Context

The requirements:
- a native feel on both platforms
- one shared core
- open source and F-Droid compatible

The options compared:
- **Flutter and Compose Multiplatform** draw their own controls on iOS, and
  can't render iOS 26's Liquid Glass at the time of writing.
- **React Native** uses native views, but its prebuilt Hermes engine
  complicates F-Droid builds.
- **Rust cores and Swift-on-Android** are steeper or less mature.
- **Native ×2** shares nothing.

## Decision

- A Kotlin Multiplatform shared core (`sharedLogic`) holds panels, the image
  pipeline, protocols and presets.
- The UIs are native: SwiftUI on iOS and Compose on Android.
- NFC sits behind a small `Transport` interface, implemented natively on each
  platform, with no third-party NFC library.

## Consequences

- Native controls and behavior on both platforms.
- The core is unit-testable on the JVM.
- More to learn: Kotlin, Swift, Gradle and two UI toolkits.
- Kotlin↔Swift interop goes through an Objective-C header today; Swift
  export is still Alpha.
- iOS on-device NFC requires a paid Apple Developer Program membership.
