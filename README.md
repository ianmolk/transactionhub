# TransactionHub

A simulated banking platform built incrementally to explore backend engineering, relational data modelling, concurrency, testing, and deployment. All balances represent simulated money.

The first increment supports creating and retrieving virtual accounts. Transfers, transaction histories, authentication, React, AWS, and CI/CD are future work.

## Stack and prerequisites

- Java 21 JDK (`java -version` should report 21; set `JAVA_HOME` accordingly).
- Spring Boot 4.1.2, Spring MVC, Spring Data JPA, Bean Validation, and Flyway.
- PostgreSQL 17 in Docker; Docker Desktop or Docker Engine with Compose must be running.
- Maven 3.9.11 through the included wrapper; no global Maven installation is needed.
- JUnit, Mockito, and Testcontainers for tests.

The wrapper and first build require internet access to download Maven and dependencies. The first integration test run also downloads container images.

## Run locally

From the repository root:

```sh
cp .env.example .env
docker compose up -d --wait
cd backend
./mvnw spring-boot:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw`. The API listens at `http://127.0.0.1:8080`. Flyway creates the schema on startup; Hibernate checks that the entity mapping matches it.

Default database settings are `localhost:5432`, database/user `transactionhub`, and password `local-development-only`. These are local development credentials. PostgreSQL and the API bind to loopback by default. This increment has no authentication or authorization: anyone with access to the API can create accounts and retrieve an account using its ID. A UUID is an identifier, not access control.

Compose reads `.env`; Spring Boot does **not** automatically read that file. If you change database settings in `.env`, also export matching application settings before starting the backend:

```sh
export DB_URL=jdbc:postgresql://localhost:5432/transactionhub
export DB_USERNAME=transactionhub
export DB_PASSWORD=local-development-only
```

Configuration:

| Variable | Purpose | Default |
| --- | --- | --- |
| `POSTGRES_DB` | Compose database name | `transactionhub` |
| `POSTGRES_USER` | Compose database user | `transactionhub` |
| `POSTGRES_PASSWORD` | Compose database password | `local-development-only` |
| `POSTGRES_PORT` | Host PostgreSQL port | `5432` |
| `DB_URL` | Backend JDBC URL | `jdbc:postgresql://localhost:5432/transactionhub` |
| `DB_USERNAME` | Backend database user | `transactionhub` |
| `DB_PASSWORD` | Backend database password | `local-development-only` |
| `SERVER_PORT` | Backend HTTP port | `8080` |
| `SERVER_ADDRESS` | Backend bind address | `127.0.0.1` |

The database volume persists across container restarts. Changing initialization credentials in `.env` does not change an already initialized database. Stop the backend with Ctrl+C and stop PostgreSQL from the repository root with `docker compose down` (data is preserved).

## API examples

Create an account:

```sh
curl -i http://127.0.0.1:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Alex Smith","currency":"AUD"}'
```

Returns `201 Created`, a `Location: /api/accounts/{id}` header, and a response shaped like:

```json
{
  "id": "b173c65a-3762-4ee5-af41-24856fd11e0b",
  "ownerName": "Alex Smith",
  "currency": "AUD",
  "balance": 0.00,
  "createdAt": "2026-11-12T00:00:00Z"
}
```

Retrieve it using the actual ID returned above:

```sh
curl -i http://127.0.0.1:8080/api/accounts/REPLACE_WITH_ACCOUNT_ID
```

Returns `200 OK` for an existing account. There is deliberately no list, balance update, transfer, or deletion endpoint yet.

Validation rules:

- `ownerName` must be a JSON string, is required, nonblank, and at most 100 characters before trimming. Leading and trailing whitespace is removed before persistence. Names need not be unique. Numbers and booleans are rejected rather than converted to names.
- `currency` is required and must be `AUD`, `USD`, `EUR`, or `GBP` (case-sensitive; surrounding whitespace is ignored). Numeric values and numeric strings are rejected. These currencies use two decimal places; no currency conversion is provided.
- Every account starts with a balance of `0.00`. Unknown JSON properties, including a client-supplied `balance`, are rejected.

Errors use `application/problem+json` responses: `400` for invalid requests or malformed UUIDs, `404` for an unknown account, and `500` with a generic detail for unexpected failures. Validation errors include an `errors` object keyed by field name. Unexpected exception details are logged on the server and excluded from responses.

## Tests and packaging

From `backend` with Java 21 configured:

```sh
./mvnw test
./mvnw verify
```

`test` runs service unit tests without Docker. `verify` also packages the application and runs `*IT` integration tests against a disposable PostgreSQL Testcontainer, using real HTTP requests. The integration tests exercise Flyway migrations, Hibernate mapping validation, persistence, request validation, error responses, and the database nonnegative balance constraint. Docker must be running; integration tests intentionally fail rather than silently skip if Docker is unavailable. They do not use or alter the Compose database.

Run the packaged application after a successful build, with Compose PostgreSQL running:

```sh
java -jar target/transactionhub-0.0.1-SNAPSHOT.jar
```

## Architecture

```text
compose.yaml
.env.example
backend/
  pom.xml
  mvnw / mvnw.cmd
  .mvn/wrapper/
  src/main/java/com/transactionhub/
    TransactionHubApplication.java
    config/
      JacksonConfiguration.java
    account/
      Account.java
      Currency.java
      AccountRepository.java
      AccountService.java
      AccountController.java
      CreateAccountRequest.java
      AccountResponse.java
      AccountNotFoundException.java
    error/
      ApiExceptionHandler.java
  src/main/resources/
    application.yml
    db/migration/V1__create_accounts.sql
  src/test/java/com/transactionhub/account/
    AccountServiceTest.java
    AccountApiIT.java
```

This is a modular monolith organized by feature. Keeping account code together makes its boundaries visible while leaving room for future transfer and transaction features without introducing distributed infrastructure prematurely.

Controllers handle HTTP and validation, services define application operations and transaction boundaries, and repositories handle persistence. Request/response records keep the HTTP contract separate from JPA entities. Constructor injection makes dependencies explicit. Read operations use read-only transactions, creation uses a write transaction, and Open Session in View is disabled so persistence access stays within the service boundary.

Money uses Java `BigDecimal` and PostgreSQL `NUMERIC(19,2)` to avoid floating-point arithmetic errors. Database constraints provide a second line of protection for required fields, supported currencies, and nonnegative balances. Flyway owns schema changes; Hibernate never creates or updates production tables automatically. UUIDs provide opaque identifiers, and timestamps use UTC instants stored as PostgreSQL `TIMESTAMP WITH TIME ZONE`. Creation timestamps are truncated to microseconds to match database precision and remain consistent between creation and retrieval.

This increment has no operation that changes an existing balance. When transfers are introduced, we will design atomic debit/credit operations, concurrent update protection, idempotency, and transaction records together. Adding account fields alone would not establish those guarantees.
