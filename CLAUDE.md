# ardaas-app

Native iOS app (SwiftUI, iOS 17+) for composing a custom Ardaas: insert a
personal benti into the standard Ardaas, save it with a label, and read the
composed Ardaas in situ. v1 scope: one benti, one insertion slot. Nitnem
"playlists" are a later ambition, not in scope.

## Build loop (important)

The agent host (Endurance, Linux) has **no Xcode and no Swift toolchain**.
Compilation is verified by:

1. **CI** — GitHub Actions macOS runner builds and tests every PR
   (`.github/workflows/ci.yml`). CI green is the compile/test gate.
2. **Sarbloc's MacBook** — pull, `xcodegen`, open `Ardaas.xcodeproj`, run.

Never claim "it builds" from the Linux host — point at the CI run.

## Project generation

The `.xcodeproj` is **generated, not committed**. Source of truth is
`project.yml` (XcodeGen). After pulling or changing `project.yml`:

```sh
brew install xcodegen   # once
xcodegen                # regenerates Ardaas.xcodeproj
```

Adding a Swift file under `Ardaas/` or `ArdaasTests/` needs no project.yml
change (directory-sourced targets), but the project must be regenerated to
pick it up.

## Structure

- `Ardaas/` — app target sources (SwiftUI)
- `ArdaasTests/` — unit tests (XCTest), test target hosted by the app
- `project.yml` — XcodeGen project definition
- `.github/workflows/ci.yml` — build + test on iOS Simulator

## Conventions

- SwiftData for persistence (`SavedArdaas`), no backend, fully offline.
- Canonical Ardaas text ships as a bundled JSON of ordered segments, each
  with `gurmukhi` / `transliteration` / `english`; one named benti slot.
- **Scripture accuracy is a hard gate**: any change to the canonical text
  requires Sarbloc's proof-read of the Gurmukhi before merge.
- Composition (segments + benti → render sequence) is pure logic and must be
  unit-tested.
- One logical change per PR, conventional commits, reference issues.
- macOS CI minutes are 10x on private repos — don't add CI jobs casually.
