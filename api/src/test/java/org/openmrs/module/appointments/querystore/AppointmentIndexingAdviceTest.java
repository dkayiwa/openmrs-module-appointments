package org.openmrs.module.appointments.querystore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.model.Appointment;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The advice's runtime behavior (afterReturning / dispatch loop) lives in querystore's
 * {@code AbstractIndexingAdvice} and is exercised by querystore's own tests; this file pins the
 * appointments-side contract: the supported type, the trigger-method set, the purge-method set
 * (empty), and the serializer-bean lookup. A typo in {@code TRIGGER_METHODS} would silently drop
 * a mutation path from querystore indexing — these tests are the regression guard.
 */
public class AppointmentIndexingAdviceTest {

	private AppointmentIndexingAdvice advice;

	private MockedStatic<Context> contextMockedStatic;

	@Before
	public void setUp() {
		advice = new AppointmentIndexingAdvice();
		contextMockedStatic = mockStatic(Context.class);
	}

	@After
	public void tearDown() {
		if (contextMockedStatic != null) {
			contextMockedStatic.close();
		}
	}

	// The advice's overridden methods are protected; this test is in the same package as
	// AppointmentIndexingAdvice, so package-private access is sufficient — no forTest accessors
	// are needed and the production class doesn't grow a test-only surface.

	@Test
	public void supportedTypeIsAppointment() {
		assertEquals(Appointment.class, advice.getSupportedType());
	}

	@Test
	public void triggerMethodsCoverEveryMutatingAppointmentsServiceCall() {
		Set<String> triggers = advice.triggerMethods();
		assertTrue("validateAndSave is the primary save path",
				triggers.contains("validateAndSave"));
		assertTrue("changeStatus is the primary status-mutation path",
				triggers.contains("changeStatus"));
		assertTrue("undoStatusChange is the reverse of changeStatus",
				triggers.contains("undoStatusChange"));
		assertTrue("reschedule fires for the newAppointment via returnValue",
				triggers.contains("reschedule"));
		// updateAppointmentProviderResponse is intentionally NOT in this set — it has its own
		// dedicated advice because the affected Appointment is wrapped in an AppointmentProvider.
		assertEquals("guard against accidental trigger additions that would skip dedup",
				4, triggers.size());
	}

	@Test
	public void purgeMethodsIsEmpty() {
		// AppointmentsService exposes no purge method; the entity is soft-cancelled via
		// AppointmentStatus.Cancelled, not deleted.
		assertTrue(advice.purgeMethods().isEmpty());
	}

	@Test
	public void serializerLookupResolvesViaContextRegisteredComponent() {
		AppointmentSerializer serializer = mock(AppointmentSerializer.class);
		contextMockedStatic.when(() -> Context.getRegisteredComponent(
				eq(AppointmentIndexingAdvice.SERIALIZER_BEAN_ID), eq(AppointmentSerializer.class)))
				.thenReturn(serializer);

		assertSame(serializer, advice.serializer());
	}
}
