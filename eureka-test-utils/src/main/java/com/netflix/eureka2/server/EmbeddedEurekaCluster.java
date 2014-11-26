/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka2.server;

import com.netflix.eureka2.client.resolver.ServerResolver;
import com.netflix.eureka2.client.resolver.ServerResolvers;
import com.netflix.eureka2.config.BridgeServerConfig;
import com.netflix.eureka2.registry.datacenter.LocalDataCenterInfo.DataCenterType;
import com.netflix.eureka2.config.BridgeServerConfig.BridgeServerConfigBuilder;
import com.netflix.eureka2.server.config.ReadServerConfig;
import com.netflix.eureka2.server.config.ReadServerConfig.ReadServerConfigBuilder;
import com.netflix.eureka2.server.ServerInstance.EurekaBridgeServerInstance;
import com.netflix.eureka2.server.ServerInstance.EurekaReadServerInstance;
import com.netflix.eureka2.server.ServerInstance.EurekaWriteServerInstance;
import com.netflix.eureka2.server.config.WriteServerConfig;
import com.netflix.eureka2.server.config.WriteServerConfig.WriteServerConfigBuilder;
import com.netflix.eureka2.transport.EurekaTransports.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Run multi-node Eureka write/read clusters within single JVM.
 *
 * @author Tomasz Bak
 */
public class EmbeddedEurekaCluster {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedEurekaCluster.class);

    private static final String WRITE_SERVER_NAME = "WriteServer";
    private static final int WRITE_SERVER_PORTS_FROM = 12100;
    private static final String READ_SERVER_NAME = "ReadServer";
    private static final int READ_SERVER_PORTS_FROM = 12200;
    private static final String BRIDGE_SERVER_NAME = "BridgeServer";
    private static final int BRIDGE_SERVER_PORTS_FROM = 12900;

    private final List<ServerInstance> writeInstances = new ArrayList<>();
    private final List<ServerInstance> readInstances = new ArrayList<>();
    private final List<ServerInstance> bridgeInstances = new ArrayList<>();

    public EmbeddedEurekaCluster(int writeCount, int readCount, boolean useBridge) {

        ServerResolver.Server[] discoveryResolverServersList = new ServerResolver.Server[writeCount];
        ServerResolver.Server[] registrationResolverServersList = new ServerResolver.Server[writeCount];
        ServerResolver.Server[] replicationResolverServersList = new ServerResolver.Server[writeCount];
        WriteServerConfig[] writeServerConfigs = new WriteServerConfig[writeCount];

        // Write cluster
        for (int i = 0; i < writeCount; i++) {
            int registrationPort = WRITE_SERVER_PORTS_FROM + 10 * i;
            int discoveryPort = registrationPort + 1;
            int replicationPort = registrationPort + 2;

            discoveryResolverServersList[i] = new ServerResolver.Server("127.0.0.1", discoveryPort);
            registrationResolverServersList[i] = new ServerResolver.Server("127.0.0.1", registrationPort);
            replicationResolverServersList[i] = new ServerResolver.Server("127.0.0.1", replicationPort);
            writeServerConfigs[i] = new WriteServerConfigBuilder()
                    .withAppName(WRITE_SERVER_NAME)
                    .withVipAddress(WRITE_SERVER_NAME)
                    .withDataCenterType(DataCenterType.Basic)
                    .withWriteServerPort(registrationPort)
                    .withReadServerPort(discoveryPort)
                    .withReplicationPort(replicationPort)
                    .withCodec(Codec.Json)
                    .build();
        }

        ServerResolver discoveryResolver = ServerResolvers.from(discoveryResolverServersList);
        ServerResolver registrationResolver = ServerResolvers.from(registrationResolverServersList);
        ServerResolver replicationResolver = ServerResolvers.from(replicationResolverServersList);

        for (int i = 0; i < writeCount; i++) {
            ServerInstance instance = new EurekaWriteServerInstance(writeServerConfigs[i], replicationResolver);
            writeInstances.add(instance);
        }

        // Read cluster
        for (int i = 0; i < readCount; i++) {
            int port = READ_SERVER_PORTS_FROM + i;
            ReadServerConfig config = new ReadServerConfigBuilder()
                    .withAppName(READ_SERVER_NAME)
                    .withVipAddress(READ_SERVER_NAME)
                    .withDataCenterType(DataCenterType.Basic)
                    .withReadServerPort(port)
                    .withCodec(Codec.Json)
                    .withWriteClusterRegistrationPort(WRITE_SERVER_PORTS_FROM)
                    .withWriteClusterDiscoveryPort(WRITE_SERVER_PORTS_FROM + 1)
                    .build();
            ServerInstance instance = new EurekaReadServerInstance(config, registrationResolver, discoveryResolver);
            readInstances.add(instance);
        }

        // Bridge cluster
        if (useBridge) {
            int port = BRIDGE_SERVER_PORTS_FROM;
            BridgeServerConfig config = new BridgeServerConfigBuilder()
                    .withAppName(BRIDGE_SERVER_NAME)
                    .withVipAddress(BRIDGE_SERVER_NAME)
                    .withDataCenterType(DataCenterType.Basic)
                    .withWriteServerPort(port)
                    .withReadServerPort(port + 1)  // explicitly set it to a different port to verify
                    .withReplicationPort(port + 2)  // explicitly set it to a different port to verify
                    .withCodec(Codec.Json)
                    .withRefreshRateSec(30)
                    .build();
            ServerInstance instance = new EurekaBridgeServerInstance(config, replicationResolver);
            bridgeInstances.add(instance);
        }
    }

    public void waitTillShutdown() {
        final CountDownLatch shutdownFinished = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    shutdown();
                    logger.info("Leaving main loop - shutdown finished.");
                } finally {
                    shutdownFinished.countDown();
                }
            }
        });
        while (true) {
            try {
                shutdownFinished.await();
                return;
            } catch (InterruptedException e) {
                // IGNORE
            }
        }
    }

    public void shutdown() {
        for (ServerInstance instance : writeInstances) {
            instance.shutdown();
        }
        for (ServerInstance instance : readInstances) {
            instance.shutdown();
        }
        for (ServerInstance instance : bridgeInstances) {
            instance.shutdown();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("ERROR: required number of write and read servers");
            System.exit(-1);
        }
        int writeCount = Integer.valueOf(args[0]);
        int readCount = Integer.valueOf(args[1]);

        boolean useBridge = false;
        if (args.length >= 3) {
            useBridge = Boolean.valueOf(args[2]);
        }
        new EmbeddedEurekaCluster(writeCount, readCount, useBridge).waitTillShutdown();
    }
}
