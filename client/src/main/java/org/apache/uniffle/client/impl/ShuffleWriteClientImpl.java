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

package org.apache.uniffle.client.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.uniffle.client.api.CoordinatorClient;
import org.apache.uniffle.client.api.ShuffleServerClient;
import org.apache.uniffle.client.api.ShuffleWriteClient;
import org.apache.uniffle.client.factory.CoordinatorClientFactory;
import org.apache.uniffle.client.factory.ShuffleServerClientFactory;
import org.apache.uniffle.client.request.RssAppHeartBeatRequest;
import org.apache.uniffle.client.request.RssFetchClientConfRequest;
import org.apache.uniffle.client.request.RssFetchRemoteStorageRequest;
import org.apache.uniffle.client.request.RssFinishShuffleRequest;
import org.apache.uniffle.client.request.RssGetShuffleAssignmentsRequest;
import org.apache.uniffle.client.request.RssGetShuffleResultRequest;
import org.apache.uniffle.client.request.RssRegisterShuffleRequest;
import org.apache.uniffle.client.request.RssReportShuffleResultRequest;
import org.apache.uniffle.client.request.RssSendCommitRequest;
import org.apache.uniffle.client.request.RssSendShuffleDataRequest;
import org.apache.uniffle.client.response.ClientResponse;
import org.apache.uniffle.client.response.ResponseStatusCode;
import org.apache.uniffle.client.response.RssAppHeartBeatResponse;
import org.apache.uniffle.client.response.RssFetchClientConfResponse;
import org.apache.uniffle.client.response.RssFetchRemoteStorageResponse;
import org.apache.uniffle.client.response.RssFinishShuffleResponse;
import org.apache.uniffle.client.response.RssGetShuffleAssignmentsResponse;
import org.apache.uniffle.client.response.RssGetShuffleResultResponse;
import org.apache.uniffle.client.response.RssRegisterShuffleResponse;
import org.apache.uniffle.client.response.RssReportShuffleResultResponse;
import org.apache.uniffle.client.response.RssSendCommitResponse;
import org.apache.uniffle.client.response.RssSendShuffleDataResponse;
import org.apache.uniffle.client.response.SendShuffleDataResult;
import org.apache.uniffle.common.PartitionRange;
import org.apache.uniffle.common.RemoteStorageInfo;
import org.apache.uniffle.common.ShuffleAssignmentsInfo;
import org.apache.uniffle.common.ShuffleBlockInfo;
import org.apache.uniffle.common.ShuffleServerInfo;
import org.apache.uniffle.common.exception.RssException;

public class ShuffleWriteClientImpl implements ShuffleWriteClient {

  private static final Logger LOG = LoggerFactory.getLogger(ShuffleWriteClientImpl.class);
  private String clientType;
  private int retryMax;
  private long retryIntervalMax;
  private List<CoordinatorClient> coordinatorClients = Lists.newLinkedList();
  private Set<ShuffleServerInfo> shuffleServerInfoSet = Sets.newConcurrentHashSet();
  private CoordinatorClientFactory coordinatorClientFactory;
  private ExecutorService heartBeatExecutorService;
  private int replica;
  private int replicaWrite;
  private int replicaRead;
  private boolean replicaSkipEnabled;
  private int dataTranferPoolSize;
  private final ForkJoinPool dataTransferPool;

  public ShuffleWriteClientImpl(String clientType, int retryMax, long retryIntervalMax, int heartBeatThreadNum,
                                int replica, int replicaWrite, int replicaRead, boolean replicaSkipEnabled,
                                int dataTranferPoolSize) {
    this.clientType = clientType;
    this.retryMax = retryMax;
    this.retryIntervalMax = retryIntervalMax;
    this.coordinatorClientFactory = new CoordinatorClientFactory(clientType);
    this.heartBeatExecutorService = Executors.newFixedThreadPool(heartBeatThreadNum,
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("client-heartbeat-%d").build());
    this.replica = replica;
    this.replicaWrite = replicaWrite;
    this.replicaRead = replicaRead;
    this.replicaSkipEnabled = replicaSkipEnabled;
    this.dataTranferPoolSize = dataTranferPoolSize;
    this.dataTransferPool = new ForkJoinPool(dataTranferPoolSize);
  }

