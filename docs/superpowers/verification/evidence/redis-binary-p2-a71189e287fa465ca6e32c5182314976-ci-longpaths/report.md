# Redis binary key CI Windows checkout correction

Base: 3af30f12ea225c872b1d65b831ab0a136ef05e18. Branch: codex/redis-binary-ci-longpaths-20261010. Review only; no commit.

CI run38034885345 / Windows job114163146217 failed at checkout with Filename too long before Java/tests. Root original log: D:/Projects/朝花夕拾/build/owned-redis-binary-ci-28432caaf3794abbb146f7e4a0f13b97/first-windows-job-raw.log.

Only .github/workflows/verify.yml and release.yml change, adding four env lines to each Windows-capable actions/checkout step. Verify matrix uses runner.os conditional COUNT1 Windows/COUNT0 Linux; release COUNT1. core.longpaths=true is supplied through step GIT_CONFIG_COUNT/KEY_0/VALUE_0. No persistent Git configuration, job/trigger/filter/permission/publication changes. All remaining workflow lines match base; schema-diff integration stays unchanged. Static audit covers all6 checkout steps,2 Windows-capable.

Synthetic fixture only: unique owned Temp runtime, isolated empty global/system Git config, no real clone/network. Same repository, commit bbb131292518678132b193c5d7d88a7a9271f8fa, tree a70bde3c62b3c4344f96a2265b20bcf2819493dd, blob d2d0034712dec9536c09e6ed593bfffd51d3aa83, absolute path length387. Exactly same checkout argv: step env false actual exit128 with explicit Filename too long; step env true actual exit0; actual file68 bytes SHA256 e6c8b1a5e44792ddf82bbd106a6bf9412c6e7820e28e3b54f235da7111c3c4d0 matches original blob byte-for-byte. Nine bounded Git commands, independent private Jobs observed empty; raw command/environment/stdin hashes/stdout/stderr/actual exits saved. Runtime/Git metadata excluded from evidence.

Current engineering input inventory878. Only two tracked workflow inputs differ; source/tests/shared tools unchanged. No Gradle/control rerun, real services/configuration/clipboard, frozen-original edits, main merge, push or tag. Git execution used D:/Git/cmd/git.exe, version in git-version.stdout.

Requirement | Evidence
--- | ---
Windows checkout fails without compatibility | checkout-false-command.json + raw stderr + actual nonzero exit
Same object checkout succeeds with step env | checkout-true-command.json + raw streams + actual exit0
Original bytes preserved | expected-file.bin, cat-file.stdout, result.json SHA
All Windows checkout paths covered and step-only env | static-audit.json, workflow.diff
