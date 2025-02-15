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

package com.hedera.node.app.authorization;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ApiPermissionConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * An implementation of {@link Authorizer}.
 */
@Singleton
public class AuthorizerImpl implements Authorizer {

    private final ConfigProvider configProvider;
    private final AccountsConfig accountsConfig;
    private final PrivilegesVerifier privilegedTransactionChecker;

    @Inject
    public AuthorizerImpl(
            @NonNull final ConfigProvider configProvider, @NonNull PrivilegesVerifier privilegedTransactionChecker) {
        this.configProvider = requireNonNull(configProvider);
        this.accountsConfig = configProvider.getConfiguration().getConfigData(AccountsConfig.class);
        this.privilegedTransactionChecker = requireNonNull(privilegedTransactionChecker);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAuthorized(@NonNull final AccountID id, @NonNull final HederaFunctionality function) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(function);
        return permissibilityOf(id, function) == OK;
    }

    @Override
    public boolean isSuperUser(@NonNull final AccountID accountID) {
        if (!accountID.hasAccountNum()) return false;
        long num = accountID.accountNumOrThrow();
        return num == accountsConfig.treasury() || num == accountsConfig.systemAdmin();
    }

    @Override
    public boolean isTreasury(@NonNull final AccountID accountID) {
        if (!accountID.hasAccountNum()) return false;
        long num = accountID.accountNumOrThrow();
        return num == accountsConfig.treasury();
    }

    @Override
    public SystemPrivilege hasPrivilegedAuthorization(
            @NonNull final AccountID payerId,
            @NonNull final HederaFunctionality functionality,
            @NonNull final TransactionBody txBody) {
        return privilegedTransactionChecker.hasPrivileges(payerId, functionality, txBody);
    }

    private ResponseCodeEnum permissibilityOf(
            @NonNull final AccountID givenPayer, @NonNull final HederaFunctionality function) {
        if (isSuperUser(givenPayer)) {
            return ResponseCodeEnum.OK;
        }

        if (!givenPayer.hasAccountNum()) {
            return ResponseCodeEnum.AUTHORIZATION_FAILED;
        }

        final long num = givenPayer.accountNumOrThrow();
        final var permissionConfig = configProvider.getConfiguration().getConfigData(ApiPermissionConfig.class);
        final var permission = permissionConfig.getPermission(function);
        return permission != null && permission.contains(num) ? OK : NOT_SUPPORTED;
    }
}
