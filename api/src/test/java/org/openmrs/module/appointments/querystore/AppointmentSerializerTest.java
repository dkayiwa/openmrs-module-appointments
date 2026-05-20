package org.openmrs.module.appointments.querystore;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.PersonName;
import org.openmrs.Provider;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentKind;
import org.openmrs.module.appointments.model.AppointmentProvider;
import org.openmrs.module.appointments.model.AppointmentProviderResponse;
import org.openmrs.module.appointments.model.AppointmentRecurringPattern;
import org.openmrs.module.appointments.model.AppointmentServiceDefinition;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.openmrs.module.appointments.model.AppointmentStatus;
import org.openmrs.module.appointments.service.impl.RecurringAppointmentType;
import org.openmrs.module.querystore.model.QueryDocument;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the {@link AppointmentSerializer} cross-cutting field contract per querystore ADR
 * Decisions 6 and 13: every populated and intentionally-omitted field listed in the serializer's
 * Javadoc must match the document this class projects. The regression to guard against is "a
 * future field rename or reordering silently drops a metadata key" — the failure mode is silent
 * because querystore happily indexes documents with whatever fields are present.
 */
public class AppointmentSerializerTest {

	private static final String APPOINTMENT_UUID = "a-uuid";
	private static final String PATIENT_UUID = "p-uuid";

	private AppointmentSerializer serializer;

	@Before
	public void setUp() {
		serializer = new AppointmentSerializer();
	}

	@Test
	public void reportsStableResourceTypeAndSupportedType() {
		assertEquals("appointments_appointment", serializer.getResourceType());
		assertEquals(Appointment.class, serializer.getSupportedType());
	}

