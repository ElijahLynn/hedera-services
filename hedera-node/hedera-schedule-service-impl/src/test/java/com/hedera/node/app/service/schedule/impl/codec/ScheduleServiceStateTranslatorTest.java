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

package com.hedera.node.app.service.schedule.impl.codec;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.BDDAssertions.assertThat;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.service.mono.pbj.PbjConverter;
import com.hedera.node.app.service.mono.state.virtual.EntityNumVirtualKey;
import com.hedera.node.app.service.mono.state.virtual.schedule.ScheduleVirtualValue;
import com.hedera.node.app.service.schedule.impl.ReadableScheduleStoreImpl;
import com.hedera.node.app.service.schedule.impl.ScheduleTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.Codec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceStateTranslatorTest extends ScheduleTestBase {
    private static final long FEE = 123L;
    private final byte[] payerKeyBytes = requireNonNull(payerKey.ed25519()).toByteArray();
    private final byte[] schedulerKeyBytes =
            requireNonNull(schedulerKey.ed25519()).toByteArray();
    private final byte[] adminKeyBytes = requireNonNull(adminKey.ed25519()).toByteArray();

    // Non-Mock objects that require constructor initialization to avoid unnecessary statics
    private com.hederahashgraph.api.proto.java.TransactionID protoTransactionId;
    private com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody protoCreateBody;
    private com.hederahashgraph.api.proto.java.TransactionBody protoTransaction;
    private EntityNumVirtualKey protoKey;
    private byte[] protoBodyBytes;

    private List<Key> signatoryList;
    private Schedule testValue;

    // Non-Mock objects, but may contain or reference mock objects.
    private ScheduleVirtualValue subject;

    @BeforeEach
    void setup() throws PreCheckException, InvalidKeyException {
        setUpBase();
        signatoryList = List.of(adminKey, schedulerKey, payerKey);
        protoKey = new EntityNumVirtualKey(scheduleInState.scheduleId().scheduleNum());
        protoCreateBody = getCreateTransactionBody(scheduleInState);
        protoTransactionId = getParentTransactionId(testValidStart, scheduler);
        protoTransaction = getParentTransaction(protoTransactionId, protoCreateBody);
        protoBodyBytes = protoTransaction.toByteArray();
        subject = ScheduleVirtualValue.from(protoBodyBytes, expirationTime.seconds());
        subject.setKey(protoKey);
        subject.witnessValidSignature(payerKeyBytes);
        subject.witnessValidSignature(schedulerKeyBytes);
        subject.witnessValidSignature(adminKeyBytes);
        testValue = scheduleInState.copyBuilder().signatories(signatoryList).build();
    }

    @Test
    void verifyTypicalForwardTranslation() throws IOException {
        assertThat(testValue.memo()).isEqualTo(subject.memo().get());
        final ScheduleVirtualValue scheduleVirtualValue = ScheduleServiceStateTranslator.pbjToState(testValue);

        assertThat(scheduleVirtualValue).isNotNull();
        final List<byte[]> actualSignatories = scheduleVirtualValue.signatories();
        final List<byte[]> expectedSignatories = subject.signatories();
        assertThat(actualSignatories).isNotNull();
        assertThat(actualSignatories).containsExactlyInAnyOrderElementsOf(expectedSignatories);
        assertThat(scheduleVirtualValue.memo()).isEqualTo(subject.memo());
        assertThat(scheduleVirtualValue.isDeleted()).isEqualTo(subject.isDeleted());
        assertThat(scheduleVirtualValue.isExecuted()).isEqualTo(subject.isExecuted());
        assertThat(scheduleVirtualValue.adminKey()).isEqualTo(subject.adminKey());
        assertThat(scheduleVirtualValue.waitForExpiryProvided()).isEqualTo(subject.waitForExpiryProvided());
        assertThat(scheduleVirtualValue.payer()).isEqualTo(subject.payer());
        assertThat(scheduleVirtualValue.schedulingAccount()).isEqualTo(subject.schedulingAccount());
        assertThat(scheduleVirtualValue.schedulingTXValidStart()).isEqualTo(subject.schedulingTXValidStart());
        assertThat(scheduleVirtualValue.expirationTimeProvided()).isEqualTo(subject.expirationTimeProvided());
        assertThat(scheduleVirtualValue.calculatedExpirationTime()).isEqualTo(subject.calculatedExpirationTime());
        assertThat(scheduleVirtualValue.getResolutionTime()).isEqualTo(subject.getResolutionTime());
        assertThat(scheduleVirtualValue.ordinaryViewOfScheduledTxn()).isEqualTo(subject.ordinaryViewOfScheduledTxn());
        assertThat(scheduleVirtualValue.scheduledTxn()).isEqualTo(subject.scheduledTxn());
        assertThat(scheduleVirtualValue.bodyBytes()).containsExactly(subject.bodyBytes());
    }

    @Test
    void verifyTypicalReverseTranslation() throws IOException {
        final Codec<SchedulableTransactionBody> protobufCodec = SchedulableTransactionBody.PROTOBUF;
        final Schedule schedule = ScheduleServiceStateTranslator.convertScheduleVirtualValueToSchedule(subject);
        final Schedule expected = testValue;

        assertThat(schedule).isNotNull();
        final List<Key> expectedSignatories = expected.signatories();
        final List<Key> actualSignatories = schedule.signatories();
        assertThat(actualSignatories).isNotNull();
        assertThat(expectedSignatories).containsExactlyInAnyOrderElementsOf(actualSignatories);
        assertThat(expected.memo()).isEqualTo(schedule.memo());
        assertThat(expected.deleted()).isEqualTo(schedule.deleted());
        assertThat(expected.executed()).isEqualTo(schedule.executed());
        assertThat(expected.adminKey()).isEqualTo(schedule.adminKey());
        assertThat(expected.waitForExpiry()).isEqualTo(schedule.waitForExpiry());
        assertThat(expected.payerAccountId()).isEqualTo(schedule.payerAccountId());
        assertThat(expected.schedulerAccountId()).isEqualTo(schedule.schedulerAccountId());
        assertThat(expected.scheduleValidStart()).isEqualTo(schedule.scheduleValidStart());
        assertThat(expected.providedExpirationSecond()).isEqualTo(schedule.providedExpirationSecond());
        assertThat(expected.calculatedExpirationSecond()).isEqualTo(schedule.calculatedExpirationSecond());
        assertThat(expected.resolutionTime()).isEqualTo(schedule.resolutionTime());
        assertThat(expected.originalCreateTransaction()).isEqualTo(schedule.originalCreateTransaction());
        assertThat(expected.scheduledTransaction()).isNotNull();
        assertThat(schedule.scheduledTransaction()).isNotNull();
        final byte[] expectedBytes = PbjConverter.asBytes(protobufCodec, expected.scheduledTransaction());
        final byte[] actualBytes = PbjConverter.asBytes(protobufCodec, schedule.scheduledTransaction());
        assertThat(expectedBytes).containsExactly(actualBytes);
    }

    @Test
    void verifyReverseTranslationOfLookupById() throws InvalidKeyException {
        scheduleStore = new ReadableScheduleStoreImpl(states);

        final ScheduleVirtualValue scheduleVirtualValue =
                ScheduleServiceStateTranslator.pbjToState(testScheduleID, scheduleStore);
        assertThat(scheduleVirtualValue).isNotNull();
        final List<byte[]> expectedSignatories = scheduleVirtualValue.signatories();
        final List<byte[]> actualSignatories = subject.signatories();
        assertThat(actualSignatories).isNotNull();
        assertThat(expectedSignatories).containsExactlyInAnyOrderElementsOf(actualSignatories);
        assertThat(scheduleVirtualValue.memo()).isEqualTo(subject.memo());
        assertThat(scheduleVirtualValue.isDeleted()).isEqualTo(subject.isDeleted());
        assertThat(scheduleVirtualValue.isExecuted()).isEqualTo(subject.isExecuted());
        assertThat(scheduleVirtualValue.adminKey()).isEqualTo(subject.adminKey());
        assertThat(scheduleVirtualValue.waitForExpiryProvided()).isEqualTo(subject.waitForExpiryProvided());
        assertThat(scheduleVirtualValue.payer()).isEqualTo(subject.payer());
        assertThat(scheduleVirtualValue.schedulingAccount()).isEqualTo(subject.schedulingAccount());
        assertThat(scheduleVirtualValue.schedulingTXValidStart()).isEqualTo(subject.schedulingTXValidStart());
        assertThat(scheduleVirtualValue.expirationTimeProvided()).isEqualTo(subject.expirationTimeProvided());
        assertThat(scheduleVirtualValue.calculatedExpirationTime()).isEqualTo(subject.calculatedExpirationTime());
        assertThat(scheduleVirtualValue.getResolutionTime()).isEqualTo(subject.getResolutionTime());
        assertThat(scheduleVirtualValue.ordinaryViewOfScheduledTxn()).isEqualTo(subject.ordinaryViewOfScheduledTxn());
        assertThat(scheduleVirtualValue.scheduledTxn()).isEqualTo(subject.scheduledTxn());
        assertThat(scheduleVirtualValue.bodyBytes()).containsExactly(subject.bodyBytes());
    }

    // TODO: Create a test with deleted and executed schedules.

    @NonNull
    private com.hederahashgraph.api.proto.java.TransactionID getParentTransactionId(
            final Timestamp validStart,
            final AccountID schedulerId) {
        return com.hederahashgraph.api.proto.java.TransactionID.newBuilder()
                .setTransactionValidStart(PbjConverter.fromPbj(validStart))
                .setNonce(4444)
                .setAccountID(PbjConverter.fromPbj(schedulerId))
                .build();
    }

    @NonNull
    private com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody getCreateTransactionBody(
            final Schedule baseValue) {
        return PbjConverter.fromPbj(baseValue.originalCreateTransaction()).getScheduleCreate();
    }

    @NonNull
    private com.hederahashgraph.api.proto.java.TransactionBody getParentTransaction(
            final com.hederahashgraph.api.proto.java.TransactionID transactionId,
            final com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody createBody) {
        return com.hederahashgraph.api.proto.java.TransactionBody.newBuilder()
                .setTransactionID(transactionId)
                .setScheduleCreate(createBody)
                .build();
    }
}
