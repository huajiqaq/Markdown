# Benchmark scenarios and comparisons

Execution and budget rules are in [AGENTS.md](AGENTS.md). Paths below are relative to this module.

## Suites

| Task | Workload and measurement | Output |
|---|---|---|
| `performanceGate` | Historical mixed full parse, lazy inline-heavy parse, complete streaming batch; fresh parser per operation | `build/reports/performance-gate/results.json` |
| `fullParsingBenchmark` | 3,000-line fence, 3,000 definitions, long multiline paragraph, materialized inline AST, 1,500-row GFM table with lazy cell inlines | `build/reports/full-parsing/results.json` |
| `incrementalScalingBenchmark` | Complete tail append / middle equal-length replace at 100 / 1,000 / 10,000 paragraphs, CommonMark and Extended | stdout |
| `failedInlineScalingBenchmark` | Fully materialized failed scans at 1,024 / 4,096 / 16,384 characters | `build/reports/failed-inline-scaling/results.json` |
| `growingBlockStreamingBenchmark` | Repeated small appends to one growing block, with optional consumption after each commit | stdout CSV plus `#` metadata |

JMH AverageTime percentiles describe iteration means. The scaling harnesses instead report empirical
individual-call samples; their setup and sample counts differ, so do not compare those percentiles
directly. `mainBenchmark` provides the broader JMH configuration; `ciBenchmark` is a smoke run.

Full-parse suites construct input outside measurement. Incremental scaling also excludes parser
setup, full-source String materialization and whole-AST traversal. Its `--coalesce 16` append reaches
the threshold and commits real parsing work. Allocation growth guards run separately in parser tests.

## Failed inline scans

Shapes are `unmatched-closers` (`a* `), `url-punctuation` (`https://a` plus a long dot suffix),
`directive`, `spoiler`, `unequal-backticks` and `wiki`. A text prefix prevents block-level interception;
each fresh-parser operation forces all inline containers. Input construction is excluded.

The suite uses two JVM forks, three 250 ms warmups, five 250 ms measurements and a 256/512 MiB
initial/maximum heap. Restrict parameters without editing the workload:

```bash
./gradlew :markdown-benchmark:failedInlineScalingBenchmark -PfailedInlineShapes=wiki,directive -PfailedInlineChars=1024,4096,16384
```

## Growing single-block streams

Defaults: three warmups, five measured streams, 1,024 / 4,096 / 16,384 ASCII characters, eight-character
chunks, 16-character coalescing, CommonMark / Extended, and both materialization modes. Heap is fixed
at 256/512 MiB. The stream starts empty; there is no large initial append or simulated arrival delay.

| Option | Values / meaning |
|---|---|
| `--sizes` | Comma-separated character counts; ASCII bytes equal UTF-16 units |
| `--chunk`, `--coalesce` | Positive chunk size; nonnegative parser coalescing threshold |
| `--shapes` | `single-line,multiline-paragraph,open-fence` |
| `--flavours` | `commonmark,extended` |
| `--materialize` | `none,commit` |
| `--warmups`, `--iterations`, `--revision` | Sampling configuration and recorded parser revision |

`none` leaves inline ASTs and deferred code literals lazy. `commit` traverses the tree and reads every
leaf literal after each publication and finalization; requesting the complete growing String has its
own output cost. Source/chunk construction, parser setup, sample arrays and final equality checks are
outside measurement. The complete `batch` envelope includes counter collection and bookkeeping.

CSV dimensions identify shape, flavour, size, chunk, threshold, materialization and operation. Rows
separate `buffer-only`, `commit`, `consume-commit`, `end-stream`, `consume-end` and `batch`. Nonempty
ASCII appends returning the previous root are buffered; committed updates publish a new root.
Metrics include sample count, p50/p95/p99/max, mean allocated bytes and cumulative time/allocation.
Totals span measured iterations; unsupported thread-allocation counters print `NA`. Batch percentiles
have only one sample per stream and are descriptive with small sample counts. Preserve the `#`
revision/JVM/OS/configuration metadata when saving stdout; strip it only for CSV import.

To inspect larger inputs, add `--sizes 4096,16384,65536` to the command in [AGENTS.md](AGENTS.md).
Keep small chunks when checking cumulative complexity. Parser JVM tests separately require less than
14-fold allocation growth for 512 versus 4,096 single-line / open-fence appends without reading each
published literal; final content checks occur outside the allocation counter.

## Comparing parser revisions

1. Prepare clean baseline and candidate checkouts with identical benchmark sources/build script,
   baseline JSON, dependencies and JDK. Copy the harness into an older checkout if absent; exclude
   `build/`. Record both parser revisions and the harness revision or patch.
2. Follow the same-environment and serial-execution rules in [AGENTS.md](AGENTS.md). Alternate revision
   order and collect at least three fresh-process runs each. Save JSON before it is overwritten;
   redirect each quiet growing-stream run to a separate CSV with its `--revision` label.
3. Compare matching configuration rows across all runs, including allocation, consumption and batch
   totals. Fourfold input with roughly fourfold cost suggests linear growth; roughly sixteenfold
   suggests quadratic growth on that input family, not a proof of worst-case complexity.
4. Recheck the historical gate, full-parse and incremental scaling suites to detect transferred costs.
   Keep correctness and deterministic growth checks separate from observational timings.

## Rendering checks

Android Macrobenchmark measures real parser/compile/layout/Compose work: cold display, long-document
flings and cross-block selection. Its physical-device requirement and output path are in
[AGENTS.md](AGENTS.md). `InlineLayoutMetricsSnapshot` exposes renderer cache hits, misses, evictions,
entry counts and estimated bytes; common tests cover width/font-scale churn and bounded retention.
