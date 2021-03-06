/*
 * Copyright 2005-2014 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.hornetq.tests.integration.cluster.failover;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.hornetq.api.core.Pair;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClusterTopologyListener;
import org.hornetq.api.core.client.TopologyMember;
import org.hornetq.core.protocol.core.impl.PacketImpl;
import org.hornetq.tests.integration.cluster.util.BackupSyncDelay;
import org.junit.Test;

public class QuorumFailOverTest extends StaticClusterWithBackupFailoverTest
{
   @Override
   protected void setupServers() throws Exception
   {
      super.setupServers();
      //we need to know who is connected to who
      servers[0].getConfiguration().getHAPolicy().setBackupGroupName("group0");
      servers[1].getConfiguration().getHAPolicy().setBackupGroupName("group1");
      servers[2].getConfiguration().getHAPolicy().setBackupGroupName("group2");
      servers[3].getConfiguration().getHAPolicy().setBackupGroupName("group0");
      servers[4].getConfiguration().getHAPolicy().setBackupGroupName("group1");
      servers[5].getConfiguration().getHAPolicy().setBackupGroupName("group2");
   }

   @Test
   public void testQuorumVoting() throws Exception
   {
      int[] liveServerIDs = new int[]{0, 1, 2};
      setupCluster();
      startServers(0, 1, 2);
      new BackupSyncDelay(servers[4], servers[1], PacketImpl.REPLICATION_SCHEDULED_FAILOVER);
      startServers(3, 4, 5);

      for (int i : liveServerIDs)
      {
         waitForTopology(servers[i], 3, 3);
      }

      waitForFailoverTopology(3, 0, 1, 2);
      waitForFailoverTopology(4, 0, 1, 2);
      waitForFailoverTopology(5, 0, 1, 2);

      for (int i : liveServerIDs)
      {
         setupSessionFactory(i, i + 3, isNetty(), false);
         createQueue(i, QUEUES_TESTADDRESS, QUEUE_NAME, null, true);
         addConsumer(i, i, QUEUE_NAME, null);
      }

      waitForBindings(0, QUEUES_TESTADDRESS, 1, 1, true);
      waitForBindings(1, QUEUES_TESTADDRESS, 1, 1, true);
      waitForBindings(2, QUEUES_TESTADDRESS, 1, 1, true);

      send(0, QUEUES_TESTADDRESS, 10, false, null);
      verifyReceiveRoundRobinInSomeOrder(true, 10, 0, 1, 2);

      final TopologyListener liveTopologyListener = new TopologyListener("LIVE-1");

      locators[0].addClusterTopologyListener(liveTopologyListener);

      assertTrue("we assume 3 is a backup", servers[3].getConfiguration().getHAPolicy().isBackup());
      assertFalse("no shared storage", servers[3].getConfiguration().getHAPolicy().isSharedStore());

      failNode(0);

      waitForFailoverTopology(4, 3, 1, 2);
      waitForFailoverTopology(5, 3, 1, 2);

      waitForBindings(3, QUEUES_TESTADDRESS, 1, 1, true);

      assertTrue(servers[3].waitForActivation(2, TimeUnit.SECONDS));
      assertFalse("3 should have failed over ", servers[3].getConfiguration().getHAPolicy().isBackup());

      failNode(1);
      assertFalse("4 should have failed over ", servers[4].getConfiguration().getHAPolicy().isBackup());
   }

   @Override
   protected boolean isSharedStorage()
   {
      return false;
   }

   private static class TopologyListener implements ClusterTopologyListener
   {
      final String prefix;
      final Map<String, Pair<TransportConfiguration, TransportConfiguration>> nodes =
         new ConcurrentHashMap<String, Pair<TransportConfiguration, TransportConfiguration>>();

      public TopologyListener(String string)
      {
         prefix = string;
      }

      @Override
      public void nodeUP(TopologyMember topologyMember, boolean last)
      {
         Pair<TransportConfiguration, TransportConfiguration> connectorPair =
            new Pair<TransportConfiguration, TransportConfiguration>(topologyMember.getLive(), topologyMember.getBackup());
         nodes.put(topologyMember.getBackupGroupName(), connectorPair);
      }

      @Override
      public void nodeDown(long eventUID, String nodeID)
      {
         nodes.remove(nodeID);
      }

      @Override
      public String toString()
      {
         return "TopologyListener(" + prefix + ", #=" + nodes.size() + ")";
      }
   }
}
