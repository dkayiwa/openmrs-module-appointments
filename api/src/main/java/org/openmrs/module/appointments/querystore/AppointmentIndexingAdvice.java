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
 * various {@code search}/{@code get} methods are read-only and so are not triggers.
 *
 * <p>{@code updateAppointmentProviderResponse} is covered by a separate advice
 * ({@link AppointmentProviderResponseIndexingAdvice}) because it carries the affected
 * {@link Appointment} indirectly through an {@code AppointmentProvider} wrapper.
 *
 * <p><b>Reschedule prev-appointment gap.</b> {@code AppointmentsServiceImpl.reschedule} internally
 * self-invokes {@code changeStatus(prevAppointment, "Cancelled", ...)} on the prior appointment
 * before saving the new one. The self-invocation bypasses the Spring AOP proxy, so this advice
 * does not fire for the prev appointment's cancellation — only for the new appointment via the
 * outer {@code reschedule} return value. <b>Concrete failure mode:</b> after a reschedule, the
 * cancelled prior appointment remains indexed as its prior status (typically {@code Scheduled})
 * in querystore until the next module restart's bootstrap pass picks up the {@code dateChanged}
 * cursor — searches for "upcoming appointments" then surface a phantom still-scheduled entry that
 * has actually been cancelled. The structural fix is in the service impl (route the inner call
 * through {@code Context.getService(AppointmentsService.class).changeStatus(...)} so AOP fires);
 * deferred from this slice because it modifies non-querystore production logic. Same property
 * applies to the Atomfeed advice on this service, which has the identical gap.
 *
 * <p><b>Recurring-pattern gap.</b> Saves routed through {@code AppointmentRecurringPatternService}
 * are covered by {@link RecurringAppointmentIndexingAdvice}, a sibling advice wired on that
 * service.
 *
 * <p><b>LinkageError asymmetry vs. sibling advices.</b> Querystore's
 * {@link org.openmrs.module.querystore.bridge.AbstractIndexingAdvice}'s {@code afterReturning} is
 * {@code final} and catches only {@link RuntimeException}; {@link LinkageError} thrown from
 * {@code serializer.serialize(...)} or any other static class lookup inside its {@code dispatch()}
 * escapes the swallow. The sibling advices {@link RecurringAppointmentIndexingAdvice} and
 * {@link AppointmentProviderResponseIndexingAdvice} were widened to {@code RuntimeException | LinkageError}
 * to absorb version-skew failures (renamed or removed querystore SPI symbols → NoClassDefFoundError /
 * NoSuchMethodError); this advice cannot mirror that because the catch site lives in the base
 * class. <b>Concrete failure mode:</b> a deployment landing a querystore version that drops or
 * renames {@code ClinicalRecordSerializer.serialize} would unwind a {@code NoSuchMethodError}
 * through the AOP proxy to the clinical-thread save on {@code validateAndSave}/{@code changeStatus}/
 * {@code undoStatusChange}/{@code reschedule}, surfacing a phantom save failure to clinicians even
 * though the originating transaction has already committed. The structural fix is one of: (a) pin
 * {@code <require_module version="…">} on querystore once it ships 1.0 so the module loader
 * rejects the skew at install time, or (b) submit an upstream patch widening
 * {@code AbstractIndexingAdvice}'s catch to {@code RuntimeException | LinkageError}. Both are
 * deferred from this slice.
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
