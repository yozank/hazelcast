/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.monitor;

import com.hazelcast.internal.management.JsonSerializable;

/**
 * Base interface for local instance statistics.
 */
public interface LocalInstanceStats extends JsonSerializable {

    /**
     * Default value for unavailable statistics.
     */
    long STAT_NOT_AVAILABLE = -99L;

    /**
     * Returns the creation time of this distributed object instance on this member.
     */
    long getCreationTime();
}
