/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.syrsafetech.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link SyrSafeTechBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Rush Almosa - Initial contribution
 */
@NonNullByDefault
public class SyrSafeTechBindingConstants {

    private static final String BINDING_ID = "syrsafetech";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_SYRLEAKAGEPROTECTION = new ThingTypeUID(BINDING_ID,
            "syrleakageprotection");

    // List of all Channel ids
    public static final String CHANNEL_SHUTOFF = "shutoff";
    public static final String CHANNEL_SELECT_PROFILE = "selectProfile";
    public static final String CHANNEL_NUMBER_OF_PROFILES = "numberOfProfiles";
    public static final String CHANNEL_PROFILE_AVAILABILITY = "profileAvailability";
    public static final String CHANNEL_PROFILE_NAME = "profileName";
    public static final String CHANNEL_PROFILE_VOLUME_LEVEL = "profileVolumeLevel";
    public static final String CHANNEL_PROFILE_TIME_LEVEL = "profileTimeLevel";
    public static final String CHANNEL_PROFILE_MAX_FLOW = "profileMaxFlow";
    public static final String CHANNEL_PROFILE_RETURN_TIME = "profileReturnTime";
    public static final String CHANNEL_PROFILE_MICROLEAKAGE = "profileMicroleakage";
    public static final String CHANNEL_PROFILE_BUZZER_ON = "profileBuzzerOn";
    public static final String CHANNEL_PROFILE_LEAKAGE_WARNING_ON = "profileLeakageWarningOn";
}