	@Test
	public void serializesFullyPopulatedAppointment() {
		Appointment appointment = newAppointment();
		appointment.setAppointmentNumber("APPT-001");
		appointment.setStatus(AppointmentStatus.Scheduled);
		appointment.setAppointmentKind(AppointmentKind.Scheduled);
		appointment.setComments("Patient prefers morning slots");
		appointment.setTeleHealthVideoLink("https://meet.example.org/abc");
		AppointmentRecurringPattern recurringPattern = new AppointmentRecurringPattern();
		recurringPattern.setType(RecurringAppointmentType.WEEK);
		recurringPattern.setPeriod(2);
		recurringPattern.setFrequency(6);
		recurringPattern.setDaysOfWeek("MON,WED");
		recurringPattern.setEndDate(utcDate(2026, Calendar.AUGUST, 30, 0, 0, 0));
		appointment.setAppointmentRecurringPattern(recurringPattern);

		AppointmentServiceDefinition service = new AppointmentServiceDefinition();
		service.setUuid("svc-uuid");
		service.setName("Cardiology");
		appointment.setService(service);

		AppointmentServiceType serviceType = new AppointmentServiceType();
		serviceType.setUuid("svctype-uuid");
		serviceType.setName("Follow-up");
		appointment.setServiceType(serviceType);

		Location location = new Location();
		location.setUuid("loc-uuid");
		location.setName("OPD Room 4");
		appointment.setLocation(location);

		Provider provider = new Provider();
		provider.setUuid("provider-uuid");
		provider.setPerson(personNamed("Dr.", "Adams"));
		AppointmentProvider appointmentProvider = new AppointmentProvider();
		appointmentProvider.setProvider(provider);
		appointmentProvider.setResponse(AppointmentProviderResponse.ACCEPTED);
		Set<AppointmentProvider> providers = new HashSet<>();
		providers.add(appointmentProvider);
		appointment.setProviders(providers);

		QueryDocument doc = serializer.serialize(appointment);

		assertEquals("appointments_appointment", doc.getResourceType());
		assertEquals(APPOINTMENT_UUID, doc.getResourceUuid());
		assertEquals(PATIENT_UUID, doc.getPatientUuid());
		assertNotNull(doc.getLastModified());
		assertNotNull(doc.getDate());

		String text = doc.getText();
		assertTrue("text should mention service", text.contains("Cardiology"));
		assertTrue("text should mention service type", text.contains("Follow-up"));
		assertTrue("text should mention provider", text.contains("Adams"));
		assertTrue("text should mention location", text.contains("OPD Room 4"));
		assertTrue("text should mention status", text.contains("Scheduled"));
		assertTrue("text should mention recurrence", text.contains("Recurring"));
		assertTrue("text should mention teleconsultation", text.contains("Teleconsultation"));
		assertTrue("text should include comments", text.contains("morning slots"));

		assertEquals("provider-uuid", doc.getMetadata().get("provider_uuid"));
		assertNotNull(doc.getMetadata().get("provider_name"));
		assertTrue(doc.getMetadata().get("provider_name").toString().contains("Adams"));
		assertEquals("loc-uuid", doc.getMetadata().get("location_uuid"));
		assertEquals("OPD Room 4", doc.getMetadata().get("location_name"));
		assertEquals("svc-uuid", doc.getMetadata().get("appointment_service_uuid"));
		assertEquals("Cardiology", doc.getMetadata().get("appointment_service_name"));
		assertEquals("svctype-uuid", doc.getMetadata().get("appointment_service_type_uuid"));
		assertEquals("Follow-up", doc.getMetadata().get("appointment_service_type_name"));
		assertEquals("APPT-001", doc.getMetadata().get("appointment_number"));
		assertEquals("Scheduled", doc.getMetadata().get("status"));
		assertEquals("Scheduled", doc.getMetadata().get("appointment_kind"));
		// start_date_time and end_date_time use ISO-8601 instant format from the appointment's
		// java.util.Date.toInstant().toString(); the fixture in newAppointment() pins these to
		// June 1, 2026 10:00 / 10:30 UTC. Asserting the exact strings guards against a typo or
		// format drift that would silently change the wire shape consumers parse.
		assertEquals("2026-06-01T10:00:00Z", doc.getMetadata().get("start_date_time"));
		assertEquals("2026-06-01T10:30:00Z", doc.getMetadata().get("end_date_time"));
		assertEquals(Boolean.TRUE, doc.getMetadata().get("is_recurring"));
		assertEquals("WEEK", doc.getMetadata().get("recurring_type"));
		assertEquals(2, doc.getMetadata().get("recurring_period"));
		assertEquals(6, doc.getMetadata().get("recurring_frequency"));
		assertEquals("MON,WED", doc.getMetadata().get("recurring_days_of_week"));
		assertEquals("2026-08-30T00:00:00Z", doc.getMetadata().get("recurring_end_date"));
		assertEquals("https://meet.example.org/abc", doc.getMetadata().get("teleconsultation_link"));
		assertEquals("Patient prefers morning slots", doc.getMetadata().get("comments"));
		// Multi-provider surface: the single provider in the fixture is still in the *_list fields.
		assertEquals(Arrays.asList("provider-uuid"), doc.getMetadata().get("provider_uuids"));
		List<?> singleProviderNames = (List<?>) doc.getMetadata().get("provider_names");
		assertEquals(1, singleProviderNames.size());
		assertTrue(singleProviderNames.get(0).toString().contains("Adams"));
		assertEquals(Arrays.asList("provider-uuid:ACCEPTED"),
				doc.getMetadata().get("provider_responses"));
	}

	@Test
	public void serializesMinimalAppointmentWithoutOptionalFields() {
		Appointment appointment = newAppointment();

		QueryDocument doc = serializer.serialize(appointment);

		assertEquals("appointments_appointment", doc.getResourceType());
		assertEquals(APPOINTMENT_UUID, doc.getResourceUuid());
		assertEquals(PATIENT_UUID, doc.getPatientUuid());
		assertNotNull(doc.getText());
		// Use containsKey (not get == null) so a regression that emits putMetadata("k", null) —
		// present-but-null — is caught: a consumer's "IS NOT NULL" / "containsKey" filter would
		// then misclassify provider-less appointments as having a (null) provider while
		// get == null stayed green.
		assertFalse(doc.getMetadata().containsKey("provider_uuid"));
		assertFalse(doc.getMetadata().containsKey("location_uuid"));
		assertFalse(doc.getMetadata().containsKey("appointment_service_uuid"));
		assertFalse(doc.getMetadata().containsKey("appointment_service_type_uuid"));
		assertFalse(doc.getMetadata().containsKey("teleconsultation_link"));
		assertFalse(doc.getMetadata().containsKey("comments"));
		// is_recurring is emitted only when true (sparse-when-false convention).
		assertFalse(doc.getMetadata().containsKey("is_recurring"));

		// The `text` chunk for an appointment with no optional fields must contain none of the
		// buildText connecting tokens for null entities — guards against a regression that
		// removes the `&& getX() != null` from any buildText branch and emits literal `null`.
		String text = doc.getText();
		assertFalse("text must not contain literal 'null' substring on a minimal appointment",
				text.contains("null"));
	}

