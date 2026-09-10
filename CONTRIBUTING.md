# Contributing

Thanks for considering a contribution. HuoCode is a small, opinionated project: before sending
anything large, open an issue to discuss the approach and avoid wasted work.

## Getting started

- Read the [README](../README.md) for the product overview and [`doc/api.yml`](../doc/api.yml) for
  the API contract.
- Runtime knobs are documented in [`doc/CONFIGURATION.md`](../doc/CONFIGURATION.md).

## Conventions

- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:` (lowercase, no
  `#` in the message).
- Format the code with `./format.sh` (`format.bat` on Windows) before committing. The CI runs the
  formatter and fails on any formatting diff.
- Keep the test suite green; the CI enforces the JaCoCo 80% coverage gate. The integration test
  `AnalyzeFlowIT` needs a `GITHUB_TOKEN`, which the CI provides.
- Never edit `@PojaGenerated` files: they are regenerated from `doc/api.yml` on every deployment
  and hand edits get overwritten.

## Branching and deployment

- Open pull requests against `preprod`.
- Pushes to `preprod` run the CI pipeline; production deploys from the `prod` branch through the
  Poja console.

## Scope guardrails

- `doc/api.yml` is the source of truth for the API. New endpoints or fields go into the spec
  first, since it regenerates the API models.
- Backends stay language-neutral: response fields are numbers and stable enum codes, never
  human-readable sentences.
- Read-only by construction: analyzed code is never built, executed, or stored.