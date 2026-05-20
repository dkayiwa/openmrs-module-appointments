package org.openmrs.module.appointments.querystore;

import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.querystore.bootstrap.HibernateTypeBootstrapper;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;

/**
 * Backfills historical {@link Appointment} records into querystore on initial install (or on a
 * fresh bootstrap of the {@code appointments_appointment} type). The base class handles
 * cursor-paginated HQL keyed on {@code COALESCE(dateChanged, dateCreated)} with {@code uuid} as
 * tie-breaker; {@link Appointment} extends {@code BaseOpenmrsData} so the default cursor and
 * patient-association expressions apply unchanged.
 */
public class AppointmentBootstrapper extends HibernateTypeBootstrapper<Appointment> {

	private final AppointmentSerializer serializer;

	public AppointmentBootstrapper(AppointmentSerializer serializer, DbSessionFactory sessionFactory) {
		super(sessionFactory);
		this.serializer = serializer;
	}

	@Override
	protected ClinicalRecordSerializer<Appointment> getSerializer() {
		return serializer;
	}
}
