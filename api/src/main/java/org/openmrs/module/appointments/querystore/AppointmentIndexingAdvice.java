package org.openmrs.module.appointments.querystore;

import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.querystore.bridge.AbstractIndexingAdvice;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Catches steady-state {@link Appointment} writes through the {@code AppointmentsService} surface
 * and dispatches them to querystore via the {@code querystore.bridge.indexer} pipeline. Wired as
 * Spring AOP advice on the service interface in {@code config.xml}, mirroring the existing
 * Atomfeed advice on the same interface.
 *
 * <p><b>Trigger surface.</b> {@code validateAndSave}, {@code changeStatus}, {@code undoStatusChange},
 * and {@code reschedule}. The base class resolves the {@link Appointment} from the return value
 * (for the save/reschedule paths) or from {@code args[0]} (for the status-change paths). The
 * remaining service methods — {@code updateAppointmentProviderResponse} (operates on
 * {@code AppointmentProvider}, not {@code Appointment}) and the various {@code search}/{@code get}
 * methods (read-only) — are not triggers.
 *
 * <p><b>Recurring-pattern gap.</b> Saves routed through {@code AppointmentRecurringPatternService}
 * do not pass through this advice; the bootstrap pass on next run catches them via the
 * effective-date cursor. This is the same migration-bridge property the querystore ADR Decision 12
 * documents and accepts for AOP gap-fillers.
 *
 * <p><b>Voiding.</b> The appointments module does not expose a {@code voidAppointment} or
 * {@code purgeAppointment} method on its service interface — soft-cancellation is modelled through
 * {@code AppointmentStatus.Cancelled} on the entity, captured by the status-change triggers above.
 * {@code purgeMethods()} is empty: the entity has no public purge path, so no trigger needs the
 * unconditional-delete routing.
 */
public class AppointmentIndexingAdvice extends AbstractIndexingAdvice<Appointment> {

	static final String SERIALIZER_BEAN_ID = "appointments.serializer.appointment";

	private static final Set<String> TRIGGER_METHODS = Collections.unmodifiableSet(new HashSet<>(
			Arrays.asList("validateAndSave", "changeStatus", "undoStatusChange", "reschedule")));

	private static final Set<String> PURGE_METHODS = Collections.emptySet();

	@Override
	protected Class<Appointment> getSupportedType() {
		return Appointment.class;
	}

	@Override
	protected AppointmentSerializer serializer() {
		return Context.getRegisteredComponent(SERIALIZER_BEAN_ID, AppointmentSerializer.class);
	}

	@Override
	protected Set<String> triggerMethods() {
		return TRIGGER_METHODS;
	}

	@Override
	protected Set<String> purgeMethods() {
		return PURGE_METHODS;
	}
}
