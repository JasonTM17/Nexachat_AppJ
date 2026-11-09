# Testing

## Server tests

Server tests need JDK 21 or newer, Maven, and a reachable PostgreSQL instance.
Create a private, disposable database first, then point `IT_DB_URL` at it:

```powershell
psql -h localhost -p 5433 -U nexachat -d postgres -c 'CREATE DATABASE nexachat_test OWNER nexachat;'
$env:IT_DB_URL = 'jdbc:postgresql://localhost:5433/nexachat_test'
$env:IT_DB_USER = 'nexachat'
$env:IT_DB_PASSWORD = '<local database password>'
mvn -q -f server/pom.xml verify
```

`IT_DB_URL` must include the base database name. The test role also needs
permission to create and drop databases on the same PostgreSQL server: migration
tests and `PollFlowTest` create uniquely named scratch databases and remove them
when their test scope ends. The base database is used by the other integration
suites and can be reused for consecutive runs.

`mvn test` runs unit tests and the real-PostgreSQL `PollFlowTest`. `mvn verify`
also runs the `*IT` suites through Maven Failsafe. Maven test forks load their
fixed server settings from `src/test/resources/hermetic-test.properties`; the
`it` profile records the same defaults in `application-it.yml`. FCM is disabled,
RAG uses the lexical provider with blank API keys, and summary LLM providers are
disabled, regardless of exported developer environment values. Only `IT_DB_URL`,
`IT_DB_USER`, and `IT_DB_PASSWORD` select the test database connection.

For concurrent full-suite runs on one PostgreSQL server, create a different
private base database for each run and give each run its own `IT_DB_URL`. The
scratch databases used by migration and poll tests remain unique within and
across runs. If both Maven processes use this checkout, also pass a different
`-Dnexachat.test.build-directory=target/<run-name>` to each command so their
compiled classes and reports stay separate.
