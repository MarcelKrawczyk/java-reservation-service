# java-reservation-service

A REST API for booking services. A **provider** offers **service offerings**, a **user** books one of them for a given time, and the service enforces the booking rules (existence, ownership, time, availability).

Stack: Java 21, Spring Boot 3.2.2, Spring Data JPA (Hibernate), Bean Validation, H2 (in-memory), springdoc-openapi 2.5.0, Lombok, Maven.

---

## Domain model

```
Provider 1 ---- * ServiceOffering 1 ---- * Reservation * ---- 1 User
Provider 1 ---- * Reservation     (a reservation also references its provider)
```

| Entity | Fields |
|---|---|
| `User` | `id`, `fullName`, `email` (unique), `phoneNumber` |
| `Provider` | `id`, `name`, `address`, `offerings` |
| `ServiceOffering` | `id`, `serviceName`, `price` (`BigDecimal`), `provider`, `reservations` |
| `Reservation` | `id`, `reservationTime` (`LocalDateTime`), `user`, `provider`, `serviceOffering` |

All ids are `Long` with `GenerationType.IDENTITY`.

## API

| Resource | Endpoints |
|---|---|
| `/users` | `POST`, `GET`, `GET /{id}`, `DELETE /{id}` |
| `/providers` | `POST`, `GET`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}` |
| `/service-offerings` | `POST`, `GET`, `GET /{id}`, `DELETE /{id}` |
| `/reservations` | `POST`, `GET`, `GET /{id}`, `GET /client/{clientId}`, `GET /provider/{providerId}` |

`/users` and `/service-offerings` use DTOs. `/providers` and `/reservations` currently accept and return the JPA entities directly. Reservations are created by referencing existing entities by id, for example:

```json
{
  "reservationTime": "2030-01-01T10:00:00",
  "user": { "id": 1 },
  "provider": { "id": 1 },
  "serviceOffering": { "id": 1 }
}
```

### Booking rules (`ReservationService.create`), in the order they are checked

1. The user, provider and service offering must exist -> `404`
2. The service offering must belong to the selected provider -> `400`
3. The reservation time must not be in the past -> `400`
4. The provider must have no reservation at exactly the same time -> `409`
5. The user must have no reservation at exactly the same time -> `409`

Errors are returned as JSON: `status`, `error`, `message`, `timestamp`, `path`.

## Running

```bash
mvn spring-boot:run     # JDK 21 and Maven required
```

There is no `application.properties`, so Spring Boot defaults apply: an in-memory H2 database, schema created at startup, all data lost on restart. Interactive API docs are served by springdoc at `/swagger-ui.html` (spec at `/v3/api-docs`).

See **Status** below before running: the current commit has build problems.

---

## Engineering notes

### 1. Invariants and concurrency
- "A provider (or a user) has at most one reservation per time slot" is enforced only by a **check-then-insert** in application code (`existsBy...` followed by `save`). There is no `@Transactional` boundary and no unique constraint in the database.
- Two concurrent requests for the same slot can both pass the check and both be saved (a time-of-check to time-of-use race). An invariant that matters should be enforced by the database (unique constraint), with the violation mapped to `409`.

### 2. Time handling
- All times are `LocalDateTime`: no zone, no offset. The value is ambiguous across time zones and around DST changes. `Instant` (stored as UTC) or `ZonedDateTime` removes the ambiguity.
- "Now" comes from `LocalDateTime.now()` (system default zone, hard-wired). Injecting a `java.time.Clock` would make the past/future rules deterministic in tests.
- Availability is an **exact timestamp match**, including seconds and nanoseconds: 10:00:00 and 10:00:00.001 are different slots. There is no duration, so overlapping bookings (10:00 and 10:15) are not detected. Modelling a reservation as a half-open interval `[start, end)` with the overlap condition `start < otherEnd AND end > otherStart` is the usual fix.
- The past-time rule exists twice: `@Future` on the entity (strictly in the future, checked at persist) and an explicit `isBefore(now)` check in the service (allows "now"). The two can disagree at the boundary.

### 3. Money
- `price` is a `BigDecimal`, not a `double`, which is the correct type for currency.
- Not yet handled: no explicit column precision/scale (Hibernate defaults apply), no non-negative check, no currency field.

### 4. Persistence and complexity
- `equals`/`hashCode` on entities follow the id-based pattern that is safe across the transient -> persistent lifecycle: equality by id when the id is set, a constant `hashCode`.
- Associations are `LAZY`, and `/reservations` and `/providers` return entities. Serialization therefore triggers lazy loads, roughly one extra query per association per row (the N+1 pattern), and it relies on Spring's open-in-view default.
- `findAll()` endpoints are not paginated, so response size and query cost grow linearly with table size.
- The availability checks filter on `(provider_id, reservation_time)` and `(user_id, reservation_time)`. A composite unique index on each pair would turn them into index lookups and enforce the invariant from section 1 at the same time.

### 5. API boundary and error model
- Entities exposed directly, with bidirectional relations (`Provider.offerings` <-> `ServiceOffering.provider`, `ServiceOffering.reservations` <-> `Reservation.serviceOffering`), can cause infinite recursion during JSON serialization.
- `spring-boot-starter-validation` is on the classpath but no controller uses `@Valid`, and the DTO records have no constraints. The entity constraints (`@NotBlank`, `@Email`, `@Future`) only fire at persist time, where a violation surfaces as HTTP 500.
- `GlobalExceptionHandler` has a catch-all `Exception` handler that returns `ex.getMessage()` to the client and does not log. It also intercepts framework exceptions (for example malformed JSON), which then come back as 500 instead of 400.
- Inconsistent "not found": `ProviderService` throws a plain `RuntimeException` (-> 500) and `delete` uses `deleteById` without an existence check (Spring Data ignores a missing id, so it returns 204), while the user and service-offering services throw `NotFoundException` (-> 404).
- A duplicate email violates the unique constraint and returns 500 instead of 409.

### 6. Cascades and data lifecycle
- `ServiceOffering.reservations` and `Provider.offerings` use `CascadeType.ALL` with `orphanRemoval`: deleting an offering silently deletes its reservations, and deleting a provider deletes its offerings and, through them, reservations.
- Deleting a user who has reservations fails on the foreign key and surfaces as 500.
- `Reservation` stores both `provider` and `serviceOffering`, although the offering already determines the provider. The redundancy is what makes the "offering belongs to provider" check necessary; dropping `provider` from `Reservation` would remove that invariant.

### 7. Testability
- Constructor injection is used consistently, which makes the services easy to unit test with mocked repositories.
- `spring-boot-starter-test` is declared but there are no tests yet.

---

## Status

Known issues in the current commit:

- **Does not compile:** `ReservationMapper` no longer matches the model and DTOs. It calls `User.getName()` (the field is `fullName`), `ServiceOffering.getName()` and `getDurationMinutes()` (the model has `serviceName` and no duration), and it calls DTO constructors with argument lists that do not exist (`UserListItemDTO`, `ProviderListItemDTO`, `ServiceOfferingListItemDTO`, `ReservationResponseDTO`).
- **Would not start:** `ReservationRepository.findByClientId` refers to a `client` property, but `Reservation` has `user`. Spring Data derives the query at startup and fails. The same leftover naming is in the `/reservations/client/{clientId}` endpoint.
- Unused so far: `ReservationMapper`, the provider DTOs, `ReservationCreateDTO`, `ReservationListItemDTO`.
- No tests and no `application.properties`.

## Roadmap

1. Fix the two issues above and add an integration test that boots the context.
2. Enforce the booking invariants in the database (unique `(provider_id, reservation_time)` and `(user_id, reservation_time)`), wrap `create` in `@Transactional`, and map `DataIntegrityViolationException` to `409`.
3. Concurrency test: N threads book the same slot (`ExecutorService` + `CountDownLatch`); expected result is exactly one `201` and N-1 `409`.
4. Model reservations as intervals with a duration on `ServiceOffering`; use `Instant` or `ZonedDateTime` and an injected `Clock`.
5. DTOs for every resource, `@Valid` at the controllers, constraints on the DTOs, and `@Positive` plus explicit precision/scale on `price`.
6. One error model: `NotFoundException` everywhere, handlers for validation, unreadable body and data integrity errors, logging for 500s, no raw exception messages in responses.
7. Pagination, `@EntityGraph` or fetch joins for list endpoints, and `spring.jpa.open-in-view=false`.
8. Explicit `application.properties` and profiles.
9. Measure before optimizing: load-test the booking endpoint and report throughput and p50/p95/p99 latency.
