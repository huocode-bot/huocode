# Configuration

HuoCode runs on AWS Lambda via [Poja](https://poja.io). Runtime settings are environment variables,
overridable from the Poja console without a rebuild. Defaults below are the in-code fallbacks
(`src/main/java/io/huocode/api/conf/AnalysisProperties.java`).

| Variable | Default | Description |
|---|---|---|
| `GITHUB_TOKEN` | *(empty)* | Fine-grained GitHub personal access token (public repos, read-only). Required for the 5 000 req/hour quota; without it the anonymous 60 req/hour limit makes analyses fail with `429`. |
| `huocode.api-direct-file-threshold` | `300` | File count below which the small-repo strategy (direct GitHub API, no clone) is used. |
| `huocode.hard-file-limit` | `3000` | Maximum number of analyzable files. Above it the analysis is rejected (`REPO_TOO_LARGE`), never silently truncated. |
| `huocode.commit-window` | `500` | Number of commits analyzed for churn (also sets the clone depth). |
| `huocode.min-files-for-relative-scoring` | `15` | Below this many analyzable files, relative scoring is disabled entirely; only absolute complexity is reported. |
| `huocode.exceeds-common-complexity-threshold` | `10` | Files with raw cyclomatic complexity above this get `exceedsCommonComplexityThreshold = true` (the widely cited McCabe "> 10" rule of thumb). |
| `huocode.max-file-size-bytes` | `1048576` (1 MiB) | Larger files are marked `error` / `FILE_TOO_LARGE` rather than analyzed. |
| `huocode.job-ttl` | `PT1H` | Lifetime of an async job record; expired jobs are purged and reported as `JOB_NOT_FOUND`. |

> Durations use ISO-8601 syntax (`PT30S`, `PT5M`, `PT1H`), parsed by `java.time.Duration`.