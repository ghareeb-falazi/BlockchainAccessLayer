/*******************************************************************************
 * Copyright (c) 2019 Institute for the Architecture of Application System - University of Stuttgart
 * Author: Ghareeb Falazi
 *
 * This program and the accompanying materials are made available under the
 * terms the Apache Software License 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 *******************************************************************************/
package blockchains.iaas.uni.stuttgart.de.adaptation.adapters.ethereum;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import javax.naming.OperationNotSupportedException;

import blockchains.iaas.uni.stuttgart.de.adaptation.adapters.AbstractAdapter;
import blockchains.iaas.uni.stuttgart.de.adaptation.utils.BooleanExpressionEvaluator;
import blockchains.iaas.uni.stuttgart.de.adaptation.utils.PoWConfidenceCalculator;
import blockchains.iaas.uni.stuttgart.de.adaptation.utils.SmartContractPathParser;
import blockchains.iaas.uni.stuttgart.de.exceptions.BalException;
import blockchains.iaas.uni.stuttgart.de.exceptions.BlockchainNodeUnreachableException;
import blockchains.iaas.uni.stuttgart.de.exceptions.InvalidTransactionException;
import blockchains.iaas.uni.stuttgart.de.exceptions.InvokeSmartContractFunctionFailure;
import blockchains.iaas.uni.stuttgart.de.exceptions.NotSupportedException;
import blockchains.iaas.uni.stuttgart.de.exceptions.ParameterException;
import blockchains.iaas.uni.stuttgart.de.exceptions.SmartContractNotFoundException;
import blockchains.iaas.uni.stuttgart.de.model.Block;
import blockchains.iaas.uni.stuttgart.de.model.LinearChainTransaction;
import blockchains.iaas.uni.stuttgart.de.model.Occurrence;
import blockchains.iaas.uni.stuttgart.de.model.Parameter;
import blockchains.iaas.uni.stuttgart.de.model.Transaction;
import blockchains.iaas.uni.stuttgart.de.model.TransactionState;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.EventValues;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.Contract;
import org.web3j.tx.Transfer;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.utils.Convert;

public class EthereumAdapter extends AbstractAdapter {
    private Credentials credentials;
    private final String nodeUrl;
    private final Web3j web3j;
    private final DateTimeFormatter formatter;
    private static final Logger log = LoggerFactory.getLogger(EthereumAdapter.class);

    public EthereumAdapter(final String nodeUrl) {
        this.nodeUrl = nodeUrl;
        this.web3j = Web3j.build(new HttpService(this.nodeUrl));
        this.formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    }

    public Web3j getWeb3j() {
        return web3j;
    }

    Credentials getCredentials() {
        return credentials;
    }

    void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public void setCredentials(String password, String fileSource) throws IOException, CipherException {
        try {
            this.credentials = WalletUtils.loadCredentials(password, fileSource);
        } catch (IOException | CipherException e) {
            log.error("Error occurred while setting the user credentials for Ethereum. Reason {}", e.getMessage());
            throw e;
        }
    }

    String testConnectionToNode() {
        try {
            log.info("Connected to Ethereum client: URL: {}, Version: {}", this.nodeUrl, this.web3j.web3ClientVersion().send().getWeb3ClientVersion());
            return "true";
        } catch (IOException e) {
            log.error("Failed to connect to Ethereum client at URL: {}. Reason: {}", this.nodeUrl, e.getMessage());

            return e.getMessage();
        }
    }

