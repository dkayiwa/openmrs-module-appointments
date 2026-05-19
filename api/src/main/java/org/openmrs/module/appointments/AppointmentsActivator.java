/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.appointments;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.appointments.querystore.AppointmentSerializer;
import org.openmrs.module.querystore.bootstrap.BootstrapService;

/**
 * This class contains the logic that is run every time this module is either started or shutdown
 */
public class AppointmentsActivator extends BaseModuleActivator {

	private Log log = LogFactory.getLog(this.getClass());

	public void startup() {
		log.info("Starting Appointments Module");
	}

	@Override
	public void started() {
		// Trigger querystore backfill of historical appointments. Idempotent: if the type has a
		// completed progress row, BootstrapService resumes from the cursor and finds no new work.
		// Wrapped so a querystore failure does not block module startup; logged at error level so
		// deployment teams notice in production — a silent failure here means pre-existing
		// appointments will not appear in querystore search until the underlying issue is fixed.
		try {
			Context.getService(BootstrapService.class).bootstrap(AppointmentSerializer.RESOURCE_TYPE);
		}
		catch (RuntimeException e) {
			log.error("Querystore backfill of " + AppointmentSerializer.RESOURCE_TYPE
					+ " failed. Steady-state writes will still flow through the AOP bridge, "
					+ "but pre-existing appointments will not appear in querystore search "
					+ "until this is resolved.", e);
		}
	}

	public void shutdown() {
		log.info("Shutting down Appointments Module");
	}

}
