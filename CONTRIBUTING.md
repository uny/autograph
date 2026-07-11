# Contributing

Thanks for considering a contribution to Autograph.

## Before you start

For anything beyond a small fix, please open an issue first to discuss the change.
This project is in early development and APIs are still moving, so it helps to align
on direction before investing time in a PR.

## Development

- Requires JDK 21 and Kotlin/Compose Multiplatform toolchains (Android SDK, Xcode for
  iOS targets). See the [Requirements](README.md#requirements) section of the README.
- Build and test: `./gradlew build`
- The `autograph-segment-swift` package has its own test suite; see
  [`AutographSegmentSwift/`](AutographSegmentSwift).

## Pull requests

- Keep PRs focused on a single change. Unrelated cleanup makes review harder.
- Add or update tests for behavior changes.
- Make sure CI passes (`./gradlew build`) before requesting review.
- Follow the existing commit style (`type: short description`, e.g. `fix: ...`,
  `feat: ...`, `chore: ...`).

## Reporting bugs

Open a [GitHub issue](https://github.com/uny/autograph/issues/new) with steps to
reproduce, expected vs. actual behavior, and relevant environment details (platform,
Kotlin/Compose versions).

For security issues, please follow [SECURITY.md](SECURITY.md) instead of filing a
public issue.
