package org.openmrs.module.appointments.querystore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentProvider;
import org.openmrs.module.querystore.bridge.AfterCommitDispatcher;
import org.openmrs.module.querystore.bridge.BridgeIndexer;
import org.openmrs.module.querystore.model.QueryDocument;

import java.lang.reflect.Method;

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
 * Pins {@link AppointmentProviderResponseIndexingAdvice}'s steady-state contract on
 * {@code AppointmentsService.updateAppointmentProviderResponse}: unwrap the affected
 * {@link org.openmrs.module.appointments.model.Appointment} from the {@code AppointmentProvider}
 * argument, route voided → delete and live → index, ignore non-trigger methods and orphan
 * providers, and swallow exceptions on both the pre-dispatch and dispatch-time paths per
 * querystore ADR Decision 12.
 */
public class AppointmentProviderResponseIndexingAdviceTest {

	private AppointmentProviderResponseIndexingAdvice advice;

	private MockedStatic<Context> contextMockedStatic;

	private AppointmentSerializer serializer;

	private BridgeIndexer indexer;

	private AfterCommitDispatcher dispatcher;

	@Before
	public void setUp() {
		advice = new AppointmentProviderResponseIndexingAdvice();

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
	public void indexesAppointmentReferencedByProvider() throws Exception {
		AppointmentProvider providerWithResponse = providerWith(appointment("appt-uuid"));

		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] { providerWithResponse }, null);

		ArgumentCaptor<QueryDocument> captor = ArgumentCaptor.forClass(QueryDocument.class);
		verify(indexer, times(1)).index(captor.capture());
		assertEquals("appt-uuid", captor.getValue().getResourceUuid());
	}

	@Test
	public void deletesVoidedAppointmentReferencedByProvider() throws Exception {
		Appointment voided = appointment("voided-uuid");
		voided.setVoided(true);

		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] { providerWith(voided) }, null);

		verify(indexer, times(1)).delete(eq("appointments_appointment"), eq("voided-uuid"));
		verify(indexer, never()).index(any(QueryDocument.class));
	}

	@Test
	public void nonTriggerMethodIsIgnored() throws Exception {
		AppointmentProvider providerWithResponse = providerWith(appointment("appt-uuid"));

		advice.afterReturning(null, methodNamed("validateAndSave"),
				new Object[] { providerWithResponse }, null);

		verify(indexer, never()).index(any(QueryDocument.class));
		verify(indexer, never()).delete(any(), any());
	}

	@Test
	public void emptyArgsIsIgnored() throws Exception {
		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] {}, null);

		verify(indexer, never()).index(any(QueryDocument.class));
		verify(indexer, never()).delete(any(), any());
	}

	@Test
	public void providerWithoutAppointmentIsIgnored() throws Exception {
		AppointmentProvider orphanProvider = new AppointmentProvider();

		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] { orphanProvider }, null);

		verify(indexer, never()).index(any(QueryDocument.class));
	}

	@Test
	public void serializerRuntimeExceptionDoesNotPropagateToCaller() throws Exception {
		AppointmentProvider providerWithResponse = providerWith(appointment("appt-uuid"));
		// Routes through the outer Decision 12 catch in afterReturning(). If a future refactor
		// removes or narrows that catch, the serializer exception would unwind to the AOP proxy
		// and the clinical-thread save would see a phantom failure even though the originating
		// transaction had already committed.
		doThrow(new RuntimeException("serializer down")).when(serializer).serialize(any(Appointment.class));

		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] { providerWithResponse }, null);

		verify(indexer, never()).index(any(QueryDocument.class));
	}

	@Test
	public void serializerLinkageErrorDoesNotPropagateToCaller() throws Exception {
		AppointmentProvider providerWithResponse = providerWith(appointment("appt-uuid"));
		// LinkageError covers NoClassDefFoundError / NoSuchMethodError that surface when a
		// deployment lands a querystore version with renamed or removed SPI symbols. Without the
		// catch widening in afterReturning, the Error would escape past the standard
		// RuntimeException catch and unwind through the AOP proxy to the clinical-thread save.
		doThrow(new NoSuchMethodError("ClinicalRecordSerializer.serialize"))
				.when(serializer).serialize(any(Appointment.class));

		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] { providerWithResponse }, null);

		verify(indexer, never()).index(any(QueryDocument.class));
	}

	@Test
	public void indexerRuntimeExceptionDoesNotPropagateToCaller() throws Exception {
		AppointmentProvider providerWithResponse = providerWith(appointment("appt-uuid"));
		// Verifies the inner per-document catch in dispatch(): a single poison row must not
		// propagate back to the clinical-thread caller, whose transaction has already committed.
		doThrow(new RuntimeException("indexer down")).when(indexer).index(any(QueryDocument.class));

		advice.afterReturning(null, methodNamed("updateAppointmentProviderResponse"),
				new Object[] { providerWithResponse }, null);

		// Anchor that the indexer was actually reached so a future short-circuit refactor can't
		// silently keep this test green.
		verify(indexer, times(1)).index(any(QueryDocument.class));
	}

	private Appointment appointment(String uuid) {
		Appointment appointment = new Appointment();
		appointment.setUuid(uuid);
		Patient patient = new Patient();
		patient.setUuid("patient-uuid");
		appointment.setPatient(patient);
		return appointment;
	}

	private AppointmentProvider providerWith(Appointment appointment) {
		AppointmentProvider provider = new AppointmentProvider();
		provider.setAppointment(appointment);
		return provider;
	}

	private Method methodNamed(String name) throws NoSuchMethodException {
		return DummyService.class.getMethod(name);
	}

	@SuppressWarnings("unused")
	private interface DummyService {
		void updateAppointmentProviderResponse();

		void validateAndSave();
	}
}
