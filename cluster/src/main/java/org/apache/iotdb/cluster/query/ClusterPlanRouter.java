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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iotdb.cluster.exception.UnsupportedPlanException;
import org.apache.iotdb.cluster.partition.PartitionGroup;
import org.apache.iotdb.cluster.partition.PartitionTable;
import org.apache.iotdb.cluster.utils.PartitionUtils;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.BatchInsertPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.UpdatePlan;
import org.apache.iotdb.db.qp.physical.sys.CountPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowChildPathsPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowDevicesPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowPlan.ShowContentType;
import org.apache.iotdb.db.qp.physical.sys.ShowTimeSeriesPlan;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.utils.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterPlanRouter {

  private static final Logger logger = LoggerFactory.getLogger(ClusterPlanRouter.class);

  private PartitionTable partitionTable;

  public ClusterPlanRouter(PartitionTable partitionTable) {
    this.partitionTable = partitionTable;
  }

  private MManager getMManager() {
    return partitionTable.getMManager();
  }

  public PartitionGroup routePlan(PhysicalPlan plan)
      throws UnsupportedPlanException, MetadataException {
    if (plan instanceof InsertPlan) {
      return routePlan((InsertPlan) plan);
    } else if (plan instanceof CreateTimeSeriesPlan) {
      return routePlan((CreateTimeSeriesPlan) plan);
    } else if (plan instanceof ShowChildPathsPlan) {
      return routePlan((ShowChildPathsPlan) plan);
    }
    //the if clause can be removed after the program is stable
    if (PartitionUtils.isLocalPlan(plan)) {
      logger.error("{} is a local plan. Please run it locally directly", plan);
    } else if (PartitionUtils.isGlobalMetaPlan(plan) || PartitionUtils.isGlobalDataPlan(plan)) {
      logger.error("{} is a global plan. Please forward it to all partitionGroups", plan);
    }
    if (plan.canbeSplit()) {
      logger.error("{} can be split. Please call splitPlanAndMapToGroups", plan);
    }
    throw new UnsupportedPlanException(plan);
  }

  public PartitionGroup routePlan(InsertPlan plan)
      throws MetadataException {
    return partitionTable.partitionByPathTime(plan.getDeviceId(), plan.getTime());
  }

  public PartitionGroup routePlan(CreateTimeSeriesPlan plan)
      throws MetadataException {
    return partitionTable.partitionByPathTime(plan.getPath().getFullPath(), 0);
  }

  public PartitionGroup routePlan(ShowChildPathsPlan plan) {
    try {
      return partitionTable.route(getMManager().getStorageGroupName(plan.getPath().getFullPath())
          , 0);
    } catch (MetadataException e) {
      //the path is too short to have no a storage group name, e.g., "root"
      //so we can do it locally.
      return partitionTable.getLocalGroups().get(0);
    }
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(PhysicalPlan plan)
      throws UnsupportedPlanException, MetadataException {
    if (plan instanceof BatchInsertPlan) {
      return splitAndRoutePlan((BatchInsertPlan) plan);
    } else if (plan instanceof ShowTimeSeriesPlan) {
      return splitAndRoutePlan((ShowTimeSeriesPlan) plan);
    } else if (plan instanceof UpdatePlan) {
      return splitAndRoutePlan((UpdatePlan) plan);
    } else if (plan instanceof CountPlan) {
      return splitAndRoutePlan((CountPlan) plan);
    } else if (plan instanceof ShowDevicesPlan) {
      return splitAndRoutePlan((ShowDevicesPlan) plan);
    } else if (plan instanceof CreateTimeSeriesPlan) {
      return splitAndRoutePlan((CreateTimeSeriesPlan) plan);
    } else if (plan instanceof InsertPlan) {
      return splitAndRoutePlan((InsertPlan) plan);
    }
    //the if clause can be removed after the program is stable
    if (PartitionUtils.isLocalPlan(plan)) {
      logger.error("{} is a local plan. Please run it locally directly", plan);
    } else if (PartitionUtils.isGlobalMetaPlan(plan) || PartitionUtils.isGlobalDataPlan(plan)) {
      logger.error("{} is a global plan. Please forward it to all partitionGroups", plan);
    }
    if (!plan.canbeSplit()) {
      logger.error("{} cannot be split. Please call routePlan", plan);
    }
    throw new UnsupportedPlanException(plan);
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(InsertPlan plan)
      throws MetadataException {
    PartitionGroup partitionGroup = partitionTable.partitionByPathTime(plan.getDeviceId(),
        plan.getTime());
    return Collections.singletonMap(plan, partitionGroup);
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(CreateTimeSeriesPlan plan)
      throws MetadataException {
    PartitionGroup partitionGroup =
        partitionTable.partitionByPathTime(plan.getPath().getFullPath(), 0);
    return Collections.singletonMap(plan, partitionGroup);
  }

  @SuppressWarnings("SuspiciousSystemArraycopy")
  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(BatchInsertPlan plan)
      throws MetadataException {
    String storageGroup = getMManager().getStorageGroupName(plan.getDeviceId());
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
    long[] times = plan.getTimes();
    if (times.length == 0) {
      return Collections.emptyMap();
    }
    long startTime = (times[0] / StorageEngine.getTimePartitionInterval()) * StorageEngine.getTimePartitionInterval();//included
    long endTime = startTime + StorageEngine.getTimePartitionInterval();//excluded
    int startLoc = 0; //included

    //Map<PartitionGroup>
    Map<PartitionGroup, List<Integer>> splitMap = new HashMap<>();
    //for each List in split, they are range1.start, range.end, range2.start, range2.end, ...
    for (int i = 1; i < times.length; i++) {// times are sorted in session API.
      if (times[i] >= endTime) {
        // a new range.
        PartitionGroup group = partitionTable.route(storageGroup, startTime);
        List<Integer> ranges = splitMap.computeIfAbsent(group, x -> new ArrayList<>());
        ranges.add(startLoc);//include
        ranges.add(i);//excluded
        //next init
        startLoc = i;
        startTime = endTime;
        endTime =
            (times[i] / StorageEngine.getTimePartitionInterval() + 1)  * StorageEngine.getTimePartitionInterval();
      }
    }
    //the final range
    PartitionGroup group = partitionTable.route(storageGroup, startTime);
    List<Integer> ranges = splitMap.computeIfAbsent(group, x -> new ArrayList<>());
    ranges.add(startLoc);//includec
    ranges.add(times.length);//excluded

    List<Integer> locs;
    for (Map.Entry<PartitionGroup, List<Integer>> entry : splitMap.entrySet()) {
      //generate a new times and values
      locs = entry.getValue();
      int count = 0;
      for (int i = 0; i < locs.size(); i += 2) {
        int start = locs.get(i);
        int end = locs.get(i + 1);
        count += end - start;
      }
      long[] subTimes = new long[count];
      int destLoc = 0;
      Object[] values = new Object[plan.getMeasurements().length];
      for (int i = 0; i < values.length; i++) {
        switch (plan.getDataTypes()[i]) {
          case TEXT:
            values[i] = new Binary[count];
            break;
          case FLOAT:
            values[i] = new float[count];
            break;
          case INT32:
            values[i] = new int[count];
            break;
          case INT64:
            values[i] = new long[count];
            break;
          case DOUBLE:
            values[i] = new double[count];
            break;
          case BOOLEAN:
            values[i] = new boolean[count];
            break;
        }
      }
      for (int i = 0; i < locs.size(); i += 2) {
        int start = locs.get(i);
        int end = locs.get(i + 1);
        System.arraycopy(plan.getTimes(), start, subTimes, destLoc, end - start);
        for (int k = 0; k < values.length; k++) {
          System.arraycopy(plan.getColumns()[k], start, values[k], destLoc, end - start);
        }
        destLoc += end - start;
      }
      BatchInsertPlan newBatch = PartitionUtils.copy(plan, subTimes, values);
      result.put(newBatch, entry.getKey());
    }
    return result;
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(UpdatePlan plan)
      throws UnsupportedPlanException {
    logger.error("UpdatePlan is not implemented");
    throw new UnsupportedPlanException(plan);
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(CountPlan plan)
      throws StorageGroupNotSetException, IllegalPathException {
    //CountPlan is quite special because it has the behavior of wildcard at the tail of the path
    // even though there is no wildcard
    Map<String, String> sgPathMap = getMManager()
        .determineStorageGroup(plan.getPath().getFullPath() + ".*");
    if (sgPathMap.isEmpty()) {
      throw new StorageGroupNotSetException(plan.getPath().getFullPath());
    }
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
    if (plan.getShowContentType().equals(ShowContentType.COUNT_TIMESERIES)) {
      //support wildcard
      for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
        CountPlan plan1 = new CountPlan(ShowContentType.COUNT_TIMESERIES,
            new Path(entry.getValue()), plan.getLevel());
        result.put(plan1, partitionTable.route(entry.getKey(), 0));
      }
    } else {
      //do not support wildcard
      if (sgPathMap.size() == 1) {
        // the path of the original plan has only one SG, or there is only one SG in the system.
        for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
          //actually, there is only one entry
          result.put(plan, partitionTable.route(entry.getKey(), 0));
        }
      } else {
        // the path of the original plan contains more than one SG, and we added a wildcard at the tail.
        // we have to remove it.
        for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
          CountPlan plan1 = new CountPlan(ShowContentType.COUNT_TIMESERIES,
              new Path(entry.getValue().substring(0, entry.getValue().lastIndexOf(".*"))),
              plan.getLevel());
          result.put(plan1, partitionTable.route(entry.getKey(), 0));
        }
      }
    }
    return result;
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(ShowDevicesPlan plan)
      throws IllegalPathException {
    //show devices is quite special because it has the behavior of wildcard at the tail of the path
    // even though there is no wildcard
    Map<String, String> sgPathMap = getMManager()
        .determineStorageGroup(plan.getPath().getFullPath() + ".*");
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
    for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
      result.put(new ShowDevicesPlan(plan.getShowContentType(), new Path(entry.getValue())),
          partitionTable.route(entry.getKey(), 0));
    }
    return result;
  }

  public Map<PhysicalPlan, PartitionGroup> splitAndRoutePlan(ShowTimeSeriesPlan plan)
      throws StorageGroupNotSetException, IllegalPathException {
    //show timeseries is quite special because it has the behavior of wildcard at the tail of the path
    // even though there is no wildcard
    Map<String, String> sgPathMap = getMManager()
        .determineStorageGroup(plan.getPath().getFullPath() + ".*");
    if (sgPathMap.isEmpty()) {
      throw new StorageGroupNotSetException(plan.getPath().getFullPath());
    }
    Map<PhysicalPlan, PartitionGroup> result = new HashMap<>();
    for (Map.Entry<String, String> entry : sgPathMap.entrySet()) {
      ShowTimeSeriesPlan newShow = new ShowTimeSeriesPlan(ShowContentType.TIMESERIES,
          new Path(entry.getValue()), plan.isContains(), plan.getKey(), plan.getValue());
      result.put(newShow, partitionTable.route(entry.getKey(), 0));
    }
    return result;
  }
}