# Performance regression checks

This module contains complementary benchmarks and regression checks.

## Parser gate

Run the same forked JMH workload used by pull-request CI:

```bash
./gradlew :markdown-benchmark:performanceGate
```

The task runs `ParserMicrobenchmark` in a fresh JVM fork, records a JSON report at
`markdown-benchmark/build/reports/performance-gate/results.json`, and fails when any scenario
exceeds the checked-in budgets in `performance-baseline.json`.

The gate checks:

- primary-score p95 latency;
- throughput derived from average time;
- `gc.alloc.rate.norm` bytes allocated per operation;
- the additional peak heap observed during an iteration.

The streaming scenario uses the existing 16-character parser coalescing threshold, flushes on newlines,
and includes `endStream()` exactly once per measured operation. Longer local
runs are available through `mainBenchmark`; `ciBenchmark` is a shorter smoke configuration.

## Full-parse shapes

```bash
./gradlew :markdown-benchmark:fullParsingBenchmark
```

This separate forked JMH suite covers a 3,000-line code fence, 3,000 consecutive reference definitions,
a long multiline paragraph, a document whose inline AST is fully materialized, and a 1,500-row,
four-column GFM table that exercises metadata traversal while cell content remains lazy. The existing
`inlineHeavyParse` scenario intentionally retains lazy inline parsing. Input construction is outside
the measurements. Allocation and latency results are written to `build/reports/full-parsing/results.json`;
the existing CI gate and its budgets are unchanged. JVM allocation tests separately reject quadratic
copying as the large block grows from 100 to 1,000 lines.

### Updating budgets

Do not update the baseline from a single noisy run. Use the same JDK, OS image, CPU class, and power
mode as CI; collect at least three reports; compare profiles/traces; then change only the affected
budget. Keep enough tolerance for shared-runner variance, and explain the intentional regression or
optimization in the pull request.

## Incremental scaling

```bash
./gradlew :markdown-benchmark:incrementalScalingBenchmark --args='--warmups 20 --iterations 40'
./gradlew :markdown-benchmark:incrementalScalingBenchmark --args='--warmups 20 --iterations 40 --coalesce 16'
```

This reports complete append and equal-length replacement latency (median/p95) and thread allocation
at 100, 1,000 and 10,000 paragraphs, for CommonMark and Extended. Parser setup, String materialization
and full AST traversal are outside the measured call. Compare revisions using the identical harness,
JDK, machine and arguments. Timing is observational; the parser's JVM tests separately reject linear
allocation growth for a fixed-size tail update. See [algorithm and complexity](../markdown-parser/INCREMENTAL_PARSING.md).

`--coalesce 16` matches `LlmStreaming`. Each measured append reaches the threshold and actually commits
a parse; it does not report the cost of accepting a small buffered chunk as parser latency.

## Compose Macrobenchmark

The `macrobenchmark` module launches a release-like, profileable benchmark Activity and measures the
real parser/compile/layout/Compose pipeline. It includes cold first display, repeated long-document
flings, and a long-press drag that selects across visible Markdown blocks.

Run it on a physical Android device:

```bash
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

Macrobenchmark writes frame/startup metrics, JSON output, and Perfetto traces under
`macrobenchmark/build/outputs/connected_android_test_additional_output/`. Compare results only on a
stable device pool; emulator numbers are suitable for functional smoke checks, not performance
baselines.

## Inline cache telemetry

The renderer's two inline caches report hit, miss, eviction, entry-count, and estimated-byte
snapshots through `InlineLayoutMetricsSnapshot`. Both caches are bounded by entry count and estimated
retained bytes, and the common tests exercise width/font-scale churn to prevent an unbounded resize
working set.
