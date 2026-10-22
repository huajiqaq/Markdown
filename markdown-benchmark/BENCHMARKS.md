# Benchmark scenarios and comparisons

Execution and budget rules are in [AGENTS.md](AGENTS.md). Paths below are relative to this module.

## Suites

| Task | Workload and measurement | Output |
|---|---|---|
| `performanceGate` | Historical mixed full parse, lazy inline-heavy parse, complete streaming batch; fresh parser per operation | `build/reports/performance-gate/results.json` |
| `fullParsingBenchmark` | 3,000-line fence, 3,000 definitions, long multiline paragraph, materialized inline AST, 1,500-row GFM table with lazy cell inlines | `build/reports/full-parsing/results.json` |
| `incrementalScalingBenchmark` | Complete tail append / middle equal-length replace at 100 / 1,000 / 10,000 paragraphs, CommonMark and Extended | stdout |
| `failedInlineScalingBenchmark` | Fully materialized failed scans at 1,024 / 4,096 / 16,384 characters | `build/reports/failed-inline-scaling/results.json` |
| `parserCallSampleTimeBenchmark` | Closed-loop individual-call latency at fixed 100 / 1,000 / 10,000 paragraphs; lazy / materialized existing AST | `build/reports/parser-call-sample-time/results.json` |
| `growingBlockStreamingBenchmark` | Repeated small appends to one growing block, with optional consumption after each commit | stdout CSV plus `#` metadata |
| `:markdown-renderer:renderCompilerScalingBenchmark` | Parser commits and real render-model compilation at 100 / 1,000 / 10,000 stable paragraphs | stdout CSV plus `#` metadata |

JMH AverageTime percentiles describe iteration means. The scaling harnesses instead report empirical
individual-call samples; their setup and sample counts differ, so do not compare those percentiles
directly. The dedicated SampleTime suite below also samples individual calls. `mainBenchmark`
provides the broader JMH configuration; `ciBenchmark` is a smoke run.

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
| `--shapes` | Default: `single-line,multiline-paragraph,open-fence`; optional: `long-list,long-table` |
| `--flavours` | Default: `commonmark,extended`; optional: `gfm` |
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

Lists and tables deliberately keep one container growing, and can be expensive. They validate the
final AST's actual list/table type; an unsupported flavour cannot silently benchmark a paragraph.
Start with explicit small inputs; the default shape/flavour matrix remains unchanged:

```bash
./gradlew -q :markdown-benchmark:growingBlockStreamingBenchmark --args='--shapes long-list,long-table --flavours gfm,extended --sizes 1024,4096 --revision CANDIDATE_SHA'
```

## Individual parser-call SampleTime

```bash
./gradlew :markdown-benchmark:parserCallSampleTimeBenchmark
# Restrict a smoke run without changing the measured method:
./gradlew :markdown-benchmark:parserCallSampleTimeBenchmark -PsampleTimeParagraphs=100 -PsampleTimeOperations=append-commit -PsampleTimeExistingAst=lazy
```

This separate native JMH `SampleTime` workload reports **closed-loop sampled parser-call p95/p99**.
In its JSON, `mode` is `sample`, and `primaryMetric.scorePercentiles["95.0"]` / `["99.0"]` describe
sampled individual benchmark invocations (batch size one) in microseconds, rather than the historical gate's
AverageTime iteration means. Keep sample counts, histograms and run-to-run variation with the result;
a short run's rare percentiles cannot establish a production tail-latency guarantee.

Parameters are `paragraphs=100,1000,10000`, `operation=tail-replace,middle-replace,append-commit` and
`existingAst=lazy,materialized`. CommonMark is fixed. Eighteen cases use two JVM forks, one worker,
two 250 ms warmups, four 500 ms measurements and a 256/512 MiB heap: 90 seconds of scheduled warmup
and measurement, plus setup/fork overhead. The Gradle properties in the command restrict parameters.

