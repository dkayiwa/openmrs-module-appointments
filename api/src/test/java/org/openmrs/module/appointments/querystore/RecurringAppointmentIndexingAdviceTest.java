package org.openmrs.module.appointments.querystore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentRecurringPattern;
import org.openmrs.module.querystore.bridge.AfterCommitDispatcher;
import org.openmrs.module.querystore.bridge.BridgeIndexer;
import org.openmrs.module.querystore.model.QueryDocument;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link RecurringAppointmentIndexingAdvice}'s steady-state contract on
 * {@code AppointmentRecurringPatternService}: UUID-keyed dedup so a save that surfaces the same
 * Appointment via both {@code returnValue} and {@code args[0]} doesn't double-index, voided →
 * delete routing, non-trigger-method ignore, and exception swallow on both the pre-dispatch
 * (serializer) and dispatch-time (indexer) paths per querystore ADR Decision 12.
 */
public class RecurringAppointmentIndexingAdviceTest {

	private RecurringAppointmentIndexingAdvice advice;

	private MockedStatic<Context> contextMockedStatic;

	private AppointmentSerializer serializer;

	private BridgeIndexer indexer;

	private AfterCommitDispatcher dispatcher;

	@Before
	public void setUp() {
		advice = new RecurringAppointmentIndexingAdvice();

		serializer = mock(AppointmentSerializer.class);
		when(serializer.getResourceType()).thenReturn("appointments_appointment");
		when(serializer.serialize(any(Appointment.class))).thenAnswer(inv -> {
			Appointment a = inv.getArgument(0);
			QueryDocument doc = new QueryDocument();
			doc.setResourceType("appointments_appointment");
			doc.setResourceUuid(a.getUuid());
			return doc;
		});

		indexer = mock(BridgeIndexer.class);
		dispatcher = mock(AfterCommitDispatcher.class);
		// Run dispatched tasks inline so the test can observe indexer calls directly.
		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return null;
		}).when(dispatcher).dispatch(any(Runnable.class));

		contextMockedStatic = mockStatic(Context.class);
		contextMockedStatic.when(() -> Context.getRegisteredComponent(
				eq(AppointmentIndexingAdvice.SERIALIZER_BEAN_ID), eq(AppointmentSerializer.class)))
				.thenReturn(serializer);
		contextMockedStatic.when(() -> Context.getRegisteredComponent(
				eq(RecurringAppointmentIndexingAdvice.BRIDGE_INDEXER_BEAN_ID), eq(BridgeIndexer.class)))
				.thenReturn(indexer);
		contextMockedStatic.when(() -> Context.getRegisteredComponent(
				eq(RecurringAppointmentIndexingAdvice.BRIDGE_DISPATCHER_BEAN_ID), eq(AfterCommitDispatcher.class)))
				.thenReturn(dispatcher);
	}

	@After
	public void tearDown() {
		if (contextMockedStatic != null) {
			contextMockedStatic.close();
		}
	}

	@Test
	public void validateAndSavePatternIndexesEachAppointmentOnce() throws Exception {
		// returnValue and args[0] are the same pattern instance — naive double-scan would
		// index every appointment twice (the bug pass-2 review flagged).
		Appointment one = appointment("uuid-1");
		Appointment two = appointment("uuid-2");
		Appointment three = appointment("uuid-3");
		AppointmentRecurringPattern pattern = patternOf(one, two, three);

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		ArgumentCaptor<QueryDocument> captor = ArgumentCaptor.forClass(QueryDocument.class);
		verify(indexer, times(3)).index(captor.capture());
		Set<String> indexed = new HashSet<>();
		for (QueryDocument doc : captor.getAllValues()) {
			indexed.add(doc.getResourceUuid());
		}
		assertEquals(new HashSet<>(Arrays.asList("uuid-1", "uuid-2", "uuid-3")), indexed);
		verify(indexer, never()).delete(any(), any());
	}

	@Test
	public void changeStatusDeduplicatesSeedAndReturnValueList() throws Exception {
		Appointment seed = appointment("seed-uuid");
		Appointment cousin = appointment("cousin-uuid");
		// changeStatus returns a list of affected appointments; the seed is itself in that list.
		List<Appointment> affected = Arrays.asList(seed, cousin);

		advice.afterReturning(affected, methodNamed("changeStatus"),
				new Object[] { seed, "Cancelled", "Asia/Kolkata" }, null);

		ArgumentCaptor<QueryDocument> captor = ArgumentCaptor.forClass(QueryDocument.class);
		verify(indexer, times(2)).index(captor.capture());
		Set<String> indexed = new HashSet<>();
		for (QueryDocument doc : captor.getAllValues()) {
			indexed.add(doc.getResourceUuid());
		}
		assertEquals(new HashSet<>(Arrays.asList("seed-uuid", "cousin-uuid")), indexed);
	}

	@Test
	public void voidedAppointmentRoutesToDeleteNotIndex() throws Exception {
		Appointment alive = appointment("alive-uuid");
		Appointment voided = appointment("voided-uuid");
		voided.setVoided(true);
		AppointmentRecurringPattern pattern = patternOf(alive, voided);

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		verify(indexer, times(1)).index(any(QueryDocument.class));
		verify(indexer, times(1)).delete(eq("appointments_appointment"), eq("voided-uuid"));
	}

	@Test
	public void nonTriggerMethodIsIgnored() throws Exception {
		Appointment one = appointment("uuid-1");
		AppointmentRecurringPattern pattern = patternOf(one);

		advice.afterReturning(pattern, methodNamed("getRecurringAppointmentByUuid"),
				new Object[] { "some-uuid" }, null);

		verify(indexer, never()).index(any());
		verify(indexer, never()).delete(any(), any());
	}

	@Test
	public void indexerRuntimeExceptionOnFirstAppointmentDoesNotStarveSiblings() throws Exception {
		// Two-appointment fixture: the indexer throws on the first, the second must still be
		// indexed. Without this sibling-isolation assertion, a "simplify" refactor that hoists
		// the per-document try/catch in dispatch() into a single outer try-around-the-loop would
		// regress to "first poison row aborts every subsequent appointment in the same save," and
		// a single-appointment test would stay green while real recurring saves silently lost
		// every appointment after the first failure.
		Appointment poison = appointment("poison-uuid");
		Appointment survivor = appointment("survivor-uuid");
		AppointmentRecurringPattern pattern = patternOf(poison, survivor);

		// Throw only on the first index call; subsequent calls succeed. doThrow().doNothing()
		// chains the per-invocation outcomes — Mockito applies them in order.
		org.mockito.Mockito.doThrow(new RuntimeException("indexer down"))
				.doNothing()
				.when(indexer).index(any(QueryDocument.class));

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		// Both appointments reached the indexer — sibling isolation holds.
		ArgumentCaptor<QueryDocument> captor = ArgumentCaptor.forClass(QueryDocument.class);
		verify(indexer, times(2)).index(captor.capture());
		java.util.Set<String> indexed = new HashSet<>();
		for (QueryDocument doc : captor.getAllValues()) {
			indexed.add(doc.getResourceUuid());
		}
		assertEquals(new HashSet<>(java.util.Arrays.asList("poison-uuid", "survivor-uuid")), indexed);
	}

	@Test
	public void indexerLinkageErrorOnFirstAppointmentDoesNotStarveSiblings() throws Exception {
		// Same sibling-isolation contract as the RuntimeException variant, but with the version-
		// skew failure mode the outer catch was widened for. Without inner-loop LinkageError
		// handling, a NoSuchMethodError from indexer.index(...) on the first appointment unwinds
		// the dispatched lambda and skips every subsequent sibling — defeating the inner
		// per-document try/catch's promise that one poison row can't starve the rest.
		Appointment poison = appointment("poison-uuid");
		Appointment survivor = appointment("survivor-uuid");
		AppointmentRecurringPattern pattern = patternOf(poison, survivor);
		org.mockito.Mockito.doThrow(new NoSuchMethodError("BridgeIndexer.index"))
				.doNothing()
				.when(indexer).index(any(QueryDocument.class));

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		verify(indexer, times(2)).index(any(QueryDocument.class));
	}

	@Test
	public void indexerDeleteLinkageErrorOnFirstVoidedDoesNotStarveSiblings() throws Exception {
		// Parallel LinkageError variant for the delete branch. Without inner-loop LinkageError
		// handling, a NoSuchMethodError from indexer.delete(...) on the first voided appointment
		// leaves clinically-cancelled subsequent siblings stranded in querystore.
		Appointment poison = appointment("poison-voided");
		poison.setVoided(true);
		Appointment survivor = appointment("survivor-voided");
		survivor.setVoided(true);
		AppointmentRecurringPattern pattern = patternOf(poison, survivor);
		org.mockito.Mockito.doThrow(new NoSuchMethodError("BridgeIndexer.delete"))
				.doNothing()
				.when(indexer).delete(eq("appointments_appointment"), any(String.class));

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		verify(indexer, times(2)).delete(eq("appointments_appointment"), any(String.class));
	}

	@Test
	public void indexerDeleteRuntimeExceptionOnFirstVoidedDoesNotStarveSiblings() throws Exception {
		// Parallel sibling-isolation contract for the delete branch of dispatch(): two voided
		// appointments, the first throws on delete, the second must still reach indexer.delete().
		// Without this, a refactor that hoists the per-delete try/catch into a single outer try
		// would silently regress to "first poison delete aborts every subsequent voided sibling,"
		// leaving stale documents in querystore for clinically-cancelled appointments — the
		// existing single-voided test (voidedAppointmentRoutesToDeleteNotIndex) would stay green
		// while real multi-occurrence cancellations leak.
		Appointment poison = appointment("poison-voided");
		poison.setVoided(true);
		Appointment survivor = appointment("survivor-voided");
		survivor.setVoided(true);
		AppointmentRecurringPattern pattern = patternOf(poison, survivor);

		org.mockito.Mockito.doThrow(new RuntimeException("delete down"))
				.doNothing()
				.when(indexer).delete(eq("appointments_appointment"), any(String.class));

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		// Both voided appointments reached the indexer.delete path — sibling isolation holds.
		verify(indexer, times(2)).delete(eq("appointments_appointment"), any(String.class));
		verify(indexer, never()).index(any(QueryDocument.class));
	}

	@Test
	public void serializerRuntimeExceptionDoesNotPropagateToCaller() throws Exception {
		Appointment one = appointment("uuid-1");
		AppointmentRecurringPattern pattern = patternOf(one);
		// Routes through the OUTER catch in afterReturning() — serialization happens before the
		// dispatcher.dispatch() lambda, so an exception here would otherwise unwind to the AOP
		// proxy and the clinical-thread caller. The inner per-document catch covers the dispatch
		// path; this case covers the pre-dispatch path.
		doThrow(new RuntimeException("serializer down")).when(serializer).serialize(any(Appointment.class));

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		verify(indexer, never()).index(any(QueryDocument.class));
	}

	@Test
	public void serializerLinkageErrorDoesNotPropagateToCaller() throws Exception {
		Appointment one = appointment("uuid-1");
		AppointmentRecurringPattern pattern = patternOf(one);
		// LinkageError covers NoClassDefFoundError / NoSuchMethodError that surface when a
		// deployment lands a querystore version with renamed or removed SPI symbols. Without the
		// catch widening in afterReturning, the Error would escape past the standard
		// RuntimeException catch and unwind through the AOP proxy to the clinical-thread save.
		doThrow(new NoSuchMethodError("ClinicalRecordSerializer.serialize"))
				.when(serializer).serialize(any(Appointment.class));

		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		verify(indexer, never()).index(any(QueryDocument.class));
	}

	private Appointment appointment(String uuid) {
		Appointment appointment = new Appointment();
		appointment.setUuid(uuid);
		Patient patient = new Patient();
		patient.setUuid("patient-uuid");
		appointment.setPatient(patient);
		return appointment;
	}

	private AppointmentRecurringPattern patternOf(Appointment... appointments) {
		AppointmentRecurringPattern pattern = new AppointmentRecurringPattern();
		Set<Appointment> set = new LinkedHashSet<>();
		Collections.addAll(set, appointments);
		pattern.setAppointments(set);
		return pattern;
	}

	private Method methodNamed(String name) throws NoSuchMethodException {
		return DummyService.class.getMethod(name);
	}

	@SuppressWarnings("unused")
	private interface DummyService {
		void validateAndSave();

		void update();

		void changeStatus();

		void getRecurringAppointmentByUuid();
	}
}
