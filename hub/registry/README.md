# Reviewed sources

`reviewed-addons.json` is a small, repository-owned curated directory—not a remote marketplace.

An entry is accepted only when it has a stable ID, semver revision, credential-free HTTPS
`/manifest.json` address, expected remote manifest ID, and an exact resource/type surface. At
installation the Hub verifies that live manifest again before persisting it. A later change is
quarantined instead of silently becoming a different source.

The shipped entries are limited to the official metadata, subtitle, and availability-link services
used by the fresh-install Hub path. Their versions, identities, resources, redirect behavior, and
public manifests are checked at install time; version or capability drift is quarantined until it is
reviewed again. One official metadata manifest advertises the protocol's `addon_catalog` extension.
The Hub tolerates that unused declaration for validation but never turns an upstream marketplace
into a curated source or an automatic install path.

Every additional source needs an independent availability, content-scope, privacy, redirect, and
capability review before being added here. Community discovery, if offered, stays visibly separate
from this curated list. Manual URLs remain an explicitly advanced, unreviewed path and are never
auto-promoted into this directory.
