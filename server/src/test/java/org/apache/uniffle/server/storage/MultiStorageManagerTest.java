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

package org.apache.uniffle.server.storage;

import java.util.List;

import com.google.common.collect.Lists;
import org.apache.uniffle.server.ShuffleDataFlushEvent;
import org.apache.uniffle.server.ShuffleServerConf;
import org.junit.jupiter.api.Test;

import org.apache.uniffle.common.RemoteStorageInfo;
import org.apache.uniffle.common.ShufflePartitionedBlock;
import org.apache.uniffle.storage.common.HdfsStorage;
import org.apache.uniffle.storage.common.LocalStorage;
import org.apache.uniffle.storage.util.StorageType;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiStorageManagerTest {

  @Test
  public void selectStorageManagerTest() {
    String remoteStorage = "test";
    String appId = "selectStorageManagerTest_appId";
    ShuffleServerConf conf = new ShuffleServerConf();
    conf.setLong(ShuffleServerConf.FLUSH_COLD_STORAGE_THRESHOLD_SIZE, 2000L);
    conf.setString(ShuffleServerConf.RSS_STORAGE_BASE_PATH, "test");
    conf.setLong(ShuffleServerConf.DISK_CAPACITY, 1024L * 1024L * 1024L);
    conf.setString(ShuffleServerConf.RSS_STORAGE_TYPE, StorageType.MEMORY_LOCALFILE_HDFS.name());
    MultiStorageManager manager = new MultiStorageManager(conf, "shuffleServerId");
    manager.registerRemoteStorage(appId, new RemoteStorageInfo(remoteStorage));
    List<ShufflePartitionedBlock> blocks = Lists.newArrayList(new ShufflePartitionedBlock(100, 1000, 1, 1, 1L, null));
    ShuffleDataFlushEvent event = new ShuffleDataFlushEvent(
        1, appId, 1, 1, 1, 1000, blocks, null, null);
    assertTrue((manager.selectStorage(event) instanceof LocalStorage));
    event = new ShuffleDataFlushEvent(
        1, appId, 1, 1, 1, 1000000, blocks, null, null);
    assertTrue((manager.selectStorage(event) instanceof HdfsStorage));
  }
}
