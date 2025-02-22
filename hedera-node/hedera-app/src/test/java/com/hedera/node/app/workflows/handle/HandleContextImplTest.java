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

package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.HapiUtils.functionOf;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.records.CryptoCreateRecordBuilder;
import com.hedera.node.app.services.ServiceScopeLookup;
import com.hedera.node.app.spi.UnknownHederaFunctionality;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableStates;
import com.hedera.node.app.spi.fixtures.state.StateTestBase;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableSingletonState;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.record.SingleTransactionRecordBuilder;
import com.hedera.node.app.state.HederaState;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.record.RecordListBuilder;
import com.hedera.node.app.workflows.handle.record.SingleTransactionRecordBuilderImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.verifier.HandleContextVerifier;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings("JUnitMalformedDeclaration")
@ExtendWith(MockitoExtension.class)
class HandleContextImplTest extends StateTestBase implements Scenarios {

    private static final Configuration DEFAULT_CONFIGURATION = HederaTestConfigBuilder.createConfig();

    @Mock
    private SingleTransactionRecordBuilderImpl recordBuilder;

    @Mock(strictness = LENIENT)
    private SavepointStackImpl stack;

    @Mock
    private HandleContextVerifier verifier;

    @Mock(strictness = LENIENT)
    private RecordListBuilder recordListBuilder;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private TransactionChecker checker;

    @Mock(strictness = Strictness.LENIENT)
    private TransactionDispatcher dispatcher;

    @Mock(strictness = Strictness.LENIENT)
    private ServiceScopeLookup serviceScopeLookup;

    @Mock
    private BlockRecordInfo blockRecordInfo;

    @Mock
    private RecordCache recordCache;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private Instant consensusNow;

    @BeforeEach
    void setup() {
        when(serviceScopeLookup.getServiceName(any())).thenReturn(TokenService.NAME);
    }

