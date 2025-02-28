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

package org.apache.spark.shuffle;

import java.util.Map;

import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.apache.spark.SparkConf;
import org.junit.jupiter.api.Test;

import org.apache.uniffle.client.util.RssClientConfig;
import org.apache.uniffle.storage.util.StorageType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RssSparkShuffleUtilsTest {
  @Test
  public void odfsConfigurationTest() {
    SparkConf conf = new SparkConf();
    Configuration conf1 = RssSparkShuffleUtils.newHadoopConfiguration(conf);
    assertFalse(conf1.getBoolean("dfs.namenode.odfs.enable", false));
    assertEquals("org.apache.hadoop.fs.Hdfs", conf1.get("fs.AbstractFileSystem.hdfs.impl"));

    conf.set(RssSparkConfig.RSS_OZONE_DFS_NAMENODE_ODFS_ENABLE, "true");
    conf1 = RssSparkShuffleUtils.newHadoopConfiguration(conf);
    assertTrue(conf1.getBoolean("dfs.namenode.odfs.enable", false));
    assertEquals("org.apache.hadoop.odfs.HdfsOdfsFilesystem", conf1.get("fs.hdfs.impl"));
    assertEquals("org.apache.hadoop.odfs.HdfsOdfs", conf1.get("fs.AbstractFileSystem.hdfs.impl"));

    conf.set(RssSparkConfig.RSS_OZONE_FS_HDFS_IMPL, "expect_odfs_impl");
    conf.set(RssSparkConfig.RSS_OZONE_FS_ABSTRACT_FILE_SYSTEM_HDFS_IMPL, "expect_odfs_abstract_impl");
    conf1 = RssSparkShuffleUtils.newHadoopConfiguration(conf);
    assertEquals("expect_odfs_impl", conf1.get("fs.hdfs.impl"));
    assertEquals("expect_odfs_abstract_impl", conf1.get("fs.AbstractFileSystem.hdfs.impl"));
  }

  @Test
  public void applyDynamicClientConfTest() {
    SparkConf conf = new SparkConf();
    Map<String, String> clientConf = Maps.newHashMap();
    String remoteStoragePath = "hdfs://path1";
    String mockKey = "spark.mockKey";
    String mockValue = "v";

    clientConf.put(RssClientConfig.RSS_REMOTE_STORAGE_PATH, remoteStoragePath);
    clientConf.put(RssClientConfig.RSS_CLIENT_TYPE, RssClientConfig.RSS_CLIENT_TYPE_DEFAULT_VALUE);
    clientConf.put(RssClientConfig.RSS_CLIENT_RETRY_MAX,
        Integer.toString(RssClientConfig.RSS_CLIENT_RETRY_MAX_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_CLIENT_RETRY_INTERVAL_MAX,
        Long.toString(RssClientConfig.RSS_CLIENT_RETRY_INTERVAL_MAX_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_DATA_REPLICA,
        Integer.toString(RssClientConfig.RSS_DATA_REPLICA_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_DATA_REPLICA_WRITE,
        Integer.toString(RssClientConfig.RSS_DATA_REPLICA_WRITE_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_DATA_REPLICA_READ,
        Integer.toString(RssClientConfig.RSS_DATA_REPLICA_READ_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_HEARTBEAT_INTERVAL,
        Long.toString(RssClientConfig.RSS_HEARTBEAT_INTERVAL_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_STORAGE_TYPE, StorageType.MEMORY_LOCALFILE_HDFS.name());
    clientConf.put(RssClientConfig.RSS_CLIENT_SEND_CHECK_INTERVAL_MS,
        Long.toString(RssClientConfig.RSS_CLIENT_SEND_CHECK_INTERVAL_MS_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_CLIENT_SEND_CHECK_TIMEOUT_MS,
        Long.toString(RssClientConfig.RSS_CLIENT_SEND_CHECK_TIMEOUT_MS_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_PARTITION_NUM_PER_RANGE,
        Integer.toString(RssClientConfig.RSS_PARTITION_NUM_PER_RANGE_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_INDEX_READ_LIMIT,
        Integer.toString(RssClientConfig.RSS_INDEX_READ_LIMIT_DEFAULT_VALUE));
    clientConf.put(RssClientConfig.RSS_CLIENT_READ_BUFFER_SIZE,
        RssClientConfig.RSS_CLIENT_READ_BUFFER_SIZE_DEFAULT_VALUE);
    clientConf.put(mockKey, mockValue);

    RssSparkShuffleUtils.applyDynamicClientConf(conf, clientConf);
    assertEquals(remoteStoragePath, conf.get(RssSparkConfig.RSS_REMOTE_STORAGE_PATH));
    assertEquals(RssClientConfig.RSS_CLIENT_TYPE_DEFAULT_VALUE,
        conf.get(RssSparkConfig.RSS_CLIENT_TYPE));
    assertEquals(Integer.toString(RssClientConfig.RSS_CLIENT_RETRY_MAX_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_CLIENT_RETRY_MAX));
    assertEquals(Long.toString(RssClientConfig.RSS_CLIENT_RETRY_INTERVAL_MAX_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_CLIENT_RETRY_INTERVAL_MAX));
    assertEquals(Integer.toString(RssClientConfig.RSS_DATA_REPLICA_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_DATA_REPLICA));
    assertEquals(Integer.toString(RssClientConfig.RSS_DATA_REPLICA_WRITE_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_DATA_REPLICA_WRITE));
    assertEquals(Integer.toString(RssClientConfig.RSS_DATA_REPLICA_READ_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_DATA_REPLICA_READ));
    assertEquals(Long.toString(RssClientConfig.RSS_HEARTBEAT_INTERVAL_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_HEARTBEAT_INTERVAL));
    assertEquals(StorageType.MEMORY_LOCALFILE_HDFS.name(), conf.get(RssSparkConfig.RSS_STORAGE_TYPE));
    assertEquals(Long.toString(RssClientConfig.RSS_CLIENT_SEND_CHECK_INTERVAL_MS_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_CLIENT_SEND_CHECK_INTERVAL_MS));
    assertEquals(Long.toString(RssClientConfig.RSS_CLIENT_SEND_CHECK_TIMEOUT_MS_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_CLIENT_SEND_CHECK_TIMEOUT_MS));
    assertEquals(Integer.toString(RssClientConfig.RSS_PARTITION_NUM_PER_RANGE_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_PARTITION_NUM_PER_RANGE));
    assertEquals(Integer.toString(RssClientConfig.RSS_INDEX_READ_LIMIT_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_INDEX_READ_LIMIT));
    assertEquals(RssClientConfig.RSS_CLIENT_READ_BUFFER_SIZE_DEFAULT_VALUE,
        conf.get(RssSparkConfig.RSS_CLIENT_READ_BUFFER_SIZE));
    assertEquals(mockValue, conf.get(mockKey));

    String remoteStoragePath2 = "hdfs://path2";
    clientConf = Maps.newHashMap();
    clientConf.put(RssClientConfig.RSS_STORAGE_TYPE, StorageType.MEMORY_HDFS.name());
    clientConf.put(RssSparkConfig.RSS_REMOTE_STORAGE_PATH, remoteStoragePath2);
    clientConf.put(mockKey, "won't be rewrite");
    clientConf.put(RssClientConfig.RSS_CLIENT_RETRY_MAX, "99999");
    RssSparkShuffleUtils.applyDynamicClientConf(conf, clientConf);
    // overwrite
    assertEquals(remoteStoragePath2, conf.get(RssSparkConfig.RSS_REMOTE_STORAGE_PATH));
    assertEquals(StorageType.MEMORY_HDFS.name(), conf.get(RssSparkConfig.RSS_STORAGE_TYPE));
    // won't be overwrite
    assertEquals(mockValue, conf.get(mockKey));
    assertEquals(Integer.toString(RssClientConfig.RSS_CLIENT_RETRY_MAX_DEFAULT_VALUE),
        conf.get(RssSparkConfig.RSS_CLIENT_RETRY_MAX));
  }
}
