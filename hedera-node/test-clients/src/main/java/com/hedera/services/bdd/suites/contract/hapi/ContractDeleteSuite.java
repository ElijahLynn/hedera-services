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

package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.spec.HapiPropertySource.asHexedSolidityAddress;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemContractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemContractUndelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@HapiTestSuite
public class ContractDeleteSuite extends HapiSuite {

    private static final Logger log = LogManager.getLogger(ContractDeleteSuite.class);
    private static final String CONTRACT = "Multipurpose";
    private static final String PAYABLE_CONSTRUCTOR = "PayableConstructor";
    private static final String CONTRACT_DESTROY = "destroy";
    private static final String RECEIVER_CONTRACT_NAME = "receiver";

    public static void main(String... args) {
        new ContractDeleteSuite().runSuiteAsync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(
                rejectsWithoutProperSig(),
                systemCannotDeleteOrUndeleteContracts(),
                deleteWorksWithMutableContract(),
                deleteFailsWithImmutableContract(),
                deleteTransfersToAccount(),
                deleteTransfersToContract(),
                cannotDeleteOrSelfDestructTokenTreasury(),
                cannotDeleteOrSelfDestructContractWithNonZeroBalance(),
                cannotSendValueToTokenAccount(),
                cannotUseMoreThanChildContractLimit());
    }

