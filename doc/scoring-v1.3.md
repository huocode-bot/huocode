# Scoring Fiche v1.3

## Overview

HuoCode computes a **code health score** (0-100) for each analyzed Java file
and a **repository health score** as the LoC-weighted average of all non-test
file scores. The score combines two signals:

1. **Cyclomatic complexity** -- worst-method complexity per file (PMD)
2. **Code churn** -- relative activity over a configurable commit window

The model is inspired by CodeScene's hotspot detection and the academic
research of Nagappan & Ball (Microsoft) on relative code churn.

## Per-File Score Formula

```
codeHealthScore = round(max(20, 100 - 55 * cn - 45 * an))
```

| Symbol | Meaning                  | Range     |
|--------|--------------------------|-----------|
| cn     | Normalized complexity penalty | [0.0, 0.6] |
| an     | Normalized activity penalty   | [0.0, 1.0] |
| MIN_SCORE | Floor (no file scores below) | 20       |

### Complexity Penalty (cn)

```
if complexity <= exceedsCommonComplexityThreshold (default: 10):
    cn = 0.0
else:
    cn = min(0.6, (complexity - threshold) / 40.0)
```

The threshold of 10 follows McCabe/SEI/NIST industry consensus.
The penalty is capped at 0.6 (reached at complexity = 50).

**Note:** Complexity is the **maximum** method-level cyclomatic complexity
in the file (not the sum). This aligns with the per-function convention
(McCabe > 10, SEI risk categories) and CodeScene's method-level approach.

### Activity Penalty (an)

A file must pass a **double guard** to be considered "active":

| Branch | Condition |
|--------|-----------|
| Volume | effectiveLines > 0 AND effectiveLines >= mean + 2 sigma |
| Ratio  | effectiveLines >= 50 AND (effectiveLines / LOC) >= mean + 2 sigma |

If active:

```
an = min(1.0, log(1 + ratio) / log(1 + ratioMax))
```

If not active: an = 0.0

The log-normalization produces diminishing returns, preventing extreme
churn from dominating the score. The double guard catches both:
- Large files with high absolute churn
- Small files with high relative churn

## Churn Calculation

```
effectiveLines = linesAdded + linesDeleted
```

- Merge commits are excluded
- Bot commits (dependabot, renovate, github-actions) are filtered out
- Blank lines are not counted (only meaningful content)
- Default commit window: 500 commits

The ratio `effectiveLines / linesOfCode` is the "Relative Code Churn"
metric from CodeScene and Microsoft Research.

## Percentile Computation

Percentiles use a CDF (Cumulative Distribution Function):
- Files sorted by metric value
- Tied values receive the same percentile
- percentile = round(100 * upperBoundIndex / totalFiles)

## Repository Health Score

```
repoHealthScore = round(SUM(codeHealthScore_i * LOC_i) / SUM(LOC_i))
```

Test files (paths containing `/test/`, `*Test.java`, `*IT.java`) are
excluded from the average. The LoC-weighted average ensures larger files
carry proportionally more weight.

## Quadrant Classification

| Active | Complex | Quadrant       |
|--------|---------|----------------|
| yes    | yes     | HOTSPOT        |
| no     | yes     | COMPLEX_STABLE |
| yes    | no      | FREQUENT_SIMPLE|
| no     | no      | HEALTHY        |

## Configuration

All values are configurable via environment variables (overridable from the Poja console without a rebuild).

| Env Var | Default | Description |
|---------|---------|-------------|
| `huocode.commit-window` | 500 | Commits analyzed for churn (also sets clone depth) |
| `huocode.min-files-for-relative-scoring` | 15 | Below this many analyzable files, relative scoring is disabled |
| `huocode.exceeds-common-complexity-threshold` | 10 | McCabe > 10 rule of thumb for the complexity flag |
| `huocode.max-file-size-bytes` | 1048576 (1 MiB) | Files above this size are marked FILE_TOO_LARGE |
| `huocode.hard-file-limit` | 3000 | Maximum analyzable files per repository |

## Limitations

- Only Java files are analyzed for complexity
- Relative scoring is disabled below 15 analyzable files (`huocode.min-files-for-relative-scoring`)
- Maximum 3000 files per repository (`huocode.hard-file-limit`)
- Files larger than 1 MiB are excluded (`huocode.max-file-size-bytes`)

## References

- McCabe, T.J. (1976). "A Complexity Measure"
- Nagappan, N. & Ball, T. (2005). "Use of Relative Code Churn Measures
  to Predict System Defect Density"
- Tornhill, A. (2015). "Your Code as a Crime Scene"
- CodeScene Code Health documentation
- SEI/Carnegie Mellon C4 Software Technology Reference Guide
- SonarSource Cognitive Complexity white paper
