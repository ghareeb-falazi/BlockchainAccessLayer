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
package blockchains.iaas.uni.stuttgart.de.adaptation.adapters.fabric;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import blockchains.iaas.uni.stuttgart.de.adaptation.interfaces.BlockchainAdapter;
import blockchains.iaas.uni.stuttgart.de.adaptation.utils.ScipParser;
import blockchains.iaas.uni.stuttgart.de.exceptions.InvalidTransactionException;
import blockchains.iaas.uni.stuttgart.de.exceptions.InvokeSmartContractFunctionFailure;
import blockchains.iaas.uni.stuttgart.de.model.SmartContractFunctionArgument;
import blockchains.iaas.uni.stuttgart.de.model.Transaction;
import blockchains.iaas.uni.stuttgart.de.model.TransactionState;
import io.reactivex.Observable;
import lombok.Builder;
import org.apache.http.MethodNotSupportedException;
import org.hyperledger.fabric.gateway.Contract;
import org.hyperledger.fabric.gateway.ContractException;
import org.hyperledger.fabric.gateway.Gateway;
import org.hyperledger.fabric.gateway.Network;
import org.hyperledger.fabric.gateway.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Builder
public class FabricAdapter implements BlockchainAdapter {
    private String walletPath;
    private String userName;
    private String connectionProfilePath;
    private static final Logger log = LoggerFactory.getLogger(FabricAdapter.class);

    public String getWalletPath() {
        return walletPath;
    }

    public void setWalletPath(String walletPath) {
        this.walletPath = walletPath;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getConnectionProfilePath() {
        return connectionProfilePath;
    }

    public void setConnectionProfilePath(String connectionProfilePath) {
        this.connectionProfilePath = connectionProfilePath;
    }

    @Override
    public CompletableFuture<Transaction> submitTransaction(long waitFor, String receiverAddress, BigDecimal value) throws InvalidTransactionException, MethodNotSupportedException {
        throw new MethodNotSupportedException("Fabric does not support submitting monetary transactions!");
    }

    @Override
    public Observable<Transaction> receiveTransactions(long waitFor, String senderId) throws MethodNotSupportedException {
        throw new MethodNotSupportedException("Fabric does not support receiving monetary transactions!");
    }

    @Override
    public CompletableFuture<TransactionState> ensureTransactionState(long waitFor, String transactionId) throws MethodNotSupportedException {
        throw new MethodNotSupportedException("Fabric does not support monetary transactions!");
    }

    @Override
    public CompletableFuture<TransactionState> detectOrphanedTransaction(String transactionId) throws MethodNotSupportedException {
        throw new MethodNotSupportedException("Fabric does not support monetary transactions!");
    }

    @Override
    public CompletableFuture<Transaction> invokeSmartContract(String functionIdentifier, List<SmartContractFunctionArgument> parameters, double requiredConfidence) {
        ScipParser parser = ScipParser.parse(functionIdentifier);
        String[] pathSegments = parser.getFunctionPathSegments();
        String channelName;
        String chaincodeName;
        String smartContractName = null;
        CompletableFuture<Transaction> result;

        if (pathSegments.length != 3 && pathSegments.length != 2) {
            String message = String.format("Unable to identify the path to the requested function. Expected path segements: 3 or 2. Found path segments: %s", pathSegments.length);
            log.error(message);
            result = new CompletableFuture<>();
            result.completeExceptionally(new InvokeSmartContractFunctionFailure(message));
        } else {
            channelName = pathSegments[0];
            chaincodeName = pathSegments[1];

            if (pathSegments.length == 3) {
                smartContractName = pathSegments[2];
            }

            final String FUNCTION_NAME = parser.getFunctionName();

            // Load an existing wallet holding identities used to access the network.
            Path walletDirectory = Paths.get(walletPath);

            try {
                Wallet wallet = Wallet.createFileSystemWallet(walletDirectory);

                // Path to a connection profile describing the network.
                Path networkConfigFile = Paths.get(this.connectionProfilePath);

                // Configure the gateway connection used to access the network.
                Gateway.Builder builder = Gateway.createBuilder()
                        .identity(wallet, userName)
                        .networkConfig(networkConfigFile);

                // Create a gateway connection
                try (Gateway gateway = builder.connect()) {

                    // Obtain a smart contract deployed on the network.
                    Network network = gateway.getNetwork(channelName);
                    Contract contract;

                    if (smartContractName != null) {
                        contract = network.getContract(chaincodeName, smartContractName);
                    } else {
                        contract = network.getContract(chaincodeName);
                    }
                    byte[] resultAsBytes = contract.evaluateTransaction(
                            FUNCTION_NAME,
                            parameters
                                    .stream()
                                    .map(SmartContractFunctionArgument::getValue)
                                    .toArray(String[]::new));
                    String resultS = new String(resultAsBytes, StandardCharsets.UTF_8);
                    log.info(resultS);
                    result = new CompletableFuture<>();
                    Transaction resultT = new Transaction();
                    resultT.setReturnValue(resultS);
                    resultT.setState(TransactionState.RETURN_VALUE);
                    result.complete(resultT);

//                    // Submit transactions that store state to the ledger.
//                    byte[] createCarResult = contract.submitTransaction("createCar", "CAR10", "VW", "Polo", "Grey", "Mary");
//                    log.info(new String(createCarResult, StandardCharsets.UTF_8));
//
//                    // Evaluate transactions that query state from the ledger.
//                    byte[] queryAllCarsResult = contract.evaluateTransaction("queryAllCars");
//                    System.out.println(new String(queryAllCarsResult, StandardCharsets.UTF_8));
                }
            } catch (IOException | ContractException e) {
                result = new CompletableFuture<>();
                result.completeExceptionally(new InvokeSmartContractFunctionFailure(e));
            }
        }

        return result;
    }
}