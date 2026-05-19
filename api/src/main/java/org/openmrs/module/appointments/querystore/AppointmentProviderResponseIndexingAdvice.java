package org.openmrs.module.appointments.querystore;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentProvider;
import org.openmrs.module.querystore.bridge.AfterCommitDispatcher;
import org.openmrs.module.querystore.bridge.BridgeIndexer;
import org.openmrs.module.querystore.model.QueryDocument;
import org.springframework.aop.AfterReturningAdvice;

import java.lang.reflect.Method;

/**
 * Catches {@code AppointmentsService.updateAppointmentProviderResponse}, which mutates an
 * {@link Appointment}'s state (provider response, and on first-accept-of-requested also the
 * status flip Requested → Scheduled) but does not pass through the standard
 * {@code validateAndSave} / {@code changeStatus} trigger surface that
 * {@link AppointmentIndexingAdvice} covers.
 *
 * <p><b>Trigger surface.</b> {@code updateAppointmentProviderResponse} only.
 *
 * <p><b>Why a separate advice.</b> The method's signature is
 * {@code updateAppointmentProviderResponse(AppointmentProvider)} — the argument is the provider
 * record, and the affected {@link Appointment} is reached via
 * {@link AppointmentProvider#getAppointment()}. {@link org.openmrs.module.querystore.bridge.AbstractIndexingAdvice}'s
 * {@code entityFrom} hook can only read an {@code Appointment} from {@code returnValue} or
 * {@code args[0]} directly; it cannot navigate through a wrapper. Rather than extend that base
 * class with non-standard entity extraction, this advice mirrors
 * {@link RecurringAppointmentIndexingAdvice}'s direct {@link AfterReturningAdvice} pattern.
 *
 * <p><b>Self-invocation note.</b> The service impl's {@code updateAppointmentProviderResponse}
 * internally calls {@code changeStatus(...)} on first-accept; that inner call bypasses the AOP
 * proxy and so does not fire {@link AppointmentIndexingAdvice}. This advice firing on the OUTER
 * method is what guarantees querystore sees both shapes of mutation (status flip + direct
 * provider-response save). Without it, provider acceptances would silently stay stale in
 * querystore until the next module-restart bootstrap pass picks them up via the dateChanged
 * cursor.
 */
public class AppointmentProviderResponseIndexingAdvice implements AfterReturningAdvice {

	private static final Log log = LogFactory.getLog(AppointmentProviderResponseIndexingAdvice.class);

	private static final String TRIGGER_METHOD = "updateAppointmentProviderResponse";

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		if (!TRIGGER_METHOD.equals(method.getName())) {
			return;
		}
		if (args == null || args.length == 0 || !(args[0] instanceof AppointmentProvider)) {
			return;
		}
		Appointment appointment = ((AppointmentProvider) args[0]).getAppointment();
		if (appointment == null || appointment.getUuid() == null) {
			return;
		}

		// Swallow RuntimeException AND LinkageError so a version-skewed querystore (renamed or
		// removed SPI symbols → NoClassDefFoundError / NoSuchMethodError) does not unwind back
		// through the AOP proxy to a clinical-thread caller whose save has already committed.
		try {
			dispatch(appointment);
		}
		catch (RuntimeException | LinkageError e) {
			log.warn(getClass().getSimpleName() + " failed for " + method.getName()
					+ "; swallowing per querystore ADR Decision 12", e);
		}
	}

	private void dispatch(Appointment appointment) {
		AppointmentSerializer serializer = Context.getRegisteredComponent(
				AppointmentIndexingAdvice.SERIALIZER_BEAN_ID, AppointmentSerializer.class);
		BridgeIndexer indexer = Context.getRegisteredComponent(
				RecurringAppointmentIndexingAdvice.BRIDGE_INDEXER_BEAN_ID, BridgeIndexer.class);
		AfterCommitDispatcher dispatcher = Context.getRegisteredComponent(
				RecurringAppointmentIndexingAdvice.BRIDGE_DISPATCHER_BEAN_ID, AfterCommitDispatcher.class);

		boolean voided = Boolean.TRUE.equals(appointment.getVoided());
		String resourceType = serializer.getResourceType();
		String uuid = appointment.getUuid();
		QueryDocument doc = voided ? null : serializer.serialize(appointment);

		dispatcher.dispatch(() -> {
			try {
				if (voided) {
					indexer.delete(resourceType, uuid);
				}
				else if (doc != null) {
					indexer.index(doc);
				}
			}
			catch (RuntimeException e) {
				log.warn("Bridge skipping " + (voided ? "delete" : "index") + " for " + resourceType
						+ "/" + uuid + " due to failure", e);
			}
		});
	}
}
