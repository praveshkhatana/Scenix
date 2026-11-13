# Streaming foundation release plan

## Goal

Turn the existing TV-first streaming foundation into a release candidate without pretending that
unreviewed providers, a physical television, or unbuilt client platforms are already complete.

## Guardrails

- All changes stay on `pegi/games-foundation`; no production TV, Hub, or user data is contacted.
- Community sources remain owner-installed and reviewed: this milestone never ships a third-party
  provider by default.
- Themes can change presentation only; playback, safety, focus and data policy remain host-owned.
- A passing check must exercise the public boundary it protects, not merely match a source string.

## Workstreams and acceptance evidence

| Workstream | Deliverable | Acceptance evidence |
| --- | --- | --- |
| Playback and language choice | Correctly distinguish control origin from media origin; retain an explicit audio-mix contract and fallback state. | JVM/Node contract tests plus isolated fixture playback with video and audio. |
| Trusted community sources | Make trust, review state, capability and health legible before enabling a source. | Hub/API self-test and TV/web rendering path; no arbitrary remote URL becomes trusted. |
| TV usability | Preserve a total D-pad route, focus restoration, readable settings groups and protected admin actions for every theme. | Focus regression gates and rendered emulator captures. |
| Theme/component platform | Keep shared tokens/contracts canonical and prevent Android/web drift. | Registry/hash parity checks and documented renderer boundary. |
| Release safety | Produce only side-by-side QA artifacts until a production device test is explicitly requested. | Debug/QA/release build, lint, signature and package verification. |

## Deliberately external follow-ups

1. Curating real community providers requires owner approval, legal policy and sustained provider
   monitoring; it cannot be replaced by a seeded list of arbitrary URLs.
2. A physical-TV/real-Hub test, target-device theme performance sweep and final update signing occur
   only against approved hardware and release credentials.
3. iOS/iPadOS/macOS renderers require separate platform projects; this milestone makes their shared
   contracts testable but does not claim clients that do not exist.

## Completion rule

The milestone is ready for review only when each changed contract has a focused test, the full
Android QA build/lint suite passes, and the isolated emulator proves native D-pad, keypad, video and
audio paths.  It remains a release candidate—not a production deployment—until the external
follow-ups are evidenced.
