/*
 * Copyright (C) 2020-2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.consensus.impl;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.spi.state.Schema;
import com.hedera.node.app.spi.state.SchemaRegistry;
import com.hedera.node.app.spi.state.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link ConsensusService} {@link com.hedera.node.app.spi.Service}.
 */
public final class ConsensusServiceImpl implements ConsensusService {
    private static final SemanticVersion GENESIS_VERSION = SemanticVersion.DEFAULT;
    public static final long RUNNING_HASH_VERSION = 3L;
    public static final int RUNNING_HASH_BYTE_ARRAY_SIZE = 48;
    public static final String TOPICS_KEY = "TOPICS";

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        registry.register(consensusSchema());
    }

    private Schema consensusSchema() {
        return new Schema(GENESIS_VERSION) {
            @NonNull
            @Override
            public Set<StateDefinition> statesToCreate() {
                return Set.of(StateDefinition.inMemory(TOPICS_KEY, TopicID.PROTOBUF, Topic.PROTOBUF));
            }
        };
    }
}
