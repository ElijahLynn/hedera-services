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

package com.hedera.node.app.service.contract.impl.exec;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QueryHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QueryHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QuerySystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.ActionStack;
import com.hedera.node.app.service.contract.impl.hevm.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.QueryContextHevmBlocks;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ScopedEvmFrameStateFactory;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;

@Module
public interface QueryModule {
    @Provides
    @QueryScope
    static HederaWorldUpdater.Enhancement provideEnhancement(
            @NonNull final HederaOperations operations,
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final SystemContractOperations systemContractOperations) {
        return new HederaWorldUpdater.Enhancement(operations, nativeOperations, systemContractOperations);
    }

    @Provides
    @QueryScope
    static ProxyWorldUpdater provideProxyWorldUpdater(
            @NonNull final HederaWorldUpdater.Enhancement enhancement, @NonNull final EvmFrameStateFactory factory) {
        return new ProxyWorldUpdater(enhancement, requireNonNull(factory), null);
    }

    @Provides
    @QueryScope
    static ActionSidecarContentTracer provideActionSidecarContentTracer() {
        return new EvmActionTracer(new ActionStack());
    }

    @Provides
    @QueryScope
    static Supplier<HederaWorldUpdater> provideFeesOnlyUpdater(
            @NonNull final HederaWorldUpdater.Enhancement enhancement, @NonNull final EvmFrameStateFactory factory) {
        return () -> new ProxyWorldUpdater(enhancement, requireNonNull(factory), null);
    }

    @Provides
    @QueryScope
    static HederaEvmContext provideHederaEvmContext(
            @NonNull final HederaOperations extWorldScope, @NonNull final HederaEvmBlocks hederaEvmBlocks) {
        return new HederaEvmContext(extWorldScope.gasPriceInTinybars(), true, hederaEvmBlocks);
    }

    @Binds
    @QueryScope
    EvmFrameStateFactory bindEvmFrameStateFactory(ScopedEvmFrameStateFactory factory);

    @Binds
    @QueryScope
    HederaOperations bindExtWorldScope(QueryHederaOperations queryExtWorldScope);

    @Binds
    @QueryScope
    HederaNativeOperations bindExtFrameScope(QueryHederaNativeOperations queryExtFrameScope);

    @Binds
    @QueryScope
    HederaEvmBlocks bindHederaEvmBlocks(QueryContextHevmBlocks queryContextHevmBlocks);

    @Binds
    @QueryScope
    SystemContractOperations bindQuerySystemContractOperations(
            QuerySystemContractOperations querySystemContractOperations);
}
