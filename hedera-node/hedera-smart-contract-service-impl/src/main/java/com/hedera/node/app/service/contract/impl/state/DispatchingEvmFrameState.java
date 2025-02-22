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

package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ACCOUNTS_LIMIT_REACHED;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_RECEIVER_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_VALUE_TRANSFER;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.MISSING_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.SELFDESTRUCT_TO_SELF;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.TOKEN_HOLDER_SELFDESTRUCT;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.TOKEN_TREASURY_SELFDESTRUCT;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.TOO_MANY_CHILD_RECORDS;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.EVM_ADDRESS_LENGTH_AS_LONG;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniUInt256;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy.UseTopLevelSigs;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.spi.state.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.EvmAccount;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;

/**
 * An implementation of {@link EvmFrameState} that uses {@link WritableKVState}s to manage
 * contract storage and bytecode, and a {@link HandleHederaNativeOperations} for additional influence over
 * the non-contract Hedera state in the current scope.
 *
 * <p>Almost every access requires a conversion from a PBJ type to a Besu type. At some
 * point it might be necessary to cache the converted values and invalidate them when
 * the state changes.
 * <p>
 * TODO - get a little further to clarify DI strategy, then bring back a code cache.
 */
public class DispatchingEvmFrameState implements EvmFrameState {
    private static final Key HOLLOW_ACCOUNT_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();
    private static final String TOKEN_BYTECODE_PATTERN = "fefefefefefefefefefefefefefefefefefefefe";

    @SuppressWarnings("java:S6418")
    private static final String TOKEN_CALL_REDIRECT_CONTRACT_BINARY =
            "6080604052348015600f57600080fd5b506000610167905077618dc65efefefefefefefefefefefefefefefefefefefefe600052366000602037600080366018016008845af43d806000803e8160008114605857816000f35b816000fdfea2646970667358221220d8378feed472ba49a0005514ef7087017f707b45fb9bf56bb81bb93ff19a238b64736f6c634300080b0033";

    private final HederaNativeOperations nativeOperations;
    private final ContractStateStore contractStateStore;

