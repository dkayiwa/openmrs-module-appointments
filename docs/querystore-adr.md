# ADR — Querystore SPI Contribution

This document records the architectural decisions behind the appointments module's contribution to [openmrs-module-querystore](https://github.com/openmrs/openmrs-module-querystore) — what resources the module indexes, what shape they take in the read store, and why each choice was made the way it was.

## Background

Querystore is a CQRS read store layered on top of OpenMRS: data-owning modules contribute a Java SPI implementation (`ResourceTypeProvider` + `ClinicalRecordSerializer` + `TypeBootstrapper`) and querystore handles indexing, embedding, kNN/BM25 retrieval, and historical backfill. The contract is defined in querystore's own [ADR Decision 13](https://github.com/openmrs/openmrs-module-querystore/blob/main/docs/adr.md#decision-13-module-extension-spi-service-provider-interface-for-custom-resource-types) and the [SPI walkthrough](https://github.com/openmrs/openmrs-module-querystore/blob/main/docs/spi-providers.md).

This module's contribution is a single resource type, `appointments_appointment`, plus the wiring to keep it in sync with the source-of-truth `Appointment` entity. Every decision below derives from querystore's contract; the citations to querystore decisions (e.g. *qsADR-13*) are abbreviations of the linked ADR.

## Conventions

- Each decision is numbered, has a Status (Accepted / Open question / Deferred), and follows the same Context / Decision / Rationale / Consequences shape as the querystore ADR.
- "Failure modes" are concrete and silent — i.e. the system produces wrong output without throwing. Where a decision deferred a structural fix, the consequence section names the user-visible failure mode explicitly.
- "Trigger surface" means the set of `AppointmentsService` / `AppointmentRecurringPatternService` methods whose Spring AOP afterReturning advice fires and dispatches to querystore.

## Table of Contents

