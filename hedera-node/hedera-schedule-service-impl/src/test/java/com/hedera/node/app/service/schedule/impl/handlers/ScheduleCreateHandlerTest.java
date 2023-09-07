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

import static org.assertj.core.api.BDDAssertions.assertThat;
import static org.assertj.core.api.BDDAssertions.assertThatThrownBy;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.DataOneOfType;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.schedule.impl.ScheduledTransactionFactory;
import com.hedera.node.app.spi.fixtures.Assertions;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.VerificationAssistant;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import java.security.InvalidKeyException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;

class ScheduleCreateHandlerTest extends ScheduleHandlerTestBase {
    private ScheduleCreateHandler subject;
    private PreHandleContext realPreContext;

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        subject = new ScheduleCreateHandler();
        setUpBase();
    }

    @Test
    void preHandleVanilla() throws PreCheckException {
        realPreContext = new PreHandleContextImpl(
                mockStoreFactory, scheduleCreateTransaction(payer), testConfig, mockDispatcher);
        subject.preHandle(realPreContext);

        assertThat(realPreContext).isNotNull();
        assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
        assertThat(realPreContext.requiredNonPayerKeys()).isNotNull().hasSize(1);
        assertThat(realPreContext.optionalNonPayerKeys()).isNotNull().hasSize(1);

        assertThat(realPreContext.requiredNonPayerKeys()).isEqualTo(Set.of(adminKey));
        assertThat(realPreContext.optionalNonPayerKeys()).isEqualTo(Set.of(payerKey));

        assertThat(mockContext).isNotNull();
    }

    @Test
    void preHandleVanillaNoAdmin() throws PreCheckException {
        final TransactionBody transactionToTest = ScheduledTransactionFactory.scheduleCreateTransactionWith(
                null, "", payer, scheduler, Timestamp.newBuilder().seconds(1L).build());
        realPreContext = new PreHandleContextImpl(mockStoreFactory, transactionToTest, testConfig, mockDispatcher);
        subject.preHandle(realPreContext);

        assertThat(realPreContext).isNotNull();
        assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
        assertThat(realPreContext.requiredNonPayerKeys()).isNotNull().isEmpty();
        assertThat(realPreContext.optionalNonPayerKeys()).isNotNull().hasSize(1);

        assertThat(realPreContext.optionalNonPayerKeys()).isEqualTo(Set.of(payerKey));
    }

    @Test
    void preHandleUsesCreatePayerIfScheduledPayerNotSet() throws PreCheckException {
        realPreContext =
                new PreHandleContextImpl(mockStoreFactory, scheduleCreateTransaction(null), testConfig, mockDispatcher);
        subject.preHandle(realPreContext);

        assertThat(realPreContext).isNotNull();
        assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
        assertThat(realPreContext.requiredNonPayerKeys()).isNotNull().hasSize(1);
        assertThat(realPreContext.optionalNonPayerKeys()).isNotNull().isEmpty();

        assertThat(realPreContext.requiredNonPayerKeys()).isEqualTo(Set.of(adminKey));
    }

    @Test
    void preHandleMissingPayerThrowsInvalidPayer() throws PreCheckException {
        reset(accountById);
        accountsMapById.put(payer, null);

        final TransactionBody createBody = scheduleCreateTransaction(payer);
        realPreContext = new PreHandleContextImpl(mockStoreFactory, createBody, testConfig, mockDispatcher);
        Assertions.assertThrowsPreCheck(
                () -> subject.preHandle(realPreContext), ResponseCodeEnum.INVALID_SCHEDULE_PAYER_ID);
    }

    @Test
    void preHandleRejectsNonWhitelist() throws PreCheckException {
        final Set<HederaFunctionality> configuredWhitelist =
                scheduleConfig.whitelist().funtionalitySet();
        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final SchedulableTransactionBody child = next.scheduledTransaction();
            final DataOneOfType transactionType = child.data().kind();
            final HederaFunctionality functionType = HandlerUtility.functionalityForType(transactionType);
            realPreContext = new PreHandleContextImpl(mockStoreFactory, createTransaction, testConfig, mockDispatcher);
            if (configuredWhitelist.contains(functionType)) {
                subject.preHandle(realPreContext);
                assertThat(realPreContext.payerKey()).isNotNull().isEqualTo(schedulerKey);
            } else {
                Assertions.assertThrowsPreCheck(
                        () -> subject.preHandle(realPreContext),
                        ResponseCodeEnum.SCHEDULED_TRANSACTION_NOT_IN_WHITELIST);
            }
        }
    }

    // TODO: Create test for Handle with a duplicate transaction

    // TODO: Create test for pure checks method

    @Test
    void handleRejectsNonWhitelist() throws HandleException, PreCheckException {
        final Set<HederaFunctionality> configuredWhitelist =
                scheduleConfig.whitelist().funtionalitySet();
        for (final Schedule next : listOfScheduledOptions) {
            final TransactionBody createTransaction = next.originalCreateTransaction();
            final TransactionID createId = createTransaction.transactionID();
            final SchedulableTransactionBody child = next.scheduledTransaction();
            final DataOneOfType transactionType = child.data().kind();
            final HederaFunctionality functionType = HandlerUtility.functionalityForType(transactionType);
            BDDMockito.given(mockContext.body()).willReturn(createTransaction);
            BDDMockito.given(mockContext.newEntityNum())
                    .willReturn(next.scheduleId().scheduleNum());
            BDDMockito.given(mockContext.allKeysForTransaction(Mockito.any(), Mockito.any()))
                    .willReturn(testChildKeys);
            // This is how you get side-effects replicated, by having the "Answer" called in place of the real method.
            BDDMockito.given(mockContext.verificationFor(
                            BDDMockito.any(Key.class), BDDMockito.any(VerificationAssistant.class)))
                    .will(new VerificationForAnswer(testChildKeys));
            final int startCount = scheduleMapById.size();
            if (configuredWhitelist.contains(functionType)) {
                subject.handle(mockContext);
                verifyHandleSucceededForWhitelist(next, createId, startCount);
            } else {
                assertThatThrownBy(() -> subject.handle(mockContext))
                        .isInstanceOf(HandleException.class)
                        .hasFieldOrPropertyWithValue("status", ResponseCodeEnum.SCHEDULED_TRANSACTION_NOT_IN_WHITELIST);
            }
        }
    }

    private void verifyHandleSucceededForWhitelist(
            final Schedule next, final TransactionID createId, final int startCount) {
        commit(writableById); // commit changes so we can inspect the underlying map
        // should be a new schedule in the map
        assertThat(scheduleMapById.size()).isEqualTo(startCount + 1);
        // verifying that the handle really ran and created the new schedule
        final Schedule wrongSchedule = writableSchedules.get(next.scheduleId());
        assertThat(wrongSchedule).isNull(); // shard and realm *should not* match here
        // get a corrected schedule ID.
        long correctRealm = createId.accountID().realmNum();
        long correctShard = createId.accountID().shardNum();
        final ScheduleID.Builder correctedBuilder = next.scheduleId().copyBuilder();
        correctedBuilder.realmNum(correctRealm).shardNum(correctShard);
        final ScheduleID correctedId = correctedBuilder.build();
        final Schedule resultSchedule = writableSchedules.get(correctedId);
        // verify the schedule was created ready for sign transactions
        assertThat(resultSchedule).isNotNull(); // shard and realm *should* match here
        assertThat(resultSchedule.deleted()).isFalse();
        assertThat(resultSchedule.executed()).isFalse();
    }

    private TransactionBody scheduleCreateTransaction(final AccountID payer) {
        final Timestamp timestampValue =
                Timestamp.newBuilder().seconds(1_234_567L).build();
        return ScheduledTransactionFactory.scheduleCreateTransactionWith(
                adminKey, "test", payer, scheduler, timestampValue);
    }
}
