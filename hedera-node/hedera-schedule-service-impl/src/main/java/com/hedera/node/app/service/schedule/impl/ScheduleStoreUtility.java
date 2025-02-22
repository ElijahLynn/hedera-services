/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.schedule.impl;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class ScheduleStoreUtility {
    private ScheduleStoreUtility() {}

    // @todo('7773') This requires rebuilding the equality virtual map on migration,
    //      because it's different from ScheduleVirtualValue (and must be, due to PBJ shift)
    @SuppressWarnings("UnstableApiUsage")
    static String calculateStringHash(@NonNull final Schedule scheduleToHash) {
        Objects.requireNonNull(scheduleToHash);
        final Hasher hasher = Hashing.sha256().newHasher();
        if (scheduleToHash.memo() != null) {
            hasher.putString(scheduleToHash.memo(), StandardCharsets.UTF_8);
        }
        if (scheduleToHash.adminKey() != null) {
            addToHash(hasher, scheduleToHash.adminKey());
        }
        if (scheduleToHash.payerAccountId() != null) {
            addToHash(hasher, scheduleToHash.payerAccountId());
        }
        if (scheduleToHash.schedulerAccountId() != null) {
            addToHash(hasher, scheduleToHash.schedulerAccountId());
        }
        if (scheduleToHash.scheduledTransaction() != null) {
            addToHash(hasher, scheduleToHash.scheduledTransaction());
        }
        hasher.putLong(scheduleToHash.providedExpirationSecond());
        hasher.putBoolean(scheduleToHash.waitForExpiry());
        return hasher.hash().toString();
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void addToHash(final Hasher hasher, final Key keyToAdd) {
        final byte[] keyBytes = Key.PROTOBUF.toBytes(keyToAdd).toByteArray();
        hasher.putInt(keyBytes.length);
        hasher.putBytes(keyBytes);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void addToHash(final Hasher hasher, final AccountID accountToAdd) {
        final byte[] accountIdBytes = AccountID.PROTOBUF.toBytes(accountToAdd).toByteArray();
        hasher.putInt(accountIdBytes.length);
        hasher.putBytes(accountIdBytes);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void addToHash(final Hasher hasher, final SchedulableTransactionBody transactionToAdd) {
        final byte[] bytes =
                SchedulableTransactionBody.PROTOBUF.toBytes(transactionToAdd).toByteArray();
        hasher.putInt(bytes.length);
        hasher.putBytes(bytes);
    }
}
