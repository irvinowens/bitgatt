/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class DeviceNameFilterFileTest {

    @Test
    public void missingFileLoadsNoProductNames() {
        List<String> names = DeviceNameFilterFile.load(new File("no-such-device-names.yaml"));
        assertTrue(names.isEmpty());
        assertFalse(names.contains("Ionic"));
        assertFalse(names.contains("Surge"));
        assertFalse(names.contains("Blaze"));
        assertFalse(names.contains("Versa"));
    }

    @Test
    public void sampleFileIsOnlyThePlaceholder() {
        List<String> names = DeviceNameFilterFile.load(new File("device-names.sample.yaml"));
        assertEquals(Collections.singletonList("Example Peripheral"), names);
    }

    @Test
    public void parseKeepsQuotedNamesAndSkipsComments() {
        String yaml = "# header\n"
                + "other:\n"
                + "  - Ignored\n"
                + "deviceNames:\n"
                + "\n"
                + "  # comment\n"
                + "  - Example Peripheral\n"
                + "  - \"Quoted Name\"\n"
                + "  - 'Single Quoted'\n"
                + "  - \n"
                + "later:\n"
                + "  - Also Ignored\n";
        assertEquals(
                Arrays.asList("Example Peripheral", "Quoted Name", "Single Quoted"),
                DeviceNameFilterFile.parse(yaml));
    }
}
