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

import static org.openhab.binding.syrsafetech.internal.SyrSafeTechBindingConstants.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpStatus;
import org.json.JSONException;
import org.json.JSONObject;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SyrSafeTechHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Rush Almosa - Initial contribution
 */
@NonNullByDefault
public class SyrSafeTechHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(SyrSafeTechHandler.class);

    private static final String COMMAND_URL = "http://%s:5333/safe-tec/%s/%s/%s";

    private SyrSafeTechConfiguration config = new SyrSafeTechConfiguration();

    private final HttpClient httpClient;

    // #region main Handler

    public SyrSafeTechHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String ipAddress = config.ipAddress;
        if (ipAddress.isEmpty()) {
            logger.error("IP address not configured");
            return;
        }

        if (command instanceof RefreshType) {
            try {
                updateData(ipAddress);
            } catch (InterruptedException | TimeoutException | ExecutionException e) {
                logger.error("Exception: {}", e.getMessage(), e);
            }
        } else if (channelUID.getId().equals(SyrSafeTechBindingConstants.CHANNEL_SHUTOFF)) {
            int newState = -1;

            if (command instanceof DecimalType) {
                newState = ((DecimalType) command).intValue();
                logger.trace("Status: {}", newState);
            }
            if (command instanceof OnOffType) {
                try {
                    int currentState = getCurrentShutoffState(ipAddress);
                    newState = currentState == 1 ? 2 : 1;
                } catch (IOException e) {
                    logger.error("Exception in getCurrentShutoffState: {}", e.getMessage(), e);
                }
            } else {
                logger.warn("Invalid command type for shutoff channel: {}", command.getClass().getSimpleName());
                return;
            }

            if (newState == 1 || newState == 2) {
                try {
                    setShutoffStatus(ipAddress, newState);
                } catch (IOException | TimeoutException | InterruptedException | ExecutionException e) {
                    logger.error("Exception in setShutoffStatus: {}", e.getMessage(), e);
                }
            } else {
                logger.warn("Invalid shutoff command: {}", command);
            }
        } else if (channelUID.getId().equals(SyrSafeTechBindingConstants.CHANNEL_SELECT_PROFILE)) {
            int newProfile = -1;

            if (command instanceof DecimalType) {
                newProfile = ((DecimalType) command).intValue();
            } else if (command instanceof RefreshType) {
                try {
                    updateSelectProfileStatus(ipAddress);
                } catch (IOException | TimeoutException | InterruptedException | ExecutionException e) {
                    logger.error("Exception in updateSelectProfileStatus: {}", e.getMessage(), e);
                }
                return;
            } else {
                logger.warn("Invalid command type for selectProfile channel: {}", command.getClass().getSimpleName());
                return;
            }

            if (newProfile >= 1 && newProfile <= 8) {
                try {
                    setSelectProfileStatus(ipAddress, newProfile);
                    updateData(ipAddress);
                } catch (IOException | TimeoutException | InterruptedException | ExecutionException e) {
                    logger.error("Exception in setSelectProfileStatus: {}", e.getMessage(), e);
                }
            } else {
                logger.warn("Invalid select profile command: {}", command);
            }
        } else if (channelUID.getId().equals(SyrSafeTechBindingConstants.CHANNEL_PROFILE_AVAILABILITY)) {
            int newPAStatus = -1;

            if (command instanceof DecimalType) {
                newPAStatus = ((DecimalType) command).intValue();
            } else if (command instanceof OnOffType) {
                newPAStatus = ((OnOffType) command) == OnOffType.ON ? 1 : 0;
            } else {
                logger.warn("Invalid command type for profileAvailability channel: {}",
                        command.getClass().getSimpleName());
                return;
            }

            int selectedProfile;
            try {
                selectedProfile = getCurrentSelectedProfile(ipAddress);
            } catch (IOException e) {
                logger.error("Exception in getCurrentSelectedProfile: {}", e.getMessage(), e);
                return;
            }

            try {
                setPAStatus(ipAddress, selectedProfile, newPAStatus);
                if (((OnOffType) command) == OnOffType.OFF) {
                    List<Integer> activeProfiles = getActiveProfiles(ipAddress);
                    if (activeProfiles.size() > 1) {
                        // Wähle ein anderes aktives Profil aus, das nicht das derzeit ausgewählte Profil ist
                        for (Integer profile : activeProfiles) {
                            if (profile != selectedProfile) {
                                setSelectProfileStatus(ipAddress, profile);
                                break;
                            }
                        }
                    } else {
                        logger.warn("Cannot deactivate profile as there is no other active profile available");
                    }
                }
            } catch (IOException | TimeoutException | InterruptedException | ExecutionException e) {
                logger.error("Exception in setPAStatus: {}", e.getMessage(), e);
            }
        } else if (channelUID.getId().equals(SyrSafeTechBindingConstants.CHANNEL_PROFILE_NAME)) {
            if (command instanceof StringType) {
                try {
                    setProfileNameStatus(ipAddress, command.toString());
                } catch (Exception e) {
                    logger.error("Failed to set profile name: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void initialize() {
        config = getConfigAs(SyrSafeTechConfiguration.class);
        if (config != null) {
            updateStatus(ThingStatus.UNKNOWN);
        } else {
            logger.error("config is not initialized");
        }
    }

    // #endregion main Handler

    // #region multiusage functions

    private int getCurrentSelectedProfile(String ipAddress) throws IOException {
        try {
            String response = sendCommand(ipAddress, "get", "PRF", "");
            return parseSelectProfileResponse(response);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.error("Exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    // Update the updateData method to accept an ipAddress parameter and call updateShutoffStatus
    private void updateData(String ipAddress) throws InterruptedException, TimeoutException, ExecutionException {
        try {
            updateShutoffStatus(ipAddress);
            updateSelectProfileStatus(ipAddress);
            updateNumberOfProfilesStatus(ipAddress);
            updatePAStatus(ipAddress);
            updateProfileNameStatus(ipAddress);
            updateProfileVolumeLevelChannel(ipAddress);
            updateProfileTimeLevelChannel(ipAddress);
            updateProfileMaxFlowChannel(ipAddress);
            updateProfileReturnTimeChannel(ipAddress);
            updateProfileMicroleakageChannel(ipAddress);
            updateProfileBuzzerOnChannel(ipAddress);
            updateProfileLeakageWarningOnChannel(ipAddress);
            updateStatus(ThingStatus.ONLINE);
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } catch (TimeoutException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Timeout: " + e.getMessage());
        }
    }

    private String sendCommand(String ipAddress, String action, String command, String parameter) throws IOException,
            InterruptedException, TimeoutException, java.util.concurrent.TimeoutException, ExecutionException {
        String url = String.format(COMMAND_URL, ipAddress, action, command, parameter);
        logger.info("Sending request to URL: {}", url);
        Request request = httpClient.newRequest(url).timeout(5, TimeUnit.SECONDS);
        ContentResponse response = request.send();

        if (response.getStatus() != HttpStatus.OK_200) {
            throw new IOException("HTTP response status not OK: " + response.getStatus());
        }
        return response.getContentAsString();
    }

    // #endregion multiusage functions

    // #region ShutOff (Close&Open)
    private int getCurrentShutoffState(String ipAddress) throws IOException {
        try {
            String response = sendCommand(ipAddress, "get", "AB", "");
            return parseShutoffResponse(response);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.error("Exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Retrieves the shutoff status of the device and updates the channel accordingly.
     *
     * @param ipAddress The IP address of the device
     * @throws IOException, TimeoutException, InterruptedException, ExecutionException
     */
    private void updateShutoffStatus(String ipAddress)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        String response = sendCommand(ipAddress, "get", "AB", "");
        updateShutoffChannel(response);
    }

    /**
     * Sends the command to toggle the shutoff status of the device and updates the channel accordingly.
     *
     * @param ipAddress The IP address of the device
     * @param newState The new state of the shutoff (1 for Opened, 2 for Closed)
     * @throws IOException, TimeoutException, InterruptedException, ExecutionException
     */
    private void setShutoffStatus(String ipAddress, int newState)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        String response = sendCommand(ipAddress, "set", "AB", String.valueOf(newState));
        updateShutoffChannel(response);
    }

    /**
     * Updates the shutoff channel based on the response received from the device.
     *
     * @param response The response from the device
     */
    private void updateShutoffChannel(String response) {
        int status = parseShutoffResponse(response);
        if (status == 1 || status == 2) {
            updateState(SyrSafeTechBindingConstants.CHANNEL_SHUTOFF, new DecimalType(status));
        } else {
            logger.warn("Invalid shutoff status received: {}", response);
        }
    }

    /**
     * Parses the response received from the device and returns the shutoff status.
     *
     * @param response The response from the device
     * @return The shutoff status (1 for Opened, 2 for Closed) or -1 if the response is invalid
     */
    private int parseShutoffResponse(String response) {
        try {
            JSONObject jsonObject = new JSONObject(response);
            if (jsonObject.has("getAB")) {
                return jsonObject.getInt("getAB");
            } else if (jsonObject.has("setAB1") && jsonObject.getString("setAB1").equals("OK")) {
                return 1;
            } else if (jsonObject.has("setAB2") && jsonObject.getString("setAB2").equals("OK")) {
                return 2;
            } else {
                return -1;
            }
        } catch (JSONException e) {
            logger.warn("Unable to parse response as a JSON object: {}", response);
            return -1;
        }
    }

    // #endregion ShutOff (Close&Open)

    // #region Profile
    private void updateSelectProfileStatus(String ipAddress)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        String response = sendCommand(ipAddress, "get", "PRF", "");
        updateSelectProfileChannel(response);
    }

    private void setSelectProfileStatus(String ipAddress, int newProfile)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        setPAStatus(ipAddress, newProfile, 1); // 1 = on
        String response = sendCommand(ipAddress, "set", "PRF", String.valueOf(newProfile));
        updateSelectProfileChannel(response);
    }

    private void updateSelectProfileChannel(String response) {
        int profile = parseSelectProfileResponse(response);
        if (profile >= 1 && profile <= 8) {
            updateState(SyrSafeTechBindingConstants.CHANNEL_SELECT_PROFILE, new DecimalType(profile));
        } else {
            logger.warn("Invalid select profile status received: {}", response);
        }
    }

    private int parseSelectProfileResponse(String response) {
        try {
            JSONObject jsonObject = new JSONObject(response);
            if (jsonObject.has("getPRF")) {
                return jsonObject.getInt("getPRF");
            } else {
                for (int i = 1; i <= 8; i++) {
                    String key = "setPRF" + i;
                    if (jsonObject.has(key)) {
                        String result = jsonObject.getString(key);
                        if ("OK".equals(result)) {
                            return i;
                        } else {
                            return -1;
                        }
                    }
                }
            }
        } catch (JSONException e) {
            logger.warn("Unable to parse response as a JSON object: {}", response);
            return -1;
        }
        return -1;
    }

    // #endregion Profile

    // #region Number of Profiles

    /**
     * Retrieves the number of available profiles and updates the channel accordingly.
     *
     * @param ipAddress The IP address of the device
     * @throws IOException, TimeoutException, InterruptedException, ExecutionException
     */
    private void updateNumberOfProfilesStatus(String ipAddress)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        String response = sendCommand(ipAddress, "get", "PRn", "");
        updateNumberOfProfilesChannel(response);
    }

    /**
     * Updates the numberOfProfiles channel based on the response received from the device.
     *
     * @param response The response from the device
     */
    private void updateNumberOfProfilesChannel(String response) {
        int numberOfProfiles = parseNumberOfProfilesResponse(response);
        if (numberOfProfiles >= 0) {
            updateState(SyrSafeTechBindingConstants.CHANNEL_NUMBER_OF_PROFILES, new DecimalType(numberOfProfiles));
        } else {
            logger.warn("Invalid number of profiles received: {}", response);
        }
    }

    /**
     * Parses the response received from the device and returns the number of available profiles.
     *
     * @param response The response from the device
     * @return The number of available profiles or -1 if the response is invalid
     */
    private int parseNumberOfProfilesResponse(String response) {
        try {
            JSONObject jsonObject = new JSONObject(response);
            if (jsonObject.has("getPRN")) {
                return jsonObject.getInt("getPRN");
            } else {
                return -1;
            }
        } catch (JSONException e) {
            logger.warn("Unable to parse response as a JSON object: {}", response);
            return -1;
        }
    }

    // #endregion Number of Profiles

    // #region Profile Access

    // Add this method to handle getting PAx status
    private int getPAStatus(String ipAddress, int profile) throws IOException {
        try {
            String response = sendCommand(ipAddress, "get", "PA" + profile, "");
            String key = "getPA" + profile;
            return parsePAResponse(response, key);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.error("Exception: {}", e.getMessage(), e);
            return -1;
        }
    }

    // Add this method to handle setting PAx status
    private void setPAStatus(String ipAddress, int profile, int status) throws IOException {
        try {
            String response = sendCommand(ipAddress, "set", "PA" + profile, String.valueOf(status));
            int newStatus = parsePAResponse(response, "set" + "PA" + profile + status);
            if (newStatus != status) {
                logger.warn("Failed to set PA{} status to {}: {}", profile, status, response);
            }
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.error("Exception: {}", e.getMessage(), e);
        }
    }

    // Add this method to parse the response for PA commands
    private int parsePAResponse(String response, String key) {
        try {
            JSONObject jsonObject = new JSONObject(response);
            if (jsonObject.has(key)) {
                return jsonObject.getInt(key);
            } else {
                return -1;
            }
        } catch (JSONException e) {
            logger.warn("Unable to parse response as a JSON object: {}", response);
            return -1;
        }
    }

    private void updatePAStatus(String ipAddress)
            throws IOException, InterruptedException, TimeoutException, ExecutionException {
        int selectedProfile = getCurrentSelectedProfile(ipAddress);
        String response = sendCommand(ipAddress, "get", "PA" + selectedProfile, "");
        updatePAChannel(response, selectedProfile);
    }

    private void updatePAChannel(String response, int selectedProfile) {
        int status = parsePAResponse(response, "getPA" + selectedProfile);
        if (status == 1 || status == 0) {
            updateState(SyrSafeTechBindingConstants.CHANNEL_PROFILE_AVAILABILITY,
                    status == 1 ? OnOffType.ON : OnOffType.OFF);
        } else {
            logger.warn("Invalid profile availability status received: {}", response);
        }
    }

    private List<Integer> getActiveProfiles(String ipAddress) throws IOException {
        List<Integer> activeProfiles = new ArrayList<>();

        for (int profile = 1; profile <= 8; profile++) {

            if (getPAStatus(ipAddress, profile) == 1) {
                activeProfiles.add(profile);
            }
        }
        return activeProfiles;
    }

    // #endregion Profile Access

    // #region Profile Name

    /**
     * Retrieves the name of the currently selected profile and updates the channel accordingly.
     *
     * @param ipAddress The IP address of the device
     * @throws IOException, TimeoutException, InterruptedException, ExecutionException
     */
    private void updateProfileNameStatus(String ipAddress)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        int selectedProfile = getCurrentSelectedProfile(ipAddress);
        String response = sendCommand(ipAddress, "get", "PN" + selectedProfile, "");
        updateProfileNameChannel(response);
    }

    /**
     * Sends the command to set the name of the currently selected profile and updates the channel accordingly.
     *
     * @param ipAddress The IP address of the device
     * @param newName The new name for the profile
     * @throws IOException, TimeoutException, InterruptedException, ExecutionException
     */
    private void setProfileNameStatus(String ipAddress, String newName)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        int selectedProfile = getCurrentSelectedProfile(ipAddress);
        String response = sendCommand(ipAddress, "set", "PN" + selectedProfile + "/" + newName, "");
        updateProfileNameChannel(response);
    }

    /**
     * Updates the profileName channel based on the response received from the device.
     *
     * @param response The response from the device
     */
    private void updateProfileNameChannel(String response) {
        String profileName = parseProfileNameResponse(response);
        if (profileName != null && !profileName.isEmpty()) {
            updateState(SyrSafeTechBindingConstants.CHANNEL_PROFILE_NAME, new StringType(profileName));
        } else {
            logger.warn("Invalid profile name received: {}", response);
        }
    }

    /**
     * Parses the response received from the device and returns the profile name.
     *
     * @param response The response from the device
     * @return The profile name or null if the response is invalid
     */
    private String parseProfileNameResponse(String response) {
        try {
            JSONObject jsonObject = new JSONObject(response);
            Iterator<String> keys = jsonObject.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("getPN")) {
                    return jsonObject.getString(key);
                } else if (key.startsWith("setPN") && jsonObject.getString(key).equals("OK")) {
                    return key.split("/")[1];
                }
            }
        } catch (JSONException e) {
            logger.warn("Unable to parse response as a JSON object: {}", response);
        }
        return "";
    }

    // #endregion Profile Name

    // #region get channels

    private void updateProfileVolumeLevelChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PV" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPV" + currentProfile, SyrSafeTechBindingConstants.CHANNEL_PROFILE_VOLUME_LEVEL);
        }
    }

    private void updateProfileTimeLevelChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PT" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPT" + currentProfile, SyrSafeTechBindingConstants.CHANNEL_PROFILE_TIME_LEVEL);
        }
    }

    private void updateProfileMaxFlowChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PF" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPF" + currentProfile, SyrSafeTechBindingConstants.CHANNEL_PROFILE_MAX_FLOW);
        }
    }

    private void updateProfileReturnTimeChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PR" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPR" + currentProfile, SyrSafeTechBindingConstants.CHANNEL_PROFILE_RETURN_TIME);
        }
    }

    private void updateProfileMicroleakageChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PM" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPM" + currentProfile, SyrSafeTechBindingConstants.CHANNEL_PROFILE_MICROLEAKAGE);
        }
    }

    private void updateProfileBuzzerOnChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PB" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPB" + currentProfile, SyrSafeTechBindingConstants.CHANNEL_PROFILE_BUZZER_ON);
        }
    }

    private void updateProfileLeakageWarningOnChannel(String ipAddress) throws IOException {
        Integer currentProfile = getCurrentSelectedProfile(ipAddress);
        String response = null;
        try {
            response = sendCommand(ipAddress, "get", "PW" + currentProfile, "");
        } catch (Exception e) {
            logger.error("Error sending command: {}", e.getMessage());
        }
        if (response != null) {
            updateChannel(response, "getPW" + currentProfile,
                    SyrSafeTechBindingConstants.CHANNEL_PROFILE_LEAKAGE_WARNING_ON);
        }
    }

    private void updateChannel(String response, String key, String channel) {
        String value = parseResponse(response, key);
        if (value != null) {
            if (channel.equals(SyrSafeTechBindingConstants.CHANNEL_PROFILE_MICROLEAKAGE)
                    || channel.equals(SyrSafeTechBindingConstants.CHANNEL_PROFILE_BUZZER_ON)
                    || channel.equals(SyrSafeTechBindingConstants.CHANNEL_PROFILE_LEAKAGE_WARNING_ON)) {
                updateState(channel, value.equals("1") ? OnOffType.ON : OnOffType.OFF);
            } else {
                updateState(channel, new StringType(value));
            }
        } else {
            logger.warn("Invalid response received: {}", response);
        }
    }

    private String parseResponse(String response, String key) {
        try {
            JSONObject jsonObject = new JSONObject(response);
            if (jsonObject.has(key)) {
                return jsonObject.getString(key);
            }
        } catch (JSONException e) {
            logger.warn("Unable to parse response as a JSON object: {}", response);
        }
        return "";
    }

    // #endregion get channels
}
