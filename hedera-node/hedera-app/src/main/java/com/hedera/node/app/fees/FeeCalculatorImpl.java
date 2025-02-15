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

import static com.hedera.node.app.service.mono.pbj.PbjConverter.fromPbj;
import static com.hedera.node.app.spi.HapiUtils.countOfCryptographicKeys;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.calc.OverflowCheckingCalc;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.Function;

/**
 * Implements a {@link FeeCalculator} based on the "hapi-fees" and "hapi-utils". Since those modules have not been
 * converted to PBJ (and likely never will be), this implementation has to convert from PBJ objects to protobuf
 * objects. After the initial modular release, we will revisit the fee system and create a new implementation that
 * is much simpler and more efficient, based on the "base cost + upgrades" model, rather than the current "usage
 * and conversion" model.
 */
public class FeeCalculatorImpl implements FeeCalculator {
    /** From 'hapi-fees', accumulates the usage (rbt, sbt, etc.) for the transaction. */
    private final UsageAccumulator usage;
    /** The current Google Protobuf representation of the fee data. */
    private final com.hederahashgraph.api.proto.java.FeeData feeData;
    /** The current Google Protobuf representation of the current exchange rate */
    private final com.hederahashgraph.api.proto.java.ExchangeRate currentRate;
    /** The basic info from parsing the transaction */
    private final SigUsage sigUsage;

    /**
     * Create a new instance. One is created per transaction.
     *
     * @param txInfo           The parsed information about the transaction. Pricing includes the number of bytes
     *                         included in the transaction body memo, as well as the protobuf-encoded number of
     *                         bytes that form the signature map. We also do a little skullduggery by inspecting
     *                         the transaction type to see if it is a crypto transfer, and extracting the number of
     *                         transfers the user sent to use. We need this, because the {@link BaseTransactionMeta}
     *                         needs it.
     * @param payerKey         The key of the payer. Used to compute the number of cryptographic keys that the payer
     *                         has on this key, so we can charge for each of those.
     * @param numVerifications The number of cryptographic signatures that were verified for this transaction. We only
     *                         know this answer after pre-handle has run.
     * @param feeData          The fee data associated with this transaction and its subtype.
     * @param currentRate      The current HBAR-to-USD exchange rate.
     */
    public FeeCalculatorImpl(
            @NonNull TransactionInfo txInfo,
            @NonNull Key payerKey,
            final int numVerifications,
            @NonNull final FeeData feeData,
            @NonNull final ExchangeRate currentRate) {
        //  Perform basic validations, and convert the PBJ objects to Google protobuf objects for `hapi-fees`.
        requireNonNull(txInfo);
        requireNonNull(payerKey);
        this.feeData = fromPbj(feeData);
        this.currentRate = fromPbj(currentRate);
        if (numVerifications < 0) {
            throw new IllegalArgumentException("numVerifications must be >= 0");
        }

        // Create the "SigUsage" object, used by the "hapi-fees" module.
        final var txBody = txInfo.txBody();
        sigUsage = new SigUsage(
                numVerifications,
                SignatureMap.PROTOBUF.measureRecord(txInfo.signatureMap()),
                countOfCryptographicKeys(payerKey));

        // Create the "BaseTransactionMeta" object, used by the "hapi-fees" module. This object is not entirely
        // modularity friendly, because it wants to know the number of transfers in a crypto transfer, which is
        // not something we really should know about here. But, since we're going to replace the fee system later
        // with a simpler model, for now, we'll go ahead and check the transaction body type here.
        final var baseMeta = new BaseTransactionMeta(
                txBody.memo().getBytes(StandardCharsets.UTF_8).length, // Has to be a faster way...
                txBody.data().kind() == TransactionBody.DataOneOfType.CRYPTO_TRANSFER
                        ? ((CryptoTransferTransactionBody) txBody.data().as())
                                .transfersOrElse(TransferList.DEFAULT)
                                .accountAmountsOrElse(Collections.emptyList())
                                .size()
                        : 0);

        // Create the "UsageAccumulator" object, which we wil use in all the different builder methods of this
        // class to record usage (bpt, rbs, sbs, etc.) for the transaction.
        this.usage = UsageAccumulator.fromGrpc(this.feeData);
        usage.resetForTransaction(baseMeta, sigUsage);
    }

    @Override
    @NonNull
    public FeeCalculator withResourceUsagePercent(double percent) {
        return this;
    }

    @Override
    @NonNull
    public FeeCalculator addBytesPerTransaction(long bytes) {
        usage.addBpt(bytes);
        return this;
    }

    @NonNull
    @Override
    public FeeCalculator addNetworkRamByteSeconds(long amount) {
        usage.addNetworkRbs(amount);
        return this;
    }

    @NonNull
    public FeeCalculator addRamByteSeconds(long amount) {
        usage.addRbs(amount);
        return this;
    }

    @NonNull
    @Override
    public FeeCalculator addStorageBytesSeconds(long seconds) {
        usage.addSbs(seconds);
        return this;
    }

    @NonNull
    @Override
    public Fees legacyCalculate(@NonNull Function<SigValueObj, com.hederahashgraph.api.proto.java.FeeData> callback) {
        final var sigValueObject = new SigValueObj(sigUsage.numSigs(), sigUsage.numPayerKeys(), sigUsage.sigsSize());
        final var matrix = callback.apply(sigValueObject);
        final var feeObject = FeeBuilder.getFeeObject(feeData, matrix, currentRate, 1);
        return new Fees(feeObject.nodeFee(), feeObject.networkFee(), feeObject.serviceFee());
    }

    @Override
    @NonNull
    public Fees calculate() {
        // Use the "hapi-fees" module to calculate the fees, and convert to one of our "Fees" objects.
        final var overflowCalc = new OverflowCheckingCalc();
        final var feeObject = overflowCalc.fees(usage, feeData, currentRate, 1);
        return new Fees(feeObject.nodeFee(), feeObject.networkFee(), feeObject.serviceFee());
    }
}
