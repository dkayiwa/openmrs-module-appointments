package org.openmrs.module.appointments.querystore;

import org.junit.Test;
import org.openmrs.api.db.hibernate.DbSessionFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Pins the SPI contribution contract: {@link AppointmentResourceProvider}'s {@code resourceType}
 * must equal {@link AppointmentSerializer#RESOURCE_TYPE} and the {@code serializer}/{@code bootstrapper}
 * accessors must return the constructor-injected instances. Querystore validates provider/serializer
 * resource-type alignment at discovery and skips mismatched providers; this test guards against
 * the slice-internal drift that would surface as "querystore silently skipped my provider."
 */
public class AppointmentResourceProviderTest {

	@Test
	public void resourceTypeMatchesSerializerConstant() {
		AppointmentSerializer serializer = new AppointmentSerializer();
		AppointmentBootstrapper bootstrapper = new AppointmentBootstrapper(serializer,
				mock(DbSessionFactory.class));

		AppointmentResourceProvider provider = new AppointmentResourceProvider(serializer, bootstrapper);

		assertEquals("provider's resource type must match the serializer's so querystore's "
				+ "discovery-time alignment check doesn't reject the provider",
				AppointmentSerializer.RESOURCE_TYPE, provider.getResourceType());
		assertEquals(serializer.getResourceType(), provider.getResourceType());
	}

	@Test
	public void accessorsReturnInjectedInstances() {
		AppointmentSerializer serializer = new AppointmentSerializer();
		AppointmentBootstrapper bootstrapper = new AppointmentBootstrapper(serializer,
				mock(DbSessionFactory.class));

		AppointmentResourceProvider provider = new AppointmentResourceProvider(serializer, bootstrapper);

		assertSame(serializer, provider.getSerializer());
		assertSame(bootstrapper, provider.getBootstrapper());
	}
}
