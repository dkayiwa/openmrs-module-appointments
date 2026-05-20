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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Serializes an {@link Appointment} into a {@link QueryDocument} per querystore ADR Decisions 6
 * and 13. The resource type is {@code appointments_appointment}; the corresponding querystore
 * index is {@code querystore_appointments_appointment}.
 *
 * <p><b>Cross-cutting fields</b> populated: {@code resource_type}, {@code resource_uuid},
 * {@code patient_uuid}, {@code last_modified}, {@code date}, {@code text}. Optional cross-cutting
 * fields populated when present: {@code provider_uuid}/{@code provider_name},
 * {@code location_uuid}/{@code location_name} (with {@code location_name} omitted — not
 * present-but-null — when the Location's name is null, matching {@code text}-chunk semantics). {@code encounter_uuid}, {@code visit_uuid},
 * {@code form_uuid}, and {@code encounter_type_*} are intentionally null — appointments are not
 * encounter-attached.
 *
 * <p><b>Appointment-specific metadata</b> emitted when the underlying field is non-null:
 * <ul>
 *   <li>{@code appointment_service_uuid} / {@code appointment_service_name} — the
 *       {@link AppointmentServiceDefinition} the appointment belongs to. The {@code _name} field
 *       is omitted (not present-but-null) when the entity's name is null.</li>
 *   <li>{@code appointment_service_type_uuid} / {@code appointment_service_type_name} — the
 *       optional {@link AppointmentServiceType} within the service. Same {@code _name}-omission
 *       convention as above.</li>
 *   <li>{@code appointment_number} — the human-readable identifier (e.g. {@code "APPT-001"}).</li>
 *   <li>{@code status} — passthrough of {@link AppointmentStatus#name()}; a rename of an enum
 *       constant in {@code AppointmentStatus} silently changes the wire format. Current values
 *       are {@code "Requested"}, {@code "Scheduled"}, {@code "CheckedIn"}, {@code "Completed"},
 *       {@code "Cancelled"}, {@code "Missed"} — consumers building filter whitelists must
 *       enumerate all six (and re-check the enum on each upstream upgrade), not hard-code a
 *       subset hedged by "etc.".</li>
 *   <li>{@code appointment_kind} — passthrough of
 *       {@link org.openmrs.module.appointments.model.AppointmentKind#name()}; same upstream-enum
 *       coupling as {@code status}.</li>
 *   <li>{@code start_date_time} / {@code end_date_time} — passthrough of
 *       {@code Date.toInstant().toString()}; the canonical form is
 *       {@code "2026-06-01T10:00:00Z"}, but a {@code Date} carrying non-zero milliseconds
 *       renders as {@code "2026-06-01T10:00:00.290Z"}. Consumers parsing these fields must
 *       accept both forms.</li>
 *   <li>{@code creator_uuid} / {@code changed_by_uuid} — UUIDs of the {@code User} who created
 *       or last modified the appointment. Names are not surfaced here; consumers needing the
 *       display name should resolve via querystore's User projection or core.</li>
 *   <li>{@code date_created} — passthrough of {@code Date.toInstant().toString()} (same
 *       optional-milliseconds shape as {@code start_date_time}). Parallel to {@code last_modified}
 *       but specifically the create-event timestamp.</li>
 *   <li>{@code related_appointment_uuid} — UUID of the predecessor appointment when the current
 *       appointment was produced by a single-occurrence edit of a recurring pattern (see
 *       {@code SingleAppointmentRecurringPatternUpdateService}: the prior occurrence is voided
 *       and the new occurrence carries this link back). <b>Not</b> populated by
 *       {@code AppointmentsService.reschedule()} — that path cancels the prior appointment and
 *       saves the new one without any back-link. Consumers querying for "all rescheduled
 *       appointments" via {@code related_appointment_uuid IS NOT NULL} will surface only the
 *       recurring-pattern single-edit case, not reschedule-flow records.</li>
 *   <li>{@code comments} — free-text appointment note.</li>
 *   <li>{@code teleconsultation_link} — meeting URL when set.</li>
 *   <li>{@code is_recurring} — emitted only when {@code true} (sparse; absence means "not recurring").</li>
 *   <li>{@code recurring_type} — passthrough of
 *       {@link org.openmrs.module.appointments.service.impl.RecurringAppointmentType#name()};
 *       currently {@code "DAY"} or {@code "WEEK"}. Same upstream-enum coupling as {@code status};
 *       present only when the pattern declares a type.</li>
 *   <li>{@code recurring_period} / {@code recurring_frequency} — Integer pattern bounds.</li>
 *   <li>{@code recurring_days_of_week} — passed through verbatim from
 *       {@link AppointmentRecurringPattern#getDaysOfWeek()}; absent for DAY-type patterns. The
 *       upstream omod mapper writes this as a comma-separated uppercase abbreviation list
 *       (e.g. {@code "MON,WED"}), but this serializer does not normalize — consumers parsing
 *       the field should match the entity's stored shape, not assume a canonical form.</li>
 *   <li>{@code recurring_end_date} — passthrough of {@code Date.toInstant().toString()} (same
 *       optional-milliseconds shape as {@code start_date_time}); absent for open-ended patterns.</li>
 * </ul>
 *
 * <p><b>Multi-valued provider fields</b> (always emitted in parallel index order when at least
 * one provider is present, so {@code provider_names[i]} corresponds to {@code provider_uuids[i]}):
 * <ul>
 *   <li>{@code provider_uuids} — array of every non-null provider's UUID.</li>
 *   <li>{@code provider_names} — parallel array of provider display names; a null
 *       {@code Provider.getName()} falls back to the provider's UUID so the two arrays stay
 *       index-parallel without null entries.</li>
 *   <li>{@code provider_responses} — array of {@code "<provider-uuid>:<response>"} strings; the
 *       {@code <response>} suffix is a passthrough of
 *       {@link org.openmrs.module.appointments.model.AppointmentProviderResponse#name()}, so a
 *       rename of an enum constant silently changes the wire format (same upstream-enum coupling
 *       as {@code status}). Current values are {@code AWAITING}, {@code ACCEPTED},
 *       {@code REJECTED}, {@code TENTATIVE}, {@code CANCELLED} — consumers building filter
 *       whitelists must enumerate all five (and re-check the enum on each upstream upgrade), not
 *       hard-code a subset. Providers with a {@code null} response are <b>omitted entirely</b>
 *       from this array — consumers derive "no response recorded yet" as the set difference
 *       between {@code provider_uuids} and the UUIDs in {@code provider_responses}; this is
 *       distinct from the explicit {@code AWAITING} enum value, which is recorded when the
 *       provider was asked but hasn't yet responded. The flat-string encoding (vs. nested
 *       objects) is intentional: not every querystore backend indexes nested objects uniformly.</li>
 * </ul>
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

		// Resolve once and pass into buildText so we don't walk the providers Set twice per call —
		// on the bootstrap backfill path this matters (100k appointments would otherwise mean
		// 200k Set walks and ArrayList allocations rather than 100k).
		List<Provider> resolvedProviders = resolveProviders(appointment);
		doc.setText(buildText(appointment, resolvedProviders));

		// Provider surface — single-valued "primary" fields keep backward-compatibility with the
		// querystore cross-cutting convention; the provider_uuids / provider_names arrays are the
		// search-correct surface for multi-provider appointments. Without the arrays, a query for
		// "appointments with Dr. C" silently misses every record where Dr. C isn't first in
		// HashSet iteration order.
		if (!resolvedProviders.isEmpty()) {
			Provider primaryProvider = resolvedProviders.get(0);
			doc.putMetadata("provider_uuid", primaryProvider.getUuid());
			doc.putMetadata("provider_name", nameOrUuidFallback(primaryProvider));

			List<String> providerUuids = new ArrayList<>(resolvedProviders.size());
			List<String> providerNames = new ArrayList<>(resolvedProviders.size());
			for (Provider provider : resolvedProviders) {
				providerUuids.add(provider.getUuid());
				providerNames.add(nameOrUuidFallback(provider));
			}
			doc.putMetadata("provider_uuids", providerUuids);
			doc.putMetadata("provider_names", providerNames);

			// Per-provider response (AWAITING / ACCEPTED / REJECTED / TENTATIVE / CANCELLED — see
			// the class Javadoc) is what the updateAppointmentProviderResponse trigger surface
			// mutates. Surfacing it as a UUID→response array lets consumers query "appointments
			// where Dr. X has declined."
			List<String> providerResponses = collectProviderResponses(appointment);
			if (!providerResponses.isEmpty()) {
				doc.putMetadata("provider_responses", providerResponses);
			}
		}
		// Location / service / service-type *_name fields are emitted only when the entity's name
		// is non-null — matching buildText's null-guarded behavior so a free-text search over the
		// `text` chunk and a structured search over `location_name` agree on which appointments
		// reference a named location. *_uuid is emitted whenever the entity exists; a present-UUID
		// with absent-name communicates "entity is set but its display label isn't populated yet."
		Location location = appointment.getLocation();
		if (location != null) {
			doc.putMetadata("location_uuid", location.getUuid());
			if (location.getName() != null) {
				doc.putMetadata("location_name", location.getName());
			}
		}

		AppointmentServiceDefinition service = appointment.getService();
		if (service != null) {
			doc.putMetadata("appointment_service_uuid", service.getUuid());
			if (service.getName() != null) {
				doc.putMetadata("appointment_service_name", service.getName());
			}
		}
		AppointmentServiceType serviceType = appointment.getServiceType();
		if (serviceType != null) {
			doc.putMetadata("appointment_service_type_uuid", serviceType.getUuid());
			if (serviceType.getName() != null) {
				doc.putMetadata("appointment_service_type_name", serviceType.getName());
			}
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
		// Audit surface — creator / changed_by / date_created enable "appointments created by
		// Dr. X" / "modified by Dr. X" / "created in date range" queries without round-tripping
		// to core. last_modified already carries the cross-cutting modification timestamp for
		// race-guard ordering (ADR Decision 3); date_created is a parallel surface for the
		// "create" event specifically. Each emitted only when the upstream value is non-null.
		if (appointment.getCreator() != null) {
			doc.putMetadata("creator_uuid", appointment.getCreator().getUuid());
		}
		if (appointment.getChangedBy() != null) {
			doc.putMetadata("changed_by_uuid", appointment.getChangedBy().getUuid());
		}
		if (appointment.getDateCreated() != null) {
			doc.putMetadata("date_created", appointment.getDateCreated().toInstant().toString());
		}
		// Recurring single-edit predecessor — populated when SingleAppointmentRecurringPatternUpdateService
		// produces a new occurrence by voiding the prior one and linking the new one back via
		// setRelatedAppointment. NOT populated by AppointmentsService.reschedule(); see the class
		// Javadoc for the full setter-path map and the consumer-query implications.
		if (appointment.getRelatedAppointment() != null) {
			doc.putMetadata("related_appointment_uuid", appointment.getRelatedAppointment().getUuid());
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

	private String buildText(Appointment appointment, List<Provider> resolvedProviders) {
		// Typical rendered text runs ~80-160 chars (service + type + date/time window + provider +
		// location + status + kind). Pre-sizing avoids the 2-3 grow/copy cycles the default capacity
		// of 16 would incur on every serialize() call on the bootstrap backfill path.
		StringBuilder sb = new StringBuilder(192).append("Appointment");
		if (appointment.getService() != null && appointment.getService().getName() != null) {
			sb.append(" for ").append(appointment.getService().getName());
		}
		if (appointment.getServiceType() != null && appointment.getServiceType().getName() != null) {
			sb.append(" (").append(appointment.getServiceType().getName()).append(')');
		}
		if (appointment.getStartDateTime() != null) {
			Instant start = appointment.getStartDateTime().toInstant();
			sb.append(" on ").append(DATE_FORMAT.format(start));
			sb.append(" at ").append(TIME_FORMAT.format(start));
			if (appointment.getEndDateTime() != null) {
				sb.append('-').append(TIME_FORMAT.format(appointment.getEndDateTime().toInstant()));
			}
		}
		if (!resolvedProviders.isEmpty()) {
			// Use the same UUID-fallback as the metadata fields so a free-text search over the
			// `text` chunk and a structured search over `provider_names` agree on which
			// appointments mention a given provider — even one whose Provider.getName() is null.
			sb.append(" with ").append(nameOrUuidFallback(resolvedProviders.get(0)));
		}
		if (appointment.getLocation() != null && appointment.getLocation().getName() != null) {
			sb.append(" at ").append(appointment.getLocation().getName());
		}
		if (appointment.getStatus() != null) {
			sb.append(". Status: ").append(appointment.getStatus().name());
		}
		if (appointment.getAppointmentKind() != null) {
			sb.append(". Kind: ").append(appointment.getAppointmentKind().name());
		}
		if (appointment.getTeleHealthVideoLink() != null && !appointment.getTeleHealthVideoLink().isEmpty()) {
			sb.append(". Teleconsultation.");
		}
		if (appointment.getAppointmentRecurringPattern() != null) {
			sb.append(" Recurring.");
		}
		if (appointment.getComments() != null && !appointment.getComments().isEmpty()) {
			sb.append(' ').append(appointment.getComments());
		}
		return sb.toString();
	}

	/**
	 * Returns {@link Provider#getName()} when set, falling back to the provider's UUID when the
	 * name is null (the case for providers with no linked {@code Person}). The fallback keeps
	 * {@code provider_name} non-null and the {@code provider_uuids}/{@code provider_names} arrays
	 * index-parallel without introducing literal-null entries that downstream JSON consumers can't
	 * reconcile.
	 */
	private String nameOrUuidFallback(Provider provider) {
		String name = provider.getName();
		return name != null ? name : provider.getUuid();
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
	private List<Provider> resolveProviders(Appointment appointment) {
		Set<AppointmentProvider> providers = appointment.getProviders();
		if (providers == null || providers.isEmpty()) {
			return Collections.emptyList();
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
	 * Collects each provider's response (any value of
	 * {@link org.openmrs.module.appointments.model.AppointmentProviderResponse}) keyed by the
	 * provider's UUID. Format: {@code "<provider-uuid>:<response>"} per entry so the metadata
	 * reads as a flat array of opaque strings rather than requiring nested-object indexing — the
	 * latter is not uniformly supported across querystore's three reference backends. A null
	 * response is skipped (some providers haven't been asked yet) rather than encoded as the
	 * string "null"; this absence is distinct from the explicit {@code AWAITING} enum value.
	 */
	private List<String> collectProviderResponses(Appointment appointment) {
		Set<AppointmentProvider> providers = appointment.getProviders();
		if (providers == null || providers.isEmpty()) {
			return Collections.emptyList();
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