	@Test
	public void emitsNoProviderFieldsWhenProvidersSetIsEmpty() {
		Appointment appointment = newAppointment();
		appointment.setProviders(Collections.<AppointmentProvider>emptySet());

		QueryDocument doc = serializer.serialize(appointment);

		// Appointment.provider (the singular Java field) is intentionally not Hibernate-mapped,
		// so persisted appointments never populate it — the serializer correctly emits no
		// provider_uuid / provider_name when the providers Set is empty. Use containsKey so a
		// regression emitting putMetadata("provider_uuid", null) stays caught.
		assertFalse(doc.getMetadata().containsKey("provider_uuid"));
		assertFalse(doc.getMetadata().containsKey("provider_name"));
	}

	@Test
	public void omitsNameFieldsWhenLocationServiceOrTypeNameIsNull() {
		Appointment appointment = newAppointment();
		// All three entities present but with null names — a transient/incomplete state the model
		// doesn't constrain against. Without the omit-on-null guards, the metadata map carries
		// present-but-null entries while `buildText` (which guards) omits them entirely, breaking
		// the contract that structured and free-text searches agree on which appointments
		// reference a named location / service / type.
		Location location = new Location();
		location.setUuid("loc-uuid");
		// no name set
		appointment.setLocation(location);

		AppointmentServiceDefinition service = new AppointmentServiceDefinition();
		service.setUuid("svc-uuid");
		// no name set
		appointment.setService(service);

		AppointmentServiceType serviceType = new AppointmentServiceType();
		serviceType.setUuid("svctype-uuid");
		// no name set
		appointment.setServiceType(serviceType);

		QueryDocument doc = serializer.serialize(appointment);

		// UUIDs are still emitted — the entities are present even if their display label isn't.
		assertEquals("loc-uuid", doc.getMetadata().get("location_uuid"));
		assertEquals("svc-uuid", doc.getMetadata().get("appointment_service_uuid"));
		assertEquals("svctype-uuid", doc.getMetadata().get("appointment_service_type_uuid"));

		// Name fields must be absent (not present-but-null) so a consumer's
		// "location_name IS NOT NULL" filter correctly excludes these appointments.
		assertFalse("location_name must not be in the metadata map when Location.name is null",
				doc.getMetadata().containsKey("location_name"));
		assertFalse("appointment_service_name must not be in the metadata map when name is null",
				doc.getMetadata().containsKey("appointment_service_name"));
		assertFalse("appointment_service_type_name must not be in the metadata map when name is null",
				doc.getMetadata().containsKey("appointment_service_type_name"));

		// The `text` chunk's null-guards in buildText must also hold — otherwise a regression
		// that removes `&& getName() != null` from the buildText conditionals would emit literal
		// "null" tokens into the searchable text while this test (which only checks metadata)
		// stayed green. Asserting absence of the connecting prepositions catches that drift.
		String text = doc.getText();
		assertFalse("text must not contain ' for null' when service name is null",
				text.contains(" for null"));
		assertFalse("text must not contain ' (null)' when service-type name is null",
				text.contains(" (null)"));
		assertFalse("text must not contain ' at null' when location name is null",
				text.contains(" at null"));
		// Generic safety net: no "null" substring should appear in a chunk built entirely from
		// null-named entities — covers any future buildText fragment that forgets the guard.
		assertFalse("text must not contain a literal 'null' substring for any null-named entity",
				text.contains("null"));
	}

