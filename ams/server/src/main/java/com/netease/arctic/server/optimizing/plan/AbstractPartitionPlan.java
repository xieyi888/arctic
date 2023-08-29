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

import com.netease.arctic.optimizing.OptimizingInputProperties;
import com.netease.arctic.optimizing.RewriteFilesInput;
import com.netease.arctic.server.optimizing.OptimizingConfig;
import com.netease.arctic.server.optimizing.OptimizingType;
import com.netease.arctic.server.table.TableRuntime;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.utils.TablePropertyUtil;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.util.BinPacking;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractPartitionPlan implements PartitionEvaluator {
  public static final int INVALID_SEQUENCE = -1;

  protected final String partition;
  protected final OptimizingConfig config;
  protected final TableRuntime tableRuntime;
  private CommonPartitionEvaluator evaluator;
  private TaskSplitter taskSplitter;

  protected ArcticTable tableObject;
  private long fromSequence = INVALID_SEQUENCE;
  private long toSequence = INVALID_SEQUENCE;
  protected final long planTime;
  protected final Map<String, String> partitionProperties;

  protected final Map<DataFile, List<ContentFile<?>>> rewriteDataFiles = Maps.newHashMap();
  protected final Map<DataFile, List<ContentFile<?>>> rewritePosDataFiles = Maps.newHashMap();
  // reserved Delete files are Delete files which are related to Data files not optimized in this plan
  protected final Set<String> reservedDeleteFiles = Sets.newHashSet();

  public AbstractPartitionPlan(TableRuntime tableRuntime,
                               ArcticTable table, String partition, long planTime) {
    this.partition = partition;
    this.tableObject = table;
    this.config = tableRuntime.getOptimizingConfig();
    this.tableRuntime = tableRuntime;
    this.planTime = planTime;
    this.partitionProperties = TablePropertyUtil.getPartitionProperties(table, partition);
  }

  @Override
  public String getPartition() {
    return partition;
  }

  protected CommonPartitionEvaluator evaluator() {
    if (evaluator == null) {
      evaluator = buildEvaluator();
    }
    return evaluator;
  }

  protected CommonPartitionEvaluator buildEvaluator() {
    return new CommonPartitionEvaluator(tableRuntime, partition, partitionProperties, planTime);
  }

  @Override
  public boolean isNecessary() {
    return evaluator().isNecessary();
  }

  @Override
  public OptimizingType getOptimizingType() {
    return evaluator().getOptimizingType();
  }

  @Override
  public long getCost() {
    return evaluator().getCost();
  }

  @Override
  public boolean addFile(DataFile dataFile, List<ContentFile<?>> deletes) {
    boolean added = evaluator().addFile(dataFile, deletes);
    if (added) {
      if (evaluator().fileShouldRewrite(dataFile, deletes)) {
        rewriteDataFiles.put(dataFile, deletes);
      } else if (evaluator().segmentFileShouldRewritePos(dataFile, deletes)) {
        rewritePosDataFiles.put(dataFile, deletes);
      } else {
        added = false;
      }
    }
    if (!added) {
      // if the Data file is not added, it's Delete files should not be removed from iceberg
      deletes.stream().map(delete -> delete.path().toString()).forEach(reservedDeleteFiles::add);
    }
    return added;
  }

  public List<TaskDescriptor> splitTasks(int targetTaskCount) {
    if (taskSplitter == null) {
      taskSplitter = buildTaskSplitter();
    }
    beforeSplit();
    return taskSplitter.splitTasks(targetTaskCount).stream()
        .map(task -> task.buildTask(buildTaskProperties()))
        .collect(Collectors.toList());
  }

  protected void beforeSplit() {
  }

  protected abstract TaskSplitter buildTaskSplitter();

  protected abstract OptimizingInputProperties buildTaskProperties();

  protected void markSequence(long sequence) {
    if (fromSequence == INVALID_SEQUENCE || fromSequence > sequence) {
      fromSequence = sequence;
    }
    if (toSequence == INVALID_SEQUENCE || toSequence < sequence) {
      toSequence = sequence;
    }
  }

  public long getFromSequence() {
    return fromSequence;
  }

  public long getToSequence() {
    return toSequence;
  }

  protected interface TaskSplitter {
    List<SplitTask> splitTasks(int targetTaskCount);
  }

  @Override
  public int getFragmentFileCount() {
    return evaluator().getFragmentFileCount();
  }

  @Override
  public long getFragmentFileSize() {
    return evaluator().getFragmentFileSize();
  }

  @Override
  public int getSegmentFileCount() {
    return evaluator().getSegmentFileCount();
  }

  @Override
  public long getSegmentFileSize() {
    return evaluator().getSegmentFileSize();
  }

  @Override
  public int getEqualityDeleteFileCount() {
    return evaluator().getEqualityDeleteFileCount();
  }

  @Override
  public long getEqualityDeleteFileSize() {
    return evaluator().getEqualityDeleteFileSize();
  }

  @Override
  public int getPosDeleteFileCount() {
    return evaluator().getPosDeleteFileCount();
  }

  @Override
  public long getPosDeleteFileSize() {
    return evaluator().getPosDeleteFileSize();
  }

  @Override
  public Weight getWeight() {
    return evaluator().getWeight();
  }

  protected class SplitTask {
    private final Set<DataFile> rewriteDataFiles = Sets.newHashSet();
    private final Set<DataFile> rewritePosDataFiles = Sets.newHashSet();
    private final Set<ContentFile<?>> deleteFiles = Sets.newHashSet();

    public SplitTask(Set<DataFile> rewriteDataFiles,
                     Set<DataFile> rewritePosDataFiles,
                     Set<ContentFile<?>> deleteFiles) {
      this.rewriteDataFiles.addAll(rewriteDataFiles);
      this.rewritePosDataFiles.addAll(rewritePosDataFiles);
      this.deleteFiles.addAll(deleteFiles);
    }

    public Set<DataFile> getRewriteDataFiles() {
      return rewriteDataFiles;
    }

    public Set<ContentFile<?>> getDeleteFiles() {
      return deleteFiles;
    }

    public Set<DataFile> getRewritePosDataFiles() {
      return rewritePosDataFiles;
    }

    public TaskDescriptor buildTask(OptimizingInputProperties properties) {
      Set<ContentFile<?>> readOnlyDeleteFiles = Sets.newHashSet();
      Set<ContentFile<?>> rewriteDeleteFiles = Sets.newHashSet();
      for (ContentFile<?> deleteFile : deleteFiles) {
        if (reservedDeleteFiles.contains(deleteFile.path().toString())) {
          readOnlyDeleteFiles.add(deleteFile);
        } else {
          rewriteDeleteFiles.add(deleteFile);
        }
      }
      RewriteFilesInput input = new RewriteFilesInput(
          rewriteDataFiles.toArray(new DataFile[0]),
          rewritePosDataFiles.toArray(new DataFile[0]),
          readOnlyDeleteFiles.toArray(new ContentFile[0]),
          rewriteDeleteFiles.toArray(new ContentFile[0]),
          tableObject);
      return new TaskDescriptor(tableRuntime.getTableIdentifier().getId(),
          partition, input, properties.getProperties());
    }
  }

  /**
   * util class for bin-pack
   */
  protected static class FileTask {
    private final DataFile file;
    private final List<ContentFile<?>> deleteFiles;
    private final boolean isRewriteDataFile;

    public FileTask(DataFile file, List<ContentFile<?>> deleteFiles, boolean isRewriteDataFile) {
      this.file = file;
      this.deleteFiles = deleteFiles;
      this.isRewriteDataFile = isRewriteDataFile;
    }

    public DataFile getFile() {
      return file;
    }

    public List<ContentFile<?>> getDeleteFiles() {
      return deleteFiles;
    }

    public boolean isRewriteDataFile() {
      return isRewriteDataFile;
    }

    public boolean isRewritePosDataFile() {
      return !isRewriteDataFile;
    }
  }

  protected class BinPackingTaskSplitter implements TaskSplitter {

    @Override
    public List<SplitTask> splitTasks(int targetTaskCount) {
      // bin-packing
      List<FileTask> allDataFiles = Lists.newArrayList();
      rewriteDataFiles.forEach((dataFile, deleteFiles) ->
          allDataFiles.add(new FileTask(dataFile, deleteFiles, true)));
      rewritePosDataFiles.forEach((dataFile, deleteFiles) ->
          allDataFiles.add(new FileTask(dataFile, deleteFiles, false)));

      List<List<FileTask>> packed = new BinPacking.ListPacker<FileTask>(
          config.getTargetSize(), Integer.MAX_VALUE, false)
          .pack(allDataFiles, f -> f.getFile().fileSizeInBytes());

      // collect
      List<SplitTask> results = Lists.newArrayListWithCapacity(packed.size());
      for (List<FileTask> fileTasks : packed) {
        Set<DataFile> rewriteDataFiles = Sets.newHashSet();
        Set<DataFile> rewritePosDataFiles = Sets.newHashSet();
        Set<ContentFile<?>> deleteFiles = Sets.newHashSet();

        fileTasks.stream().filter(FileTask::isRewriteDataFile).forEach(f -> {
          rewriteDataFiles.add(f.getFile());
          deleteFiles.addAll(f.getDeleteFiles());
        });
        fileTasks.stream().filter(FileTask::isRewritePosDataFile).forEach(f -> {
          rewritePosDataFiles.add(f.getFile());
          deleteFiles.addAll(f.getDeleteFiles());
        });
        results.add(new SplitTask(rewriteDataFiles, rewritePosDataFiles, deleteFiles));
      }
      return results;
    }
  }
}
