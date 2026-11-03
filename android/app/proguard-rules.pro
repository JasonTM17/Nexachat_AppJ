# NexaChat ProGuard rules — release builds currently run with minify disabled.
# Enable isMinifyEnabled only after adding keep rules for:
#   - Moshi-generated adapters (handled by moshi-kotlin-codegen + its bundled rules)
#   - Retrofit service interfaces / DTOs
#   - Room entities and DAOs (Room ships consumer rules; verify before enabling)
#   - STOMP frame models (reflection-free, but confirm after implementation)
