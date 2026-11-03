# Security Policy

Scenix is a self-hosted Android TV client (`app/`) and a local Node.js hub (`hub/`). The hub has no
npm dependencies and runs on Node built-ins; the app is Kotlin and ships a small set of third-party
libraries (AndroidX, Media3, GeckoView, libtorrent4j). This document covers the code in this
repository, the CI security pipeline, release artifacts, and the add-on trust model.

## Supported versions

| Component | Supported |
| --- | --- |
| Android app | The latest `v*` release tag, plus the current `main` and `games-mode` branches |
| Hub | The current `main` and `games-mode` branches |
| Older tags and snapshots | Not supported; please reproduce on a supported revision |
| Node.js runtime | 18 or newer (the hub uses Node built-ins and has no npm dependencies) |
| Android platform | Android 11 / API 30 is the target device; `minSdk` is 26 |

The app currently reports `versionName 0.1-p1` / `versionCode 1`. Until a release is cut, treat
branch builds as development artifacts rather than production releases.

## Reporting a vulnerability

**Do not open a public issue for a suspected vulnerability.**

1. Use GitHub's private vulnerability reporting: open the repository's **Security** tab and choose
   **Report a vulnerability**. This creates a private security advisory visible only to the
   maintainers.
2. If private reporting is unavailable, open a minimal public issue asking for a private channel.
   Do not include exploit details, tokens, personal data, or media URLs in that issue.

Please include the affected component (app or hub), the revision/commit, reproduction steps, and the
impact you believe it has. Proof-of-concept files are welcome but must be safe to open.

What to expect, on a best-effort basis:

- acknowledgement within 3 business days;
- an initial triage and severity assessment within 10 business days;
- a fix or mitigation plan within 90 days for confirmed high-severity issues;
- credit in the advisory unless you ask to remain anonymous.

Please do not test against other people's hubs, media servers, accounts, or devices. Do not run
denial-of-service or high-volume automated scans against infrastructure you do not own. If you need a
safe target, run the hub locally from this repository and use the shipped fixture data in `app/`.

## In scope

- `app/` — Kotlin client, manifest, assets, Gradle build, release APK.
- `hub/` — Node hub, built-in providers/rows/rules, HTTP routes, add-on host, media/torrent plumbing.
- `scripts/` — deployment, installation, and security scripts.
- `.github/` — CI workflows and dependency automation.
- Release artifacts published from this repository.

Out of scope: third-party add-on servers, IPTV/torrent sources, upstream libraries (report those to
their maintainers), the TV's firmware, and content rights or legality questions.

## What the CI pipeline scans

`.github/workflows/security.yml` runs on every pull request, on pushes to `main` and `games-mode`, on
`v*` tag pushes, and on manual dispatch. Everything uses free GitHub-hosted runners and free features
available to public repositories.

| Job | What it does |
| --- | --- |
| **CodeQL** | Static analysis with a `java-kotlin` matrix entry (autobuild, with a fallback that runs `app/gradlew assembleDebug`) and a `javascript-typescript` entry. Uses the `security-extended` query pack and uploads SARIF. |
| **Semgrep** | Runs the official `semgrep/semgrep` container with `p/security-audit`, `p/owasp-top-ten`, and `p/secrets` at ERROR severity. Generated, build, and runtime directories are excluded. Findings fail the job and SARIF is uploaded. |
| **Gitleaks** | Scans the full Git history on pushes and manual runs, and only the PR commit range on pull requests. Runs the official gitleaks CLI container; no license key is required. |
| **Dependency review** | Runs on pull requests and fails when a newly introduced dependency has a high-or-worse advisory. Works from the GitHub dependency graph for Gradle; npm is not present today. |
| **Android** | Sets up JDK 17 and the Android SDK, then runs `lintDebug assembleDebug`. Uploads the debug APK and lint report as workflow artifacts. |
| **Release APK + VirusTotal** | On tag pushes and manual dispatch, builds the release APK when possible, publishes its SHA-256 in the job summary and as an artifact, and calls `scripts/virustotal-scan.sh` when the optional `VT_API_KEY` secret exists. |

Local equivalents: `scripts/scan-local.sh` runs gitleaks, Semgrep, and `npm audit` when they are
installed and prints which tools were skipped.

