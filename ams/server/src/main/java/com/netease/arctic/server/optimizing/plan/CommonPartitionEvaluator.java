/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.optimizing.plan;

import com.netease.arctic.server.optimizing.OptimizingConfig;
import com.netease.arctic.server.optimizing.OptimizingType;
import com.netease.arctic.server.table.TableRuntime;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CommonPartitionEvaluator implements PartitionEvaluator {
  private static final Logger LOG = LoggerFactory.getLogger(CommonPartitionEvaluator.class);

  private final Set<String> deleteFileSet = Sets.newHashSet();
  protected final TableRuntime tableRuntime;

  private final String partition;
  protected final OptimizingConfig config;
  protected final long fragmentSize;
  protected final long planTime;
  protected final Map<String, String> partitionProperties;
  private final boolean reachFullInterval;

  // fragment files
  protected int fragmentFileCount = 0;
  protected long fragmentFileSize = 0;

  // segment files
  protected int segmentFileCount = 0;
  protected long segmentFileSize = 0;
  protected int rewriteSegmentFileCount = 0;
  protected long rewriteSegmentFileSize = 0L;
  protected int rewritePosSegmentFileCount = 0;
  protected long rewritePosSegmentFileSize = 0L;

  // delete files
  protected int equalityDeleteFileCount = 0;
  protected long equalityDeleteFileSize = 0L;
  protected int posDeleteFileCount = 0;
  protected long posDeleteFileSize = 0L;

  private long cost = -1;
  private Boolean necessary = null;
  private OptimizingType optimizingType = null;
  private String name;

  public CommonPartitionEvaluator(TableRuntime tableRuntime, String partition, Map<String, String> partitionProperties,
                                  long planTime) {
    this.partition = partition;
    this.tableRuntime = tableRuntime;
    this.config = tableRuntime.getOptimizingConfig();
    this.fragmentSize = config.getTargetSize() / config.getFragmentRatio();
    this.planTime = planTime;
    this.reachFullInterval = config.getFullTriggerInterval() >= 0 &&
        planTime - tableRuntime.getLastFullOptimizingTime() > config.getFullTriggerInterval();
    this.partitionProperties = partitionProperties;
  }

  @Override
  public String getPartition() {
    return partition;
  }

  protected boolean isFragmentFile(DataFile dataFile) {
    return dataFile.fileSizeInBytes() <= fragmentSize;
  }

  @Override
  public boolean addFile(DataFile dataFile, List<ContentFile<?>> deletes) {
    if (!config.isEnabled()) {
      return false;
    }
    if (isFragmentFile(dataFile)) {
      return addFragmentFile(dataFile, deletes);
    } else {
      return addSegmentFile(dataFile, deletes);
    }
  }

  private boolean isDuplicateDelete(ContentFile<?> delete) {
    boolean deleteExist = deleteFileSet.contains(delete.path().toString());
    if (!deleteExist) {
      deleteFileSet.add(delete.path().toString());
    }
    return deleteExist;
  }

  private boolean addFragmentFile(DataFile dataFile, List<ContentFile<?>> deletes) {
    if (!fileShouldRewrite(dataFile, deletes)) {
      return false;
    }
    fragmentFileSize += dataFile.fileSizeInBytes();
    fragmentFileCount += 1;

    for (ContentFile<?> delete : deletes) {
      addDelete(delete);
    }
    return true;
  }

  private boolean addSegmentFile(DataFile dataFile, List<ContentFile<?>> deletes) {
    if (fileShouldRewrite(dataFile, deletes)) {
      rewriteSegmentFileSize += dataFile.fileSizeInBytes();
      rewriteSegmentFileCount += 1;
    } else if (segmentFileShouldRewritePos(dataFile, deletes)) {
      rewritePosSegmentFileSize += dataFile.fileSizeInBytes();
      rewritePosSegmentFileCount += 1;
    } else {
      return false;
    }

    segmentFileSize += dataFile.fileSizeInBytes();
    segmentFileCount += 1;
    for (ContentFile<?> delete : deletes) {
      addDelete(delete);
    }
    return true;
  }

  protected boolean fileShouldFullOptimizing(DataFile dataFile, List<ContentFile<?>> deleteFiles) {
    if (config.isFullRewriteAllFiles()) {
      return true;
    }
    if (isFragmentFile(dataFile)) {
      return true;
    }
    // if a file is related any delete files or is not big enough, it should full optimizing
    return !deleteFiles.isEmpty() || dataFile.fileSizeInBytes() < config.getTargetSize() * 0.9;
  }

  public boolean fileShouldRewrite(DataFile dataFile, List<ContentFile<?>> deletes) {
    if (isFullOptimizing()) {
      return fileShouldFullOptimizing(dataFile, deletes);
    }
    if (isFragmentFile(dataFile)) {
      return true;
    }
    return getRecordCount(deletes) > dataFile.recordCount() * config.getMajorDuplicateRatio();
  }

  public boolean segmentFileShouldRewritePos(DataFile dataFile, List<ContentFile<?>> deletes) {
    if (isFullOptimizing()) {
      return false;
    }
    if (isFragmentFile(dataFile)) {
      return false;
    }
    if (deletes.stream().anyMatch(delete -> delete.content() == FileContent.EQUALITY_DELETES)) {
      return true;
    } else if (deletes.stream().filter(delete -> delete.content() == FileContent.POSITION_DELETES).count() >= 2) {
      return true;
    } else {
      return false;
    }
  }

  protected boolean isFullOptimizing() {
    return reachFullInterval();
  }

  private long getRecordCount(List<ContentFile<?>> files) {
    return files.stream().mapToLong(ContentFile::recordCount).sum();
  }

  private void addDelete(ContentFile<?> delete) {
    if (isDuplicateDelete(delete)) {
      return;
    }
    if (delete.content() == FileContent.POSITION_DELETES) {
      posDeleteFileCount += 1;
      posDeleteFileSize += delete.fileSizeInBytes();
    } else {
      equalityDeleteFileCount += 1;
      equalityDeleteFileSize += delete.fileSizeInBytes();
    }
  }

  @Override
  public boolean isNecessary() {
    if (necessary == null) {
      necessary = isFullNecessary() || isMajorNecessary() || isMinorNecessary();
      LOG.debug("{} necessary = {}, {}", name(), necessary, this);
    }
    return necessary;
  }

  @Override
  public long getCost() {
    if (cost < 0) {
      // We estimate that the cost of writing is 3 times that of reading
      cost = (rewriteSegmentFileSize + fragmentFileSize) * 4 +
          rewritePosSegmentFileSize / 10 + posDeleteFileSize + equalityDeleteFileSize;
      int fileCnt = rewriteSegmentFileCount + rewritePosSegmentFileCount + fragmentFileCount + posDeleteFileCount +
          equalityDeleteFileCount;
      cost += fileCnt * config.getOpenFileCost();
    }
    return cost;
  }

  @Override
  public PartitionEvaluator.Weight getWeight() {
    return new Weight(getCost());
  }

  @Override
  public OptimizingType getOptimizingType() {
    if (optimizingType == null) {
      optimizingType = isFullNecessary() ? OptimizingType.FULL :
          isMajorNecessary() ? OptimizingType.MAJOR : OptimizingType.MINOR;
      LOG.debug("{} optimizingType = {} ", name(), optimizingType);
    }
    return optimizingType;
  }

  public boolean isMajorNecessary() {
    return rewriteSegmentFileSize > 0;
  }

  public boolean isMinorNecessary() {
    int smallFileCount = fragmentFileCount + equalityDeleteFileCount;
    return smallFileCount >= config.getMinorLeastFileCount() || (smallFileCount > 1 && reachMinorInterval());
  }

  protected boolean reachMinorInterval() {
    return config.getMinorLeastInterval() >= 0 &&
        planTime - tableRuntime.getLastMinorOptimizingTime() > config.getMinorLeastInterval();
  }

  protected boolean reachFullInterval() {
    return reachFullInterval;
  }

  public boolean isFullNecessary() {
    if (!reachFullInterval()) {
      return false;
    }
    return anyDeleteExist() || fragmentFileCount >= 2;
  }

  protected String name() {
    if (name == null) {
      name = String.format("partition %s of %s", partition, tableRuntime.getTableIdentifier().toString());
    }
    return name;
  }

  public boolean anyDeleteExist() {
    return equalityDeleteFileCount > 0 || posDeleteFileCount > 0;
  }

  @Override
  public int getFragmentFileCount() {
    return fragmentFileCount;
  }

  @Override
  public long getFragmentFileSize() {
    return fragmentFileSize;
  }

  @Override
  public int getSegmentFileCount() {
    return segmentFileCount;
  }

  @Override
  public long getSegmentFileSize() {
    return segmentFileSize;
  }

  public int getRewriteSegmentFileCount() {
    return rewriteSegmentFileCount;
  }

  public long getRewriteSegmentFileSize() {
    return rewriteSegmentFileSize;
  }

  public int getRewritePosSegmentFileCount() {
    return rewritePosSegmentFileCount;
  }

  public long getRewritePosSegmentFileSize() {
    return rewritePosSegmentFileSize;
  }

  @Override
  public int getEqualityDeleteFileCount() {
    return equalityDeleteFileCount;
  }

  @Override
  public long getEqualityDeleteFileSize() {
    return equalityDeleteFileSize;
  }

  @Override
  public int getPosDeleteFileCount() {
    return posDeleteFileCount;
  }

  @Override
  public long getPosDeleteFileSize() {
    return posDeleteFileSize;
  }

  public static class Weight implements PartitionEvaluator.Weight {

    private final long cost;

    public Weight(long cost) {
      this.cost = cost;
    }

    @Override
    public int compareTo(PartitionEvaluator.Weight o) {
      return Long.compare(this.cost, ((Weight) o).cost);
    }
  }

  @Override
  public String toString() {
    return "CommonPartitionEvaluator{" +
        "partition='" + partition + '\'' +
        ", config=" + config +
        ", fragmentSize=" + fragmentSize +
        ", planTime=" + planTime +
        ", lastMinorOptimizeTime=" + tableRuntime.getLastMinorOptimizingTime() +
        ", lastMajorOptimizeTime=" + tableRuntime.getLastMajorOptimizingTime() +
        ", lastFullOptimizeTime=" + tableRuntime.getLastFullOptimizingTime() +
        ", fragmentFileCount=" + fragmentFileCount +
        ", fragmentFileSize=" + fragmentFileSize +
        ", segmentFileCount=" + segmentFileCount +
        ", segmentFileSize=" + segmentFileSize +
        ", rewriteSegmentFileCount=" + rewriteSegmentFileCount +
        ", rewriteSegmentFileSize=" + rewriteSegmentFileSize +
        ", rewritePosSegmentFileCount=" + rewritePosSegmentFileCount +
        ", rewritePosSegmentFileSize=" + rewritePosSegmentFileSize +
        ", equalityDeleteFileCount=" + equalityDeleteFileCount +
        ", equalityDeleteFileSize=" + equalityDeleteFileSize +
        ", posDeleteFileCount=" + posDeleteFileCount +
        ", posDeleteFileSize=" + posDeleteFileSize +
        '}';
  }
}
