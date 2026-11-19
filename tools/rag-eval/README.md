# RAG evaluation

`run-benchmark.sh` (or `run-benchmark.ps1`) runs `RagBenchmarkEvaluationTest`:
a synthetic Vietnamese + English corpus of 32 conversations and 192 lookup
questions with gold message ids. It prints recall@3/@5, MRR, citation validity,
the share of requests answered with zero LLM calls, and the input-character
reduction against the whole-window baseline.

The benchmark needs no database and no external key; it uses the offline
lexical embedding provider.

## Current baseline

Command (exit 0, 2026-11-19, base `07ce491e`):
`mvn -o -B -f server/pom.xml test -Dtest=RagBenchmarkEvaluationTest`

Verbatim output (locale prints decimals with `,`):

```text
rag-eval: conversations=32 messages=6400 lookupQuestions=192 recall@3=1,000 recall@5=1,000 mrr=1,000 citationValidity=1,000 zeroLlm=70,0% meanInputChars=1640,6 baselineChars=7951,4 reduction=79,4%
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```
