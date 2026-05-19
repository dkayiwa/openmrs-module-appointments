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
	public void indexerRuntimeExceptionDoesNotPropagateToCaller() throws Exception {
		Appointment one = appointment("uuid-1");
		AppointmentRecurringPattern pattern = patternOf(one);
		doThrow(new RuntimeException("indexer down")).when(indexer).index(any(QueryDocument.class));

		// Verifies the inner per-document catch in dispatch(): a single poison row must not
		// propagate back to the clinical-thread caller, whose transaction has already committed.
		advice.afterReturning(pattern, methodNamed("validateAndSave"), new Object[] { pattern }, null);

		// Anchor that the indexer was actually reached; without this, a future refactor that
		// short-circuits dispatch before the per-document loop would silently keep this test green.
		verify(indexer, times(1)).index(any(QueryDocument.class));
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
