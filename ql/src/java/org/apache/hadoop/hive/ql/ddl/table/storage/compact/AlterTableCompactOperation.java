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

package org.apache.hadoop.hive.ql.ddl.table.storage.compact;

import org.apache.hadoop.hive.conf.Constants;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.CompactionRequest;
import org.apache.hadoop.hive.metastore.utils.JavaUtils;
import org.apache.hadoop.hive.ql.ddl.DDLOperationContext;
import org.apache.hadoop.hive.ql.io.AcidUtils;

import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.metastore.api.CompactionResponse;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponse;
import org.apache.hadoop.hive.metastore.api.ShowCompactRequest;
import org.apache.hadoop.hive.metastore.api.ShowCompactResponseElement;
import org.apache.hadoop.hive.metastore.txn.TxnStore;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.ddl.DDLOperation;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;

import static org.apache.hadoop.hive.ql.io.AcidUtils.compactionTypeStr2ThriftType;

/**
 * Operation process of compacting a table.
 */
public class AlterTableCompactOperation extends DDLOperation<AlterTableCompactDesc> {
  public AlterTableCompactOperation(DDLOperationContext context, AlterTableCompactDesc desc) {
    super(context, desc);
  }

  @Override
  public int execute() throws HiveException {
    Table table = context.getDb().getTable(desc.getTableName());
    if (!AcidUtils.isTransactionalTable(table)) {
      throw new HiveException(ErrorMsg.NONACID_COMPACTION_NOT_SUPPORTED, table.getDbName(), table.getTableName());
    }

    String partitionName = getPartitionName(table);

    CompactionResponse resp = compact(table, partitionName);
    if (!resp.isAccepted()) {
      String message = Constants.ERROR_MESSAGE_NO_DETAILS_AVAILABLE;
      if (resp.isSetErrormessage()) {
        message = resp.getErrormessage();
      }
      throw new HiveException(ErrorMsg.COMPACTION_REFUSED,
          table.getDbName(), table.getTableName(), partitionName == null ? "" : "(partition=" + partitionName + ")", message);
    }

    if (desc.isBlocking() && resp.isAccepted()) {
      waitForCompactionToFinish(resp);
    }

    return 0;
  }

  private String getPartitionName(Table table) throws HiveException {
    String partitionName = null;
    if (desc.getPartitionSpec() == null) {
      if (table.isPartitioned()) { // Compaction can only be done on the whole table if the table is non-partitioned.
        throw new HiveException(ErrorMsg.NO_COMPACTION_PARTITION);
      }
    } else {
      Map<String, String> partitionSpec = desc.getPartitionSpec();
      List<Partition> partitions = context.getDb().getPartitions(table, partitionSpec);
      if (partitions.size() > 1) {
        throw new HiveException(ErrorMsg.TOO_MANY_COMPACTION_PARTITIONS);
      } else if (partitions.size() == 0) {
        throw new HiveException(ErrorMsg.INVALID_PARTITION_SPEC);
      }
      partitionName = partitions.get(0).getName();
    }
    return partitionName;
  }

  private CompactionResponse compact(Table table, String partitionName) throws HiveException {
    CompactionRequest req = new CompactionRequest(table.getDbName(), table.getTableName(),
        compactionTypeStr2ThriftType(desc.getCompactionType()));
    req.setPartitionname(partitionName);
    req.setPoolName(desc.getPoolName());
    req.setProperties(desc.getProperties());
    req.setInitiatorId(JavaUtils.hostname() + "-" + HiveMetaStoreClient.MANUALLY_INITIATED_COMPACTION);
    req.setInitiatorVersion(HiveMetaStoreClient.class.getPackage().getImplementationVersion());
    CompactionResponse resp = context.getDb().compact(req);
    if (resp.isAccepted()) {
      context.getConsole().printInfo("Compaction enqueued with id " + resp.getId());
    } else {
      context.getConsole().printInfo("Compaction already enqueued with id " + resp.getId() + "; State is " +
          resp.getState());
    }
    return resp;
  }

  private void waitForCompactionToFinish(CompactionResponse resp) throws HiveException {
    StringBuilder progressDots = new StringBuilder();
    long waitTimeMs = 1000;
    long waitTimeOut = HiveConf.getLongVar(context.getConf(), HiveConf.ConfVars.HIVE_COMPACTOR_WAIT_TIMEOUT);
    wait: while (true) {
      //double wait time until 5min
      waitTimeMs = waitTimeMs*2;
      waitTimeMs = Math.min(waitTimeMs, waitTimeOut);
      try {
        Thread.sleep(waitTimeMs);
      } catch (InterruptedException ex) {
        context.getConsole().printInfo("Interrupted while waiting for compaction with id=" + resp.getId());
        break;
      }
      ShowCompactRequest request = new ShowCompactRequest(resp.getId());
      ShowCompactResponse compaction = context.getDb().showCompactions(request);
      if (compaction.getCompactsSize() == 1) {
        ShowCompactResponseElement comp = compaction.getCompacts().get(0);
        switch (comp.getState()) {
          case TxnStore.WORKING_RESPONSE:
          case TxnStore.INITIATED_RESPONSE:
            //still working
            context.getConsole().printInfo(progressDots.toString());
            progressDots.append(".");
            continue wait;
          default:
            //done
            context.getConsole().printInfo("Compaction with id " + resp.getId() + " finished with status: " +
                comp.getState());
            break wait;
        }
      }else {
        throw new HiveException("No suitable compaction found");
      }
    }
  }
}
