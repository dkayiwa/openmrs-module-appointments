package org.openmrs.module.appointments.querystore;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentRecurringPattern;
import org.openmrs.module.querystore.bridge.AfterCommitDispatcher;
import org.openmrs.module.querystore.bridge.BridgeIndexer;
import org.openmrs.module.querystore.model.QueryDocument;
import org.springframework.aop.AfterReturningAdvice;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catches steady-state {@link Appointment} writes that flow through
 * {@code AppointmentRecurringPatternService} instead of {@code AppointmentsService}. Without this
 * advice, a save on a recurring pattern would not be reflected in querystore until the next
 * module restart's bootstrap pass picked it up via the {@code dateChanged} cursor — leaving
 * patient-scoped retrieval stale in the meantime.
 *
 * <p>This advice cannot extend {@link org.openmrs.module.querystore.bridge.AbstractIndexingAdvice}
 * because that base's {@code collectTree} is typed {@code List<T>} on a single root type, and
 * recurring writes need to fan one {@link AppointmentRecurringPattern} (or one {@code List<Appointment>})
 * out into many indexable {@link Appointment} documents. Instead it implements
 * {@link AfterReturningAdvice} directly and reuses the {@code querystore.bridge.indexer} and
 * {@code querystore.bridge.dispatcher} beans through {@link Context#getRegisteredComponent} — the
 * same beans {@code AbstractIndexingAdvice} uses internally.
 *
 * <p><b>Trigger surface.</b> All four mutating methods on
 * {@code AppointmentRecurringPatternService}: the two {@code validateAndSave}/{@code update}
 * overloads that return an {@code AppointmentRecurringPattern}, the {@code update} overload that
 * returns a single {@code Appointment}, and {@code changeStatus} which returns
 * {@code List<Appointment>}.
 *
 * <p><b>Per-appointment voided routing</b> mirrors {@code AbstractIndexingAdvice}: a voided node
 * is dispatched as a delete, a non-voided node is serialized and upserted. The recurring service
 * does not expose a purge path, so unconditional-delete routing is not needed.
 *
 * <p><b>Failure isolation</b> mirrors the base advice — serialization or dispatch failure is
 * caught and logged per-node so a single poison record cannot starve its siblings, and never
 * propagates back to the clinical-thread caller.
 */
public class RecurringAppointmentIndexingAdvice implements AfterReturningAdvice {

	private static final Log log = LogFactory.getLog(RecurringAppointmentIndexingAdvice.class);

	private static final Set<String> TRIGGER_METHODS = Collections.unmodifiableSet(new HashSet<>(
			Arrays.asList("validateAndSave", "update", "changeStatus")));

	static final String BRIDGE_INDEXER_BEAN_ID = "querystore.bridge.indexer";

	static final String BRIDGE_DISPATCHER_BEAN_ID = "querystore.bridge.dispatcher";

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		if (!TRIGGER_METHODS.contains(method.getName())) {
			return;
		}

		Collection<Appointment> appointments = extractAppointments(returnValue, args);
		if (appointments.isEmpty()) {
			return;
		}

		// Swallow RuntimeException AND LinkageError so a version-skewed querystore (renamed or
		// removed SPI symbols → NoClassDefFoundError / NoSuchMethodError) does not unwind back
		// through the AOP proxy to a clinical-thread caller whose save has already committed.
		try {
			dispatch(appointments);
		}
		catch (RuntimeException | LinkageError e) {
			log.warn(getClass().getSimpleName() + " failed for " + method.getName()
					+ "; swallowing per querystore ADR Decision 12", e);
		}
	}

	/**
	 * Pulls the affected {@link Appointment} set from the advised method's return value AND its
	 * first argument, deduplicating by UUID. The four signatures we care about (see
	 * {@link #TRIGGER_METHODS}) collectively expose appointments through three shapes — a recurring
	 * pattern (whose {@code appointments} set we fan out), a single appointment, or a list of
	 * appointments — and we must scan both {@code returnValue} and {@code args[0]} because the
	 * shapes are different per overload. The dedup is load-bearing: in
	 * {@code validateAndSave(pattern)} the pattern is both arg and return value (same instance), so
	 * a naive scan would index every appointment twice; in {@code changeStatus(seed, ...)} the seed
	 * appointment also appears in the returned {@code pendingAppointments} list.
	 *
	 * <p>Reads {@link AppointmentRecurringPattern#getAppointments()} which is mapped lazy. The
	 * advice fires before transaction commit, with the originating session still open, so lazy
	 * initialisation resolves; this is the same boundary {@code AbstractIndexingAdvice} relies on
	 * for core types.
	 */
	private Collection<Appointment> extractAppointments(Object returnValue, Object[] args) {
		Map<String, Appointment> collected = new LinkedHashMap<>();
		collectFrom(returnValue, collected);
		if (args != null && args.length > 0) {
			collectFrom(args[0], collected);
		}
		return collected.values();
	}

	private void collectFrom(Object value, Map<String, Appointment> sink) {
		if (value instanceof AppointmentRecurringPattern) {
			Set<Appointment> patternAppointments = ((AppointmentRecurringPattern) value).getAppointments();
			if (patternAppointments != null) {
				for (Appointment appointment : patternAppointments) {
					addIfNew(appointment, sink);
				}
			}
		}
		else if (value instanceof Appointment) {
			addIfNew((Appointment) value, sink);
		}
		else if (value instanceof Collection) {
			for (Object item : (Collection<?>) value) {
				if (item instanceof Appointment) {
					addIfNew((Appointment) item, sink);
				}
			}
		}
	}

	/** UUID-keyed dedup insert; null appointments and null UUIDs are silently dropped. */
	private void addIfNew(Appointment appointment, Map<String, Appointment> sink) {
		if (appointment == null || appointment.getUuid() == null) {
			return;
		}
		sink.putIfAbsent(appointment.getUuid(), appointment);
	}

	private void dispatch(Collection<Appointment> appointments) {
		AppointmentSerializer serializer = Context.getRegisteredComponent(
				AppointmentIndexingAdvice.SERIALIZER_BEAN_ID, AppointmentSerializer.class);
		BridgeIndexer indexer = Context.getRegisteredComponent(
				BRIDGE_INDEXER_BEAN_ID, BridgeIndexer.class);
		AfterCommitDispatcher dispatcher = Context.getRegisteredComponent(
				BRIDGE_DISPATCHER_BEAN_ID, AfterCommitDispatcher.class);

		List<QueryDocument> toIndex = new ArrayList<>(appointments.size());
		List<String> toDelete = new ArrayList<>();
		for (Appointment appointment : appointments) {
			if (Boolean.TRUE.equals(appointment.getVoided())) {
				toDelete.add(appointment.getUuid());
			}
			else {
				QueryDocument doc = serializer.serialize(appointment);
				if (doc != null) {
					toIndex.add(doc);
				}
			}
		}

		if (toIndex.isEmpty() && toDelete.isEmpty()) {
			return;
		}
		String resourceType = serializer.getResourceType();
		dispatcher.dispatch(() -> {
			for (QueryDocument doc : toIndex) {
				try {
					indexer.index(doc);
				}
				catch (RuntimeException | LinkageError e) {
					log.warn("Bridge skipping index for " + resourceType + "/" + doc.getResourceUuid()
							+ " due to failure", e);
				}
			}
			for (String uuid : toDelete) {
				try {
					indexer.delete(resourceType, uuid);
				}
				catch (RuntimeException | LinkageError e) {
					log.warn("Bridge skipping delete for " + resourceType + "/" + uuid
							+ " due to failure", e);
				}
			}
		});
	}
}
