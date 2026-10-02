# Changelog

All notable changes to the click2 Android SDK. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/). While the version is 0.x, minor versions may contain breaking changes; they are called out here.

## [Unreleased]

### Changed

- Campaign install reports (Play install referrer without a click2 link) now send up to 4,000 characters of the
  referrer instead of 1,000. Meta ads referrers carry encrypted JSON in `utm_content` and are often longer than 1,000
  characters; a truncated one can't be decrypted by the server.

### Tests

- Shared fixture `campaign-referrer.json`: a Meta ads install referrer case (reported as a campaign install).

### Docs

- README: install snippet at 0.3.1; app settings are now under Apps & SDKs → App settings in the click2 dashboard.

## [0.3.1] - 2026-10-02

### Fixed

- Organic Play installs (`utm_source=google-play&utm_medium=organic`, or google-play with no other campaign keys) and
  click2 referrers whose link is for another host are no longer reported as campaign installs. Campaign keys are
  matched as parameter names with a value (`utm_*`, `gclid`, `gbraid`, `wbraid`), not as substrings.
- Install reports answered with HTTP 408 or 429 are retried on a later launch instead of being treated as final.
- Configured hosts are normalized once (trimmed, lowercased, trailing dot removed) and that list is used everywhere,
  so the last link's host matches for `track()` attribution when hosts were configured with capitals or a trailing
  dot. Surrounding spaces are now accepted instead of throwing.
- Shared fixtures: an encoded slash in the first path segment (`/api%2Fx`) is decoded before splitting (not a link);
  new `campaign-referrer.json`.

## [0.3.0] - 2026-10-01

- Play Store campaign installs (UTM / gclid in the install referrer) without a click2 link are reported for attribution.
- `Click2Link.variant` (the link rule or A/B variant), remembered for event attribution; requests send `Accept-Language`
  (link rules by language).
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

[Unreleased]: https://github.com/click2-page/click2-android/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/click2-page/click2-android/releases/tag/v0.3.1
[0.3.0]: https://github.com/click2-page/click2-android/releases/tag/v0.3.0
[0.2.0]: https://github.com/click2-page/click2-android/releases/tag/v0.2.0