    private static TransactionBody defaultTransactionBody() {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ALICE.accountID()))
                .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
                .build();
    }

    private HandleContextImpl createContext(TransactionBody txBody) {
        HederaFunctionality function;
        try {
            function = functionOf(txBody);
        } catch (UnknownHederaFunctionality e) {
            throw new RuntimeException(e);
        }
        final var txInfo =
                new TransactionInfo(Transaction.DEFAULT, txBody, SignatureMap.DEFAULT, Bytes.EMPTY, function);

        return new HandleContextImpl(
                txBody,
                txInfo,
                ALICE.accountID(),
                ALICE.account().keyOrThrow(),
                networkInfo,
                TransactionCategory.USER,
                recordBuilder,
                stack,
                DEFAULT_CONFIGURATION,
                verifier,
                recordListBuilder,
                checker,
                dispatcher,
                serviceScopeLookup,
                blockRecordInfo,
                recordCache,
                feeManager,
                exchangeRateManager,
                consensusNow);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithInvalidArguments() {
        final var txInfo = new TransactionInfo(
                Transaction.DEFAULT,
                defaultTransactionBody(),
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                HederaFunctionality.CRYPTO_TRANSFER);
        final var allArgs = new Object[] {
            txInfo.txBody(),
            txInfo,
            ALICE.accountID(),
            ALICE.account().keyOrThrow(),
            networkInfo,
            TransactionCategory.USER,
            recordBuilder,
            stack,
            DEFAULT_CONFIGURATION,
            verifier,
            recordListBuilder,
            checker,
            dispatcher,
            serviceScopeLookup,
            blockRecordInfo,
            recordCache,
            feeManager,
            exchangeRateManager,
            consensusNow
        };

        final var constructor = HandleContextImpl.class.getConstructors()[0];
        for (int i = 0; i < allArgs.length; i++) {
            final var index = i;
            // Skip transactionID and payerKey, they are optional
            if (index == 1 || index == 3) {
                continue;
            }
            assertThatThrownBy(() -> {
                        final var argsWithNull = Arrays.copyOf(allArgs, allArgs.length);
                        argsWithNull[index] = null;
                        constructor.newInstance(argsWithNull);
                    })
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Handling new EntityNumber")
    final class EntityIdNumTest {

        @Mock
        private WritableStates writableStates;

        @Mock
        private WritableSingletonState<EntityNumber> entityNumberState;

        private HandleContext handleContext;

        @BeforeEach
        void setUp() {
            final var payer = ALICE.accountID();
            final var payerKey = ALICE.account().keyOrThrow();
            when(writableStates.<EntityNumber>getSingleton(anyString())).thenReturn(entityNumberState);
            when(stack.createWritableStates(EntityIdService.NAME)).thenReturn(writableStates);
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());
            final var txInfo = new TransactionInfo(
                    Transaction.DEFAULT,
                    defaultTransactionBody(),
                    SignatureMap.DEFAULT,
                    Bytes.EMPTY,
                    HederaFunctionality.CRYPTO_TRANSFER);
            handleContext = new HandleContextImpl(
                    txInfo.txBody(),
                    txInfo,
                    payer,
                    payerKey,
                    networkInfo,
                    TransactionCategory.USER,
                    recordBuilder,
                    stack,
                    DEFAULT_CONFIGURATION,
                    verifier,
                    recordListBuilder,
                    checker,
                    dispatcher,
                    serviceScopeLookup,
                    blockRecordInfo,
                    recordCache,
                    feeManager,
                    exchangeRateManager,
                    consensusNow);
        }

        @Test
        void testNewEntityNumWithInitialState() {
            // when
            final var actual = handleContext.newEntityNum();

            // then
            assertThat(actual).isEqualTo(1L);
            verify(entityNumberState).get();
            verify(entityNumberState).put(EntityNumber.newBuilder().number(1L).build());
        }

        @Test
        void testPeekingAtNewEntityNumWithInitialState() {
            // when
            final var actual = handleContext.peekAtNewEntityNum();

            // then
            assertThat(actual).isEqualTo(1L);
            verify(entityNumberState).get();
            verify(entityNumberState, never()).put(any());
        }

        @Test
        void testNewEntityNum() {
            // given
            when(entityNumberState.get())
                    .thenReturn(EntityNumber.newBuilder().number(42L).build());

            // when
            final var actual = handleContext.newEntityNum();

            // then
            assertThat(actual).isEqualTo(43L);
            verify(entityNumberState).get();
            verify(entityNumberState).put(EntityNumber.newBuilder().number(43L).build());
        }

        @Test
        void testPeekingAtNewEntityNum() {
            // given
            when(entityNumberState.get())
                    .thenReturn(EntityNumber.newBuilder().number(42L).build());

            // when
            final var actual = handleContext.peekAtNewEntityNum();

            // then
            assertThat(actual).isEqualTo(43L);
            verify(entityNumberState).get();
            verify(entityNumberState, never()).put(any());
        }
    }

    @Nested
    @DisplayName("Handling of transaction data")
    final class TransactionDataTest {
        @BeforeEach
        void setUp() {
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());
        }

        @Test
        void testGetBody() {
            // given
            final var txBody = defaultTransactionBody();
            final var context = createContext(txBody);

            // when
            final var actual = context.body();

            // then
            assertThat(actual).isEqualTo(txBody);
        }
    }

    @Nested
    @DisplayName("Handling of stack data")
    final class StackDataTest {

        @BeforeEach
        void setUp() {
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());
        }

        @Test
        void testGetStack() {
            // given
            final var context = createContext(defaultTransactionBody());

            // when
            final var actual = context.savepointStack();

            // then
            assertThat(actual).isEqualTo(stack);
        }

        @Test
        void testCreateReadableStore(@Mock ReadableStates readableStates) {
            // given
            when(stack.createReadableStates(TokenService.NAME)).thenReturn(readableStates);
            final var context = createContext(defaultTransactionBody());

            // when
            final var store = context.readableStore(ReadableAccountStore.class);

            // then
            assertThat(store).isNotNull();
        }

        @Test
        void testCreateWritableStore(@Mock WritableStates writableStates) {
            // given
            when(stack.createWritableStates(TokenService.NAME)).thenReturn(writableStates);
            final var context = createContext(defaultTransactionBody());

            // when
            final var store = context.writableStore(WritableAccountStore.class);

            // then
            assertThat(store).isNotNull();
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testCreateStoreWithInvalidParameters() {
            // given
            final var context = createContext(defaultTransactionBody());

            // then
            assertThatThrownBy(() -> context.readableStore(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.readableStore(List.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.writableStore(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.writableStore(List.class)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Handling of verification data")
    final class VerificationDataTest {
        @BeforeEach
        void setUp() {
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testVerificationForWithInvalidParameters() {
            // given
            final var context = createContext(defaultTransactionBody());

            // then
            assertThatThrownBy(() -> context.verificationFor((Key) null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.verificationFor((Bytes) null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void testVerificationForKey(@Mock SignatureVerification verification) {
            // given
            when(verifier.verificationFor(Key.DEFAULT)).thenReturn(verification);
            final var context = createContext(defaultTransactionBody());

            // when
            final var actual = context.verificationFor(Key.DEFAULT);

            // then
            assertThat(actual).isEqualTo(verification);
        }

        @Test
        void testVerificationForAlias(@Mock SignatureVerification verification) {
            // given
            when(verifier.verificationFor(ERIN.account().alias())).thenReturn(verification);
            final var context = createContext(defaultTransactionBody());

            // when
            final var actual = context.verificationFor(ERIN.account().alias());

            // then
            assertThat(actual).isEqualTo(verification);
        }
    }

    @Nested
    @DisplayName("Requesting keys of child transactions")
    final class KeyRequestTest {

        private HandleContext context;

        @BeforeEach
        void setup() {
            when(stack.createReadableStates(TokenService.NAME)).thenReturn(defaultTokenReadableStates());
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());

            context = createContext(defaultTransactionBody());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testAllKeysForTransactionWithInvalidParameters() {
            // given
            final var bob = BOB.accountID();

            // when
            assertThatThrownBy(() -> context.allKeysForTransaction(null, bob)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.allKeysForTransaction(defaultTransactionBody(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testAllKeysForTransactionSuccess() throws PreCheckException {
            // given
            doAnswer(invocation -> {
                        final var innerContext = invocation.getArgument(0, PreHandleContext.class);
                        innerContext.requireKey(BOB.account().key());
                        innerContext.optionalKey(CAROL.account().key());
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // when
            final var keys = context.allKeysForTransaction(defaultTransactionBody(), ERIN.accountID());
            assertThat(keys.payerKey()).isEqualTo(ERIN.account().key());
            assertThat(keys.requiredNonPayerKeys())
                    .containsExactly(BOB.account().key());
            assertThat(keys.optionalNonPayerKeys())
                    .containsExactly(CAROL.account().key());
        }

        @Test
        void testAllKeysForTransactionWithFailingPureCheck() throws PreCheckException {
            // given
            doThrow(new PreCheckException(INVALID_TRANSACTION_BODY))
                    .when(dispatcher)
                    .dispatchPureChecks(any());

            // when
            assertThatThrownBy(() -> context.allKeysForTransaction(defaultTransactionBody(), ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        @Test
        void testAllKeysForTransactionWithFailingPreHandle() throws PreCheckException {
            // given
            doThrow(new PreCheckException(INSUFFICIENT_ACCOUNT_BALANCE))
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // when
            assertThatThrownBy(() -> context.allKeysForTransaction(defaultTransactionBody(), ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INSUFFICIENT_ACCOUNT_BALANCE));
        }
    }

    @Nested
    @DisplayName("Requesting network info")
    final class NetworkInfoTest {

        private HandleContext context;

        @BeforeEach
        void setup() {
            when(stack.createReadableStates(TokenService.NAME)).thenReturn(defaultTokenReadableStates());
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());

            context = createContext(defaultTransactionBody());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void exposesGivenNetworkInfo() {
            assertSame(networkInfo, context.networkInfo());
        }
    }

    @Nested
    @DisplayName("Creating Service APIs")
    final class ServiceApiTest {

        private HandleContext context;

        @BeforeEach
        void setup() {
            when(stack.createReadableStates(TokenService.NAME)).thenReturn(defaultTokenReadableStates());
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());

            context = createContext(defaultTransactionBody());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void failsAsExpectedWithoutAvailableApi() {
            assertThrows(IllegalArgumentException.class, () -> context.serviceApi(Object.class));
        }
    }

    @Nested
    @DisplayName("Handling of record builder")
    final class RecordBuilderTest {

        @BeforeEach
        void setup() {
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testMethodsWithInvalidParameters() {
            // given
            final var context = createContext(defaultTransactionBody());

            // then
            assertThatThrownBy(() -> context.recordBuilder(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.recordBuilder(List.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.addChildRecordBuilder(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.addChildRecordBuilder(List.class))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.addRemovableChildRecordBuilder(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.addRemovableChildRecordBuilder(List.class))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void testGetRecordBuilder() {
            // given
            final var context = createContext(defaultTransactionBody());

            // when
            final var actual = context.recordBuilder(CryptoCreateRecordBuilder.class);

            // then
            assertThat(actual).isEqualTo(recordBuilder);
        }

        @Test
        void testAddChildRecordBuilder(@Mock SingleTransactionRecordBuilderImpl childRecordBuilder) {
            // given
            when(recordListBuilder.addChild(any())).thenReturn(childRecordBuilder);
            final var context = createContext(defaultTransactionBody());

            // when
            final var actual = context.addChildRecordBuilder(CryptoCreateRecordBuilder.class);

            // then
            assertThat(actual).isEqualTo(childRecordBuilder);
        }

        @Test
        void testAddRemovableChildRecordBuilder(@Mock SingleTransactionRecordBuilderImpl childRecordBuilder) {
            // given
            when(recordListBuilder.addRemovableChild(any())).thenReturn(childRecordBuilder);
            final var context = createContext(defaultTransactionBody());

            // when
            final var actual = context.addRemovableChildRecordBuilder(CryptoCreateRecordBuilder.class);

            // then
            assertThat(actual).isEqualTo(childRecordBuilder);
        }
    }

    @Nested
    @DisplayName("Handling of dispatcher")
    final class DispatcherTest {

        private static final Predicate<Key> VERIFIER_CALLBACK = key -> true;
        private static final String FOOD_SERVICE = "FOOD_SERVICE";
        private static final Map<String, String> BASE_DATA = Map.of(
                A_KEY, APPLE,
                B_KEY, BANANA,
                C_KEY, CHERRY,
                D_KEY, DATE,
                E_KEY, EGGPLANT,
                F_KEY, FIG,
                G_KEY, GRAPE);

        @Mock(strictness = LENIENT)
        private HederaState baseState;

        @Mock(strictness = LENIENT, answer = Answers.RETURNS_SELF)
        private SingleTransactionRecordBuilderImpl childRecordBuilder;

        private SavepointStackImpl stack;

        @BeforeEach
        void setup() {
            final var baseKVState = new MapWritableKVState<>(FRUIT_STATE_KEY, BASE_DATA);
            final var writableStates =
                    MapWritableStates.builder().state(baseKVState).build();
            when(baseState.createReadableStates(FOOD_SERVICE)).thenReturn(writableStates);
            when(baseState.createWritableStates(FOOD_SERVICE)).thenReturn(writableStates);

            doAnswer(invocation -> {
                        final var childContext = invocation.getArgument(0, HandleContext.class);
                        final var childStack = (SavepointStackImpl) childContext.savepointStack();
                        childStack
                                .peek()
                                .createWritableStates(FOOD_SERVICE)
                                .get(FRUIT_STATE_KEY)
                                .put(A_KEY, ACAI);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchHandle(any());

            when(childRecordBuilder.status()).thenReturn(ResponseCodeEnum.OK);
            when(recordListBuilder.addPreceding(any())).thenReturn(childRecordBuilder);
            when(recordListBuilder.addChild(any())).thenReturn(childRecordBuilder);
            when(recordListBuilder.addRemovableChild(any())).thenReturn(childRecordBuilder);

            stack = new SavepointStackImpl(baseState);
        }

        private HandleContextImpl createContext(TransactionBody txBody, TransactionCategory category) {
            HederaFunctionality function;
            try {
                function = functionOf(txBody);
            } catch (UnknownHederaFunctionality e) {
                throw new RuntimeException(e);
            }

            final var txInfo =
                    new TransactionInfo(Transaction.DEFAULT, txBody, SignatureMap.DEFAULT, Bytes.EMPTY, function);
            return new HandleContextImpl(
                    txBody,
                    txInfo,
                    ALICE.accountID(),
                    ALICE.account().keyOrThrow(),
                    networkInfo,
                    category,
                    recordBuilder,
                    stack,
                    DEFAULT_CONFIGURATION,
                    verifier,
                    recordListBuilder,
                    checker,
                    dispatcher,
                    serviceScopeLookup,
                    blockRecordInfo,
                    recordCache,
                    feeManager,
                    exchangeRateManager,
                    consensusNow);
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testDispatchWithInvalidArguments() {
            // given
            final var txBody = defaultTransactionBody();
            final var context = createContext(txBody, TransactionCategory.USER);

            // then
            assertThatThrownBy(() -> context.dispatchPrecedingTransaction(
                            null, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                            context.dispatchPrecedingTransaction(txBody, null, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.dispatchPrecedingTransaction(
                            txBody, SingleTransactionRecordBuilder.class, null, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.dispatchChildTransaction(
                            null, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                            () -> context.dispatchChildTransaction(txBody, null, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.dispatchChildTransaction(
                            txBody, SingleTransactionRecordBuilder.class, (Predicate<Key>) null, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.dispatchRemovableChildTransaction(
                            null, SingleTransactionRecordBuilder.class, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.dispatchRemovableChildTransaction(
                            txBody, null, VERIFIER_CALLBACK, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> context.dispatchRemovableChildTransaction(
                            txBody, SingleTransactionRecordBuilder.class, (Predicate<Key>) null, AccountID.DEFAULT))
                    .isInstanceOf(NullPointerException.class);
        }

        private static Stream<Arguments> createContextDispatchers() {
            return Stream.of(
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatchPrecedingTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT)),
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatchChildTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT)),
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatchRemovableChildTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT)));
        }

        @ParameterizedTest
        @MethodSource("createContextDispatchers")
        void testDispatchSucceeds(Consumer<HandleContext> contextDispatcher) throws PreCheckException {
            // given
            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ALICE.accountID()))
                    .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
                    .build();
            final var context = createContext(txBody, TransactionCategory.USER);

            // when
            contextDispatcher.accept(context);

            // then
            verify(dispatcher).dispatchPureChecks(txBody);
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(ACAI);
            verify(childRecordBuilder).status(SUCCESS);
            // TODO: Check that record was added to recordListBuilder
        }

        @ParameterizedTest
        @MethodSource("createContextDispatchers")
        void testDispatchPreHandleFails(Consumer<HandleContext> contextDispatcher) throws PreCheckException {
            // given
            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ALICE.accountID()))
                    .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
                    .build();
            doThrow(new PreCheckException(ResponseCodeEnum.INVALID_TOPIC_ID))
                    .when(dispatcher)
                    .dispatchPureChecks(txBody);
            final var context = createContext(txBody, TransactionCategory.USER);

            // when
            contextDispatcher.accept(context);

            // then
            verify(childRecordBuilder).status(ResponseCodeEnum.INVALID_TOPIC_ID);
            verify(dispatcher, never()).dispatchHandle(any());
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(APPLE);
            // TODO: Check that record was added to recordListBuilder
        }

        @ParameterizedTest
        @MethodSource("createContextDispatchers")
        void testDispatchHandleFails(Consumer<HandleContext> contextDispatcher) {
            // given
            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ALICE.accountID()))
                    .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
                    .build();
            doThrow(new HandleException(ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT))
                    .when(dispatcher)
                    .dispatchHandle(any());
            final var context = createContext(txBody, TransactionCategory.USER);

            // when
            contextDispatcher.accept(context);

            // then
            verify(childRecordBuilder).status(ResponseCodeEnum.ACCOUNT_DOES_NOT_OWN_WIPED_NFT);
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(APPLE);
            // TODO: Check that record was added to recordListBuilder
        }

        @ParameterizedTest
        @EnumSource(TransactionCategory.class)
        void testDispatchPrecedingWithNonUserTxnFails(TransactionCategory category) {
            if (category != TransactionCategory.USER) {
                // given
                final var context = createContext(defaultTransactionBody(), category);

                // then
                assertThatThrownBy(() -> context.dispatchPrecedingTransaction(
                                defaultTransactionBody(),
                                SingleTransactionRecordBuilder.class,
                                VERIFIER_CALLBACK,
                                AccountID.DEFAULT))
                        .isInstanceOf(IllegalArgumentException.class);
                verify(recordListBuilder, never()).addPreceding(any());
                verify(dispatcher, never()).dispatchHandle(any());
                assertThat(stack.createReadableStates(FOOD_SERVICE)
                                .get(FRUIT_STATE_KEY)
                                .get(A_KEY))
                        .isEqualTo(APPLE);
            }
        }

        @Test
        void testDispatchPrecedingWithNonEmptyStackFails() {
            // given
            final var context = createContext(defaultTransactionBody(), TransactionCategory.USER);
            stack.createSavepoint();

            // then
            assertThatThrownBy(() -> context.dispatchPrecedingTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT))
                    .isInstanceOf(IllegalStateException.class);
            verify(recordListBuilder, never()).addPreceding(any());
            verify(dispatcher, never()).dispatchHandle(any());
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(APPLE);
        }

        @Test
        void testDispatchPrecedingWithChangedDataFails() {
            // given
            final var context = createContext(defaultTransactionBody(), TransactionCategory.USER);
            stack.peek().createWritableStates(FOOD_SERVICE).get(FRUIT_STATE_KEY).put(B_KEY, BLUEBERRY);

            // then
            assertThatThrownBy(() -> context.dispatchPrecedingTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT))
                    .isInstanceOf(IllegalStateException.class);
            verify(recordListBuilder, never()).addPreceding(any());
            verify(dispatcher, never()).dispatchHandle(any());
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(APPLE);
        }

        @Test
        void testDispatchChildFromPrecedingFails() {
            // given
            final var context = createContext(defaultTransactionBody(), TransactionCategory.PRECEDING);

            // then
            assertThatThrownBy(() -> context.dispatchChildTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(recordListBuilder, never()).addPreceding(any());
            verify(dispatcher, never()).dispatchHandle(any());
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(APPLE);
        }

        @Test
        void testDispatchRemovableChildFromPrecedingFails() {
            // given
            final var context = createContext(defaultTransactionBody(), TransactionCategory.PRECEDING);

            // then
            assertThatThrownBy(() -> context.dispatchRemovableChildTransaction(
                            defaultTransactionBody(),
                            SingleTransactionRecordBuilder.class,
                            VERIFIER_CALLBACK,
                            AccountID.DEFAULT))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(recordListBuilder, never()).addPreceding(any());
            verify(dispatcher, never()).dispatchHandle(any());
            assertThat(stack.createReadableStates(FOOD_SERVICE)
                            .get(FRUIT_STATE_KEY)
                            .get(A_KEY))
                    .isEqualTo(APPLE);
        }
    }

    @Nested
    @DisplayName("Requesting exchange rate info")
    final class ExchangeRateInfoTest {

        @Mock
        private ExchangeRateInfo exchangeRateInfo;

        private HandleContext context;

        @BeforeEach
        void setup() {
            when(stack.createWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder()
                            .state(MapWritableKVState.builder("ACCOUNTS").build())
                            .state(MapWritableKVState.builder("ALIASES").build())
                            .build());
            when(exchangeRateManager.exchangeRateInfo(any())).thenReturn(exchangeRateInfo);

            context = createContext(defaultTransactionBody());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testExchangeRateInfo() {
            assertSame(exchangeRateInfo, context.exchangeRateInfo());
        }
    }
}
