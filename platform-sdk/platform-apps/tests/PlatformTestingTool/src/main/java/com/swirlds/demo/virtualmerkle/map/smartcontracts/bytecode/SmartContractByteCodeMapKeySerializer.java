/*
 * Copyright (C) 2016-2023 Hedera Hashgraph, LLC
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

package com.swirlds.demo.virtualmerkle.map.smartcontracts.bytecode;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.serialize.AbstractFixedSizeKeySerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class is the serializer of {@link SmartContractByteCodeMapKey}.
 */
public final class SmartContractByteCodeMapKeySerializer
        extends AbstractFixedSizeKeySerializer<SmartContractByteCodeMapKey> {

    private static final long CLASS_ID = 0xee36c20c7ccc69daL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
    }

    public SmartContractByteCodeMapKeySerializer() {
        super(CLASS_ID, ClassVersion.ORIGINAL, SmartContractByteCodeMapKey.getSizeInBytes(),
                1, SmartContractByteCodeMapKey::new);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(@NonNull final SmartContractByteCodeMapKey key, @NonNull final WritableSequentialData out) {
        key.serialize(out);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmartContractByteCodeMapKey deserialize(@NonNull final ReadableSequentialData in) {
        final SmartContractByteCodeMapKey key = new SmartContractByteCodeMapKey();
        key.deserialize(in);
        return key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@NonNull final BufferedData buffer, @NonNull final SmartContractByteCodeMapKey keyToCompare) {
        return keyToCompare.equals(buffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated(forRemoval = true)
    public boolean equals(ByteBuffer buffer, int dataVersion, SmartContractByteCodeMapKey keyToCompare)
            throws IOException {
        return keyToCompare.equals(buffer);
    }
}
