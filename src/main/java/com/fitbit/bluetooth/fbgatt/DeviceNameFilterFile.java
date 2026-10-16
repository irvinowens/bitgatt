/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.fitbit.bluetooth.fbgatt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;

/**
 * Loads a device-name scan list from YAML. A missing file yields an empty list.
 * No product names are built in.
 *
 * <pre>
 * deviceNames:
 *   - Example Peripheral
 * </pre>
 */
public final class DeviceNameFilterFile {
    private static final String KEY = "deviceNames";

    private DeviceNameFilterFile() {
    }

    @NonNull
    public static List<String> load(@NonNull File file) {
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        StringBuilder yaml = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                yaml.append(line).append('\n');
            }
        } catch (IOException ex) {
            return Collections.emptyList();
        }
        return parse(yaml.toString());
    }

    @NonNull
    public static List<String> parse(@NonNull String yaml) {
        List<String> names = new ArrayList<>();
        boolean inList = false;
        for (String raw : yaml.split("\n", -1)) {
            String line = stripComment(raw).trim();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("-")) {
                int colon = line.indexOf(':');
                String key = colon >= 0 ? line.substring(0, colon).trim() : "";
                String rest = colon >= 0 ? line.substring(colon + 1).trim() : "";
                inList = KEY.equals(key) && rest.isEmpty();
                continue;
            }
            if (!inList) {
                continue;
            }
            String value = unquote(line.substring(1).trim());
            if (!value.isEmpty()) {
                names.add(value);
            }
        }
        return names;
    }

    private static String stripComment(String line) {
        Character quote = null;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quote != null) {
                if (character == quote) {
                    quote = null;
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '#') {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char open = value.charAt(0);
            char close = value.charAt(value.length() - 1);
            if ((open == '"' && close == '"') || (open == '\'' && close == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }
}
