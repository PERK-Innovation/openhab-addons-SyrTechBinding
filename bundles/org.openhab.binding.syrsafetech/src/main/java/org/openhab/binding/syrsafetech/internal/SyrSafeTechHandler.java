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

    private static final String COMMAND_URL = "http://%s:5333/safe-tec/%s/%s";

    private SyrSafeTechConfiguration config = new SyrSafeTechConfiguration();

    private final HttpClient httpClient;

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
        }
    }

    private int getCurrentShutoffState(String ipAddress) throws IOException {
        try {
            String response = sendCommand(ipAddress, "get", "AB");
            return parseShutoffResponse(response);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            logger.error("Exception: {}", e.getMessage(), e);
            return -1;
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

    /**
     * Retrieves the shutoff status of the device and updates the channel accordingly.
     *
     * @param ipAddress The IP address of the device
     * @throws IOException, TimeoutException, InterruptedException, ExecutionException
     */
    private void updateShutoffStatus(String ipAddress)
            throws IOException, TimeoutException, InterruptedException, ExecutionException {
        String response = sendCommand(ipAddress, "get", "AB");
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
        String response = sendCommand(ipAddress, "set", "AB" + newState);
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

    // Update the updateData method to accept an ipAddress parameter and call updateShutoffStatus
    private void updateData(String ipAddress) throws InterruptedException, TimeoutException, ExecutionException {
        try {
            updateShutoffStatus(ipAddress);
            updateStatus(ThingStatus.ONLINE);
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } catch (TimeoutException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Timeout: " + e.getMessage());
        }
    }

    private String sendCommand(String ipAddress, String action, String command) throws IOException,
            InterruptedException, TimeoutException, java.util.concurrent.TimeoutException, ExecutionException {
        String url = String.format(COMMAND_URL, ipAddress, action, command);
        logger.info("Sending request to URL: {}", url);
        Request request = httpClient.newRequest(url).timeout(5, TimeUnit.SECONDS);
        ContentResponse response = request.send();

        if (response.getStatus() != HttpStatus.OK_200) {
            throw new IOException("HTTP response status not OK: " + response.getStatus());
        }
        return response.getContentAsString();
    }
}
