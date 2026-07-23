# Rami Mihon personal rabbit repository

This starter is based on the public `rustedimac/extensions-source` branch
`add-ko-ntk`. It builds three independently configurable Korean extensions and
publishes their APKs and Mihon indexes to this repository:

- White Rabbit — Newtoki (`newtoki#.org`)
- Blue Rabbit — Toki (`toki#.com`)
- Red Rabbit — SBXH (`sbxh#.com`)

Blacktoon is intentionally not included. The original NTK package and source
IDs belong to White Rabbit so existing NTK library entries remain connected.

The workflow is manual: **Actions → Build and publish personal NTK extension →
Run workflow**.

Mihon repository URL after the first successful build:

`https://raw.githubusercontent.com/rami791121/rami-mihon/main/index.pb`

The signing key must be stored in GitHub Actions secrets and must never be
committed to this public repository.