Trial setup builds and parses the source outside timing. Replacements alternate `X` / `Y` at a
fixed tail or middle offset, ensuring real equal-length updates with constant size. Each streaming
append is exactly 16 characters with a 16-character threshold, so it commits; Invocation teardown
deletes that chunk to restore the original size. Lightweight fixture checks verify committed content
and source length without flattening the document. This is a stable-size append-call experiment;
use the growing-stream suite for accumulated output costs.

`materialized` prepares the complete existing AST in Trial setup and the changed/restored paragraph
in Invocation teardown; `lazy` leaves them unmaterialized. The sampled method calls only the parser
API, with a constant operation dispatch. Initial construction, restoration and AST consumption are
outside its sampled interval. Fixtures can still cause GC/cache effects, alter call spacing and add
timer/harness overhead. Pauses during restoration are omitted (coordinated omission), so these are
neither arrival-driven service tails nor UI/frame latency. The task intentionally omits the GC
profiler: fixture-inclusive bytes/op would not isolate parser-call allocation. Existing allocation
guards and the AverageTime gate remain separate and unchanged. This task joins the existing serial
benchmark ordering when requested with the other suites.

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

```bash
./gradlew -q :markdown-renderer:renderCompilerScalingBenchmark --args='--revision CANDIDATE_SHA'
```

This compiler harness separates `parser-commit` and real `DefaultRenderModelCompiler` cost, including
identity/revision scans, inline materialization and IR construction. Defaults use already displayed
inlines; the compiler still rebuilds its model on every update. `--initial displayed,cold` also isolates
the first materialization in a `compile-first-inline-materialization` row. Input/setup, initial display,
endStream, verification and IR consumption are excluded; stage time and thread allocation go to CSV.
It does not measure layout or Compose. Preserve its revision/environment metadata when saving stdout.
Here `cold` means an unmaterialized prefix AST in an already running JVM, not process cold start.
The default 40 warm samples (five first-materialization samples) provide observational percentiles;
fixed parameter order and short warmups can retain JIT/GC bias, so repeat fresh processes before comparison.
Stages share a heap: compiler allocation can trigger a pause in a subsequent parser sample.

Android Macrobenchmark measures real parser/compile/layout/Compose work: cold display, long-document
flings and cross-block selection. Its physical-device requirement and output path are in
[AGENTS.md](AGENTS.md). `InlineLayoutMetricsSnapshot` exposes renderer cache hits, misses, evictions,
entry counts and estimated bytes; common tests cover width/font-scale churn and bounded retention.

## Same-machine observations

Revision label `7972986-plus-verification`, JDK 21.0.10, macOS/aarch64, one process per CLI and five
measured trials. Compiler times are medians; MB means decimal mean allocated bytes per compile.

| Scenario | Observed change | Context |
|---|---|---|
| Growing list, 1,024 → 4,096 characters | Whole-stream allocation 10.6–14.5× | GFM / Extended; lazy / consumed snapshots |
| Growing table, 1,024 → 4,096 characters | Whole-stream allocation 12.8–14.2× | Same variants; batch includes consumption/bookkeeping |
| Displayed prefix, 100 → 10,000 paragraphs | Compiler 0.793 → 53.186 ms; 0.503 → 34.929 MB | Parser commit 0.090 → 0.117 ms |

Raw files: `build/reports/growing-block-streaming/structured.csv` and
`../markdown-renderer/build/reports/render-compiler-scaling/results.csv`. These are single-process
observations, not new budgets or worst-case proofs. They expose growing-container and whole-document
compiler costs; they do not measure layout/Compose. The historical baseline remains unchanged.

The next compiler optimization should first reuse immutable block IR in a bounded per-renderer
session, then consume trusted snapshots with stable block identities, semantic revisions, changed
regions and global-dependency versions. Public mutable AST input must retain full semantic validation;
parser origin alone cannot prove that callers have not changed it. Gate any fast path with full-compile
semantic equivalence, stable-prefix IR reuse, bounded visited/compiled nodes and allocation growth,
including stream finalization and global invalidation. Container-level list/table checkpoints follow;
neither cached IR nor faster parsing alone establishes end-to-end UI performance.
