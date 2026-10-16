/*
 * Copyright 2019 Fitbit, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package android.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit-test stand-in. The platform {@code IntentFilter} stores nothing, so a
 * receiver test cannot see which action was registered.
 */
public class IntentFilter {
    private final List<String> actions = new ArrayList<>();

    public IntentFilter() {
    }

    public IntentFilter(String action) {
        if (action != null) {
            actions.add(action);
        }
    }

    public void addAction(String action) {
        actions.add(action);
    }

    public int countActions() {
        return actions.size();
    }

    public String getAction(int index) {
        return actions.get(index);
    }
}
