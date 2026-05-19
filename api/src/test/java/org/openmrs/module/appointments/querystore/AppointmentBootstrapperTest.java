package org.openmrs.module.appointments.querystore;

import org.junit.Test;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Pins the constructor wiring contract: {@link AppointmentBootstrapper#getSerializer()} must
 * return the serializer instance passed at construction. The paginated-HQL behavior is provided
 * by querystore's {@code HibernateTypeBootstrapper} (tested upstream); the appointments-side
 * contribution is only the constructor injection + serializer accessor, and the regression to
 * guard against is "someone changes the constructor and the serializer reference falls out of
 * sync without the type system catching it."
 */
public class AppointmentBootstrapperTest {

	@Test
	public void getSerializerReturnsInjectedInstance() {
		AppointmentSerializer serializer = new AppointmentSerializer();
		DbSessionFactory sessionFactory = mock(DbSessionFactory.class);

		AppointmentBootstrapper bootstrapper = new AppointmentBootstrapper(serializer, sessionFactory);

		ClinicalRecordSerializer<?> resolved = bootstrapper.getSerializer();
		assertSame("bootstrapper must hand back the serializer it was constructed with",
				serializer, resolved);
	}

	@Test
	public void resourceTypeMatchesSerializer() {
		// The bootstrapper inherits getResourceType from the base class, which delegates to its
		// serializer — guard against an accidental override that would drift from the serializer.
		AppointmentSerializer serializer = new AppointmentSerializer();
		AppointmentBootstrapper bootstrapper = new AppointmentBootstrapper(serializer,
				mock(DbSessionFactory.class));

		assertEquals(AppointmentSerializer.RESOURCE_TYPE, bootstrapper.getResourceType());
	}
}
