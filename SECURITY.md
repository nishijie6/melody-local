# Security policy

## Supported version

Security fixes are applied to the latest published version. Older APKs may not receive separate patches.

## Report a vulnerability

Do not disclose an unpatched vulnerability in a public issue.

Use GitHub's private vulnerability reporting or a private Security Advisory for `nishijie6/melody-local`. Include:

- the affected version and Android version
- clear reproduction steps
- expected and actual behavior
- impact and any known workarounds
- logs or proof-of-concept material with personal data removed

If private reporting is unavailable, open a public issue asking the maintainer to provide a private contact channel, without including vulnerability details.

## Sensitive repository material

Release signing keys and passwords are never part of the public repository. Maintainers must keep these files outside version control:

- `release-signing/*.jks`
- `keystore.properties`
- personal `local.properties`

Do not upload private keys to issues, Actions artifacts or GitHub Releases. Losing the release key prevents future versions from upgrading installations signed with that key; exposing it allows unauthorized APKs to impersonate the application.

## Scope

Useful reports include permission bypasses, unintended file disclosure, malicious LRC handling, MediaStore URI misuse, unsafe exported components, notification or MediaSession control vulnerabilities, and release-signing failures.

Feature requests and ordinary playback bugs can use the public issue tracker.
