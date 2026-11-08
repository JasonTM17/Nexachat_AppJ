# RAG evaluation

`run-benchmark.sh` (or `run-benchmark.ps1`) runs `RagBenchmarkEvaluationTest`:
a synthetic Vietnamese + English corpus of 32 conversations and 192 lookup
questions with gold message ids. It prints recall@3/@5, MRR, citation validity,
the share of requests answered with zero LLM calls, and the input-character
reduction against the whole-window baseline.

The benchmark needs no database and no external key; it uses the offline
lexical embedding provider.