    private HapiSpec cannotUseMoreThanChildContractLimit() {
        final var illegalNumChildren =
                HapiSpecSetup.getDefaultNodeProps().getInteger("consensus.handle.maxFollowingRecords") + 1;
        final var fungible = "fungible";
        final var contract = "ManyChildren";
        final var precompileViolation = "precompileViolation";
        final var internalCreateViolation = "internalCreateViolation";
        final AtomicReference<String> treasuryMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        return defaultHapiSpec("CannotUseMoreThanChildContractLimit")
                .given(
                        cryptoCreate(TOKEN_TREASURY)
                                .exposingCreatedIdTo(id -> treasuryMirrorAddr.set(asHexedSolidityAddress(id))),
                        tokenCreate(fungible).treasury(TOKEN_TREASURY),
                        tokenCreate(fungible)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(1234567)
                                .exposingCreatedIdTo(idLit ->
                                        tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))))
                .when(
                        uploadInitCode(contract),
                        contractCreate(contract),
                        sourcing(() -> contractCall(
                                        contract,
                                        "checkBalanceRepeatedly",
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        asHeadlongAddress(treasuryMirrorAddr.get()),
                                        BigInteger.valueOf(illegalNumChildren))
                                .via(precompileViolation)
                                .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)),
                        sourcing(() -> contractCall(
                                        contract, "createThingsRepeatedly", BigInteger.valueOf(illegalNumChildren))
                                .via(internalCreateViolation)
                                .gas(15_000_000)
                                .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)))
                .then(
                        getTxnRecord(precompileViolation)
                                .andAllChildRecords()
                                .hasChildRecords(IntStream.range(0, 50)
                                        .mapToObj(i -> recordWith().status(REVERTED_SUCCESS))
                                        .toArray(TransactionRecordAsserts[]::new)),
                        getTxnRecord(internalCreateViolation)
                                .andAllChildRecords()
                                // Reverted internal CONTRACT_CREATION messages are not externalized
                                .hasChildRecords());
    }

    private HapiSpec cannotSendValueToTokenAccount() {
        final var multiKey = "multiKey";
        final var nonFungibleToken = "NFT";
        final var contract = "ManyChildren";
        final var internalViolation = "internal";
        final var externalViolation = "external";
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        return defaultHapiSpec("CannotSendValueToTokenAccount")
                .given(
                        newKeyNamed(multiKey),
                        cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(nonFungibleToken)
                                .supplyType(TokenSupplyType.INFINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(0)
                                .supplyKey(multiKey)
                                .exposingCreatedIdTo(idLit ->
                                        tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))))
                .when(
                        uploadInitCode(contract),
                        contractCreate(contract),
                        sourcing(() -> contractCall(
                                        contract, "sendSomeValueTo", asHeadlongAddress(tokenMirrorAddr.get()))
                                .sending(ONE_HBAR)
                                .payingWith(TOKEN_TREASURY)
                                .via(internalViolation)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                        sourcing((() -> contractCall(tokenMirrorAddr.get())
                                .sending(1L)
                                .payingWith(TOKEN_TREASURY)
                                .refusingEthConversion()
                                .via(externalViolation)
                                .hasKnownStatus(LOCAL_CALL_MODIFICATION_EXCEPTION))))
                .then(
                        getTxnRecord(internalViolation).hasPriority(recordWith().feeGreaterThan(0L)),
                        getTxnRecord(externalViolation).hasPriority(recordWith().feeGreaterThan(0L)));
    }

    HapiSpec cannotDeleteOrSelfDestructTokenTreasury() {
        final var someToken = "someToken";
        final var selfDestructCallable = "SelfDestructCallable";
        final var multiKey = "multi";
        final var escapeRoute = "civilian";
        final var beneficiary = "beneficiary";
        return defaultHapiSpec("CannotDeleteOrSelfDestructTokenTreasury")
                .given(
                        cryptoCreate(beneficiary).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(multiKey),
                        cryptoCreate(escapeRoute),
                        uploadInitCode(selfDestructCallable),
                        contractCustomCreate(selfDestructCallable, "1")
                                .adminKey(multiKey)
                                .balance(123),
                        contractCustomCreate(selfDestructCallable, "2")
                                .adminKey(multiKey)
                                .balance(321),
                        tokenCreate(someToken).adminKey(multiKey).treasury(selfDestructCallable + "1"))
                .when(
                        contractDelete(selfDestructCallable + "1").hasKnownStatus(ACCOUNT_IS_TREASURY),
                        tokenAssociate(selfDestructCallable + "2", someToken),
                        tokenUpdate(someToken).treasury(selfDestructCallable + "2"),
                        contractDelete(selfDestructCallable + "1"),
                        contractCall(selfDestructCallable + "2", CONTRACT_DESTROY)
                                .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                                .payingWith(beneficiary),
                        tokenAssociate(escapeRoute, someToken),
                        tokenUpdate(someToken).treasury(escapeRoute))
                .then(contractCall(selfDestructCallable + "2", CONTRACT_DESTROY).payingWith(beneficiary));
    }

    HapiSpec cannotDeleteOrSelfDestructContractWithNonZeroBalance() {
        final var someToken = "someToken";
        final var multiKey = "multi";
        final var selfDestructableContract = "SelfDestructCallable";
        final var otherMiscContract = "PayReceivable";
        final var beneficiary = "beneficiary";

        return defaultHapiSpec("CannotDeleteOrSelfDestructContractWithNonZeroBalance")
                .given(
                        cryptoCreate(beneficiary).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(multiKey),
                        uploadInitCode(selfDestructableContract),
                        contractCreate(selfDestructableContract)
                                .adminKey(multiKey)
                                .balance(123),
                        uploadInitCode(otherMiscContract),
                        contractCreate(otherMiscContract),
                        tokenCreate(someToken)
                                .initialSupply(0L)
                                .adminKey(multiKey)
                                .supplyKey(multiKey)
                                .treasury(selfDestructableContract)
                                .supplyType(TokenSupplyType.INFINITE)
                                .tokenType(NON_FUNGIBLE_UNIQUE))
                .when(
                        mintToken(someToken, List.of(ByteString.copyFromUtf8("somemetadata"))),
                        tokenAssociate(otherMiscContract, someToken),
                        cryptoTransfer(TokenMovement.movingUnique(someToken, 1)
                                .between(selfDestructableContract, otherMiscContract)))
                .then(
                        contractDelete(otherMiscContract).hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES),
                        contractCall(selfDestructableContract, CONTRACT_DESTROY)
                                .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                                .payingWith(beneficiary));
    }

    HapiSpec rejectsWithoutProperSig() {
        return defaultHapiSpec("rejectsWithoutProperSig")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when()
                .then(contractDelete(CONTRACT).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE));
    }

    private HapiSpec systemCannotDeleteOrUndeleteContracts() {
        return defaultHapiSpec("SystemCannotDeleteOrUndeleteContracts")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when()
                .then(
                        systemContractDelete(CONTRACT)
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .hasPrecheck(NOT_SUPPORTED),
                        systemContractUndelete(CONTRACT)
                                .payingWith(SYSTEM_UNDELETE_ADMIN)
                                .hasPrecheck(NOT_SUPPORTED),
                        getContractInfo(CONTRACT).hasAnswerOnlyPrecheck(OK));
    }

    private HapiSpec deleteWorksWithMutableContract() {
        final var tbdFile = "FTBD";
        final var tbdContract = "CTBD";
        return defaultHapiSpec("DeleteWorksWithMutableContract")
                .given(
                        fileCreate(tbdFile),
                        fileDelete(tbdFile),
                        createDefaultContract(tbdContract).bytecode(tbdFile).hasKnownStatus(FILE_DELETED))
                .when(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .then(
                        contractDelete(CONTRACT)
                                .claimingPermanentRemoval()
                                .hasPrecheck(PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION),
                        contractDelete(CONTRACT),
                        getContractInfo(CONTRACT).has(contractWith().isDeleted()));
    }

    private HapiSpec deleteFailsWithImmutableContract() {
        return defaultHapiSpec("DeleteFailsWithImmutableContract")
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT).omitAdminKey())
                .when()
                .then(contractDelete(CONTRACT).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT));
    }

    private HapiSpec deleteTransfersToAccount() {
        return defaultHapiSpec("DeleteTransfersToAccount")
                .given(
                        cryptoCreate(RECEIVER_CONTRACT_NAME).balance(0L),
                        uploadInitCode(PAYABLE_CONSTRUCTOR),
                        contractCreate(PAYABLE_CONSTRUCTOR).balance(1L))
                .when(contractDelete(PAYABLE_CONSTRUCTOR).transferAccount(RECEIVER_CONTRACT_NAME))
                .then(getAccountBalance(RECEIVER_CONTRACT_NAME).hasTinyBars(1L));
    }

    private HapiSpec deleteTransfersToContract() {
        final var suffix = "Receiver";

        return defaultHapiSpec("DeleteTransfersToContract")
                .given(
                        uploadInitCode(PAYABLE_CONSTRUCTOR),
                        contractCreate(PAYABLE_CONSTRUCTOR).balance(0L),
                        contractCustomCreate(PAYABLE_CONSTRUCTOR, suffix).balance(1L))
                .when(contractDelete(PAYABLE_CONSTRUCTOR).transferContract(PAYABLE_CONSTRUCTOR + suffix))
                .then(getAccountBalance(PAYABLE_CONSTRUCTOR + suffix).hasTinyBars(1L));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
