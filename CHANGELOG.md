# Changelog

All notable changes to the click2 Android SDK. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/). While the version is 0.x, minor versions may contain breaking changes; they are called out here.

## [Unreleased]

- In-app events and revenue: `Click2.track(name, revenue, currency, properties)` (suspend) and `trackInBackground`,
  credited to the link that last opened the app within `Click2Config.attributionWindowMillis` (default 7 days).
- `Click2.userId`: your user id, sent with installs and events for the team's integrations.
- `Click2Link.linkUrl`: the click2 link behind an email click-tracking URL.
- **Breaking (0.x):** `Click2Config` and `Click2Link` gained parameters, which changes their `copy()`/constructor
  signatures for code compiled against 0.2 (source-compatible thanks to defaults).

## [0.2.0] - 2026-09-30

First public release.

- Universal Links / App Links handling with per-platform routes (`OpenRoute`, `OpenWeb`, `Failed`, `NotAClick2Link`).
- Deferred deep links (Play Install Referrer, saved to disk and retried until resolved).
- Install and open attribution, with a tracking switch (`isTrackingEnabled`) for consent.
- Only the configured link hosts are ever contacted.

[Unreleased]: https://github.com/click2-page/click2-android/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/click2-page/click2-android/releases/tag/v0.2.0
