/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.uniffle.test;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.File;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.jupiter.api.Test;

import org.apache.uniffle.client.api.CoordinatorClient;
import org.apache.uniffle.client.factory.CoordinatorClientFactory;
import org.apache.uniffle.client.request.RssAccessClusterRequest;
import org.apache.uniffle.client.response.ResponseStatusCode;
import org.apache.uniffle.client.response.RssAccessClusterResponse;
import org.apache.uniffle.common.util.Constants;
import org.apache.uniffle.coordinator.CoordinatorConf;
import org.apache.uniffle.server.ShuffleServer;
import org.apache.uniffle.server.ShuffleServerConf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccessClusterTest extends CoordinatorTestBase {

  @Test
  public void test(@TempDir File tempDir) throws Exception {
    File cfgFile = File.createTempFile("tmp", ".conf", tempDir);
    FileWriter fileWriter = new FileWriter(cfgFile);
    PrintWriter printWriter = new PrintWriter(fileWriter);
    printWriter.println("9527");
    printWriter.println(" 135 ");
    printWriter.println("2 ");
    printWriter.flush();
    printWriter.close();

    CoordinatorConf coordinatorConf = getCoordinatorConf();
    coordinatorConf.setInteger("rss.coordinator.access.loadChecker.serverNum.threshold", 2);
    coordinatorConf.setString("rss.coordinator.access.candidates.path", cfgFile.getAbsolutePath());
    coordinatorConf.setString(
        "rss.coordinator.access.checkers",
        "org.apache.uniffle.coordinator.AccessCandidatesChecker,org.apache.uniffle.coordinator.AccessClusterLoadChecker");
    createCoordinatorServer(coordinatorConf);

    ShuffleServerConf shuffleServerConf = getShuffleServerConf();
    createShuffleServer(shuffleServerConf);
    startServers();

    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);
    String accessId = "111111";
    RssAccessClusterRequest request = new RssAccessClusterRequest(
        accessId, Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION), 2000);
    RssAccessClusterResponse response = coordinatorClient.accessCluster(request);
    assertEquals(ResponseStatusCode.ACCESS_DENIED, response.getStatusCode());
    assertTrue(response.getMessage().startsWith("Denied by AccessCandidatesChecker"));

    accessId = "135";
    request = new RssAccessClusterRequest(
        accessId, Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION), 2000);
    response = coordinatorClient.accessCluster(request);
    assertEquals(ResponseStatusCode.ACCESS_DENIED, response.getStatusCode());
    assertTrue(response.getMessage().startsWith("Denied by AccessClusterLoadChecker"));

    shuffleServerConf.setInteger("rss.rpc.server.port", SHUFFLE_SERVER_PORT + 2);
    shuffleServerConf.setInteger("rss.jetty.http.port", 18082);
    ShuffleServer shuffleServer = new ShuffleServer(shuffleServerConf);
    shuffleServer.start();
    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);

    CoordinatorClient client = new CoordinatorClientFactory("GRPC")
        .createCoordinatorClient(LOCALHOST, COORDINATOR_PORT_1 + 13);
    request = new RssAccessClusterRequest(
        accessId, Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION), 2000);
    response = client.accessCluster(request);
    assertEquals(ResponseStatusCode.INTERNAL_ERROR, response.getStatusCode());
    assertTrue(response.getMessage().startsWith("UNAVAILABLE: io exception"));

    request = new RssAccessClusterRequest(
        accessId, Sets.newHashSet(Constants.SHUFFLE_SERVER_VERSION), 2000);
    response = coordinatorClient.accessCluster(request);
    assertEquals(ResponseStatusCode.SUCCESS, response.getStatusCode());
    assertTrue(response.getMessage().startsWith("SUCCESS"));
    shuffleServer.stopServer();
  }
}

