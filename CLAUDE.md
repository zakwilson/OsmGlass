# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:7510c1e2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.


## Build & Test

_Add your build and test commands here_

```bash
# Example:
# npm install
# npm test
```

### Build requires a UTF-8 locale

The vendored OsmAnd build (`OsmAnd/gradlew`) copies test resources with non-ASCII
filenames (e.g. `resources/test-resources/search/ludwigstraße.json`). The JVM
decodes filenames using `sun.jnu.encoding`, which is derived from the OS locale —
**not** from `-Dfile.encoding`/`-Dsun.jnu.encoding` (those are ignored). Under a
POSIX/C or non-UTF-8 code page, `:OsmAnd-java:collectTestResources` fails with
`Failed to create MD5 hash for file '…ludwigstraße.json' as it does not exist`.

- **Linux/macOS/CI:** `OsmAnd/gradlew` now self-exports `LC_ALL=C.UTF-8` when the
  active locale isn't UTF-8 (`/etc/locale.conf` only applies to login shells, so
  non-login/CI contexts otherwise inherit POSIX). No action needed.
- **Windows:** a script can't change `sun.jnu.encoding` (it follows the system
  ANSI code page / `GetACP`, which `chcp` does not affect). `OsmAnd/gradlew.bat`
  warns if the system code page isn't UTF-8; the real fix is enabling
  *Settings → Time & Language → Administrative language settings → "Beta: Use
  Unicode UTF-8 for worldwide language support"* and rebooting.

## Architecture Overview

_Add a brief overview of your project architecture_

## Conventions & Patterns

_Add your project-specific conventions here_
