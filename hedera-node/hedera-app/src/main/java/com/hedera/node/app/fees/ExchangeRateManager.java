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

package com.hedera.node.app.fees;

import static java.math.BigInteger.valueOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.util.FileUtilities;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.RatesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.stream.LongStream;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Parses the exchange rate information and makes it available to the workflows.
 *
 * <p>All fees in Hedera are based on the exchange rate between HBAR and USD. Fees are paid in HBAR, but based on the
 * current USD price of the HBAR. The "ERT", exchange rate tool, is responsible for tracking the exchange rate of
 * various exchanges, and updating the {@link ExchangeRateSet} on a periodic basis. Currently, this is in a special
 * file, but could be from any other source. The encoded {@link Bytes} are passed to the {@link #update(Bytes)} method.
 * This <strong>MUST</strong> be done on the same thread that this manager is used by -- the manager is not threadsafe.
 *
 * <p>The {@link ExchangeRateSet} has two rates -- a "current" rate and the "next" rate. Each rate has an expiration
 * time, in <strong>consensus</strong> seconds since the epoch. During "handle", we know the consensus time. We will
 * ask the manager for the "active" rate, which is the "current" rate if the consensus time is before the expiration
 * time of the current rate, or the "next" rate if the consensus time is after the expiration time of the current rate.
 *
 * <p>If the consensus time is after the expiration time of the "next" rate, then we simply continue to use the "next"
 * rate, since we have nothing more recent to rely on.
 */
@Singleton
public final class ExchangeRateManager {

    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100);

    private final ConfigProvider configProvider;

    private ExchangeRateInfo currentExchangeRateInfo;
    private ExchangeRateSet midnightRates;

    @Inject
    public ExchangeRateManager(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
    }

    public void init(@NonNull final HederaState state, @NonNull final Bytes bytes) {
        requireNonNull(state, "state must not be null");
        requireNonNull(bytes, "bytes must not be null");

        // First we try to read midnightRates from state
        midnightRates = state.createReadableStates(FeeService.NAME)
                .<ExchangeRateSet>getSingleton(FeeService.MIDNIGHT_RATES_STATE_KEY)
                .get();
        if (midnightRates != ExchangeRateSet.DEFAULT) {
            // midnightRates were found in state, a regular update is sufficient
            update(bytes);
        }

        // If midnightRates were not found in state, we initialize them from the file
        try {
            midnightRates = ExchangeRateSet.PROTOBUF.parse(bytes.toReadableSequentialData());
        } catch (IOException e) {
            // an error here is fatal and needs to be handled by the general initialization code
            throw new UncheckedIOException(
                    "An exception occurred while parsing the midnightRates during initialization", e);
        }
        this.currentExchangeRateInfo = new ExchangeRateInfoImpl(midnightRates);
    }

    /**
     * Updates the exchange rate information. MUST BE CALLED on the handle thread!
     *
     * @param bytes The protobuf encoded {@link ExchangeRateSet}.
     */
    public void update(@NonNull final Bytes bytes) {
        requireNonNull(bytes, "bytes must not be null");

        // Parse the exchange rate file. If we cannot parse it, we just continue with whatever our previous rate was.
        final ExchangeRateSet proposedRates;
        try {
            proposedRates = ExchangeRateSet.PROTOBUF.parse(bytes.toReadableSequentialData());
        } catch (final IOException e) {
            throw new HandleException(ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE);
        }

        // Validate mandatory fields
        if (!(proposedRates.hasCurrentRate()
                && proposedRates.currentRateOrThrow().hasExpirationTime()
                && proposedRates.hasNextRate())) {
            throw new HandleException(ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE);
        }

        // Check bounds
        // TODO: This check needs to be skipped if payer is SysAdmin or Treasury
        final var ratesConfig = configProvider.getConfiguration().getConfigData(RatesConfig.class);
        final var limitPercent = ratesConfig.intradayChangeLimitPercent();
        if (!isNormalIntradayChange(midnightRates, proposedRates, limitPercent)) {
            throw new HandleException(ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED);
        }

        // Update the current ExchangeRateInfo and eventually the midnightRates
        this.currentExchangeRateInfo = new ExchangeRateInfoImpl(proposedRates);
        // TODO: If payer is SysAdmin also update the midnightRates
    }

    public void updateMidnightRates(@NonNull final HederaState state) {
        midnightRates = currentExchangeRateInfo.exchangeRates();
        final var singleton = state.createWritableStates(FeeService.NAME)
                .<ExchangeRateSet>getSingleton(FeeService.MIDNIGHT_RATES_STATE_KEY);
        singleton.put(midnightRates);
    }

    /**
     * Gets the current {@link ExchangeRateSet}. MUST BE CALLED ON THE HANDLE THREAD!!
     * @return The current {@link ExchangeRateSet}.
     */
    @NonNull
    public ExchangeRateSet exchangeRates() {
        return currentExchangeRateInfo.exchangeRates();
    }

    /**
     * Gets the {@link ExchangeRate} that should be used as of the given consensus time. MUST BE CALLED ON THE HANDLE
     * THREAD!!
     *
     * @param consensusTime The consensus time. If after the expiration time of the current rate, the next rate will
     *                      be returned. Otherwise, the current rate will be returned.
     * @return The {@link ExchangeRate} that should be used as of the given consensus time.
     */
    @NonNull
    public ExchangeRate activeRate(@NonNull final Instant consensusTime) {
        return currentExchangeRateInfo.activeRate(consensusTime);
    }

    /**
     * Get the {@link ExchangeRateInfo} that is based on the given state.
     *
     * @param state The {@link HederaState} to use.
     * @return The {@link ExchangeRateInfo}.
     */
    @NonNull
    public ExchangeRateInfo exchangeRateInfo(@NonNull final HederaState state) {
        final var hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var shardNum = hederaConfig.shard();
        final var realmNum = hederaConfig.realm();
        final var fileNum = configProvider
                .getConfiguration()
                .getConfigData(FilesConfig.class)
                .exchangeRates();
        final var fileID = FileID.newBuilder()
                .shardNum(shardNum)
                .realmNum(realmNum)
                .fileNum(fileNum)
                .build();
        final var bytes = FileUtilities.getFileContent(state, fileID);
        final ExchangeRateSet exchangeRates;
        try {
            exchangeRates = ExchangeRateSet.PROTOBUF.parse(bytes.toReadableSequentialData());
        } catch (IOException e) {
            // This should never happen
            throw new IllegalStateException(e);
        }
        return new ExchangeRateInfoImpl(exchangeRates);
    }

    private static boolean isNormalIntradayChange(
            @NonNull final ExchangeRateSet midnightRates,
            @NonNull final ExchangeRateSet proposedRates,
            final int limitPercent) {
        return canonicalTest(
                        limitPercent,
                        midnightRates.currentRate().centEquiv(),
                        midnightRates.currentRate().hbarEquiv(),
                        proposedRates.currentRate().centEquiv(),
                        proposedRates.currentRate().hbarEquiv())
                && canonicalTest(
                        limitPercent,
                        midnightRates.nextRate().centEquiv(),
                        midnightRates.nextRate().hbarEquiv(),
                        proposedRates.nextRate().centEquiv(),
                        proposedRates.nextRate().hbarEquiv());
    }

    private static boolean canonicalTest(
            final long bound, final long oldC, final long oldH, final long newC, final long newH) {
        final var b100 = valueOf(bound).add(ONE_HUNDRED);

        final var oC = valueOf(oldC);
        final var oH = valueOf(oldH);
        final var nC = valueOf(newC);
        final var nH = valueOf(newH);

        return LongStream.of(bound, oldC, oldH, newC, newH).allMatch(i -> i > 0)
                && oC.multiply(nH)
                                .multiply(b100)
                                .subtract(nC.multiply(oH).multiply(ONE_HUNDRED))
                                .signum()
                        >= 0
                && oH.multiply(nC)
                                .multiply(b100)
                                .subtract(nH.multiply(oC).multiply(ONE_HUNDRED))
                                .signum()
                        >= 0;
    }

    /**
     * Converts tinybars to tiny cents using the exchange rate at a given time.
     *
     * @param amount The amount in tiny cents.
     * @param consensusTime The consensus time to use for the exchange rate.
     * @return The amount in tinybars.
     */
    public long getTinybarsFromTinyCents(final long amount, @NonNull final Instant consensusTime) {
        final var rate = activeRate(consensusTime);
        return getAFromB(amount, rate.hbarEquiv(), rate.centEquiv());
    }

    private static long getAFromB(final long bAmount, final int aEquiv, final int bEquiv) {
        final var aMultiplier = BigInteger.valueOf(aEquiv);
        final var bDivisor = BigInteger.valueOf(bEquiv);
        return BigInteger.valueOf(bAmount)
                .multiply(aMultiplier)
                .divide(bDivisor)
                .longValueExact();
    }
}
