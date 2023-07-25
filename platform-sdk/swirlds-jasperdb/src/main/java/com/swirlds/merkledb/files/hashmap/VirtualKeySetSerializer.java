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

package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.common.units.UnitConstants.BYTES_PER_LONG;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.merkledb.serialize.AbstractFixedSizeKeySerializer;
import com.swirlds.virtualmap.VirtualLongKey;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A key serializer used by {@link HalfDiskVirtualKeySet} when MerkleDb is operating in long key
 * mode. This key serializer only implements methods require to serialize a long key, and is not a
 * general purpose key serializer.
 */
@ConstructableIgnored
public class VirtualKeySetSerializer extends AbstractFixedSizeKeySerializer<VirtualLongKey> {

    public VirtualKeySetSerializer() {
        // Class ID / version aren't used for this class
        super(0, 0, BYTES_PER_LONG, 0);
    }

    @Override
    public void serialize(VirtualLongKey data, WritableSequentialData out) {
        out.writeLong(data.getKeyAsLong());
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated(forRemoval = true)
    public int serialize(final VirtualLongKey data, final ByteBuffer buffer) throws IOException {
        buffer.putLong(data.getKeyAsLong());
        return BYTES_PER_LONG;
    }

    @Override
    public VirtualLongKey deserialize(ReadableSequentialData in) {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated(forRemoval = true)
    public VirtualLongKey deserialize(final ByteBuffer buffer, final long dataVersion) throws IOException {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(final BufferedData buffer, final VirtualLongKey keyToCompare) throws IOException {
        return buffer.readLong() == keyToCompare.getKeyAsLong();
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated(forRemoval = true)
    public boolean equals(final ByteBuffer buffer, final int dataVersion, final VirtualLongKey keyToCompare)
            throws IOException {
        return buffer.getLong() == keyToCompare.getKeyAsLong();
    }
}
