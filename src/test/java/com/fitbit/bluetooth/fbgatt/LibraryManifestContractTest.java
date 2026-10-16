/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LibraryManifestContractTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test
    public void manifestMatchesThePermissionAndReceiverResolution() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new File("src/main/AndroidManifest.xml"));
        Element manifest = document.getDocumentElement();

        Element scan = permission(manifest, "android.permission.BLUETOOTH_SCAN");
        assertEquals("neverForLocation", scan.getAttributeNS(ANDROID, "usesPermissionFlags"));
        assertTrue(permission(manifest, "android.permission.BLUETOOTH_CONNECT") != null);
        assertTrue(permission(manifest, "android.permission.BLUETOOTH_ADVERTISE") != null);
        assertEquals("30", permission(manifest, "android.permission.BLUETOOTH").getAttributeNS(ANDROID, "maxSdkVersion"));
        assertEquals("30", permission(manifest, "android.permission.BLUETOOTH_ADMIN").getAttributeNS(ANDROID, "maxSdkVersion"));
        assertEquals("30", permission(manifest, "android.permission.ACCESS_FINE_LOCATION").getAttributeNS(ANDROID, "maxSdkVersion"));
        assertEquals("30", permission(manifest, "android.permission.ACCESS_COARSE_LOCATION").getAttributeNS(ANDROID, "maxSdkVersion"));

        NodeList features = manifest.getElementsByTagName("uses-feature");
        boolean ble = false;
        for (int i = 0; i < features.getLength(); i++) {
            Element feature = (Element) features.item(i);
            if ("android.hardware.bluetooth_le".equals(feature.getAttributeNS(ANDROID, "name"))) {
                ble = true;
                assertEquals("false", feature.getAttributeNS(ANDROID, "required"));
            }
        }
        assertTrue(ble);

        NodeList receivers = manifest.getElementsByTagName("receiver");
        Element scanReceiver = null;
        for (int i = 0; i < receivers.getLength(); i++) {
            Element receiver = (Element) receivers.item(i);
            String name = receiver.getAttributeNS(ANDROID, "name");
            assertFalse(name.contains("LowEnergyAclListener"));
            if (name.endsWith("HandleIntentBasedScanResult")) {
                scanReceiver = receiver;
            }
        }
        assertTrue(scanReceiver != null);
        assertEquals("true", scanReceiver.getAttributeNS(ANDROID, "exported"));
        assertTrue(scanReceiver.getAttributeNS(ANDROID, "permission").isEmpty());
        assertNull(scanReceiver.getAttributeNodeNS(ANDROID, "permission"));
        NodeList actions = scanReceiver.getElementsByTagName("action");
        assertEquals(1, actions.getLength());
        assertEquals("com.fitbit.bluetooth.fbgatt.ScannedDevice",
                ((Element) actions.item(0)).getAttributeNS(ANDROID, "name"));

        assertEquals(0, manifest.getElementsByTagName("service").getLength());
        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            String name = ((Element) permissions.item(i)).getAttributeNS(ANDROID, "name");
            assertFalse(name.toUpperCase().contains("COMPANION"));
        }
    }

    private static Element permission(Element manifest, String name) {
        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            Element permission = (Element) permissions.item(i);
            if (name.equals(permission.getAttributeNS(ANDROID, "name"))) {
                return permission;
            }
        }
        return null;
    }
}
