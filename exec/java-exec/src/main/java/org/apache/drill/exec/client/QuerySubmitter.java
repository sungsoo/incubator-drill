/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.client;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.coord.ZKClusterCoordinator;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.proto.UserBitShared.QueryId;
import org.apache.drill.exec.proto.UserProtos;
import org.apache.drill.exec.record.RecordBatchLoader;
import org.apache.drill.exec.rpc.RpcException;
import org.apache.drill.exec.rpc.user.ConnectionThrottle;
import org.apache.drill.exec.rpc.user.QueryResultBatch;
import org.apache.drill.exec.rpc.user.UserResultsListener;
import org.apache.drill.exec.server.BootStrapContext;
import org.apache.drill.exec.server.Drillbit;
import org.apache.drill.exec.server.RemoteServiceSet;
import org.apache.drill.exec.util.VectorUtil;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;

public class QuerySubmitter {

  public static void main(String args[]) throws Exception {
    QuerySubmitter submitter = new QuerySubmitter();
    Options o = new Options();
    JCommander jc = null;
    try {
      jc = new JCommander(o, args);
      jc.setProgramName("./submit_plan");
    } catch (ParameterException e) {
      System.out.println(e.getMessage());
      String[] valid = {"-f", "file", "-t", "physical"};
      new JCommander(o, valid).usage();
      System.exit(-1);
    }
    if (o.help) {
      jc.usage();
      System.exit(0);
    }
    System.exit(submitter.submitQuery(o.location, o.planType, o.zk, o.local, o.bits));
  }

  static class Options {
    @Parameter(names = {"-f"}, description = "file containing plan", required=true)
    public String location = null;

    @Parameter(names = {"-t"}, description = "type of plan, logical/physical", required=true)
    public String planType;

    @Parameter(names = {"-zk"}, description = "zookeeper connect string.", required=false)
    public String zk = "localhost:2181";

    @Parameter(names = {"-local"}, description = "run query in local mode", required=false)
    public boolean local;

    @Parameter(names = "-bits", description = "number of drillbits to run. local mode only", required=false)
    public int bits = 1;

    @Parameter(names = {"-h", "-help", "--help"}, description = "show usage", help=true)
    public boolean help = false;
  }

  public int submitQuery(String planLocation, String type, String zkQuorum, boolean local, int bits) throws Exception {
    DrillConfig config = DrillConfig.create();
    DrillClient client = null;
    
    try{
      if (local) {
        RemoteServiceSet serviceSet = RemoteServiceSet.getLocalServiceSet();
        Drillbit[] drillbits = new Drillbit[bits];
        for (int i = 0; i < bits; i++) {
          drillbits[i] = new Drillbit(config, serviceSet);
          drillbits[i].run();
        }
        client = new DrillClient(config, serviceSet.getCoordinator());
      } else {
        ZKClusterCoordinator clusterCoordinator = new ZKClusterCoordinator(config, zkQuorum);
        clusterCoordinator.start(10000);
        client = new DrillClient(config, clusterCoordinator);
      }
      client.connect();
      QueryResultsListener listener = new QueryResultsListener();
      String plan = Charsets.UTF_8.decode(ByteBuffer.wrap(Files.readAllBytes(Paths.get(planLocation)))).toString();
      UserProtos.QueryType queryType;
      type = type.toLowerCase();
      switch(type) {
        case "sql":
          queryType = UserProtos.QueryType.SQL;
          break;
        case "logical":
          queryType = UserProtos.QueryType.LOGICAL;
          break;
        case "physical":
          queryType = UserProtos.QueryType.PHYSICAL;
          break;
        default:
          System.out.println("Invalid query type: " + type);
          return -1;
      }
      Stopwatch watch = new Stopwatch();
      watch.start();
      client.runQuery(queryType, plan, listener);
      int rows = listener.await();
      System.out.println(String.format("Got %d record%s in %f seconds", rows, rows > 1 ? "s" : "", (float)watch.elapsed(TimeUnit.MILLISECONDS) / (float)1000));
      return 0;
    }finally{
      if(client != null) client.close();
    }
  }

  private class QueryResultsListener implements UserResultsListener {
    AtomicInteger count = new AtomicInteger();
    private CountDownLatch latch = new CountDownLatch(1);
    RecordBatchLoader loader = new RecordBatchLoader(new BootStrapContext(DrillConfig.create()).getAllocator());
    int width;

    @Override
    public void submissionFailed(RpcException ex) {
      System.out.println(String.format("Query failed: %s", ex));
      latch.countDown();
    }

    @Override
    public void resultArrived(QueryResultBatch result, ConnectionThrottle throttle) {
      int rows = result.getHeader().getRowCount();
      if (result.getData() != null) {
        count.addAndGet(rows);
        try {
          loader.load(result.getHeader().getDef(), result.getData());
        } catch (SchemaChangeException e) {
          submissionFailed(new RpcException(e));
        }
        
        VectorUtil.showVectorAccessibleContent(loader);
      }
      
      if (result.getHeader().getIsLastChunk()) {
        //System.out.println(StringUtils.repeat("-", width*17 + 1));
        latch.countDown();
      }
      result.release();
    }

    public int await() throws Exception {
      latch.await();
      return count.get();
    }

    @Override
    public void queryIdArrived(QueryId queryId) {
    }
  }
}