	@Test
	public void emitsOnlyApplicableRecurringFieldsForDayTypePattern() {
		Appointment appointment = newAppointment();
		// DAY-type ad-hoc pattern: type + period + frequency, but no daysOfWeek and no endDate.
		// The conditional-emit guards in the serializer must keep recurring_days_of_week and
		// recurring_end_date out of the metadata map entirely (not present-but-null), because a
		// querystore consumer doing "recurring_end_date IS NOT NULL" would otherwise misclassify
		// open-ended DAY patterns as bounded.
		AppointmentRecurringPattern pattern = new AppointmentRecurringPattern();
		pattern.setType(RecurringAppointmentType.DAY);
		pattern.setPeriod(1);
		pattern.setFrequency(5);
		appointment.setAppointmentRecurringPattern(pattern);

		QueryDocument doc = serializer.serialize(appointment);

		assertEquals(Boolean.TRUE, doc.getMetadata().get("is_recurring"));
		assertEquals("DAY", doc.getMetadata().get("recurring_type"));
		assertEquals(1, doc.getMetadata().get("recurring_period"));
		assertEquals(5, doc.getMetadata().get("recurring_frequency"));
		assertFalse("daysOfWeek must not be in the metadata map at all for DAY patterns",
				doc.getMetadata().containsKey("recurring_days_of_week"));
		assertFalse("endDate must not be in the metadata map at all for open-ended patterns",
				doc.getMetadata().containsKey("recurring_end_date"));
		// The `text` chunk must mention recurrence so a free-text search agrees with the
		// structured surface. Without this, a regression that gates the buildText "Recurring."
		// token on type==WEEK would silently desync free-text and structured search for DAY
		// patterns.
		assertTrue("text chunk must mention recurrence for any non-null pattern",
				doc.getText().contains("Recurring"));
	}

	@Test
	public void fallsBackToProviderUuidWhenProviderNameIsNull() {
		Appointment appointment = newAppointment();
		// Provider with no linked Person — Provider.getName() returns null in this state.
		// Without the fallback in the serializer, provider_names would contain a literal null
		// which a JSON-rendering backend serialises as ["Dr. Alpha", null, ...], producing a
		// list entry no consumer can reconcile back to any user.
		Provider namelessProvider = new Provider();
		namelessProvider.setUuid("nameless-uuid");
		AppointmentProvider ap = new AppointmentProvider();
		ap.setProvider(namelessProvider);
		appointment.setProviders(new LinkedHashSet<>(Collections.singletonList(ap)));

		QueryDocument doc = serializer.serialize(appointment);

		List<?> providerNames = (List<?>) doc.getMetadata().get("provider_names");
		assertEquals(1, providerNames.size());
		assertEquals("provider_names must not contain literal null; UUID is the fallback",
				"nameless-uuid", providerNames.get(0));
		// Singular provider_name (cross-cutting) follows the same arrays — verify it is non-null.
		assertNotNull(doc.getMetadata().get("provider_name"));
		// The free-text `text` chunk must mention the same fallback so a free-text search and a
		// structured search agree on which appointments reference this provider.
		assertTrue("text chunk must include the provider UUID when name is null",
				doc.getText().contains("nameless-uuid"));
	}

