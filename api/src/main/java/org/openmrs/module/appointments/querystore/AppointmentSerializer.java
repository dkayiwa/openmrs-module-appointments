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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

	// Captured at class load. Determines how Date instants project onto the {@code date} field and
	// the human-readable date/time substrings in {@code text}. Consumers comparing {@code date}
	// across deployments should align JVM time zones, or the cross-tier {@code querystore_*}
	// wildcard query may surface the same clinical instant under different calendar dates.
	private static final ZoneId DOC_ZONE = ZoneId.systemDefault();

	// Thread-safe and reusable; AppointmentSerializer is a Spring singleton serialised concurrently
	// on the bootstrap backfill path, so per-call SimpleDateFormat instantiation would churn heap.
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(DOC_ZONE);

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(DOC_ZONE);

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

		// last_modified drives querystore's conditional-upsert race guard (ADR Decision 3): a
		// non-null value is required for ordering concurrent writes correctly. Fall back through
		// dateChanged -> dateCreated -> startDateTime so legacy / test-seeded appointments missing
		// the audit columns still produce a comparable timestamp.
		Date lastModifiedDate = appointment.getDateChanged() != null
				? appointment.getDateChanged()
				: appointment.getDateCreated() != null
						? appointment.getDateCreated()
						: appointment.getStartDateTime();
		if (lastModifiedDate != null) {
			doc.setLastModified(lastModifiedDate.toInstant());
		}

		Date clinicalDate = appointment.getStartDateTime() != null
				? appointment.getStartDateTime()
				: lastModifiedDate;
		if (clinicalDate != null) {
			doc.setDate(clinicalDate.toInstant().atZone(DOC_ZONE).toLocalDate());
		}

		doc.setText(buildText(appointment));

		// Provider surface — single-valued "primary" fields keep backward-compatibility with the
		// querystore cross-cutting convention; the *_list fields are the search-correct surface
		// for multi-provider appointments. Without the lists, a query for "appointments with
		// Dr. C" silently misses every record where Dr. C isn't first in HashSet iteration order.
		List<Provider> resolvedProviders = resolveProviders(appointment);
		if (!resolvedProviders.isEmpty()) {
			Provider primaryProvider = resolvedProviders.get(0);
			doc.putMetadata("provider_uuid", primaryProvider.getUuid());
			doc.putMetadata("provider_name", primaryProvider.getName());

			List<String> providerUuids = new ArrayList<>(resolvedProviders.size());
			List<String> providerNames = new ArrayList<>(resolvedProviders.size());
			for (Provider provider : resolvedProviders) {
				providerUuids.add(provider.getUuid());
				providerNames.add(provider.getName());
			}
			doc.putMetadata("provider_uuids", providerUuids);
			doc.putMetadata("provider_names", providerNames);

			// Per-provider response (ACCEPTED / REJECTED / AWAITING) is what the
			// updateAppointmentProviderResponse trigger surface mutates. Surfacing it as a
			// UUID→response array lets consumers query "appointments where Dr. X has declined."
			List<String> providerResponses = collectProviderResponses(appointment);
			if (!providerResponses.isEmpty()) {
				doc.putMetadata("provider_responses", providerResponses);
			}
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
		// AppointmentRecurringPattern carries only a numeric id (it doesn't extend BaseOpenmrsData
		// and has no uuid), so the recurring grouping is surfaced as a boolean flag plus the
		// pattern's structured shape (type, period, frequency, daysOfWeek, endDate). Without these,
		// reporting tools can ask "any recurring?" but not "weekly recurring on Mondays."
		// Consumers wanting all occurrences of a single recurrence still need to query by
		// appointment-service + patient_uuid and group client-side; the pattern itself has no UUID.
		AppointmentRecurringPattern recurringPattern = appointment.getAppointmentRecurringPattern();
		if (recurringPattern != null) {
			doc.putMetadata("is_recurring", Boolean.TRUE);
			if (recurringPattern.getType() != null) {
				doc.putMetadata("recurring_type", recurringPattern.getType().name());
			}
			if (recurringPattern.getPeriod() != null) {
				doc.putMetadata("recurring_period", recurringPattern.getPeriod());
			}
			if (recurringPattern.getFrequency() != null) {
				doc.putMetadata("recurring_frequency", recurringPattern.getFrequency());
			}
			if (recurringPattern.getDaysOfWeek() != null && !recurringPattern.getDaysOfWeek().isEmpty()) {
				doc.putMetadata("recurring_days_of_week", recurringPattern.getDaysOfWeek());
			}
			if (recurringPattern.getEndDate() != null) {
				doc.putMetadata("recurring_end_date", recurringPattern.getEndDate().toInstant().toString());
			}
		}
		if (appointment.getTeleHealthVideoLink() != null
				&& !appointment.getTeleHealthVideoLink().isEmpty()) {
			doc.putMetadata("teleconsultation_link", appointment.getTeleHealthVideoLink());
		}

		return doc;
	}

	private String buildText(Appointment a) {
		// Typical rendered text runs ~80-160 chars (service + type + date/time window + provider +
		// location + status + kind). Pre-sizing avoids the 2-3 grow/copy cycles the default capacity
		// of 16 would incur on every serialize() call on the bootstrap backfill path.
		StringBuilder sb = new StringBuilder(192).append("Appointment");
		if (a.getService() != null && a.getService().getName() != null) {
			sb.append(" for ").append(a.getService().getName());
		}
		if (a.getServiceType() != null && a.getServiceType().getName() != null) {
			sb.append(" (").append(a.getServiceType().getName()).append(')');
		}
		if (a.getStartDateTime() != null) {
			Instant start = a.getStartDateTime().toInstant();
			sb.append(" on ").append(DATE_FORMAT.format(start));
			sb.append(" at ").append(TIME_FORMAT.format(start));
			if (a.getEndDateTime() != null) {
				sb.append('-').append(TIME_FORMAT.format(a.getEndDateTime().toInstant()));
			}
		}
		List<Provider> providers = resolveProviders(a);
		if (!providers.isEmpty() && providers.get(0).getName() != null) {
			sb.append(" with ").append(providers.get(0).getName());
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
	 * Returns every non-null {@link Provider} attached to the appointment, in Hibernate iteration
	 * order. The first element is treated as the primary provider for the legacy
	 * {@code provider_uuid} / {@code provider_name} cross-cutting fields; the full list is what
	 * the multi-valued {@code provider_uuids} / {@code provider_names} metadata fields surface for
	 * search.
	 *
	 * <p>Note: {@code Appointment} also has a singular {@code provider} field on the Java class,
	 * but it is intentionally not Hibernate-mapped (see {@code Appointment.hbm.xml}) — persisted
	 * appointments never populate it, so it is not consulted here.
	 */
	private List<Provider> resolveProviders(Appointment a) {
		Set<AppointmentProvider> providers = a.getProviders();
		if (providers == null) {
			return new ArrayList<>(0);
		}
		List<Provider> resolved = new ArrayList<>(providers.size());
		for (AppointmentProvider ap : providers) {
			if (ap != null && ap.getProvider() != null) {
				resolved.add(ap.getProvider());
			}
		}
		return resolved;
	}

	/**
	 * Collects each provider's response (ACCEPTED / REJECTED / AWAITING) keyed by the provider's
	 * UUID. Format: {@code "<provider-uuid>:<response>"} per entry so the metadata reads as a
	 * flat array of opaque strings rather than requiring nested-object indexing — the latter is
	 * not uniformly supported across querystore's three reference backends. A null response is
	 * skipped (some providers haven't been asked yet) rather than encoded as the string "null".
	 */
	private List<String> collectProviderResponses(Appointment a) {
		Set<AppointmentProvider> providers = a.getProviders();
		if (providers == null) {
			return new ArrayList<>(0);
		}
		List<String> responses = new ArrayList<>(providers.size());
		for (AppointmentProvider ap : providers) {
			if (ap == null || ap.getProvider() == null || ap.getResponse() == null) {
				continue;
			}
			responses.add(ap.getProvider().getUuid() + ":" + ap.getResponse().name());
		}
		return responses;
	}
}
