/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.authorization;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Verifies whether an account is authorized to perform a specific function.
 */
public interface Authorizer {
    /**
     * Checks if the given account is authorized to perform the given function.
     *
     * @param id The ID of the account to check
     * @param function The specific functionality to check
     * @return true if the account is authorized, otherwise false.
     */
    boolean isAuthorized(@NonNull AccountID id, @NonNull HederaFunctionality function);

    /**
     * Checks whether the given account refers to a superuser. If the {@link AccountID} does not contain an account
     * number (for example, because it uses an alias), then this method will return false.
     *
     * @param id The ID of the account to check
     * @return Whether the ID definitively refers to a super-user
     */
    boolean isSuperUser(@NonNull AccountID id);

    /**
     * Checks whether the given account refers to a treasury account. If the {@link AccountID} does not contain an account
     * number (for example, because it uses an alias), then this method will return false.
     *
     * @param id The ID of the account to check
     * @return Whether the ID definitively refers to a super-user
     */
    boolean isTreasury(@NonNull AccountID id);

    /**
     * Checks whether an account is exempt from paying fees.
     *
     * @param id the {@link AccountID} to check
     * @param functionality the {@link HederaFunctionality} of the transaction
     * @param txBody the {@link TransactionBody} of the transaction
     * @return {@code true} if the account is exempt from paying fees, otherwise {@code false}
     */
    SystemPrivilege hasPrivilegedAuthorization(
            @NonNull AccountID id, @NonNull HederaFunctionality functionality, @NonNull TransactionBody txBody);

    enum SystemPrivilege {
        /** The operation does not require any system privileges. */
        UNNECESSARY,

        /** The operation requires system privileges that its payer does not have. */
        UNAUTHORIZED,

        /** The operation cannot be performed, no matter the privileges of its payer. */
        IMPERMISSIBLE,

        /** The operation requires system privileges, and its payer has those privileges. */
        AUTHORIZED;
    }
}