    /**
     * Subscribes for the event of detecting a transition of the state of a given transaction which is assumed to having been
     * MINED before. The method supports detecting
     * (i) a transaction being not found anymore (invalidated), or (ii) not having a containing block (orphaned), or
     * (iii) having received enough block-confirmations (durably committed).
     *
     * @param txHash         the hash of the transaction to monitor
     * @param waitFor        the number of block-confirmations to wait until the transaction is considered persisted (-1 if the
     *                       transaction is never to be considered persisted)
     * @param observedStates the set of states that will be reported to the calling method
     * @return a future which is used to handle the subscription and receive the callback
     */
    private CompletableFuture<Transaction> subscribeForTxEvent(String txHash, long waitFor, TransactionState... observedStates) {
        final CompletableFuture<Transaction> result = new CompletableFuture<>();
        final Disposable subscription = web3j.blockFlowable(false).subscribe(ethBlock -> {
            try {
                // make sure the transaction exists
                final EthTransaction transaction = web3j.ethGetTransactionByHash(txHash).send();
                // if not, then it is either invalidated or did not exist in the first place
                if (!transaction.getTransaction().isPresent()) {
                    final String msg = String.format("The transaction of the hash %s is not found!", txHash);
                    log.info(msg);
                    handleDetectedState(transaction.getTransaction(), TransactionState.NOT_FOUND, observedStates, result);

                    return;
                }

                // make sure the transaction is still contained in a block, i.e., it was not orphaned
                final String retrievedBlockHash = transaction.getTransaction().get().getBlockHash();

                if (retrievedBlockHash == null || retrievedBlockHash.isEmpty()) {
                    final String msg = String.format("The transaction of the hash %s has no block (orphaned?)",
                            txHash);
                    log.info(msg);

                    handleDetectedState(transaction.getTransaction(), TransactionState.PENDING, observedStates, result);
                    return;
                }
                // check if enough block-confirmations have occurred.
                if (waitFor >= 0 && ethBlock.getBlock() != null) {
                    if (ethBlock.getBlock().getNumber()
                            .subtract(transaction.getTransaction().get().getBlockNumber())
                            .intValue() >= waitFor) {
                        final String msg = String.format("The transaction of the hash %s has been confirmed",
                                txHash);
                        log.info(msg);

                        handleDetectedState(transaction.getTransaction(), TransactionState.CONFIRMED, observedStates, result);
                    }
                }
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });

        //dispose the flowable when the CompletableFuture completes (either when detecting an event, or manually)
        result.whenComplete((v, e) -> subscription.dispose());

        return result;
    }

    private static CompletionException wrapEthereumExceptions(Throwable e) {
        return new CompletionException(mapEthereumException(e));
    }

    private static BalException mapEthereumException(Throwable e) {
        BalException result;

        if (e instanceof BalException)
            result = (BalException) e;
        else if (e.getCause() instanceof BalException)
            result = (BalException) e.getCause();
        else if (e.getCause() instanceof IOException)
            result = new BlockchainNodeUnreachableException(e.getMessage());
        else if (e instanceof IllegalArgumentException || e instanceof OperationNotSupportedException)
            result = new InvokeSmartContractFunctionFailure(e.getMessage());
        else if (e.getCause() instanceof RuntimeException)
            result = new InvalidTransactionException(e.getMessage());
        else {
            log.error("Unexpected exception was thrown!");
            result = new InvalidTransactionException(e.getMessage());
        }

        return result;
    }

    @Override
    public CompletableFuture<Transaction> submitTransaction(String receiverAddress, BigDecimal value, double requiredConfidence)
            throws InvalidTransactionException {
        if (credentials == null) {
            log.error("Credentials are not set for the Ethereum user");
            throw new NullPointerException("Credentials are not set for the Ethereum user");
        }

        try {
            final long waitFor = ((PoWConfidenceCalculator) this.confidenceCalculator).getEquivalentBlockDepth(requiredConfidence);
            return Transfer.sendFunds(web3j, credentials, receiverAddress, value, Convert.Unit.WEI)  // 1 wei = 10^-18 Ether
                    .sendAsync()
                    // when an exception (e.g., ConnectException happens), the following is skipped
                    .thenCompose(tx -> subscribeForTxEvent(tx.getTransactionHash(), waitFor, TransactionState.CONFIRMED, TransactionState.NOT_FOUND))
                    .exceptionally((e) -> {
                                throw wrapEthereumExceptions(e);
                            }
                    );
        } catch (Exception e) {// this seems to never get invoked
            final String msg = "An error occurred while trying to submit a new transaction to ethereum. Reason: " + e.getMessage();
            log.error(msg);

            throw new InvalidTransactionException(msg);
        }
    }

    @Override
    public Observable<Transaction> receiveTransactions(String senderId, double requiredConfidence) {
        if (credentials == null) {
            log.error("Credentials are not set for the Ethereum user");
            throw new NullPointerException("Credentials are not set for the Ethereum user");
        }

        final long waitFor = ((PoWConfidenceCalculator) this.confidenceCalculator).getEquivalentBlockDepth(requiredConfidence);
        final String myAddress = credentials.getAddress();
        final PublishSubject<Transaction> result = PublishSubject.create();
        final Disposable newTransactionObservable = web3j.transactionFlowable().subscribe(tx -> {
            if (myAddress.equalsIgnoreCase(tx.getTo())) {
                if (senderId == null || senderId.trim().length() == 0 || senderId.equalsIgnoreCase(tx.getFrom())) {
                    log.info("New transaction received from:" + tx.getFrom());
                    subscribeForTxEvent(tx.getHash(), waitFor, TransactionState.CONFIRMED)
                            .thenAccept(result::onNext)
                            .exceptionally(error -> {
                                result.onError(wrapEthereumExceptions(error));
                                return null;
                            });
                }
            }
        }, e -> result.onError(wrapEthereumExceptions(e)));

        return result.doFinally(newTransactionObservable::dispose);
    }

    @Override
    public CompletableFuture<TransactionState> ensureTransactionState(String transactionId, double requiredConfidence) {
        final long waitFor = ((PoWConfidenceCalculator) this.confidenceCalculator).getEquivalentBlockDepth(requiredConfidence);
        // only monitor the transition into the CONFIRMED state or the NOT_FOUND state
        return subscribeForTxEvent(transactionId, waitFor, TransactionState.CONFIRMED, TransactionState.NOT_FOUND)
                .thenApply(Transaction::getState)
                .exceptionally((e) -> {
                    throw wrapEthereumExceptions(e);
                });
    }

    @Override
    public CompletableFuture<TransactionState> detectOrphanedTransaction(String transactionId) {
        // only monitor the transition into the PENDING state
        return subscribeForTxEvent(transactionId, -1, TransactionState.PENDING, TransactionState.NOT_FOUND)
                .thenApply(Transaction::getState)
                .exceptionally((e) -> {
                    throw wrapEthereumExceptions(e);
                });
    }

    @Override
    public CompletableFuture<Transaction> invokeSmartContract(
            String smartContractPath,
            String functionIdentifier,
            List<Parameter> inputs,
            List<Parameter> outputs,
            double requiredConfidence
    ) throws NotSupportedException, ParameterException {
        if (credentials == null) {
            log.error("Credentials are not set for the Ethereum user");
            throw new NullPointerException("Credentials are not set for the Ethereum user");
        }
        Objects.requireNonNull(smartContractPath);
        Objects.requireNonNull(functionIdentifier);
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(outputs);

        try {
            long waitFor = ((PoWConfidenceCalculator) this.confidenceCalculator).getEquivalentBlockDepth(requiredConfidence);
            final String[] pathSegments = SmartContractPathParser.parse(smartContractPath).getSmartContractPathSegments();

            if (pathSegments.length != 1) {
                throw new SmartContractNotFoundException("Malformed Ethereum path!");
            }

            if (!pathSegments[0].matches("^0x[a-fA-F0-9]{40}$")) {
                throw new SmartContractNotFoundException("Malformed Ethereum address!");
            }

            final String smartContractAddress = pathSegments[0];
            List<TypeReference<?>> outputParameters = new ArrayList<>();
            Class<? extends Type> currentReturnType;

            for (Parameter output : outputs) {
                currentReturnType = EthereumTypeMapper.getEthereumType(output.getType());
                outputParameters.add(TypeReference.create(currentReturnType));
            }

            final Function function = new Function(
                    functionIdentifier,  // function we're calling
                    this.convertToSolidityTypes(inputs),  // Parameters to pass as Solidity Types
                    outputParameters); //Type of returned value

            final String encodedFunction = FunctionEncoder.encode(function);

            // if we are expecting a return value, we try to invoke as a method call, otherwise, we immediately try a transaction
            if (outputParameters.size() > 0) {
                Transaction resultFromEthCall = invokeFunctionByMethodCall(encodedFunction, smartContractAddress, outputs, function.getOutputParameters());

                if (resultFromEthCall != null) {
                    return CompletableFuture.completedFuture(resultFromEthCall);
                }
            }

            return this.invokeFunctionByTransaction(waitFor, encodedFunction, smartContractAddress);
        } catch (Exception e) {
            log.error("Decoding smart contract function call failed. Reason: {}", e.getMessage());
            throw mapEthereumException(e);
        }
    }

    @Override
    public Observable<Occurrence> subscribeToEvent(String smartContractAddress, String eventIdentifier, List<Parameter> outputParameters, double degreeOfConfidence, String filter) throws BalException {
        long waitFor = ((PoWConfidenceCalculator) this.confidenceCalculator).getEquivalentBlockDepth(degreeOfConfidence);
        List<TypeReference<?>> types = this.convertTypes(outputParameters);
        final Event event = new Event(eventIdentifier, types);
        final EthFilter ethFilter = this.generateFilter(smartContractAddress, event, types);
        final PublishSubject<Occurrence> result = PublishSubject.create();

        Disposable newEventObservable = web3j.ethLogFlowable(ethFilter).subscribe(log -> {
            final EventValues values = Contract.staticExtractEventParameters(event, log);
            List<Parameter> parameters = new ArrayList<>();

            for (int i = 0; i < outputParameters.size(); i++) {
                parameters.add(Parameter.builder()
                        .name(outputParameters.get(i).getName())
                        .value(ParameterDecoder.decode(values.getNonIndexedValues().get(i)))
                        .build());
            }

            if (BooleanExpressionEvaluator.evaluate(filter, parameters)) {

                EthBlock block = this.web3j.ethGetBlockByHash(log.getBlockHash(), false).send();

                subscribeForTxEvent(log.getTransactionHash(), waitFor, TransactionState.CONFIRMED)
                        .thenAccept(tx -> {
                            LocalDateTime timestamp = LocalDateTime.ofEpochSecond(block.getBlock().getTimestamp().longValue(), 0, ZoneOffset.UTC);
                            String timestampS = formatter.format(timestamp);
                            result.onNext(Occurrence.builder().parameters(parameters).isoTimestamp(timestampS).build());
                        })
                        .exceptionally(error -> {
                            result.onError(wrapEthereumExceptions(error));
                            return null;
                        });
            }
        }, e -> result.onError(wrapEthereumExceptions(e)));

        return result.doFinally(newEventObservable::dispose);
    }

    @Override
    public String testConnection() {
        return this.testConnectionToNode();
    }

    private List<TypeReference<?>> convertTypes(List<Parameter> parameters) {
        return parameters
                .stream()
                .map(param -> (EthereumTypeMapper.getEthereumType(param.getType())))
                .map(TypeReference::create)
                .collect(Collectors.toList());
    }

    private EthFilter generateFilter(String smartContractAddress, Event event, List<TypeReference<?>> types) {

        EthFilter filter = new EthFilter(
                DefaultBlockParameterName.LATEST,
                DefaultBlockParameterName.LATEST,
                smartContractAddress).
                addSingleTopic(EventEncoder.encode(event));
        // for each parameter, we add a null topic
        for (TypeReference t : types) {
            filter = filter.addNullTopic();
        }

        return filter;
    }

    private Transaction invokeFunctionByMethodCall(String encodedFunction, String scAddress, List<Parameter> outputs,
                                                   List<TypeReference<Type>> returnTypes) throws InvokeSmartContractFunctionFailure {
        try {
            org.web3j.protocol.core.methods.request.Transaction transaction = org.web3j.protocol.core.methods.request.Transaction
                    .createEthCallTransaction(credentials.getAddress(), scAddress, encodedFunction);
            EthCall ethCall = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();
            List<Type> decoded = FunctionReturnDecoder.decode(ethCall.getValue(), returnTypes);

            if (returnTypes.size() != decoded.size())
                throw new InvokeSmartContractFunctionFailure("Failed to invoke function by ethCall");

            Transaction tx = new LinearChainTransaction();
            tx.setState(TransactionState.RETURN_VALUE);
            List<Parameter> returnedValues = new ArrayList<>();

            for (int i = 0; i < decoded.size(); i++) {
                returnedValues.add(Parameter
                        .builder()
                        .name(outputs.get(i).getName())
                        .value(ParameterDecoder.decode(decoded.get(i)))
                        .build());
            }

            tx.setReturnValues(returnedValues);

            return tx;
        } catch (Exception e) {
            log.debug("Failed to execute smart contract function via eth_call. Reason: {}", e.getMessage());
            // this is important so we know we should try a transaction
            return null;
        }
    }

    private CompletableFuture<Transaction> invokeFunctionByTransaction(long waitFor, String encodedFunction, String scAddress) {
        return this
                .retrieveNewNonce()
                .thenApply(nonce -> {
                    try {
                        return org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                                credentials.getAddress(),
                                nonce,
                                DefaultGasProvider.GAS_PRICE,
                                DefaultGasProvider.GAS_LIMIT,
                                scAddress,
                                encodedFunction);
                    } catch (Exception e) {
                        log.error("An error occurred while trying to create a function signature!. Reason: {}", e.getMessage());
                        throw new CompletionException(wrapEthereumExceptions(e));
                    }
                })
                .thenCompose(transaction -> web3j.ethSendTransaction(transaction).sendAsync())
                .thenCompose(tx -> subscribeForTxEvent(tx.getTransactionHash(), waitFor, TransactionState.CONFIRMED, TransactionState.NOT_FOUND))
                .exceptionally((e) -> {
                    throw wrapEthereumExceptions(e);
                });
    }

