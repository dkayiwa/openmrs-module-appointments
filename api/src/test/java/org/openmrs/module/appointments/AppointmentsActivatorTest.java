package org.openmrs.module.appointments;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.querystore.AppointmentSerializer;
import org.openmrs.module.querystore.bootstrap.BootstrapService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the activator's started()-time contract: a {@code RuntimeException} from
 * {@link BootstrapService#bootstrap(String)} must NOT propagate out of {@code started()}. If the
 * exception escapes, the module-load hook in OpenMRS treats startup as failed and the module
 * never reaches its post-load state — clinicians lose appointment functionality because of a
 * querystore-backfill issue. The error is logged so deployment teams notice; the module still
 * starts because the AOP bridge keeps steady-state writes flowing.
 */
public class AppointmentsActivatorTest {

	private AppointmentsActivator activator;

	private MockedStatic<Context> contextMockedStatic;

	private BootstrapService bootstrapService;

	@Before
	public void setUp() {
		activator = new AppointmentsActivator();
		bootstrapService = mock(BootstrapService.class);
		contextMockedStatic = mockStatic(Context.class);
		when(Context.getService(BootstrapService.class)).thenReturn(bootstrapService);
	}

	@After
	public void tearDown() {
		if (contextMockedStatic != null) {
			contextMockedStatic.close();
		}
	}

	@Test
	public void startedInvokesBootstrapForAppointmentsResourceType() {
		activator.started();

		verify(bootstrapService).bootstrap(eq(AppointmentSerializer.RESOURCE_TYPE));
	}

	@Test
	public void startedSwallowsBootstrapRuntimeException() {
		doThrow(new RuntimeException("querystore backend down"))
				.when(bootstrapService).bootstrap(eq(AppointmentSerializer.RESOURCE_TYPE));

		// If this throws, OpenMRS treats module startup as failed and the AOP advices never wire
		// up — every appointment write would then silently bypass querystore until the deployment
		// team intervenes manually. The catch in started() is what keeps the module alive.
		activator.started();
	}

	@Test
	public void startedSwallowsLinkageErrorFromVersionSkewedQuerystore() {
		// LinkageError covers NoClassDefFoundError / NoSuchMethodError that surface when a
		// deployment lands a querystore version with renamed or removed SPI symbols. Without
		// catching LinkageError separately from RuntimeException, the module-load hook would
		// see an uncaught Error and treat startup as failed — the AOP advices never wire and
		// every appointment write silently bypasses querystore. require_module without a
		// version= pin allows this skew to reach production today.
		doThrow(new NoSuchMethodError(
				"org.openmrs.module.querystore.bootstrap.BootstrapService.bootstrap"))
				.when(bootstrapService).bootstrap(eq(AppointmentSerializer.RESOURCE_TYPE));

		activator.started();
	}

	@Test
	public void startedSwallowsApiExceptionWhenBootstrapServiceNotRegistered() {
		// Context.getService throws APIException (a RuntimeException) when the service is missing
		// — happens when querystore is present but its own startup failed (DB migration crash,
		// backend host unreachable). This test pins the lookup site inside the try{} block, so a
		// future refactor that moves Context.getService outside the try (e.g., field injection)
		// will be caught by this test before the regression reaches a deployment.
		contextMockedStatic.when(() -> Context.getService(BootstrapService.class))
				.thenThrow(new APIException("BootstrapService not registered"));

		activator.started();
	}
}
