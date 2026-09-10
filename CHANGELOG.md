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

### Fixed

- JGit `setDepth(0)` failure on full clones (depth is now applied only when requested).
- Ambiguous-object errors surfaced through the repository HEAD lookup.
- S3 job store no longer treats terminal or missing jobs as active.

### Security

- Repository access is strictly `https` only, submodules are never recursed into, and analyzed code
  is never built or executed (read-only, syntactic analysis only).