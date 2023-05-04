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

package com.swirlds.platform.event.tipset;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.function.IntToLongFunction;
import java.util.function.LongToIntFunction;

/**
 * Represents a slice of the hashgraph, containing one "tip" from each event creator.
 */
public class Tipset {

    /**
     * Maps node ID to node index.
     */
    private final LongToIntFunction nodeIdToIndex;

    /**
     * Maps node index to consensus weight.
     */
    private final IntToLongFunction indexToWeight;

    /**
     * The tip generations.
     */
    private final long[] tips;

    /**
     * Create an empty tipset.
     *
     * @param nodeCount     the number of nodes in the address book
     * @param nodeIdToIndex maps node ID to node index
     * @param indexToWeight maps node index to consensus weight
     */
    public Tipset(
            final int nodeCount,
            @NonNull final LongToIntFunction nodeIdToIndex,
            @NonNull final IntToLongFunction indexToWeight) {
        this.nodeIdToIndex = nodeIdToIndex;
        this.indexToWeight = indexToWeight;
        this.tips = new long[nodeCount];
    }

    /**
     * Build an empty tipset (i.e. where all tip weights are 0) using another tipset as a template.
     *
     * @param tipset the tipset to use as a template
     * @return a new empty tipset
     */
    private static @NonNull Tipset buildEmptyTipset(@NonNull final Tipset tipset) {
        return new Tipset(tipset.size(), tipset.nodeIdToIndex, tipset.indexToWeight);
    }

    /**
     * <p>
     * Merge a list of tipsets together.
     * </p>
     *
     * <p>
     * The resulting tipset will contain the union of the node IDs of all source tipsets. The generation for each node
     * ID will be equal to the maximum generation found for that node ID from all source tipsets. If a tipset does not
     * contain a generation for a node ID, the generation for that node ID is considered to be 0.
     * </p>
     *
     * @param tipsets the tipsets to merge, must be non-empty, tipsets must be constructed from the same address book or
     *                else this method has undefined behavior
     * @return a new tipset
     */
    public static @NonNull Tipset merge(@NonNull final List<Tipset> tipsets) {
        Objects.requireNonNull(tipsets, "tipsets must not be null");
        if (tipsets.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge an empty list of tipsets");
        }

        final int length = tipsets.get(0).tips.length;
        final Tipset newTipset = buildEmptyTipset(tipsets.get(0));

        for (int index = 0; index < length; index++) {
            long max = 0;
            for (final Tipset tipSet : tipsets) {
                max = Math.max(max, tipSet.tips[index]);
            }
            newTipset.tips[index] = max;
        }

        return newTipset;
    }

    /**
     * Get the tip generation for a given node ID.
     *
     * @param nodeId the node ID in question
     * @return the tip generation for the node ID
     */
    public long getTipGenerationForNodeId(final long nodeId) {
        return tips[nodeIdToIndex.applyAsInt(nodeId)];
    }

    /**
     * Get the tip generation for a given node index. Returns 0 if this node has not been added to the tipset.
     *
     * @param index the index of the node in question
     * @return the tip generation for the node index
     */
    public long getTipGenerationForNodeIndex(final int index) { // TODO test
        return tips[index];
    }

    /**
     * Get the number of tips currently being tracked.
     *
     * @return the number of tips
     */
    public int size() {
        return tips.length;
    }

    /**
     * Advance a single tip within the tipset.
     *
     * @return this object
     */
    public @NonNull Tipset advance(final long creatorId, final long generation) {
        final int index = nodeIdToIndex.applyAsInt(creatorId);
        tips[index] = Math.max(tips[index], generation);
        return this;
    }

    /**
     * <p>
     * Get the number of tip advancements, weighted using the provided function, between this tipset and another
     * tipset.
     * </p>
     *
     * <p>
     * A tip advancement is defined as an increase in the tip generation for a node ID. The exception to this rule is
     * that an increase in generation for the target node ID is never counted as a tip advancement. The tip advancement
     * count is defined as the sum of all tip advancements after being appropriately weighted.
     * </p>
     *
     * @param nodeId compute the advancement count relative to this node ID
     * @param that   the tipset to compare to
     * @return the number of tip advancements to get from this tipset to that tipset
     */
    public long getWeightedAdvancementCount(final long nodeId, @NonNull final Tipset that) {
        long count = 0;

        final int selfIndex = nodeIdToIndex.applyAsInt(nodeId);
        for (int index = 0; index < tips.length; index++) {
            if (index == selfIndex) {
                // We don't consider self advancement here, since self advancement does nothing to help consensus.
                continue;
            }

            if (this.tips[index] < that.tips[index]) {
                count += indexToWeight.applyAsLong(index);
            }
        }

        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("(");
        for (int index = 0; index < tips.length; index++) {
            sb.append(index).append(":").append(tips[index]);
            if (index < tips.length - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