CI is a safety net, not a proof of safety. A clean run means the configured rules found nothing; it
does not mean the code is free of vulnerabilities. Manual review is still required for changes in
`app/src/main/java/com/scenix/tv/core/`, `hub/core/`, `scripts/`, and the release/signing path.

## Release signing and provenance

**Current state:** release builds use the Android debug signing key:

```groovy
release {
    signingConfig signingConfigs.debug
}
```

There is no keystore, keystore password, or signing property checked into this repository, and the
release APK produced by CI is therefore **debug-signed**. It is suitable for testing and sideloading,
not for treating as a production-trusted build. Verify the certificate before trusting any APK:

```bash
apksigner verify --print-certs app-release.apk
```

**Planned production signing** (not yet implemented in this repository):

1. Generate a dedicated release keystore offline. Never commit it (`*.jks`, `*.keystore`, `*.p12`,
   `keystore.properties`, and `signing.properties` are covered by `.github/CODEOWNERS`).
2. Store the keystore bytes and passwords in GitHub Actions secrets or an external HSM/KMS. The
   release workflow reads them from the environment and deletes temporary files after signing.
3. Publish the APK together with its SHA-256, the GitHub Actions run URL, the commit SHA, and the
   VirusTotal analysis link in the release notes.
4. Consumers can check provenance with:
   ```bash
   sha256sum -c release-apk.sha256
   apksigner verify --print-certs app-release.apk
   ```

The intended provenance chain is: reviewed commit → GitHub Actions run on the protected branch or
tag → checksum → certificate fingerprint → optional VirusTotal report. A future improvement is to
sign artifacts with Sigstore/cosign or GitHub artifact attestations, but that is not enabled yet.

## No arbitrary add-on code

This is a hard rule, not a preference:

- **Native add-ons** are files under `hub/providers/`, `hub/rows/`, or `hub/rules/` that export a
  documented module contract. They are first-party code, loaded at hub boot by `hub/core/registry.js`,
  and must be reviewed like any other code in `hub/core/`.
- **External add-ons** are installed at runtime from `hub/data/addons.json` and are **data only**:
  a manifest URL, catalogs, streams, subtitles, and the static `games.json` file. The hub must never
  download and execute JavaScript, WebAssembly, or native code from an external add-on.
- Do not add `eval`, `new Function`, `vm.runIn*`, dynamic `require()`/`import()` of downloaded
  content, shell execution of add-on-supplied strings, or any other path that turns remote content
  into code in the hub process.
- The Android app must not load add-on code into its process. Remote game pages may run inside the
  bundled GeckoView engine as web content; that is browser sandboxing, not an add-on execution API.
- A pull request that adds an arbitrary-code-execution path for remote content will be rejected even
  if it is gated behind a setting or an "admin only" flag.

If you find a bypass of this model, report it privately using the process above.

## GitHub settings that must still be enabled manually

These controls live in repository settings, not in the repository tree, so they are not applied by a
merge:

1. **Protect `main` and `games-mode`** (Settings → Branches, or repository rulesets):
   - require a pull request before merging;
   - require review from Code Owners (this makes `.github/CODEOWNERS` mandatory);
   - require the status checks: `CodeQL (java-kotlin)`, `CodeQL (javascript-typescript)`, `Semgrep`,
     `Gitleaks (secrets)`, `Dependency review` (PRs only), and `Android lint + debug APK`;
   - dismiss stale approvals and require branches to be up to date before merging;
   - block force pushes and deletions.
2. **Protect release tags** (`v*`) with a ruleset so only maintainers can create or move them.
3. **Enable secret scanning and push protection** (Settings → Code security). Both are free for public
   repositories and stop credentials before they enter history, complementing the Gitleaks job.
4. **Enable Dependabot alerts and Dependabot security updates** so `.github/dependabot.yml` findings
   are actionable.
5. **Enable private vulnerability reporting** so the disclosure route above works.
6. **Enable code scanning** for the repository (Settings → Code security) so the CodeQL and Semgrep
   SARIF uploads are collected on the default branch.
7. **Create the optional `VT_API_KEY` repository secret** with a free VirusTotal API key. Without it,
   the release job cleanly skips the VirusTotal step and still publishes the checksum.
8. Optionally restrict Actions to selected actions and require signed commits for the protected
   branches.