1. [Contribute via the querystore SPI rather than maintain a per-module read store](#decision-1-contribute-via-the-querystore-spi-rather-than-maintain-a-per-module-read-store)
2. [One resource type — `appointments_appointment`](#decision-2-one-resource-type--appointments_appointment)
3. [Three AOP advices, one per trigger-surface shape](#decision-3-three-aop-advices-one-per-trigger-surface-shape)
4. [Multi-provider arrays alongside cross-cutting single-provider fields](#decision-4-multi-provider-arrays-alongside-cross-cutting-single-provider-fields)
5. [Recurring-pattern structure surfaced as flat metadata, no pattern UUID](#decision-5-recurring-pattern-structure-surfaced-as-flat-metadata-no-pattern-uuid)
6. [Omit-on-null for entity name fields; UUID fallback for provider names](#decision-6-omit-on-null-for-entity-name-fields-uuid-fallback-for-provider-names)
7. [`<require_module>` querystore, no version pin while SNAPSHOT](#decision-7-require_module-querystore-no-version-pin-while-snapshot)
8. [`AppointmentsServiceImpl.reschedule()` prev-appointment gap accepted](#decision-8-appointmentsserviceimplreschedule-prev-appointment-gap-accepted)
9. [`AppointmentAudit` history not indexed in this resource type](#decision-9-appointmentaudit-history-not-indexed-in-this-resource-type)
10. [Voided appointments route to delete, not to a `voided=true` field](#decision-10-voided-appointments-route-to-delete-not-to-a-voidedtrue-field)
11. [LinkageError catch widening for version-skew tolerance](#decision-11-linkageerror-catch-widening-for-version-skew-tolerance)
12. [Bootstrap is activator-triggered on every module start](#decision-12-bootstrap-is-activator-triggered-on-every-module-start)
13. [Cross-cutting `date` field = `startDateTime`](#decision-13-cross-cutting-date-field--startdatetime)
14. [`text` chunk composition: clinical prose only, no audit metadata](#decision-14-text-chunk-composition-clinical-prose-only-no-audit-metadata)
15. [`DOC_ZONE` = JVM-default timezone, captured at class load](#decision-15-doc_zone--jvm-default-timezone-captured-at-class-load)
16. [Bean discovery via `Context.getRegisteredComponent`, not Spring `@Autowired`](#decision-16-bean-discovery-via-contextgetregisteredcomponent-not-spring-autowired)
17. [Open questions](#open-questions)

---

## Decision 1: Contribute via the querystore SPI rather than maintain a per-module read store

### Status
Accepted.

### Context
Querystore's [qsADR-13](https://github.com/openmrs/openmrs-module-querystore/blob/main/docs/adr.md#decision-13-module-extension-spi-service-provider-interface-for-custom-resource-types) names three options for any data-owning module that wants its records available to AI/analytics consumers: maintain its own indexed store (no coupling, every consumer federates), wait for a monolithic querystore that depends on every data-owning module (single point of release pressure), or implement the SPI (modules depend on querystore; querystore knows nothing about specific modules).

### Decision
The appointments module contributes `appointments_appointment` via the SPI exactly as qsADR-13 specifies. Three classes plus wiring:

- `AppointmentSerializer implements ClinicalRecordSerializer<Appointment>` — projects an `Appointment` to a `QueryDocument` with the cross-cutting field contract.
- `AppointmentBootstrapper extends HibernateTypeBootstrapper<Appointment>` — paginated HQL backfill for initial install and resume-from-cursor on subsequent module starts.
- `AppointmentResourceProvider implements ResourceTypeProvider` — registers the SPI bundle so querystore's `Context.getRegisteredComponents(ResourceTypeProvider.class)` finds it on each `BootstrapService.bootstrap(...)` invocation.

### Rationale
1. **Avoids a parallel indexed store inside the appointments module.** Maintaining our own Lucene/ES/MySQL projection plus its own re-sync pipeline would double the surface a module maintainer has to keep stable across OpenMRS platform upgrades. The SPI lets querystore own the backend tier (per qsADR-3).
2. **Cross-type retrieval comes for free.** Consumers querying `querystore_*` wildcards (qsADR-4) automatically include `querystore_appointments_appointment` without any consumer-side enumeration.
3. **Embedding pipeline is shared.** Querystore embeds at write time per qsADR-8; we do not pick an embedding model or run the pipeline ourselves.
4. **Voiding, retired-metadata, race-guard, locale-aware serialization** are all defined by querystore (qsADR-6 / 8 / 10 / 11) and apply uniformly to our type.

### Consequences
- Deployments without querystore cannot install this module (see [Decision 7](#decision-7-require_module-querystore-no-version-pin-while-snapshot)).
- A querystore SPI change forces a coordinated module-side update; this is the price of inheriting all the shared infrastructure.
- The appointments module never sees the backend tier; whether deployments run MySQL / Lucene / Elasticsearch is opaque to us.

---

## Decision 2: One resource type — `appointments_appointment`

### Status
Accepted.

### Context
The module owns five candidate entity types:

| Entity | Patient-scoped? | Persisted? | Clinical-event-shaped? |
|---|---|---|---|
| `Appointment` | yes | yes | yes |
| `AppointmentServiceDefinition` | no (metadata) | yes | no |
| `AppointmentServiceType` | no (metadata) | yes | no |
| `AppointmentRecurringPattern` | no (group-of-occurrences) | yes | no |
| `AppointmentAudit` | yes (per-appointment-event-history) | yes | trace-shaped, not event-shaped |

Querystore's qsADR-13 scopes "clinical resource types" to patient-scoped clinical data. qsADR-11 puts knowledge-base / reference-data indices out of v1 scope.

### Decision
Index `appointments_appointment` only. `AppointmentServiceDefinition` and `AppointmentServiceType` are metadata, out of scope for clinical retrieval. `AppointmentRecurringPattern` is a group identifier, not a clinical event — its structural fields are surfaced inline on the constituent `Appointment` documents instead (see [Decision 5](#decision-5-recurring-pattern-structure-surfaced-as-flat-metadata-no-pattern-uuid)). `AppointmentAudit` is deferred (see [Decision 9](#decision-9-appointmentaudit-history-not-indexed-in-this-resource-type)).

The resource type name follows qsADR-13's `<moduleid>_<type>` convention. The corresponding index name is `querystore_appointments_appointment`.

### Rationale
1. **Patient-scoped clinical events are what consumers (chartsearchai, reporting tools) actually ask for.** "Show me cardiology appointments for patient X" is the query shape; "show me the service-definition record for cardiology" is metadata browsing, served by core's own service registry without querystore.
2. **Avoids polluting the cross-type wildcard with non-clinical hits.** A consumer doing patient-scoped retrieval on `querystore_*` should not get back appointment-service catalogue rows alongside clinical events.
3. **Single resource type keeps the indexer/bootstrapper/advice triple narrow.** Adding a second type would double the test surface and the wiring without serving a request anyone has made.

### Consequences
- A future consumer asking "list all clinical services this facility offers" cannot answer the question from querystore. They round-trip to core's `AppointmentServiceDefinitionService`, which is what they would do today regardless.
- A future request to surface metadata as `querystore_appointments_service` is straightforward — it would be a new `ResourceTypeProvider` in this module, not a change to the existing one.

---

## Decision 3: Three AOP advices, one per trigger-surface shape

### Status
Accepted.

### Context
Querystore's bridge advice (`org.openmrs.module.querystore.bridge.AbstractIndexingAdvice<T>`) handles the common case: a service method whose return value or `args[0]` is the affected entity. But `AppointmentsService` and `AppointmentRecurringPatternService` between them have three distinct trigger-surface shapes:

1. **One-Appointment-per-call**: `validateAndSave(Appointment)`, `changeStatus(Appointment, ...)`, `undoStatusChange(Appointment)`, `reschedule(String uuid, Appointment, ...) → Appointment`. Entity is in `returnValue` or `args[0]` directly.
2. **Recurring fan-out**: `AppointmentRecurringPatternService.validateAndSave(AppointmentRecurringPattern)` returns the pattern; its `Set<Appointment> appointments` must be fanned out into N indexable documents. `update(pattern, Appointment)` and `update(pattern, List<Appointment>)` and `changeStatus(seed, ...) → List<Appointment>` are all variations on this shape with different arg/return permutations.
3. **Wrapper-arg**: `updateAppointmentProviderResponse(AppointmentProvider)` — the affected `Appointment` is reached via `args[0].getAppointment()`, not as `args[0]` itself.

### Decision
Three advice classes wired in `omod/src/main/resources/config.xml`:

| Advice class | Service point | Pattern |
|---|---|---|
| `AppointmentIndexingAdvice` | `AppointmentsService` | extends `AbstractIndexingAdvice<Appointment>` (querystore base) |
| `RecurringAppointmentIndexingAdvice` | `AppointmentRecurringPatternService` | implements `AfterReturningAdvice` directly; fans out + UUID-dedups |
| `AppointmentProviderResponseIndexingAdvice` | `AppointmentsService` (separate `<advice>` block from #1) | implements `AfterReturningAdvice` directly; unwraps `AppointmentProvider.getAppointment()` |

The recurring and provider-response advices reuse querystore's `BridgeIndexer` and `AfterCommitDispatcher` beans via `Context.getRegisteredComponent` — the same beans `AbstractIndexingAdvice` uses internally — so the after-commit semantics, embedding pipeline, and version-protected write path are identical across all three.

### Rationale
1. **`AbstractIndexingAdvice` is final on its outer `afterReturning` and typed `List<T>` on `collectTree`.** Neither the recurring fan-out (one input → N entities) nor the wrapper-arg unwrap (entity reached via a getter chain) fits the base's contract. We could subclass with a custom `entityFrom`, except `entityFrom` is `private final` in the base.
2. **Three small advices are easier to reason about than one polymorphic dispatcher.** Each advice's trigger set, entity extraction, and dispatch routing are visible in one ~80-line file.
3. **Splitting the AppointmentsService advice in two** (the standard `AppointmentIndexingAdvice` and the separate `AppointmentProviderResponseIndexingAdvice`) is forced by the same final-outer-catch constraint: a second trigger method that needs a different `entityFrom` cannot share the base class.

### Consequences
- Three advice classes share roughly 30 lines of dispatch-loop skeleton (bean lookups, voided→delete vs index routing, per-document try/catch). This duplication is below the abstraction-cost threshold; the right structural fix is upstream in querystore (promote `AbstractIndexingAdvice.indexer()`/`dispatcher()` to `protected`, or add a custom-entity-extractor hook).
- A future fourth shape would force a fourth advice. If that happens twice, the case for promoting the bridge pattern to a shared helper in this module strengthens.

---

## Decision 4: Multi-provider arrays alongside cross-cutting single-provider fields

### Status
Accepted.

### Context
An `Appointment` carries a `Set<AppointmentProvider>` — zero, one, or many providers per appointment. The querystore cross-cutting field contract (qsADR-13) names `provider_uuid` and `provider_name` as optional single-value fields. There is no cross-cutting multi-valued provider contract.

Three failure modes are at stake:

1. **Indexing only the first provider.** A `Set<AppointmentProvider>` has non-deterministic iteration order. "Appointments with Dr. C" silently returns false negatives for every multi-provider appointment where C wasn't first.
2. **Encoding nested objects.** `[{uuid, name, response}, ...]` cannot be indexed uniformly across querystore's three reference backends (qsADR-3).
3. **Provider-response state.** The `AppointmentProvider.response` field (`AWAITING`/`ACCEPTED`/`REJECTED`/`TENTATIVE`/`CANCELLED`) is what the `updateAppointmentProviderResponse` advice was added to track; consumers must be able to filter on it.

### Decision
Three multi-valued fields, always emitted in parallel index order when at least one provider is present:

- `provider_uuids` — array of every non-null provider's UUID.
- `provider_names` — parallel array of provider display names (with the UUID-fallback in [Decision 6](#decision-6-omit-on-null-for-entity-name-fields-uuid-fallback-for-provider-names)).
- `provider_responses` — array of `"<provider-uuid>:<response>"` strings, where `<response>` is the `AppointmentProviderResponse.name()` of the corresponding `AppointmentProvider`. Providers whose response is `null` are **omitted entirely**, not encoded as `"<uuid>:null"`.

The cross-cutting `provider_uuid` / `provider_name` single-value fields are kept and populated from the first non-null provider in the set — they're what consumers reading the querystore documentation expect, and dropping them would force every consumer to handle the array shape even for the common single-provider case.

### Rationale
1. **Parallel arrays index uniformly.** Every querystore backend can index `["uuid-1", "uuid-2"]` as a multi-valued field; nested objects work only on Elasticsearch.
2. **`<uuid>:<response>` flat strings keep filterability without nested-object support.** A consumer asking "appointments where Dr. X has declined" runs `provider_responses CONTAINS "uuid-X:REJECTED"`.
3. **Null-response omission lets consumers derive AWAITING-not-yet-asked.** `provider_uuids` minus the UUIDs in `provider_responses` = providers who haven't responded. This is distinct from an explicit `AWAITING` response, which is a legitimate value of the enum (a provider who was asked and explicitly hasn't decided yet).
4. **Singular fields preserve the cross-cutting wire shape.** Consumers reading "every querystore document has provider_uuid when applicable" don't need to special-case our type.

### Consequences
- Three arrays per multi-provider appointment vs. zero arrays for the no-provider case. On the bootstrap backfill path this adds ~30 bytes per document for the common single-provider case; we accepted it because the single-provider case is also the common consumer query.
- A future provider-rename (extremely rare) would require re-indexing because the array contents are denormalized — same property as every other denormalized field in querystore.
- `provider_responses` enum values are passthrough of `AppointmentProviderResponse.name()`. An upstream rename of any of the five enum constants silently changes the wire format. The class Javadoc documents this; the only mitigation is the `<require_module>` pin once querystore (and through it the appointments module's release cycle) reaches 1.0.

---

## Decision 5: Recurring-pattern structure surfaced as flat metadata, no pattern UUID

### Status
Accepted.

### Context
`AppointmentRecurringPattern` groups N related `Appointment` occurrences. The class carries `type` (DAY/WEEK), `period`, `frequency`, `daysOfWeek` (a comma-separated `String`), and `endDate`. Critically, `AppointmentRecurringPattern` **does not extend `BaseOpenmrsData`** — it has only an `Integer id`, no `uuid`.

Two questions:

1. Should the recurring pattern be its own resource type (`appointments_recurring_pattern`), grouping its occurrences?
2. If not, how do consumers ask "show me all occurrences of this recurring booking"?

### Decision
- No separate resource type for the recurring pattern. The pattern's structured shape is denormalized onto each constituent `Appointment` document via five metadata fields:
  - `is_recurring` (Boolean) — sparse-when-false (omitted entirely for non-recurring appointments).
  - `recurring_type` — `name()` passthrough of `RecurringAppointmentType`.
  - `recurring_period` / `recurring_frequency` — Integer.
  - `recurring_days_of_week` — verbatim passthrough of `AppointmentRecurringPattern.getDaysOfWeek()`.
  - `recurring_end_date` — `Date.toInstant().toString()` passthrough.
- Cross-occurrence grouping (the "all occurrences of this recurring booking" query) is **not natively supported**. Consumers needing this query must group client-side by appointment-service + patient-UUID + recurring-type, or query core directly via `AppointmentRecurringPatternService`.

### Rationale
1. **The pattern has no UUID.** A `ResourceTypeProvider` requires a stable `resource_uuid` per document (qsADR-13). The pattern's `Integer id` is database-local and not safe to expose as a cross-deployment identifier.
2. **Denormalized structured fields cover the reporting use case.** "Weekly recurring Monday/Wednesday appointments" is filterable on `recurring_type:WEEK AND recurring_days_of_week:*MON* AND recurring_days_of_week:*WED*`. The lack of a group identifier is uncomfortable but not query-blocking for the use cases we know about.
3. **A separate `appointments_recurring_pattern` resource type would require either (a) a synthetic UUID derived from pattern fields, which is brittle, or (b) extending `AppointmentRecurringPattern` to have a real UUID, which is a model change beyond this slice's scope.**

### Consequences
- Consumers wanting cross-occurrence grouping must round-trip to core or implement client-side joining. This is a real consumer-visible gap; if a consumer's use case demands it, the right follow-up is to add a UUID to `AppointmentRecurringPattern` in core and revisit.
- `recurring_days_of_week` is a verbatim `String` passthrough — its format depends on whoever wrote it (the omod mapper convention is comma-separated uppercase like `"MON,WED"`, but the field is not validated). Consumers parsing this must match the entity's stored shape, not assume canonical form.

---

## Decision 6: Omit-on-null for entity name fields; UUID fallback for provider names

### Status
Accepted.

### Context
The serializer projects names for four entity types: `Location.name`, `AppointmentServiceDefinition.name`, `AppointmentServiceType.name`, and `Provider.getName()`. None of these getters are guaranteed non-null:

- `Location` / `AppointmentServiceDefinition` / `AppointmentServiceType` — `name` columns are not declared NOT NULL in the entity models; transient/incomplete states allow null.
- `Provider.getName()` — returns `null` when the Provider has no linked `Person` (an unusual but legal production state).

Two questions:

1. When the source name is null, should the metadata field be **present-but-null** (`"location_name": null`) or **absent entirely** (key not in map)?
2. Should the `text` chunk (the embedding-targeted prose) and the metadata field behave the same way?

### Decision
- For `location_name`, `appointment_service_name`, `appointment_service_type_name`: **omit on null** — the key is absent from the metadata map, not present-but-null. Matches `buildText`'s null-guard behavior so structured search (`location_name IS NOT NULL`) and free-text search agree on which appointments reference a named entity.
- For `provider_name` (singular cross-cutting field) and every entry in `provider_names` (multi-valued array): **fall back to the provider's UUID** when `Provider.getName()` is null. Keeps the `provider_uuids` / `provider_names` arrays index-parallel without injecting `null` entries, and keeps the singular field non-null so the `provider_uuid IS NOT NULL` filter remains usable.
- `buildText` uses the same fallback for the provider position (`with <uuid>`) so structured and free-text agree.

### Rationale
The two patterns address two different failure modes:

1. **For `location`/`service`/`service-type`**: a null name doesn't have a useful substitute, and the parallel-array contract doesn't apply (these are single-valued). Omit-on-null lets `location_name IS NOT NULL` cleanly partition the index.
2. **For `provider`**: parallel arrays mean `provider_uuids[i]` and `provider_names[i]` must agree per index — injecting null in the names array would break consumers' `provider_names[i]` lookups, and JSON-serialising a literal null is an unreconcilable wire artefact. UUID fallback keeps the arrays parallel without compromising the present-iff-applicable invariant.

### Consequences
- Consumers querying `provider_name CONTAINS "..."` may surface UUIDs when the provider has no linked Person. This is acceptable because (a) the case is rare in production, (b) the alternative — `provider_names: [..., null, ...]` — is worse, and (c) the UUID is still a stable reference.
- The omit-on-null convention is documented in the class Javadoc and exercised by `omitsNameFieldsWhenLocationServiceOrTypeNameIsNull` plus `fallsBackToProviderUuidWhenProviderNameIsNull`.

---

## Decision 7: `<require_module>` querystore, no version pin while SNAPSHOT

### Status
Accepted (with deferred follow-up).

### Context
Querystore is declared in this module's `config.xml`. Two axes of choice:

| Axis | Options | Trade-off |
|---|---|---|
| Required vs. optional | `<require_module>` / `<aware_of_module>` | Required = appointments cannot load without querystore. Optional = appointments loads but the SPI beans fail Spring instantiation because their classes inherit from missing querystore types. |
| Version-pinned vs. floating | `version="1.0.0"` / no `version=` | Pinned = module loader rejects mismatched querystore at install time. Floating = mismatch surfaces at runtime when a method is called or a class is loaded. |

### Decision
- `<require_module>org.openmrs.module.querystore</require_module>` (mandatory).
- No `version=` attribute while querystore is on `1.0.0-SNAPSHOT`. Pin once querystore ships 1.0.

### Rationale
1. **`<aware_of_module>` does not shield JVM-level class resolution.** `AppointmentSerializer implements ClinicalRecordSerializer<Appointment>` — instantiation at Spring context refresh triggers loading of the querystore interface. Without querystore on the classpath, the appointments module fails to load entirely. `<require_module>` makes that failure clean (module loader rejects the install with a clear error) instead of silent (Spring partial-context-refresh ambiguity).
2. **No version pin during SNAPSHOT** because pinning would require bumping this module on every querystore SNAPSHOT roll. The cost is mitigated by [Decision 11](#decision-11-linkageerror-catch-widening-for-version-skew-tolerance).

### Consequences
- Bahmni deployments that don't run querystore cannot install or upgrade this module. The intended path is "install querystore first, then the appointments module update."
- A breaking querystore SPI change ships as silent runtime failure (NoSuchMethodError on first advice fire) instead of a clean module-loader reject. The fallback in the activator's `started()` swallows this for the bootstrap call; the advice catches in [Decision 11](#decision-11-linkageerror-catch-widening-for-version-skew-tolerance) cover steady-state writes.
- **Follow-up**: when querystore tags 1.0, add `version="1.0.0"` (or whatever the next stable is) to the require_module declaration.

---

## Decision 8: `AppointmentsServiceImpl.reschedule()` prev-appointment gap accepted

### Status
Deferred (concrete failure mode documented; structural fix lives outside this slice).

### Context
`AppointmentsServiceImpl.reschedule(originalUuid, newAppointment, ...)` internally calls `changeStatus(prevAppointment, "Cancelled", new Date())` on the prior appointment before saving the new one. The inner call is a **self-invocation** — it bypasses the Spring AOP proxy, so `AppointmentIndexingAdvice` does not fire for the prev appointment's status flip. Only the outer `reschedule` trigger fires, and its `entityFrom` resolves the *new* appointment from `returnValue`, not the prev.

### Decision
Accept the gap. Document the failure mode and the structural fix; do not change `AppointmentsServiceImpl.reschedule` from inside this slice.

### Rationale
1. **The structural fix lives in production service-impl code, not in querystore-side code.** Rewiring the inner `changeStatus` to route through `Context.getService(AppointmentsService.class)` so the proxy fires is a one-line change in `AppointmentsServiceImpl.reschedule` — but it modifies non-querystore production logic and could surface latent behavior the rest of the module depends on.
2. **The Atomfeed advice on the same service has the exact same gap.** It's a pre-existing module-wide event-trigger issue that the querystore slice inherits, not a querystore-specific bug.
3. **Querystore's bootstrap path catches the drift on the next module restart** via the `dateChanged` cursor. The window where the prev-appointment is stale is at most until the next restart of the module.

### Consequences
- *Failure mode if shipped without fix:* after a user reschedules an appointment, the prior appointment's `Cancelled` status is not propagated to querystore until the next module-restart bootstrap pass. In the interim window — typically hours to weeks depending on the deployment's restart cadence — searches for "upcoming appointments" surface a phantom still-`Scheduled` entry that has actually been cancelled, alongside the newly-scheduled appointment. Two visibly-active appointments for the same patient-time slot.
- The fix is small and orthogonal; we recommend filing it as a follow-up against `AppointmentsServiceImpl.reschedule` rather than in the querystore advices.

---

## Decision 9: `AppointmentAudit` history not indexed in this resource type

### Status
Deferred (open for a future contributor with a concrete consumer).

### Context
`Appointment.appointmentAudits` is a `Set<AppointmentAudit>` capturing status-change history (who changed what status when, with optional notes). Two ways to surface this:

1. Fold the audit log into the parent `Appointment` document as nested objects.
2. Index `AppointmentAudit` as its own resource type (`appointments_audit`).

### Decision
Don't index `AppointmentAudit` in `appointments_appointment`. Defer the question of a separate `appointments_audit` resource type until a consumer asks for it.

### Rationale
1. **Nested-object indexing isn't uniform across querystore backends** (qsADR-3). Same constraint that drove the flat-string encoding in [Decision 4](#decision-4-multi-provider-arrays-alongside-cross-cutting-single-provider-fields). Embedding audit history in the parent document also bloats every `appointments_appointment` doc with N audit rows even for consumers that don't query the history.
2. **A separate `appointments_audit` resource type is a real piece of work** — needs its own `ResourceTypeProvider`, serializer, bootstrapper, advice triggers, and trigger paths (status changes fire `changeStatus` which writes audit rows transitively). That's all justified if a consumer actually asks "appointments where the audit log mentions X"; without a consumer, the work and the test surface are speculative.
3. **The structurally right answer is a separate type, not folding.** Folding loses the per-audit-row granularity the audit log exists to provide; a separate type preserves it.

### Consequences
- Free-text search "appointments where the audit log mentions X" returns nothing from querystore today. Consumers asking that question round-trip to `AppointmentAuditDao`.
- A future consumer's request would be a new module-side contribution following exactly the same SPI pattern this slice established — not a refactor of the existing `appointments_appointment` type.

---

## Decision 10: Voided appointments route to delete, not to a `voided=true` field

### Status
Accepted (follows querystore qsADR-10).

### Context
OpenMRS uses soft-delete via `voided=true` everywhere. Querystore's qsADR-10 says voided records are *deleted* from the read store, not flagged.

### Decision
The advices route voided appointments to `indexer.delete(...)` rather than indexing them with a `voided=true` field. The serializer never sees a voided appointment for indexing — the advice partitions at the dispatch site.

`AppointmentStatus.Cancelled` is a separate concept: a cancelled-but-not-voided appointment is still indexed (with `status: "Cancelled"`). The two distinctions:

| Source state | Querystore action |
|---|---|
| `voided=false`, any `status` | indexed |
| `voided=true` | deleted from index |
| `status=Cancelled`, `voided=false` | indexed with `status: "Cancelled"` |

### Rationale
1. **Follows qsADR-10 uniformly.** All querystore resource types behave the same way under voiding.
2. **Cancelled appointments are user-visible** — they appear in cancelled-search-results UIs and should remain in the index. Voided appointments are deletion-equivalent: the user-facing record is gone.

### Consequences
- Consumers asking "audit-trail of voided appointments" cannot answer from querystore. Voided records are not retained.
- A voided patient's appointments are also removed from the index via `bulkDeleteByPatient`, which querystore's own `PatientIndexingAdvice` handles upstream — we don't need to listen to patient-void events ourselves.

---

## Decision 11: LinkageError catch widening for version-skew tolerance

### Status
Accepted (workaround for the deferred version pin in [Decision 7](#decision-7-require_module-querystore-no-version-pin-while-snapshot)).

### Context
The advices and the activator catch exceptions from querystore-side code (`BridgeIndexer.index`, `serializer.serialize`, `BootstrapService.bootstrap`). A version-skewed querystore — for example, a deployment running an older querystore where a method was renamed — throws `NoSuchMethodError` or `NoClassDefFoundError`, which are subclasses of `Error` and `LinkageError`, **not** subclasses of `RuntimeException`. Without explicit `LinkageError` handling, version-skew errors escape the swallow and:

- *In the activator*: prevent the module from loading.
- *In an advice's outer catch*: unwind through the AOP proxy back to the clinical-thread save, surfacing a phantom failure even though the originating transaction has already committed.
- *In an advice's inner per-document catch*: unwind the after-commit lambda, causing every subsequent appointment in the same save to be silently skipped (sibling starvation).

### Decision
Every catch site in our advices and activator catches `RuntimeException | LinkageError`:

- `AppointmentsActivator.started()` — outer catch around the `BootstrapService.bootstrap(...)` call.
- `RecurringAppointmentIndexingAdvice.afterReturning` — outer catch around `dispatch(...)`.
- `RecurringAppointmentIndexingAdvice.dispatch` — inner per-document catches in both the index loop and the delete loop.
- `AppointmentProviderResponseIndexingAdvice.afterReturning` — outer catch around `dispatch(...)`.
- `AppointmentProviderResponseIndexingAdvice.dispatch` — inner catch around the single-document index/delete.

`AppointmentIndexingAdvice` cannot mirror this because its outer `afterReturning` is inherited from `AbstractIndexingAdvice.afterReturning`, which is `final` in querystore and catches only `RuntimeException`. This asymmetry is documented in the class Javadoc with the same concrete failure-mode sentence.

### Rationale
1. **The fallback for a deferred version pin is to absorb the failure mode the version pin would prevent.** Once querystore ships 1.0 and we pin it, this catch widening becomes belt-and-suspenders; until then it's load-bearing.
2. **`Error` is broader than `LinkageError`** but catching all of `Throwable` is an anti-pattern (swallows `OutOfMemoryError`, `ThreadDeath`, etc.). `LinkageError` covers exactly the class-resolution-vs-loaded-bytecode mismatches that version skew produces.

### Consequences
- A `LinkageError` from inside querystore's own runtime path lands in our `log.warn` instead of unwinding to the clinical-thread caller. Deployment teams see the symptom in logs.
- The `AppointmentIndexingAdvice` asymmetry remains. The structural fixes are (a) version-pin querystore, or (b) submit an upstream patch widening `AbstractIndexingAdvice`'s catch to `RuntimeException | LinkageError`. Both are out of slice scope.

---

## Decision 12: Bootstrap is activator-triggered on every module start

### Status
Accepted.

### Context
Once querystore is installed and this module loads, the `querystore_appointments_appointment` index needs to be populated from the source-of-truth table. Querystore's `BootstrapService.bootstrap("appointments_appointment")` is the entry point that walks the table via the `AppointmentBootstrapper`'s paginated HQL. The question is who triggers it. Three options:

| Option | Triggered by | Trade-off |
|---|---|---|
| Activator `started()` | Auto on every module load | Zero-touch; fires on every restart |
| Admin invocation | Manual REST/SQL/CLI | One-time on install; requires runbook for upgrades |
| First-search lazy projection | Querystore's `ensureIndexed` on cold `searchByPatient` | Async warmup; latency cost on first query per patient |

### Decision
The activator's `started()` callback calls `BootstrapService.bootstrap(AppointmentSerializer.RESOURCE_TYPE)` on every module start. The call is wrapped in a `RuntimeException | LinkageError` catch (see [Decision 11](#decision-11-linkageerror-catch-widening-for-version-skew-tolerance)) so a querystore failure logs at error level and module load continues.

### Rationale
1. **Idempotent on querystore's side.** `BootstrapService.bootstrap(...)` persists a progress row; subsequent invocations resume from the cursor. After the first complete pass, every later call walks zero pages and re-sets COMPLETED — no re-projection cost, no risk of duplicate writes (the conditional-upsert guard from qsADR-3 absorbs any race).
2. **Zero-touch for deployment teams.** Fresh installs and module upgrades both work without an explicit admin command. The alternative (admin-triggered) means a documentation/runbook obligation and a window where the index is empty until someone notices.
3. **Lazy projection is a complement, not a replacement.** Querystore's `ensureIndexed` (cold-search-triggered, per-patient) is meant for deployments without scheduled backfill; we still want bulk backfill to be the default so common queries don't pay the first-touch latency.

### Consequences
- A misconfigured deployment with querystore returning errors logs at error level on every module restart, not just once when first noticed. Deployment teams must monitor for the recurring log line.
- A module reinstall on a deployment with completed bootstrap is a no-op from the slice's perspective — the activator call resumes from the cursor end-of-data and exits in milliseconds. No risk of double-projection.
- If the underlying querystore backend is unreachable at module-start time, the swallow logs the error and the module loads anyway. Steady-state writes flow through the AOP advices once the backend recovers; the pre-existing rows remain stale until the next module restart picks up the bootstrap call again.

---

## Decision 13: Cross-cutting `date` field = `startDateTime`

### Status
Accepted.

### Context
Querystore's cross-cutting field contract names `date` (a `LocalDate`) as the clinical date of the event (qsADR-6). For an appointment, three candidate timestamps could fill that slot:

| Candidate | What it represents |
|---|---|
| `startDateTime` | When the appointment is scheduled to happen |
| `dateChanged` ?? `dateCreated` | When the appointment record was last modified or created |
| `dateCreated` only | When the appointment record was created |

The choice affects every date-range query against the index.

### Decision
`date` = `startDateTime.toInstant().atZone(DOC_ZONE).toLocalDate()`. Modification timestamps are in `last_modified` (cross-cutting Instant) and the appointment-specific `date_created` (passthrough). The audit-shape "when was this record created/modified" question is answered by `date_created` / `last_modified`; the clinical-shape "when is the appointment" question is answered by `date`.

### Rationale
1. **Matches the user mental model.** A clinician asking "today's appointments" means today's *scheduled* appointments, not appointments edited today. A consumer's `date:today` query expects the same.
2. **Querystore consumers issuing cross-type date-range queries** (e.g. "show me everything clinical that happened on Tuesday") want the event date, not the audit-trail date. Appointments contributing audit-trail dates instead of event dates would distort cross-type retrieval.
3. **`date_created` is a separate addressable field** for the "created today" use case, so the audit-shape query is still expressible — just via a different field.

### Consequences
- Searches by `date` return appointments scheduled for that date, regardless of when they were created or last modified. An appointment created Monday for Tuesday surfaces in `date:Tuesday`, not `date:Monday`.
- An appointment with `startDateTime=null` (rare; only present in transient/incomplete states) falls back through the chain to `lastModifiedDate` (see [Decision 5](#decision-5-recurring-pattern-structure-surfaced-as-flat-metadata-no-pattern-uuid)'s sibling fallback). The serializer pins this with `fallsBackFromStartDateTimeToLastModifiedForClinicalDate`.
- The fallback means "create-only" appointments (no start time set) still surface in date-range queries via their audit date — preventing them from being lost entirely. Consumers strictly filtering to scheduled events accept this minor imprecision because the alternative is silent omission.

---

## Decision 14: `text` chunk composition: clinical prose only, no audit metadata

### Status
Accepted.

### Context
The serializer produces both structured metadata fields and a natural-language `text` chunk. The `text` is what querystore embeds at write time (qsADR-8) for kNN semantic search; metadata is for structured filtering. Including a field in `text` makes it discoverable via prose similarity ("find appointments mentioning chest pain"); excluding it from `text` confines the field to exact-match filter queries.

The composition choice is a real trade-off. Including audit metadata in `text` would let consumers search "appointments Dr. Admin created" via the embedded prose. Excluding it confines that query to structured filter syntax (`creator_uuid:...`). Including too much narrative dilutes the embedding signal for clinical queries.

### Decision
`text` contains clinical prose only:

- Service name and service type
- Scheduled date and time window
- Primary provider name (with UUID fallback per [Decision 6](#decision-6-omit-on-null-for-entity-name-fields-uuid-fallback-for-provider-names))
- Location name
- Status (`name()` of the enum)
- Kind (`name()` of the enum)
- "Teleconsultation" marker when teleHealthVideoLink is set
- "Recurring" marker when any recurring pattern is present
- Free-text comments

`text` deliberately excludes:

- `creator_uuid` / `changed_by_uuid` (audit metadata)
- `date_created` (audit timestamp)
- `provider_uuids` / `provider_names` / `provider_responses` (multi-valued surface — primary provider is in text; the full set is filter-only)
- `related_appointment_uuid` (relational traversal target)
- All `recurring_*` structured fields (the boolean "Recurring" marker is in text; the structured shape is filter-only)

### Rationale
1. **Embedding similarity is most useful when the prose matches how a clinician describes an appointment.** "Cardiology follow-up with Dr. Adams tomorrow morning" is a query a kNN search should answer; "appointments where Dr. Admin clicked save" is structured-filter territory.
2. **Audit metadata has no semantic-search use case worth diluting the embedding corpus for.** Creator/changedBy UUIDs are opaque strings that wouldn't usefully participate in embedding similarity. Even creator/changedBy display names would mostly add noise — the meaningful clinical descriptor is the provider, not the back-office user who entered the record.
3. **The multi-valued provider surface is filter-only by design** ([Decision 4](#decision-4-multi-provider-arrays-alongside-cross-cutting-single-provider-fields)). The primary provider is in text for the common single-provider case; the full set is reachable via the parallel arrays.

### Consequences
- Free-text search like "appointments created by Dr. Admin" doesn't work. The same query expressed as `creator_uuid:<admin-uuid>` does work. Consumers and dashboards must use the right surface per query shape.
- Multi-provider appointments are still discoverable in text via the primary provider — but the secondary providers are not. Consumers searching for "appointments with Dr. C" where C is a secondary provider must use `provider_uuids` or `provider_names`, not the embedded text.
- The full recurring structure is filter-only. Free-text search "weekly Monday recurring" doesn't match a non-recurring appointment but also won't match a weekly Monday recurring appointment unless the consumer's search backend interprets the `is_recurring`-derived "Recurring" token. Structured queries are the right surface.

---

## Decision 15: `DOC_ZONE` = JVM-default timezone, captured at class load

### Status
Accepted (with deployment-time caveat).

### Context
The `date` cross-cutting field is a `LocalDate`. Projecting a UTC `Instant` (the underlying `Date.toInstant()`) onto a calendar date requires picking a timezone. Three options:

| Option | Behavior |
|---|---|
| `ZoneId.systemDefault()` at class load | Calendar boundaries match the JVM's local time; consistent with the rest of the module |
| `ZoneOffset.UTC` | Calendar boundaries are UTC midnight; deployment-independent but shifted from local clinical time for non-UTC deployments |
| Read from a module property | Deployment-configurable but adds an operational knob |

The choice is load-bearing because date-range queries (`date:2026-06-01`) return different result sets depending on which day a given clinical Instant falls onto.

### Decision
```java
private static final ZoneId DOC_ZONE = ZoneId.systemDefault();
```

Captured once at class load. Applied to both the `date` field projection and the human-readable date/time substrings in `text`.

### Rationale
1. **Consistent with the rest of the appointments module.** Every other `Date.toLocalDate()`-equivalent operation in the module already uses JVM-default. A consumer reading "today's appointments" gets the same calendar interpretation as the module's existing reporting surfaces.
2. **Most deployments are single-timezone.** A deployment running in IST sees IST-aligned calendar boundaries; a deployment running in EST sees EST-aligned. The clinical user expects local-time semantics.
3. **UTC was rejected because it offsets boundaries by hours.** An appointment scheduled for 2026-06-01 23:00 IST (= 17:30 UTC) would land on `date:2026-06-01` under JVM-default-IST but `date:2026-06-01` under UTC also — but an appointment scheduled for 2026-06-01 06:00 IST (= 00:30 UTC) lands on `date:2026-06-01` under IST and `date:2026-06-01` under UTC. The boundary cases differ. Local-time alignment is more intuitive for the IST-zoned deployments that drive this module's primary usage (Bahmni distributions, primarily Indian healthcare).
4. **A configured property was rejected because the operational cost outweighs the gain.** A deployment that genuinely needs UTC can set the JVM `user.timezone` system property (the module already does this for test runs); a dedicated module property would add a knob with no clear consumer.

### Consequences
- A multi-region deployment (extremely rare for the appointments module) where different OpenMRS nodes run in different JVM time zones would project the same clinical Instant onto different `LocalDate` values per node. Cross-node date-range queries against the same shared backend would surface inconsistent results.
- The capture is at class load. Subsequent changes to `TimeZone.setDefault(...)` at runtime do not affect the serializer's behaviour. The module's test harness sets `user.timezone=Asia/Kolkata` via Maven `argLine`, locking the test-time projection.
- A deployment that wants UTC must set `-Duser.timezone=UTC` at JVM startup. Documented in the class Javadoc.

---

## Decision 16: Bean discovery via `Context.getRegisteredComponent`, not Spring `@Autowired`

### Status
Accepted (forced by OpenMRS's AOP wiring lifecycle).

### Context
The three advice classes need three dependencies each on every fire: the `AppointmentSerializer` Spring bean (for projection), the `querystore.bridge.indexer` bean (for upsert/delete), and the `querystore.bridge.dispatcher` bean (for after-commit scheduling). The "obvious" pattern would be Spring constructor injection or `@Autowired` fields. The slice does neither — every dependency is resolved on each `afterReturning` invocation via `Context.getRegisteredComponent(beanId, ExpectedType.class)`.

The non-obvious constraint: OpenMRS's `<advice>` mechanism in `config.xml` is interpreted by the OpenMRS module loader, not by Spring. The module loader instantiates the advice class via its no-arg constructor at module-load time, *before* the module's Spring context is refreshed. There is no Spring proxy around the advice instance and no Spring lifecycle hook to inject collaborators into it. A field annotated `@Autowired` would stay null.

### Decision
Every dependency lookup in the three advice classes goes through `Context.getRegisteredComponent(...)`. Each lookup is performed inside the method body (e.g. `dispatch()`) on every invocation rather than cached on the instance:

```java
AppointmentSerializer serializer = Context.getRegisteredComponent(
        AppointmentIndexingAdvice.SERIALIZER_BEAN_ID, AppointmentSerializer.class);
BridgeIndexer indexer = Context.getRegisteredComponent(
        BRIDGE_INDEXER_BEAN_ID, BridgeIndexer.class);
AfterCommitDispatcher dispatcher = Context.getRegisteredComponent(
        BRIDGE_DISPATCHER_BEAN_ID, AfterCommitDispatcher.class);
```

The serializer bean ID is captured as a package-visible constant (`AppointmentIndexingAdvice.SERIALIZER_BEAN_ID`); the bridge bean IDs are captured as constants on `RecurringAppointmentIndexingAdvice` and reused by the sibling advice. Constants exist so the test fixtures and production lookups agree.

### Rationale
1. **`@Autowired` does not work for `<advice>`-wired classes.** The module loader instantiates them outside Spring's bean lifecycle. A `@Autowired AppointmentSerializer serializer` field would compile, the module would load, and the field would stay null forever. The first advice fire would NPE — caught by the outer swallow per [Decision 11](#decision-11-linkageerror-catch-widening-for-version-skew-tolerance) and logged at warn level, with the result that every appointment write silently bypasses querystore.
2. **Per-invocation lookup costs are negligible.** `Context.getRegisteredComponent` is a HashMap lookup; the cost is dwarfed by the surrounding serialize → embed → upsert work. Caching the result on the advice instance would save ~3 hash lookups per write at the cost of a thread-safety concern (the advice is shared across threads).
3. **Querystore's own `AbstractIndexingAdvice` uses the same pattern for its `indexer()` / `dispatcher()` methods.** This isn't a slice-level invention; it's the convention OpenMRS modules use for AOP-advice dependencies.
4. **Bean-ID constants over string literals** so a future bean rename in `moduleApplicationContext.xml` propagates to the test fixtures and back. A magic string in two places drifts; a constant in two places doesn't.

### Consequences
- A future contributor seeing the per-invocation lookups and "simplifying" to `@Autowired` would break the module silently. The pattern is documented here so the next maintainer reads the constraint before the refactor.
- A rename of any of the three bean IDs (`appointments.serializer.appointment`, `querystore.bridge.indexer`, `querystore.bridge.dispatcher`) is a coordinated change across `moduleApplicationContext.xml`, the constant definitions, and the test fixtures. The tests stub `Context.getRegisteredComponent` via `MockedStatic<Context>` referencing the same constants, so the rename surfaces at test time rather than at production fire-time.
- Querystore's own `<bean id="querystore.bridge.indexer">` and `<bean id="querystore.bridge.dispatcher">` IDs are part of its de-facto public SPI. A rename upstream is a breaking change; this module's `<require_module>` declaration (see [Decision 7](#decision-7-require_module-querystore-no-version-pin-while-snapshot)) plus the `LinkageError` catch widening (see [Decision 11](#decision-11-linkageerror-catch-widening-for-version-skew-tolerance)) form the safety net.

---

## Open questions

### Cross-occurrence grouping for recurring appointments
[Decision 5](#decision-5-recurring-pattern-structure-surfaced-as-flat-metadata-no-pattern-uuid) declines to surface a recurring-pattern identifier because `AppointmentRecurringPattern` has no UUID. If a consumer needs this query, the right structural fix is to add `extends BaseOpenmrsData` to `AppointmentRecurringPattern` — a core model change.

### `appointment_audits` as a separate `appointments_audit` resource type
[Decision 9](#decision-9-appointmentaudit-history-not-indexed-in-this-resource-type) defers this until a concrete consumer asks. The contribution would follow the same SPI pattern this slice established.

### Reschedule prev-appointment self-invocation
[Decision 8](#decision-8-appointmentsserviceimplreschedule-prev-appointment-gap-accepted) accepts the gap and recommends the structural fix live in `AppointmentsServiceImpl.reschedule`, not the querystore advices. The Atomfeed advice on the same service has the identical gap; fixing one fixes the other.

### Embedding model alignment
Querystore embeds at write time per qsADR-8. Consumers issuing kNN queries against `querystore_appointments_appointment` must embed their query text with the same model querystore used at index time, per qsADR-13's embedding-model contract. The model identifier is part of querystore's public SPI, not ours; this module surfaces no model identifier directly.

### Cross-module end-to-end test
The slice has 248 unit tests + 41 IT tests + 73 omod IT tests. None of them exercise the full `AppointmentsService.validateAndSave(...) → querystore_appointments_appointment document indexed` pipeline with both modules wired in a real Spring context. The right structural fix is a distribution-level test suite, not in either contributing module. Deferred.
