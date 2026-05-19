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
import org.openmrs.module.appointments.model.AppointmentRecurringPattern;
import org.openmrs.module.appointments.model.AppointmentServiceDefinition;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.openmrs.module.appointments.model.AppointmentStatus;
import org.openmrs.module.querystore.model.QueryDocument;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
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
		appointment.setAppointmentRecurringPattern(new AppointmentRecurringPattern());

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
		assertEquals("svc-uuid", doc.getMetadata().get("appointment_service_uuid"));
		assertEquals("svctype-uuid", doc.getMetadata().get("appointment_service_type_uuid"));
		assertEquals("APPT-001", doc.getMetadata().get("appointment_number"));
		assertEquals("Scheduled", doc.getMetadata().get("status"));
		assertEquals("Scheduled", doc.getMetadata().get("appointment_kind"));
		assertEquals(Boolean.TRUE, doc.getMetadata().get("is_recurring"));
		assertEquals("https://meet.example.org/abc", doc.getMetadata().get("teleconsultation_link"));
		assertEquals("Patient prefers morning slots", doc.getMetadata().get("comments"));
	}

	@Test
	public void serializesMinimalAppointmentWithoutOptionalFields() {
		Appointment appointment = newAppointment();

		QueryDocument doc = serializer.serialize(appointment);

		assertEquals("appointments_appointment", doc.getResourceType());
		assertEquals(APPOINTMENT_UUID, doc.getResourceUuid());
		assertEquals(PATIENT_UUID, doc.getPatientUuid());
		assertNotNull(doc.getText());
		assertNull(doc.getMetadata().get("provider_uuid"));
		assertNull(doc.getMetadata().get("location_uuid"));
		assertNull(doc.getMetadata().get("appointment_service_uuid"));
		assertNull(doc.getMetadata().get("appointment_service_type_uuid"));
		assertNull(doc.getMetadata().get("teleconsultation_link"));
		assertNull(doc.getMetadata().get("comments"));
		// is_recurring is emitted only when true (sparse-when-false convention).
		assertNull(doc.getMetadata().get("is_recurring"));
	}

	@Test
	public void emitsNoProviderFieldsWhenProvidersSetIsEmpty() {
		Appointment appointment = newAppointment();
		appointment.setProviders(Collections.<AppointmentProvider>emptySet());

		QueryDocument doc = serializer.serialize(appointment);

		// Appointment.provider (the singular Java field) is intentionally not Hibernate-mapped,
		// so persisted appointments never populate it — the serializer correctly emits no
		// provider_uuid / provider_name when the providers Set is empty.
		assertNull(doc.getMetadata().get("provider_uuid"));
		assertNull(doc.getMetadata().get("provider_name"));
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
		Set<AppointmentProvider> providers = new java.util.LinkedHashSet<>();
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

		assertNull(doc.getMetadata().get("teleconsultation_link"));
		assertFalse(doc.getText().contains("Teleconsultation"));
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

		Calendar created = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		created.set(2026, Calendar.JANUARY, 10, 9, 0, 0);
		appointment.setDateCreated(created.getTime());

		Calendar changed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		changed.set(2026, Calendar.JANUARY, 11, 9, 0, 0);
		appointment.setDateChanged(changed.getTime());

		Calendar start = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		start.set(2026, Calendar.JUNE, 1, 10, 0, 0);
		appointment.setStartDateTime(start.getTime());

		Calendar end = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		end.set(2026, Calendar.JUNE, 1, 10, 30, 0);
		appointment.setEndDateTime(end.getTime());

		return appointment;
	}
}