  private boolean sendShuffleDataAsync(
      String appId,
      Map<ShuffleServerInfo, Map<Integer, Map<Integer, List<ShuffleBlockInfo>>>> serverToBlocks,
      Map<ShuffleServerInfo, List<Long>> serverToBlockIds,
      Map<Long, AtomicInteger> blockIdsTracker) {
    // If one or more servers is failed, the sending is not totally successful.
    AtomicBoolean isAllServersSuccess = new AtomicBoolean(true);
    if (serverToBlocks != null) {
      dataTransferPool.submit(() -> serverToBlocks.entrySet().parallelStream().forEach(entry -> {
        ShuffleServerInfo ssi = entry.getKey();
        try {
          Map<Integer, Map<Integer, List<ShuffleBlockInfo>>> shuffleIdToBlocks = entry.getValue();
          // todo: compact unnecessary blocks that reach replicaWrite
          RssSendShuffleDataRequest request = new RssSendShuffleDataRequest(
                  appId, retryMax, retryIntervalMax, shuffleIdToBlocks);
          long s = System.currentTimeMillis();
          RssSendShuffleDataResponse response = getShuffleServerClient(ssi).sendShuffleData(request);
          LOG.info("ShuffleWriteClientImpl sendShuffleData cost:" + (System.currentTimeMillis() - s));

          if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
            // mark a replica of block that has been sent
            serverToBlockIds.get(ssi).forEach(block -> blockIdsTracker.get(block).incrementAndGet());
            LOG.info("Send: " + serverToBlockIds.get(ssi).size()
                    + " blocks to [" + ssi.getId() + "] successfully");
          } else {
            isAllServersSuccess.set(false);
            LOG.warn("Send: " + serverToBlockIds.get(ssi).size() + " blocks to [" + ssi.getId()
                    + "] failed with statusCode[" + response.getStatusCode() + "], ");
          }
        } catch (Exception e) {
          isAllServersSuccess.set(false);
          LOG.warn("Send: " + serverToBlockIds.get(ssi).size() + " blocks to [" + ssi.getId() + "] failed.", e);
        }
      })).join();
    }
    return isAllServersSuccess.get();
  }

  private void genServerToBlocks(ShuffleBlockInfo sbi, List<ShuffleServerInfo> serverList,
                                 Map<ShuffleServerInfo,
                                  Map<Integer, Map<Integer, List<ShuffleBlockInfo>>>> serverToBlocks,
                                 Map<ShuffleServerInfo, List<Long>> serverToBlockIds) {
    int partitionId = sbi.getPartitionId();
    int shuffleId = sbi.getShuffleId();
    for (ShuffleServerInfo ssi : serverList) {
      if (!serverToBlockIds.containsKey(ssi)) {
        serverToBlockIds.put(ssi, Lists.newArrayList());
      }
      serverToBlockIds.get(ssi).add(sbi.getBlockId());

      if (!serverToBlocks.containsKey(ssi)) {
        serverToBlocks.put(ssi, Maps.newHashMap());
      }
      Map<Integer, Map<Integer, List<ShuffleBlockInfo>>> shuffleIdToBlocks = serverToBlocks.get(ssi);
      if (!shuffleIdToBlocks.containsKey(shuffleId)) {
        shuffleIdToBlocks.put(shuffleId, Maps.newHashMap());
      }

      Map<Integer, List<ShuffleBlockInfo>> partitionToBlocks = shuffleIdToBlocks.get(shuffleId);
      if (!partitionToBlocks.containsKey(partitionId)) {
        partitionToBlocks.put(partitionId, Lists.newArrayList());
      }
      partitionToBlocks.get(partitionId).add(sbi);
    }
  }

  @Override
  public SendShuffleDataResult sendShuffleData(String appId, List<ShuffleBlockInfo> shuffleBlockInfoList) {

    // shuffleServer -> shuffleId -> partitionId -> blocks
    Map<ShuffleServerInfo, Map<Integer,
        Map<Integer, List<ShuffleBlockInfo>>>> primaryServerToBlocks = Maps.newHashMap();
    Map<ShuffleServerInfo, Map<Integer,
      Map<Integer, List<ShuffleBlockInfo>>>> secondaryServerToBlocks = Maps.newHashMap();
    Map<ShuffleServerInfo, List<Long>> primaryServerToBlockIds = Maps.newHashMap();
    Map<ShuffleServerInfo, List<Long>> secondaryServerToBlockIds = Maps.newHashMap();

    // send shuffle block to shuffle server
    // for all ShuffleBlockInfo, create the data structure as shuffleServer -> shuffleId -> partitionId -> blocks
    // it will be helpful to send rpc request to shuffleServer

    // In order to reduce the data to send in quorum protocol,
    // we split these blocks into two rounds: primary and secondary.
    // The primary round contains [0, replicaWrite) replicas,
    // which is minimum number when there is no sending server failures.
    // The secondary round contains [replicaWrite, replica) replicas,
    // which is minimum number when there is at most *replicaWrite - replica* sending server failures.
    for (ShuffleBlockInfo sbi : shuffleBlockInfoList) {
      List<ShuffleServerInfo> allServers = sbi.getShuffleServerInfos();
      if (replicaSkipEnabled) {
        genServerToBlocks(sbi, allServers.subList(0, replicaWrite),
          primaryServerToBlocks, primaryServerToBlockIds);
        genServerToBlocks(sbi, allServers.subList(replicaWrite, replica),
          secondaryServerToBlocks, secondaryServerToBlockIds);
      } else {
        // When replicaSkip is disabled, we send data to all replicas within one round.
        genServerToBlocks(sbi, allServers,
          primaryServerToBlocks, primaryServerToBlockIds);
      }
    }

    // maintain the count of blocks that have been sent to the server
    Map<Long, AtomicInteger> blockIdsTracker = Maps.newConcurrentMap();
    primaryServerToBlockIds.values().forEach(
      blockList -> blockList.forEach(block -> blockIdsTracker.put(block, new AtomicInteger(0)))
    );
    secondaryServerToBlockIds.values().forEach(
      blockList -> blockList.forEach(block -> blockIdsTracker.put(block, new AtomicInteger(0)))
    );

    Set<Long> failedBlockIds = Sets.newConcurrentHashSet();
    Set<Long> successBlockIds = Sets.newConcurrentHashSet();
    // if send block failed, the task will fail
    // todo: better to have fallback solution when send to multiple servers

    // sent the primary round of blocks.
    boolean isAllSuccess = sendShuffleDataAsync(
        appId, primaryServerToBlocks, primaryServerToBlockIds, blockIdsTracker);

    // The secondary round of blocks is sent only when the primary group issues failed sending.
    // This should be infrequent.
    // Even though the secondary round may send blocks more than replicaWrite replicas,
    // we do not apply complicated skipping logic, because server crash is rare in production environment.
    if (!isAllSuccess && !secondaryServerToBlocks.isEmpty()) {
      LOG.info("The sending of primary round is failed partially, so start the secondary round");
      sendShuffleDataAsync(appId, secondaryServerToBlocks, secondaryServerToBlockIds, blockIdsTracker);
    }

    // check success and failed blocks according to the replicaWrite
    blockIdsTracker.entrySet().forEach(blockCt -> {
        long blockId = blockCt.getKey();
        int count = blockCt.getValue().get();
        if (count >= replicaWrite) {
          successBlockIds.add(blockId);
        } else {
          failedBlockIds.add(blockId);
        }
      }
    );
    return new SendShuffleDataResult(successBlockIds, failedBlockIds);
  }

  @Override
  public boolean sendCommit(Set<ShuffleServerInfo> shuffleServerInfoSet, String appId, int shuffleId, int numMaps) {
    AtomicInteger successfulCommit = new AtomicInteger(0);
    shuffleServerInfoSet.stream().forEach(ssi -> {
      RssSendCommitRequest request = new RssSendCommitRequest(appId, shuffleId);
      String errorMsg = "Failed to commit shuffle data to " + ssi + " for shuffleId[" + shuffleId + "]";
      long startTime = System.currentTimeMillis();
      try {
        RssSendCommitResponse response = getShuffleServerClient(ssi).sendCommit(request);
        if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
          int commitCount = response.getCommitCount();
          LOG.info("Successfully sendCommit for appId[" + appId + "], shuffleId[" + shuffleId
              + "] to ShuffleServer[" + ssi.getId() + "], cost "
              + (System.currentTimeMillis() - startTime) + " ms, got committed maps["
              + commitCount + "], map number of stage is " + numMaps);
          if (commitCount >= numMaps) {
            RssFinishShuffleResponse rfsResponse =
                getShuffleServerClient(ssi).finishShuffle(new RssFinishShuffleRequest(appId, shuffleId));
            if (rfsResponse.getStatusCode() != ResponseStatusCode.SUCCESS) {
              String msg = "Failed to finish shuffle to " + ssi + " for shuffleId[" + shuffleId
                  + "] with statusCode " + rfsResponse.getStatusCode();
              LOG.error(msg);
              throw new Exception(msg);
            } else {
              LOG.info("Successfully finish shuffle to " + ssi + " for shuffleId[" + shuffleId + "]");
            }
          }
        } else {
          String msg = errorMsg + " with statusCode " + response.getStatusCode();
          LOG.error(msg);
          throw new Exception(msg);
        }
        successfulCommit.incrementAndGet();
      } catch (Exception e) {
        LOG.error(errorMsg, e);
      }
    });
    // check if every commit/finish call is successful
    return successfulCommit.get() == shuffleServerInfoSet.size();
  }

  @Override
  public void registerShuffle(
      ShuffleServerInfo shuffleServerInfo,
      String appId,
      int shuffleId,
      List<PartitionRange> partitionRanges,
      RemoteStorageInfo remoteStorage) {
    RssRegisterShuffleRequest request =
        new RssRegisterShuffleRequest(appId, shuffleId, partitionRanges, remoteStorage);
    RssRegisterShuffleResponse response = getShuffleServerClient(shuffleServerInfo).registerShuffle(request);

    String msg = "Error happened when registerShuffle with appId[" + appId + "], shuffleId[" + shuffleId
        + "], " + shuffleServerInfo;
    throwExceptionIfNecessary(response, msg);
    shuffleServerInfoSet.add(shuffleServerInfo);
  }

  @Override
  public void registerCoordinators(String coordinators) {
    List<CoordinatorClient> clients = coordinatorClientFactory.createCoordinatorClient(coordinators);
    coordinatorClients.addAll(clients);
  }

  @Override
  public Map<String, String> fetchClientConf(int timeoutMs) {
    RssFetchClientConfResponse response =
        new RssFetchClientConfResponse(ResponseStatusCode.INTERNAL_ERROR, "Empty coordinator clients");
    for (CoordinatorClient coordinatorClient : coordinatorClients) {
      response = coordinatorClient.fetchClientConf(new RssFetchClientConfRequest(timeoutMs));
      if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
        LOG.info("Success to get conf from {}", coordinatorClient.getDesc());
        break;
      } else {
        LOG.warn("Fail to get conf from {}", coordinatorClient.getDesc());
      }
    }
    return response.getClientConf();
  }

  @Override
  public RemoteStorageInfo fetchRemoteStorage(String appId) {
    RemoteStorageInfo remoteStorage = new RemoteStorageInfo("");
    for (CoordinatorClient coordinatorClient : coordinatorClients) {
      RssFetchRemoteStorageResponse response =
          coordinatorClient.fetchRemoteStorage(new RssFetchRemoteStorageRequest(appId));
      if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
        remoteStorage = response.getRemoteStorageInfo();
        LOG.info("Success to get storage {} from {}", remoteStorage, coordinatorClient.getDesc());
        break;
      } else {
        LOG.warn("Fail to get conf from {}", coordinatorClient.getDesc());
      }
    }
    return remoteStorage;
  }

  @Override
  public ShuffleAssignmentsInfo getShuffleAssignments(String appId, int shuffleId, int partitionNum,
      int partitionNumPerRange, Set<String> requiredTags) {
    RssGetShuffleAssignmentsRequest request = new RssGetShuffleAssignmentsRequest(
        appId, shuffleId, partitionNum, partitionNumPerRange, replica, requiredTags);

    RssGetShuffleAssignmentsResponse response = new RssGetShuffleAssignmentsResponse(ResponseStatusCode.INTERNAL_ERROR);
    for (CoordinatorClient coordinatorClient : coordinatorClients) {
      try {
        response = coordinatorClient.getShuffleAssignments(request);
      } catch (Exception e) {
        LOG.error(e.getMessage());
      }

      if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
        LOG.info("Success to get shuffle server assignment from {}", coordinatorClient.getDesc());
        break;
      }
    }
    String msg = "Error happened when getShuffleAssignments with appId[" + appId + "], shuffleId[" + shuffleId
        + "], numMaps[" + partitionNum + "], partitionNumPerRange[" + partitionNumPerRange + "] to coordinator";
    throwExceptionIfNecessary(response, msg);

    return new ShuffleAssignmentsInfo(response.getPartitionToServers(), response.getServerToPartitionRanges());
  }

  @Override
  public void reportShuffleResult(
      Map<Integer, List<ShuffleServerInfo>> partitionToServers,
      String appId,
      int shuffleId,
      long taskAttemptId,
      Map<Integer, List<Long>> partitionToBlockIds,
      int bitmapNum) {
    Map<ShuffleServerInfo, List<Integer>> groupedPartitions = Maps.newConcurrentMap();
    Map<Integer, Integer> partitionReportTracker = Maps.newConcurrentMap();
    for (Map.Entry<Integer, List<ShuffleServerInfo>> entry : partitionToServers.entrySet()) {
      for (ShuffleServerInfo ssi : entry.getValue()) {
        if (!groupedPartitions.containsKey(ssi)) {
          groupedPartitions.putIfAbsent(ssi, Lists.newArrayList());
        }
        groupedPartitions.get(ssi).add(entry.getKey());
      }
      partitionReportTracker.putIfAbsent(entry.getKey(), 0);
    }
    for (Map.Entry<ShuffleServerInfo, List<Integer>> entry : groupedPartitions.entrySet()) {
      Map<Integer, List<Long>> requestBlockIds = Maps.newHashMap();
      for (Integer partitionId : entry.getValue()) {
        requestBlockIds.put(partitionId, partitionToBlockIds.get(partitionId));
      }
      RssReportShuffleResultRequest request = new RssReportShuffleResultRequest(
          appId, shuffleId, taskAttemptId, requestBlockIds, bitmapNum);
      ShuffleServerInfo ssi = entry.getKey();
      try {
        RssReportShuffleResultResponse response = getShuffleServerClient(ssi).reportShuffleResult(request);
        if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
          LOG.info("Report shuffle result to " + ssi + " for appId[" + appId
              + "], shuffleId[" + shuffleId + "] successfully");
          for (Integer partitionId : entry.getValue()) {
            partitionReportTracker.put(partitionId, partitionReportTracker.get(partitionId) + 1);
          }
        } else {
          LOG.warn("Report shuffle result to " + ssi + " for appId[" + appId
              + "], shuffleId[" + shuffleId + "] failed with " + response.getStatusCode());
        }
      } catch (Exception e) {
        LOG.warn("Report shuffle result is failed to " + ssi
            + " for appId[" + appId + "], shuffleId[" + shuffleId + "]");
      }
    }
    // quorum check
    for (Map.Entry<Integer, Integer> entry: partitionReportTracker.entrySet()) {
      if (entry.getValue() < replicaWrite) {
        throw new RssException("Quorum check of report shuffle result is failed for appId["
          + appId + "], shuffleId[" + shuffleId + "]");
      }
    }
  }

  @Override
  public Roaring64NavigableMap getShuffleResult(String clientType, Set<ShuffleServerInfo> shuffleServerInfoSet,
      String appId, int shuffleId, int partitionId) {
    RssGetShuffleResultRequest request = new RssGetShuffleResultRequest(
        appId, shuffleId, partitionId);
    boolean isSuccessful = false;
    Roaring64NavigableMap blockIdBitmap = Roaring64NavigableMap.bitmapOf();
    int successCnt = 0;
    for (ShuffleServerInfo ssi : shuffleServerInfoSet) {
      try {
        RssGetShuffleResultResponse response = getShuffleServerClient(ssi).getShuffleResult(request);
        if (response.getStatusCode() == ResponseStatusCode.SUCCESS) {
          // merge into blockIds from multiple servers.
          Roaring64NavigableMap blockIdBitmapOfServer = response.getBlockIdBitmap();
          blockIdBitmap.or(blockIdBitmapOfServer);
          successCnt++;
          if (successCnt >= replicaRead) {
            isSuccessful = true;
            break;
          }
        }
      } catch (Exception e) {
        LOG.warn("Get shuffle result is failed from " + ssi
            + " for appId[" + appId + "], shuffleId[" + shuffleId + "]");
      }
    }
    if (!isSuccessful) {
      throw new RssException("Get shuffle result is failed for appId["
          + appId + "], shuffleId[" + shuffleId + "]");
    }
    return blockIdBitmap;
  }

  @Override
  public void sendAppHeartbeat(String appId, long timeoutMs) {
    RssAppHeartBeatRequest request = new RssAppHeartBeatRequest(appId, timeoutMs);
    List<Callable<Void>> callableList = Lists.newArrayList();
    shuffleServerInfoSet.stream().forEach(shuffleServerInfo -> {
          callableList.add(() -> {
            try {
              ShuffleServerClient client =
                  ShuffleServerClientFactory.getInstance().getShuffleServerClient(clientType, shuffleServerInfo);
              RssAppHeartBeatResponse response = client.sendHeartBeat(request);
              if (response.getStatusCode() != ResponseStatusCode.SUCCESS) {
                LOG.warn("Failed to send heartbeat to " + shuffleServerInfo);
              }
            } catch (Exception e) {
              LOG.warn("Error happened when send heartbeat to " + shuffleServerInfo, e);
            }
            return null;
          });
        }
    );

    coordinatorClients.stream().forEach(coordinatorClient -> {
      callableList.add(() -> {
        try {
          RssAppHeartBeatResponse response = coordinatorClient.sendAppHeartBeat(request);
          if (response.getStatusCode() != ResponseStatusCode.SUCCESS) {
            LOG.warn("Failed to send heartbeat to " + coordinatorClient.getDesc());
          } else {
            LOG.info("Successfully send heartbeat to " + coordinatorClient.getDesc());
          }
        } catch (Exception e) {
          LOG.warn("Error happened when send heartbeat to " + coordinatorClient.getDesc(), e);
        }
        return null;
      });
    });
    try {
      List<Future<Void>> futures = heartBeatExecutorService.invokeAll(callableList, timeoutMs, TimeUnit.MILLISECONDS);
      for (Future<Void> future : futures) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
    } catch (InterruptedException ie) {
      LOG.warn("heartbeat is interrupted", ie);
    }
  }

  @Override
  public void close() {
    heartBeatExecutorService.shutdownNow();
    coordinatorClients.forEach(CoordinatorClient::close);
  }

  private void throwExceptionIfNecessary(ClientResponse response, String errorMsg) {
    if (response != null && response.getStatusCode() != ResponseStatusCode.SUCCESS) {
      LOG.error(errorMsg);
      throw new RssException(errorMsg);
    }
  }

  @VisibleForTesting
  public ShuffleServerClient getShuffleServerClient(ShuffleServerInfo shuffleServerInfo) {
    return ShuffleServerClientFactory.getInstance().getShuffleServerClient(clientType, shuffleServerInfo);
  }

}
