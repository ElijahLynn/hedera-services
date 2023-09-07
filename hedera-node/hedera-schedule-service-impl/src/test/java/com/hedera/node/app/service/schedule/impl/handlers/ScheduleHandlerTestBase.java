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

package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl.SCHEDULES_BY_EQUALITY_KEY;
import static com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl.SCHEDULES_BY_EXPIRY_SEC_KEY;
import static com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl.SCHEDULES_BY_ID_KEY;
import static com.hedera.node.app.signature.impl.SignatureVerificationImpl.failedVerification;
import static com.hedera.node.app.signature.impl.SignatureVerificationImpl.passedVerification;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.file.FileDeleteTransactionBody;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.file.SystemDeleteTransactionBody;
import com.hedera.hapi.node.file.SystemUndeleteTransactionBody;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.Builder;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.token.TokenDissociateTransactionBody;
import com.hedera.hapi.node.token.TokenFeeScheduleUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenFreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenGrantKycTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.token.TokenPauseTransactionBody;
import com.hedera.hapi.node.token.TokenRevokeKycTransactionBody;
import com.hedera.hapi.node.token.TokenUnfreezeAccountTransactionBody;
import com.hedera.hapi.node.token.TokenUnpauseTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.token.TokenWipeAccountTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.UtilPrngTransactionBody;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.ScheduleRecordBuilder;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.schedule.impl.ReadableScheduleStoreImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleTestBase;
import com.hedera.node.app.service.schedule.impl.WritableScheduleStoreImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fixtures.state.MapReadableStates;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableStates;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.state.ReadableKVState;
import com.hedera.node.app.spi.state.ReadableKVStateBase;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableKVState;
import com.hedera.node.app.spi.state.WritableKVStateBase;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.TransactionKeys;
import com.hedera.node.app.spi.workflows.VerificationAssistant;
import com.hedera.node.app.workflows.dispatcher.ReadableStoreFactory;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.record.SingleTransactionRecordBuilderImpl;
import com.hedera.node.app.workflows.handle.validation.AttributeValidatorImpl;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.InvalidKeyException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.assertj.core.api.BDDAssertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

// TODO: Make this extend ScheduleTestBase in the enclosing package
@SuppressWarnings("ProtectedField")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.WARN)
class ScheduleHandlerTestBase extends ScheduleTestBase  {
    @Mock(strictness = Mock.Strictness.LENIENT)
    protected TransactionDispatcher mockDispatcher;

    @Mock(strictness = Mock.Strictness.LENIENT)
    protected HandleContext mockContext;

    protected final TransactionKeys testChildKeys =
            createChildKeys(adminKey, schedulerKey, payerKey, optionKey, otherKey);

    protected void setUpBase() throws PreCheckException, InvalidKeyException {
        super.setUpBase();
        setUpContext();
    }

    @SuppressWarnings("unchecked")
    private void setUpContext() {
        given(mockContext.configuration()).willReturn(testConfig);
        given(mockContext.consensusNow()).willReturn(testConsensusTime);
        given(mockContext.attributeValidator()).willReturn(new AttributeValidatorImpl(mockContext));
        given(mockContext.payer()).willReturn(payer);
        given(mockContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(mockContext.readableStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        given(mockContext.writableStore(WritableScheduleStore.class)).willReturn(writableSchedules);
        given(mockContext.verificationFor(eq(payerKey), any())).willReturn(passedVerification(payerKey));
        given(mockContext.verificationFor(eq(adminKey), any())).willReturn(passedVerification(adminKey));
        given(mockContext.verificationFor(eq(schedulerKey), any())).willReturn(failedVerification(schedulerKey));
        given(mockContext.verificationFor(eq(optionKey), any())).willReturn(failedVerification(optionKey));
        given(mockContext.verificationFor(eq(otherKey), any())).willReturn(failedVerification(otherKey));
        given(mockContext.dispatchChildTransaction(
                        any(), eq(ScheduleRecordBuilder.class), any(Predicate.class)))
                .willReturn(new SingleTransactionRecordBuilderImpl(testConsensusTime));
    }

    private static TransactionKeys createChildKeys(
            final Key payerKey, final Key adminKey, final Key schedulerKey, final Key... optionalKeys) {
        return new TestTransactionKeys(payerKey, Set.of(adminKey, schedulerKey), Set.of(optionalKeys));
    }

    // This provides Mock answers for Context code.  In order to actually test the Handler code, however, this
    // class MUST call the callback for each key, and generate an success/failure based on whether the key is in
    // the required (success) or optional (failure) set in the TransactionKeys provided to the constructor.
    // Not calling the callback, passing a different key, or not responding with a correct Verification could
    // cause incorrect test results and permit errors to pass testing.
    protected static final class VerificationForAnswer implements Answer<SignatureVerification> {
        private static final String TYPE_FAIL_MESSAGE = "Incorrect Argument type, expected %s but got %s";
        private final TransactionKeys keysForTransaction;

        VerificationForAnswer(TransactionKeys testKeys) {
            keysForTransaction = testKeys;
        }

        @Override
        public SignatureVerification answer(final InvocationOnMock invocation) {
            final SignatureVerification result;
            final Object[] arguments = invocation.getArguments();
            if (arguments.length != 2) {
                result = null;
                BDDAssertions.fail("Incorrect Argument count, expected 2 but got %d".formatted(arguments.length));
            } else if (arguments[0] instanceof Key keyToTest) {
                if (arguments[1] instanceof VerificationAssistant callback) {
                    if (keysForTransaction.requiredNonPayerKeys().contains(keyToTest)) {
                        result = new SignatureVerificationImpl(keyToTest, null, true);
                        callback.test(keyToTest, result);
                    } else {
                        result = new SignatureVerificationImpl(keyToTest, null, false);
                        callback.test(keyToTest, new SignatureVerificationImpl(keyToTest, null, false));
                    }
                } else {
                    result = null;
                    // Spotless forces this layout, because it mangles ternaries early
                    final String actualType;
                    if (arguments[1] == null) actualType = "null";
                    else actualType = arguments[1].getClass().getCanonicalName();
                    BDDAssertions.fail(TYPE_FAIL_MESSAGE.formatted("VerificationAssistant", actualType));
                }
            } else {
                result = null;
                // just barely short enough to avoid spotless mangling
                final String actualType =
                        arguments[0] == null ? "null" : arguments[0].getClass().getCanonicalName();
                BDDAssertions.fail(TYPE_FAIL_MESSAGE.formatted("Key", actualType));
            }
            return result;
        }
    }
}
