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

package com.hedera.node.app.integration;

import com.hedera.node.app.integration.facilities.ReplayAdvancingConsensusNow;
import com.hedera.node.app.integration.infra.InMemoryWritableStoreFactory;
import com.hedera.node.app.integration.infra.RecordingName;
import com.hedera.node.app.integration.infra.ReplayFacilityTransactionDispatcher;
import com.hedera.node.app.service.mono.utils.replay.ReplayAssetRecording;
import com.hedera.node.app.services.ServicesModule;
import com.hedera.node.app.workflows.handle.HandlersModule;
import dagger.BindsInstance;
import dagger.Component;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

@Singleton
@Component(
        modules = {
            ServicesModule.class,
            HandlersModule.class,
            ReplayFacilityModule.class,
        })
public interface ReplayFacilityComponent {
    @Component.Factory
    interface Factory {
        ReplayFacilityComponent create(@BindsInstance @RecordingName @NonNull final String recordingName);
    }

    ReplayAssetRecording assetRecording();

    ReplayAdvancingConsensusNow consensusNow();

    InMemoryWritableStoreFactory writableStoreFactory();

    ReplayFacilityTransactionDispatcher transactionDispatcher();
}