    public DispatchingEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore) {
        this.nativeOperations = requireNonNull(nativeOperations);
        this.contractStateStore = requireNonNull(contractStateStore);
    }

    @Override
    public void setStorageValue(final long number, @NonNull final UInt256 key, @NonNull final UInt256 value) {
        final var slotKey = new SlotKey(number, tuweniToPbjBytes(requireNonNull(key)));
        final var oldSlotValue = contractStateStore.getSlotValue(slotKey);
        // Ensure we don't change any prev/next keys until the base commit
        final var slotValue = new SlotValue(
                tuweniToPbjBytes(requireNonNull(value)),
                oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.previousKey(),
                oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.nextKey());
        // We don't call remove() here when the new value is zero, again because we
        // want to preserve the prev/next key information until the base commit; only
        // then will we remove the zeroed out slot from the K/V state
        contractStateStore.putSlot(slotKey, slotValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull UInt256 getStorageValue(final long number, @NonNull final UInt256 key) {
        final var slotKey = new SlotKey(number, tuweniToPbjBytes(requireNonNull(key)));
        return valueOrZero(contractStateStore.getSlotValue(slotKey));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull UInt256 getOriginalStorageValue(final long number, @NonNull final UInt256 key) {
        final var slotKey = new SlotKey(number, tuweniToPbjBytes(requireNonNull(key)));
        return valueOrZero(contractStateStore.getOriginalSlotValue(slotKey));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull List<StorageAccesses> getStorageChanges() {
        final Map<Long, List<StorageAccess>> modifications = new TreeMap<>();
        contractStateStore.getModifiedSlotKeys().forEach(slotKey -> modifications
                .computeIfAbsent(slotKey.contractNumber(), k -> new ArrayList<>())
                .add(StorageAccess.newWrite(
                        pbjToTuweniUInt256(slotKey.key()),
                        valueOrZero(contractStateStore.getOriginalSlotValue(slotKey)),
                        valueOrZero(contractStateStore.getSlotValue(slotKey)))));
        final List<StorageAccesses> allChanges = new ArrayList<>();
        modifications.forEach(
                (number, storageAccesses) -> allChanges.add(new StorageAccesses(number, storageAccesses)));
        return allChanges;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getKvStateSize() {
        return contractStateStore.getNumSlots();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull RentFactors getRentFactorsFor(final long number) {
        final var account = validatedAccount(number);
        return new RentFactors(account.contractKvPairsNumber(), account.expirationSecond());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes getCode(final long number) {
        final var numberedBytecode = contractStateStore.getBytecode(new EntityNumber(number));
        if (numberedBytecode == null) {
            return Bytes.EMPTY;
        } else {
            final var code = numberedBytecode.code();
            return code == null ? Bytes.EMPTY : pbjToTuweniBytes(code);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getCodeHash(final long number) {
        final var numberedBytecode = contractStateStore.getBytecode(new EntityNumber(number));
        if (numberedBytecode == null) {
            return Hash.EMPTY;
        } else {
            return CodeFactory.createCode(pbjToTuweniBytes(numberedBytecode.code()), 0, false)
                    .getCodeHash();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes getTokenRedirectCode(@NonNull final Address address) {
        return proxyBytecodeFor(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getTokenRedirectCodeHash(@NonNull final Address address) {
        return CodeFactory.createCode(proxyBytecodeFor(address), 0, false).getCodeHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNonce(final long number) {
        return validatedAccount(number).ethereumNonce();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumTreasuryTitles(final long number) {
        return validatedAccount(number).numberTreasuryTitles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isContract(final long number) {
        return validatedAccount(number).smartContract();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumPositiveTokenBalances(final long number) {
        return validatedAccount(number).numberPositiveBalances();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCode(final long number, @NonNull final Bytes code) {
        contractStateStore.putBytecode(new EntityNumber(number), new Bytecode(tuweniToPbjBytes(requireNonNull(code))));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNonce(final long number, final long nonce) {
        nativeOperations.setNonce(number, nonce);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Wei getBalance(long number) {
        return Wei.of(validatedAccount(number).tinybarBalance());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getIdNumber(@NonNull Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            throw new IllegalArgumentException("Address " + address + " has no associated Hedera id");
        }
        return number;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Address getAddress(final long number) {
        final var account = validatedAccount(number);
        if (account.deleted()) {
            return null;
        }
        final var alias = account.alias();
        if (alias.length() == EVM_ADDRESS_LENGTH_AS_LONG) {
            return pbjToBesuAddress(alias);
        } else {
            return asLongZeroAddress(number);
        }
    }

    @Override
    public boolean isHollowAccount(@NonNull final Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return false;
        }
        final var account = nativeOperations.getAccount(number);
        if (account == null) {
            return false;
        }
        return HOLLOW_ACCOUNT_KEY.equals(account.key());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finalizeHollowAccount(@NonNull final Address address) {
        nativeOperations.finalizeHollowAccountAsContract(tuweniToPbjBytes(address));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryTransfer(
            @NonNull final Address sendingContract,
            @NonNull final Address recipient,
            final long amount,
            final boolean delegateCall) {
        final var from = (ProxyEvmAccount) getAccount(sendingContract);
        if (from == null) {
            return Optional.of(MISSING_ADDRESS);
        }
        final var to = getAccount(recipient);
        if (to == null) {
            return Optional.of(MISSING_ADDRESS);
        } else if (to instanceof TokenEvmAccount) {
            return Optional.of(ILLEGAL_STATE_CHANGE);
        }
        // Note we can still use top-level signatures to meet receiver signature requirements
        final var status = nativeOperations.transferWithReceiverSigCheck(
                amount,
                from.number,
                ((ProxyEvmAccount) to).number,
                new ActiveContractVerificationStrategy(
                        from.number, tuweniToPbjBytes(from.getAddress()), delegateCall, UseTopLevelSigs.YES));
        if (status != OK) {
            if (status == INVALID_SIGNATURE) {
                return Optional.of(INVALID_RECEIVER_SIGNATURE);
            } else {
                throw new IllegalStateException("Transfer from 0.0." + from.number
                        + " to 0.0." + ((ProxyEvmAccount) to).number
                        + " failed with status " + status + " despite valid preconditions");
            }
        } else {
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryLazyCreation(@NonNull final Address address) {
        if (isLongZero(address)) {
            throw new IllegalArgumentException("Cannot perform lazy creation at long-zero address " + address);
        }
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number != MISSING_ENTITY_NUMBER) {
            final var account = nativeOperations.getAccount(number);
            if (account != null) {
                if (account.expiredAndPendingRemoval()) {
                    return Optional.of(INVALID_VALUE_TRANSFER);
                } else {
                    throw new IllegalArgumentException(
                            "Unexpired account 0.0." + number + " already exists at address " + address);
                }
            }
        }
        final var status = nativeOperations.createHollowAccount(tuweniToPbjBytes(address));
        if (status != OK) {
            if (status == MAX_CHILD_RECORDS_EXCEEDED) {
                return Optional.of(TOO_MANY_CHILD_RECORDS);
            } else if (status == MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED) {
                return Optional.of(ACCOUNTS_LIMIT_REACHED);
            } else {
                throw new IllegalStateException(
                        "Lazy creation of account at address " + address + " failed with unexpected status " + status);
            }
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryTrackingDeletion(
            @NonNull final Address deleted, @NonNull final Address beneficiary) {
        if (deleted.equals(beneficiary)) {
            return Optional.of(SELFDESTRUCT_TO_SELF);
        }
        final var beneficiaryAccount = getAccount(beneficiary);
        if (beneficiaryAccount == null || beneficiaryAccount instanceof TokenEvmAccount) {
            return Optional.of(MISSING_ADDRESS);
        }
        // Token addresses don't have bytecode that could run a selfdestruct, so this cast is safe
        final var deletedAccount = (ProxyEvmAccount) requireNonNull(getAccount(deleted));
        if (deletedAccount.numTreasuryTitles() > 0) {
            return Optional.of(TOKEN_TREASURY_SELFDESTRUCT);
        }
        if (deletedAccount.numPositiveTokenBalances() > 0) {
            return Optional.of(TOKEN_HOLDER_SELFDESTRUCT);
        }
        nativeOperations.trackDeletion(deletedAccount.number, ((ProxyEvmAccount) beneficiaryAccount).number);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Account getAccount(@NonNull final Address address) {
        return getMutableAccount(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable EvmAccount getMutableAccount(@NonNull final Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return null;
        }
        final var account = nativeOperations.getAccount(number);
        if (account == null) {
            final var token = nativeOperations.getToken(number);
            if (token != null) {
                // If the token is deleted or expired, the system contract executed by the redirect
                // bytecode will fail with a more meaningful error message, so don't check that here
                return new TokenEvmAccount(address, this);
            }
            return null;
        }
        if (account.deleted() || account.expiredAndPendingRemoval() || isNotPriority(address, account)) {
            return null;
        }
        return new ProxyEvmAccount(number, this);
    }

    private Bytes proxyBytecodeFor(final Address address) {
        return Bytes.fromHexString(
                TOKEN_CALL_REDIRECT_CONTRACT_BINARY.replace(TOKEN_BYTECODE_PATTERN, address.toUnprefixedHexString()));
    }

    private boolean isNotPriority(
            final Address address, final @NonNull com.hedera.hapi.node.state.token.Account account) {
        final var alias = requireNonNull(account).alias();
        return alias != null
                && alias.length() == EVM_ADDRESS_LENGTH_AS_LONG
                && !address.equals(pbjToBesuAddress(alias));
    }

    private com.hedera.hapi.node.state.token.Account validatedAccount(final long number) {
        final var account = nativeOperations.getAccount(number);
        if (account == null) {
            throw new IllegalArgumentException("No account has number " + number);
        }
        return account;
    }

    private UInt256 valueOrZero(@Nullable final SlotValue slotValue) {
        return (slotValue == null) ? UInt256.ZERO : pbjToTuweniUInt256(slotValue.value());
    }
}
