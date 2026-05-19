package org.openmrs.module.appointments.querystore;

import org.openmrs.Location;
import org.openmrs.Provider;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentProvider;
import org.openmrs.module.appointments.model.AppointmentRecurringPattern;
import org.openmrs.module.appointments.model.AppointmentServiceDefinition;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.openmrs.module.appointments.model.AppointmentStatus;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Set;

/**
 * Serializes an {@link Appointment} into a {@link QueryDocument} per querystore ADR Decisions 6
 * and 13. The resource type is {@code appointments_appointment}; the corresponding querystore
 * index is {@code querystore_appointments_appointment}.
 *
 * <p>Cross-cutting fields populated: {@code resource_type}, {@code resource_uuid},
 * {@code patient_uuid}, {@code last_modified}, {@code date}, {@code text}. Optional cross-cutting
 * fields populated when present: {@code provider_uuid}/{@code provider_name},
 * {@code location_uuid}/{@code location_name}. {@code encounter_uuid}, {@code visit_uuid},
 * {@code form_uuid}, and {@code encounter_type_*} are intentionally null — appointments are not
 * encounter-attached.
 */
public class AppointmentSerializer implements ClinicalRecordSerializer<Appointment> {

	public static final String RESOURCE_TYPE = "appointments_appointment";

	private static final ZoneId DOC_ZONE = ZoneId.systemDefault();

	@Override
	public String getResourceType() {
		return RESOURCE_TYPE;
	}

	@Override
	public Class<Appointment> getSupportedType() {
		return Appointment.class;
	}

	@Override
	public QueryDocument serialize(Appointment appointment) {
		QueryDocument doc = new QueryDocument();
		doc.setResourceType(RESOURCE_TYPE);
		doc.setResourceUuid(appointment.getUuid());
		if (appointment.getPatient() != null) {
			doc.setPatientUuid(appointment.getPatient().getUuid());
		}

		Date lastModifiedDate = appointment.getDateChanged() != null
				? appointment.getDateChanged()
				: appointment.getDateCreated();
		if (lastModifiedDate != null) {
			doc.setLastModified(lastModifiedDate.toInstant());
		}

		Date clinicalDate = appointment.getStartDateTime() != null
				? appointment.getStartDateTime()
				: lastModifiedDate;
		if (clinicalDate != null) {
			// LocalDate.ofInstant(Instant, ZoneId) is JDK 9+; the module compiles to release 8.
			doc.setDate(clinicalDate.toInstant().atZone(DOC_ZONE).toLocalDate());
		}

		doc.setText(buildText(appointment));

		Provider primaryProvider = resolvePrimaryProvider(appointment);
		if (primaryProvider != null) {
			doc.putMetadata("provider_uuid", primaryProvider.getUuid());
			doc.putMetadata("provider_name", primaryProvider.getName());
		}
		Location location = appointment.getLocation();
		if (location != null) {
			doc.putMetadata("location_uuid", location.getUuid());
			doc.putMetadata("location_name", location.getName());
		}

		AppointmentServiceDefinition service = appointment.getService();
		if (service != null) {
			doc.putMetadata("appointment_service_uuid", service.getUuid());
			doc.putMetadata("appointment_service_name", service.getName());
		}
		AppointmentServiceType serviceType = appointment.getServiceType();
		if (serviceType != null) {
			doc.putMetadata("appointment_service_type_uuid", serviceType.getUuid());
			doc.putMetadata("appointment_service_type_name", serviceType.getName());
		}

		if (appointment.getAppointmentNumber() != null) {
			doc.putMetadata("appointment_number", appointment.getAppointmentNumber());
		}
		AppointmentStatus status = appointment.getStatus();
		if (status != null) {
			doc.putMetadata("status", status.name());
		}
		if (appointment.getAppointmentKind() != null) {
			doc.putMetadata("appointment_kind", appointment.getAppointmentKind().name());
		}
		if (appointment.getStartDateTime() != null) {
			doc.putMetadata("start_date_time", appointment.getStartDateTime().toInstant().toString());
		}
		if (appointment.getEndDateTime() != null) {
			doc.putMetadata("end_date_time", appointment.getEndDateTime().toInstant().toString());
		}
		if (appointment.getComments() != null && !appointment.getComments().isEmpty()) {
			doc.putMetadata("comments", appointment.getComments());
		}
		AppointmentRecurringPattern recurringPattern = appointment.getAppointmentRecurringPattern();
		doc.putMetadata("is_recurring", recurringPattern != null);
		// AppointmentRecurringPattern carries only a numeric id (it doesn't extend BaseOpenmrsData
		// and has no uuid), so the recurring grouping is surfaced only as a boolean flag here.
		// Consumers wanting all occurrences of a recurrence should query by appointment-service +
		// patient_uuid and group client-side.
		if (appointment.getTeleHealthVideoLink() != null
				&& !appointment.getTeleHealthVideoLink().isEmpty()) {
			doc.putMetadata("teleconsultation_link", appointment.getTeleHealthVideoLink());
		}

		return doc;
	}

	private String buildText(Appointment a) {
		StringBuilder sb = new StringBuilder("Appointment");
		if (a.getService() != null && a.getService().getName() != null) {
			sb.append(" for ").append(a.getService().getName());
		}
		if (a.getServiceType() != null && a.getServiceType().getName() != null) {
			sb.append(" (").append(a.getServiceType().getName()).append(')');
		}
		if (a.getStartDateTime() != null) {
			SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
			SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
			sb.append(" on ").append(dateFmt.format(a.getStartDateTime()));
			sb.append(" at ").append(timeFmt.format(a.getStartDateTime()));
			if (a.getEndDateTime() != null) {
				sb.append('-').append(timeFmt.format(a.getEndDateTime()));
			}
		}
		Provider provider = resolvePrimaryProvider(a);
		if (provider != null && provider.getName() != null) {
			sb.append(" with ").append(provider.getName());
		}
		if (a.getLocation() != null && a.getLocation().getName() != null) {
			sb.append(" at ").append(a.getLocation().getName());
		}
		if (a.getStatus() != null) {
			sb.append(". Status: ").append(a.getStatus().name());
		}
		if (a.getAppointmentKind() != null) {
			sb.append(". Kind: ").append(a.getAppointmentKind().name());
		}
		if (a.getTeleHealthVideoLink() != null && !a.getTeleHealthVideoLink().isEmpty()) {
			sb.append(". Teleconsultation.");
		}
		if (a.getAppointmentRecurringPattern() != null) {
			sb.append(" Recurring.");
		}
		if (a.getComments() != null && !a.getComments().isEmpty()) {
			sb.append(' ').append(a.getComments());
		}
		return sb.toString();
	}

	/**
	 * Appointments carry both a singular {@code provider} field (legacy) and a {@code providers} set
	 * (current). The set is canonical when populated; the singular field is a fallback for older
	 * data that has not been migrated.
	 */
	private Provider resolvePrimaryProvider(Appointment a) {
		Set<AppointmentProvider> providers = a.getProviders();
		if (providers != null) {
			for (AppointmentProvider ap : providers) {
				if (ap != null && ap.getProvider() != null) {
					return ap.getProvider();
				}
			}
		}
		return a.getProvider();
	}
}