	@Test
	public void surfacesEveryProviderAndResponseForMultiProviderAppointments() {
		Appointment appointment = newAppointment();
		Provider providerOne = new Provider();
		providerOne.setUuid("provider-1");
		providerOne.setPerson(personNamed("Dr.", "Alpha"));
		Provider providerTwo = new Provider();
		providerTwo.setUuid("provider-2");
		providerTwo.setPerson(personNamed("Dr.", "Beta"));
		Provider providerThree = new Provider();
		providerThree.setUuid("provider-3");
		providerThree.setPerson(personNamed("Dr.", "Gamma"));

		AppointmentProvider apOne = new AppointmentProvider();
		apOne.setProvider(providerOne);
		apOne.setResponse(AppointmentProviderResponse.ACCEPTED);
		AppointmentProvider apTwo = new AppointmentProvider();
		apTwo.setProvider(providerTwo);
		apTwo.setResponse(AppointmentProviderResponse.REJECTED);
		AppointmentProvider apThree = new AppointmentProvider();
		apThree.setProvider(providerThree);
		// providerThree has no response yet (e.g. AWAITING semantics modelled as null)

		// LinkedHashSet preserves insertion order so the assertions are deterministic; production
		// uses Hibernate's set ordering which is not stable but the *list* surface is what
		// guarantees the search-correct coverage regardless.
		Set<AppointmentProvider> providers = new LinkedHashSet<>();
		providers.add(apOne);
		providers.add(apTwo);
		providers.add(apThree);
		appointment.setProviders(providers);

		QueryDocument doc = serializer.serialize(appointment);

		// Every provider UUID must appear — a consumer searching "appointments with provider-3"
		// would miss this record entirely if only the primary were indexed.
		assertEquals(Arrays.asList("provider-1", "provider-2", "provider-3"),
				doc.getMetadata().get("provider_uuids"));
		List<?> names = (List<?>) doc.getMetadata().get("provider_names");
		assertEquals(3, names.size());

		// Per-provider responses encoded as "<uuid>:<response>" so consumers can filter on
		// "where Dr. X has declined" without nested-object indexing. Providers with no response
		// recorded yet are omitted entirely; the consumer can derive "awaiting" by set difference
		// against provider_uuids.
		assertEquals(Arrays.asList("provider-1:ACCEPTED", "provider-2:REJECTED"),
				doc.getMetadata().get("provider_responses"));
	}

	@Test
	public void usesFirstProviderInIterationOrderWhenMultiplePresent() {
		Appointment appointment = newAppointment();
		Provider providerOne = new Provider();
		providerOne.setUuid("provider-1");
		providerOne.setPerson(personNamed("Dr.", "First"));
		Provider providerTwo = new Provider();
		providerTwo.setUuid("provider-2");
		providerTwo.setPerson(personNamed("Dr.", "Second"));
		AppointmentProvider apOne = new AppointmentProvider();
		apOne.setProvider(providerOne);
		AppointmentProvider apTwo = new AppointmentProvider();
		apTwo.setProvider(providerTwo);
		// LinkedHashSet preserves insertion order so the test is deterministic; production
		// behaviour depends on the Hibernate-returned set's iteration order, but the assertion here
		// is that we deterministically return some non-null provider in a multi-provider scenario.
		Set<AppointmentProvider> providers = new LinkedHashSet<>();
		providers.add(apOne);
		providers.add(apTwo);
		appointment.setProviders(providers);

		QueryDocument doc = serializer.serialize(appointment);

		Object providerUuid = doc.getMetadata().get("provider_uuid");
		assertNotNull(providerUuid);
		assertTrue("expected one of the configured providers",
				"provider-1".equals(providerUuid) || "provider-2".equals(providerUuid));
	}

	@Test
	public void leavesLastModifiedAndDateNullWhenBothAuditDatesMissing() {
		Appointment appointment = new Appointment();
		appointment.setUuid(APPOINTMENT_UUID);
		Patient patient = new Patient();
		patient.setUuid(PATIENT_UUID);
		appointment.setPatient(patient);
		// No dateCreated, no dateChanged, no startDateTime — neither cursor source available.

		QueryDocument doc = serializer.serialize(appointment);

		// Required cross-cutting fields per querystore ADR Decision 13 carry the contract that
		// downstream consumers may rely on. When the source entity carries neither audit date,
		// the serializer currently emits nulls and the backend's conditional-upsert race guard
		// (which depends on last_modified) falls back to last-write-wins. Document the behaviour
		// here so a future maintainer doesn't accidentally regress to a half-populated document.
		assertNull(doc.getLastModified());
		assertNull(doc.getDate());
		assertEquals(APPOINTMENT_UUID, doc.getResourceUuid());
		assertNotNull(doc.getText());
	}

	@Test
	public void usesDateCreatedAsLastModifiedFallback() {
		Appointment appointment = newAppointment();
		appointment.setDateChanged(null);

		QueryDocument doc = serializer.serialize(appointment);

		assertNotNull("falls back to dateCreated", doc.getLastModified());
		assertEquals(appointment.getDateCreated().toInstant(), doc.getLastModified());
	}

