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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Minimal implementation support for an {@link HtsCall} that needs an {@link HederaWorldUpdater.Enhancement}.
 */
public abstract class AbstractHtsCall implements HtsCall {
    protected final HederaWorldUpdater.Enhancement enhancement;

    protected AbstractHtsCall(@NonNull final HederaWorldUpdater.Enhancement enhancement) {
        this.enhancement = requireNonNull(enhancement);
    }

    protected HederaNativeOperations nativeOperations() {
        return enhancement.nativeOperations();
    }

    protected SystemContractOperations systemContractOperations() {
        return enhancement.systemOperations();
    }

    protected ResponseCodeEnum standardized(@NonNull final ResponseCodeEnum status) {
        return requireNonNull(status) == INVALID_SIGNATURE ? INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE : status;
    }
}
