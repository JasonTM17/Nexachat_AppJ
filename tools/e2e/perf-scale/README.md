# Server scale benchmark

This benchmark uses only the disposable schema nexachat_qa_20261105 in the
existing NexaChat PostgreSQL database. It expects all current Flyway migrations
to have been applied there by the integration-test harness. It never restarts
a container and never reads or writes the public data tables. Its search path
contains only the QA schema; shared extension functions are schema-qualified.

From the repository root, run:

    .\tools\e2e\perf-scale\run.ps1

The wrapper streams benchmark.sql to the already-running nexachat-db-1
container using the compose database role. Pass -ContainerName if the
container has a different name. The SQL transaction removes any previous
benchmark fixtures, seeds 200,000 synthetic TEXT messages across 50 DIRECT
conversations, runs the requested EXPLAIN (ANALYZE, BUFFERS) plans, builds a
temporary immutable-unaccent/trigram index for the after comparison, and
removes the fixtures and temporary index/function before commit. If a statement
fails, PostgreSQL rolls the transaction back, including the fixture inserts.

The before-search query calls `public.unaccent(content)` and the after-search
query uses the proposed indexed expression `qa_immutable_unaccent(content)`.
Production still needs to change the search repository expression to use the
same immutable wrapper and add the function/index in a Flyway migration. The
SQL prints extension availability and installed schema before benchmarking.