	@Test
	public void usesStartDateTimeAsLastModifiedFallbackWhenAuditDatesMissing() {
		// Legacy or test-seeded appointment missing both audit columns but with startDateTime set.
		// Without the third tier of the fallback chain, querystore's conditional-upsert race guard
		// (ADR Decision 3) has no version timestamp for these records and falls back to
		// last-write-wins — under concurrent indexing, the freshest projection can lose to a
		// slower bootstrap scan and the document goes stale silently.
		Appointment appointment = new Appointment();
		appointment.setUuid(APPOINTMENT_UUID);
		Patient patient = new Patient();
		patient.setUuid(PATIENT_UUID);
		appointment.setPatient(patient);
		Date startDateTime = utcDate(2026, Calendar.JUNE, 1, 10, 0, 0);
		appointment.setStartDateTime(startDateTime);

		QueryDocument doc = serializer.serialize(appointment);

		assertNotNull("falls back to startDateTime when both audit dates are null",
				doc.getLastModified());
		assertEquals(startDateTime.toInstant(), doc.getLastModified());
	}

	@Test
	public void fallsBackFromStartDateTimeToLastModifiedForClinicalDate() {
		// Appointment with audit dates but no startDateTime — doc.date must fall back to
		// lastModifiedDate so cross-tier wildcard date-range queries don't silently miss the
		// record. Without this fallback, legacy appointments without start times would be
		// undiscoverable via "date IN [range]" searches even when last_modified is in range.
		Appointment appointment = new Appointment();
		appointment.setUuid(APPOINTMENT_UUID);
		Patient patient = new Patient();
		patient.setUuid(PATIENT_UUID);
		appointment.setPatient(patient);
		Date changed = utcDate(2026, Calendar.JANUARY, 11, 9, 0, 0);
		appointment.setDateChanged(changed);
		// No startDateTime.

		QueryDocument doc = serializer.serialize(appointment);

		assertNotNull("doc.date falls back to lastModified when startDateTime is null",
				doc.getDate());
		assertEquals(changed.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
				doc.getDate());
	}

	@Test
	public void leavesEmbeddingUnsetSoQueryStorePopulatesIt() {
		Appointment appointment = newAppointment();
		QueryDocument doc = serializer.serialize(appointment);
		assertNull("embedding is populated by the querystore pipeline, not by the serializer",
				doc.getEmbedding());
	}

	@Test
	public void skipsTeleconsultationLinkWhenEmpty() {
		Appointment appointment = newAppointment();
		appointment.setTeleHealthVideoLink("");

		QueryDocument doc = serializer.serialize(appointment);

		assertFalse(doc.getMetadata().containsKey("teleconsultation_link"));
		assertFalse(doc.getText().contains("Teleconsultation"));
	}

	/**
	 * Calendar.set(...) does not zero the milliseconds field, so the resulting Date inherits the
	 * wall-clock millis from when the Calendar was constructed. That makes serialised instant
	 * strings (e.g. {@code start_date_time}) drift run-to-run and breaks deterministic assertions.
	 * Helper explicitly zeros millis so the instant strings render as {@code 2026-06-01T10:00:00Z}
	 * not {@code 2026-06-01T10:00:00.290Z}.
	 */
	private Date utcDate(int year, int month, int day, int hour, int minute, int second) {
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		cal.set(year, month, day, hour, minute, second);
		cal.set(Calendar.MILLISECOND, 0);
		return cal.getTime();
	}

	private Person personNamed(String given, String family) {
		Person person = new Person();
		PersonName name = new PersonName(given, null, family);
		person.addName(name);
		return person;
	}

	private Appointment newAppointment() {
		Appointment appointment = new Appointment();
		appointment.setUuid(APPOINTMENT_UUID);

		Patient patient = new Patient();
		patient.setUuid(PATIENT_UUID);
		appointment.setPatient(patient);

		appointment.setDateCreated(utcDate(2026, Calendar.JANUARY, 10, 9, 0, 0));
		appointment.setDateChanged(utcDate(2026, Calendar.JANUARY, 11, 9, 0, 0));
		appointment.setStartDateTime(utcDate(2026, Calendar.JUNE, 1, 10, 0, 0));
		appointment.setEndDateTime(utcDate(2026, Calendar.JUNE, 1, 10, 30, 0));

		return appointment;
	}
}
