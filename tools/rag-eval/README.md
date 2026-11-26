# RAG evaluation

`run-benchmark.sh` (or `run-benchmark.ps1`) runs `RagBenchmarkEvaluationTest`:
a synthetic Vietnamese + English corpus of 32 conversations and 192 lookup
questions with gold message ids. It prints recall@3/@5, MRR, citation validity,
the share of requests answered with zero LLM calls, and the input-character
reduction against the whole-window baseline.

The benchmark needs no database and no external key; it uses the offline
lexical embedding provider.

## Representative Vietnamese and English slices

The v1 manifests freeze synthetic conversations, question IDs and expected
message IDs. The development and holdout fixtures cover edited messages, sender
nicknames, unaccented Vietnamese, long histories, ambiguous questions, missing
evidence and similar conversations. A deterministic seed controls generated
history. Only the holdout command opens both manifests to check they are disjoint.

```shell
mvn -o -B -f server/pom.xml test -Dtest=RagRepresentativeSliceEvaluationTest
mvn -o -B -f server/pom.xml test -Dtest=RagHoldoutSliceEvaluationTest
```

Surefire's default discovery includes the development slice and excludes holdout.
Holdout reports quality metrics and checks the corpus separation and source scope.
Capture the base revision, manifest SHA-256 values, seed, profile and log for
each comparison. Version a new fixture when the frozen questions or gold sources
need to change. [Development manifest](../../server/src/test/resources/rag/eval/dev/v1/manifest.json) · [Holdout manifest](../../server/src/test/resources/rag/eval/holdout/v1/manifest.json)

- Recall@3, Recall@5 and MRR use the first gold message in the ranked retrieval results for answerable questions. The report also counts gold hits after the answerability filter.
- An answer counts as supported when it contains the expected fact and cites a gold source. Citation validity checks the conversation, message visibility and sequence of each returned citation.
- A negative case must return `NOT_FOUND`, an empty citation list and no answer or simulated provider call. The opt-out case also expects the context omission indicator.
- The zero-LLM rate counts requests that never reached the simulated answer provider. The provider fixture returns a controlled error when called. The evaluation uses an in-memory index and lexical embeddings, so these runs measure local behavior on synthetic fixtures.

## Current baseline

Command (exit 0, 2026-11-19, base `07ce491e`):
`mvn -o -B -f server/pom.xml test -Dtest=RagBenchmarkEvaluationTest`

Verbatim output (locale prints decimals with `,`):

```text
rag-eval: conversations=32 messages=6400 lookupQuestions=192 recall@3=1,000 recall@5=1,000 mrr=1,000 citationValidity=1,000 zeroLlm=70,0% meanInputChars=1640,6 baselineChars=7951,4 reduction=79,4%
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```