    // based on https://github.com/web3j/web3j/blob/master/abi/src/test/java/org/web3j/abi/FunctionEncoderTest.java
    private List<Type> convertToSolidityTypes(List<Parameter> params) throws ParameterException {
        List<Type> result = new ArrayList<>();

        for (Parameter param : params) {
            result.add(ParameterEncoder.encode(param));
        }

        return result;
    }

    private CompletableFuture<BigInteger> retrieveNewNonce() {
        return web3j.ethGetTransactionCount(
                credentials.getAddress(), DefaultBlockParameterName.LATEST)
                .sendAsync()
                .thenApply(EthGetTransactionCount::getTransactionCount);
    }

    private static void handleDetectedState(final Optional<org.web3j.protocol.core.methods.response.Transaction> transactionDetails,
                                            final TransactionState detectedState, final TransactionState[] interesting,
                                            CompletableFuture<Transaction> future) {
        // Only complete the future if we are interested in this event
        if (Arrays.asList(interesting).contains(detectedState)) {
            final LinearChainTransaction result = new LinearChainTransaction();
            result.setState(detectedState);
            // it is important that this list is not null
            result.setReturnValues(new ArrayList<>());

            if (transactionDetails.isPresent()) {
                result.setBlock(new Block(transactionDetails.get().getBlockNumber(), transactionDetails.get().getBlockHash()));
                result.setFrom(transactionDetails.get().getFrom());
                result.setTo(transactionDetails.get().getTo());
                result.setTransactionHash(transactionDetails.get().getHash());
                result.setValue(transactionDetails.get().getValue());
            }
            future.complete(result);
        }
    }
}
