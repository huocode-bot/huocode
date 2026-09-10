<div align="center">

<img src="assets/logo/horizontal-logo.svg" alt="HuoCode" width="380"/>

**Free, instant hotspot analysis for any public GitHub repository.**

[![License](https://img.shields.io/github/license/huocode-bot/huocode)](LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/huocode-bot/huocode?style=social)](https://github.com/huocode-bot/huocode/stargazers)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.2-6DB33F)
[![OpenAPI](https://img.shields.io/badge/API-1.3.0-blue)](/doc/api.yml)

</div>

---

## What is HuoCode?

HuoCode analyzes a public GitHub repository and produces a **hotspot map**: the files that deserve
attention first, by intersecting **high cyclomatic complexity** with **frequent modification**.
This methodology has been validated in the industry for over a decade (the same foundation CodeScene
builds on).

Paste a GitHub URL, get a visual answer within seconds. Zero install, zero setup.

For every file in the analyzed repository, HuoCode computes:

- a per-file **Code Health Score** (0 to 100) and a **quadrant** explaining *why* the file is flagged,
- raw complexity and churn numbers (`complexity`, `commits`, `authors`),
- the analysis **window** behind the result (number of commits, distinct authors, date range),

and it aggregates everything into a single **Repo Health Score**.

### Quadrants

| | Low churn | High churn |
|---|---|---|
| **High complexity** | `complex_stable`: complex but rarely touched, lower urgency | `hotspot`: complex AND frequently changed, **top priority** |
| **Low complexity** | `healthy`: nothing to flag | `frequent_simple`: changed often but simple, generally fine |

### Scope (v1)

v1 analyzes **Java only**. Files in any other language are explicitly marked `unsupported_language`.
They are never silently ignored, never blended into the scores. The language roadmap is
[below](#roadmap).

## How scoring works

- Per-file `codeHealthScore` runs **0 to 100, where 100 = the healthiest file in the repository**
  (low complexity *and* rarely modified). Scores near 0 are **hotspots**: complex and frequently
  changed, i.e. the files worth reviewing first. `repoHealthScore` is the unweighted average across
  all analyzed files.
- Scoring is **relative within the analyzed repository** (percentile normalization), never a
  universal absolute scale. Below 15 analyzable files, relative scoring is disabled entirely and
  only the raw complexity signal is reported. Fields are omitted, never fabricated.
- The complexity signal is also carried separately: `complexityPercentile` (100 = the most complex
  file in the repository) and `exceedsCommonComplexityThreshold` (raw complexity > 10, the widely
  cited McCabe rule of thumb).
- Honest by design: this tool never states "this file is bad" as fact. It surfaces *comparative*
  signals for human review. See the disclaimer below.

## API

A public, read-mostly API. No authentication required. `POST /analyze` is rate-limited and may
require a Cloudflare Turnstile step-up challenge for suspicious traffic.

| Endpoint | Purpose |
|---|---|
| `POST /analyze` | Trigger analysis. Returns the result synchronously (cached or small repository) or a `202` job id (larger repository, processed asynchronously). |
| `GET /analyze/{jobId}` | Poll an async analysis until it completes or fails. |
| `GET /report/{owner}/{repo}` | **Permanent, shareable result** for the latest analyzed commit. Cached results are returned instantly and free. |
| `GET /report/{owner}/{repo}/files` | Paginated, sortable file listing with per-status filtering. |
| `GET /examples` | Suggested repositories for a first analysis. |
| `GET /health` | Service health. |

Every response field is a number or a stable enum code. The API never emits human-readable
sentences, so any client can localize the rendering. Full contract, examples and schemas:
[`doc/api.yml`](/doc/api.yml) (OpenAPI 3.0).

## Architecture

- **Hexagonal layout** (`port/`, `adapter/`, `service/`, `scoring/`, `retrieval/`, package
  `io.huocode.api`).
- **JGit for clone and churn analysis**, no `git` binary on the runtime, Lambda-friendly.
- **PMD as a JVM library** for cyclomatic complexity, no subprocess.
- **Two retrieval strategies** selected by file count: direct GitHub API calls for small
  repositories, shallow clone + `git log --numstat` for larger ones.
- **S3-backed cache** keyed by `{owner}/{repo}/{commit SHA}`. Immutable keys power the permanent
  `/report/{owner}/{repo}` links.
- **Sync/async boundary** driven by repository size: small repositories answer synchronously,
  larger ones are processed asynchronously behind a queue and polled by the client.
- **Read-only by construction**: code from analyzed repositories is never built, executed or hooked.
  Static, syntactic analysis only.

## Configuration

Runtime knobs (GitHub token, file limits, commit window, scoring thresholds...) are environment
variables. See [**`doc/CONFIGURATION.md`**](/doc/CONFIGURATION.md).

## Roadmap

- **More languages**, added as new analyzers without touching the API contract:
  JavaScript/TypeScript (ESLint complexity), Python (`radon`), Go (`gocyclo`), C (`lizard`).
- Private repositories via GitHub OAuth.
- Trend tracking over time: is a hotspot getting better or worse, commit after commit.
- Pull-request bot integration.
- Temporal coupling between files (files habitually changed together).
- Function-level drill-down ("X-Ray").

## Disclaimer

HuoCode identifies areas that deserve human review, based on complexity and modification-frequency
metrics. It is **not a security audit**, nor a guarantee of code reliability. For critical projects
(finance, healthcare, infrastructure), manual review by experts and dedicated audit tools remain
essential.

## License

[Apache-2.0](LICENSE). See the [license file](LICENSE) for details.