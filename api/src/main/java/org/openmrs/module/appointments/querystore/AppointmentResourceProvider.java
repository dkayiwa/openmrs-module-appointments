package org.openmrs.module.appointments.querystore;

import org.openmrs.module.querystore.bootstrap.TypeBootstrapper;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;
import org.openmrs.module.querystore.spi.ResourceTypeProvider;

/**
 * Querystore SPI contribution (ADR Decision 13). Discovered at querystore bootstrap via
 * {@code Context.getRegisteredComponents(ResourceTypeProvider.class)}.
 */
public class AppointmentResourceProvider implements ResourceTypeProvider {

	private final AppointmentSerializer serializer;

	private final AppointmentBootstrapper bootstrapper;

	public AppointmentResourceProvider(AppointmentSerializer serializer,
	                                   AppointmentBootstrapper bootstrapper) {
		this.serializer = serializer;
		this.bootstrapper = bootstrapper;
	}

	@Override
	public String getResourceType() {
		return AppointmentSerializer.RESOURCE_TYPE;
	}

	@Override
	public ClinicalRecordSerializer<?> getSerializer() {
		return serializer;
	}

	@Override
	public TypeBootstrapper<?> getBootstrapper() {
		return bootstrapper;
	}
}
