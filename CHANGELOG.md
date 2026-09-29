# Changelog

All notable changes to HuoCode are documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning follows the API
contract version in [`doc/api.yml`](doc/api.yml) (currently 1.3.0).

## [Unreleased]

### Added

- Public README, Apache-2.0 license, and a dedicated configuration reference
  (`doc/CONFIGURATION.md`).
- Community guidelines: bug report and feature request templates, contributing guide, security
  policy.
- Asynchronous analysis pipeline (SQS-backed worker Lambda) for repositories above the synchronous
  threshold.
- JGit-based clone and churn analysis, removing the `git` binary from the runtime.
- In-JVM PMD cyclomatic complexity analysis (no subprocess).
- S3-backed result cache keyed by commit SHA, powering permanent shareable report URLs
  (`/report/{owner}/{repo}`) and the paginated file listing (`/report/{owner}/{repo}/files`).

### Changed

- Lambda-oriented defaults: hard file limit lowered from 20000 to 3000, job TTL lowered from 48
  hours to 1 hour.
- Active-job pointer cleanup now tolerates a missing `s3:DeleteObject` permission.
- Scoring fiche v1.3: `codeHealthScore` is now `round(max(20, 100 - 55*cn - 45*an))`, replacing the
  product of percentiles. Complexity is the worst method's cyclomatic complexity instead of the file
  total, and activity uses a double guard (churn above `mean + 2 sigma`, or at least 50 effective
  lines with a churn ratio above `mean + 2 sigma`).
- `repoHealthScore` is now a lines-of-code-weighted average of `codeHealthScore` across non-test
  files, so a small healthy file can no longer offset a large unhealthy one.
- `scoreVersion` in the API contract now advertises `1.3`, matching the implemented fiche.

### Fixed

- JGit `setDepth(0)` failure on full clones (depth is now applied only when requested).
- Ambiguous-object errors surfaced through the repository HEAD lookup.
- S3 job store no longer treats terminal or missing jobs as active.
- Activity thresholds now use the sample standard deviation (Bessel correction, `N-1`) instead of
  the population standard deviation.
- README and API contract no longer describe the superseded product-of-percentiles formula.

### Security

- Repository access is strictly `https` only, submodules are never recursed into, and analyzed code
  is never built or executed (read-only, syntactic analysis only).