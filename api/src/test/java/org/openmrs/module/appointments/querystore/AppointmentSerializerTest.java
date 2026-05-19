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
		assertEquals(Boolean.FALSE, doc.getMetadata().get("is_recurring"));
	}

	@Test
	public void fallsBackToSingularProviderFieldWhenProvidersSetIsEmpty() {
		Appointment appointment = newAppointment();
		Provider legacyProvider = new Provider();
		legacyProvider.setUuid("legacy-provider-uuid");
		legacyProvider.setPerson(personNamed("Dr.", "Legacy"));
		appointment.setProvider(legacyProvider);
		appointment.setProviders(Collections.<AppointmentProvider>emptySet());

		QueryDocument doc = serializer.serialize(appointment);

		assertEquals("legacy-provider-uuid", doc.getMetadata().get("provider_uuid"));
		assertNotNull(doc.getMetadata().get("provider_name"));
		assertTrue(doc.getMetadata().get("provider_name").toString().contains("Legacy"));
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
