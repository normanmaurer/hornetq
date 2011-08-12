/*
 * Copyright 2010 Red Hat, Inc.
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

package org.hornetq.core.server.cluster.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.api.core.management.ManagementHelper;
import org.hornetq.api.core.management.NotificationType;
import org.hornetq.api.core.management.ResourceNames;
import org.hornetq.core.client.impl.ServerLocatorInternal;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.message.impl.MessageImpl;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.postoffice.BindingType;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.ServerMessage;
import org.hornetq.core.server.cluster.ClusterConnection;
import org.hornetq.core.server.cluster.MessageFlowRecord;
import org.hornetq.core.server.cluster.Transformer;
import org.hornetq.utils.UUID;
import org.hornetq.utils.UUIDGenerator;

/**
 * A ClusterConnectionBridge
 *
 * @author tim
 * @author Clebert Suconic
 *
 *
 */
public class ClusterConnectionBridge extends BridgeImpl
{
   private static final Logger log = Logger.getLogger(ClusterConnectionBridge.class);

   private static final boolean isTrace = log.isTraceEnabled();

   private final ClusterConnection clusterConnection;

   private final MessageFlowRecord flowRecord;

   private final SimpleString managementAddress;

   private final SimpleString managementNotificationAddress;

   private ClientConsumer notifConsumer;

   private final SimpleString idsHeaderName;

   private final String targetNodeID;

   private final TransportConfiguration connector;

   private final ServerLocatorInternal discoveryLocator;

   public ClusterConnectionBridge(final ClusterConnection clusterConnection,
                                  final ServerLocator targetLocator,
                                  final ServerLocatorInternal discoveryLocator,
                                  final int reconnectAttempts,
                                  final long retryInterval,
                                  final double retryMultiplier,
                                  final long maxRetryInterval,
                                  final UUID nodeUUID,
                                  final String targetNodeID,
                                  final SimpleString name,
                                  final Queue queue,
                                  final Executor executor,
                                  final SimpleString filterString,
                                  final SimpleString forwardingAddress,
                                  final ScheduledExecutorService scheduledExecutor,
                                  final Transformer transformer,
                                  final boolean useDuplicateDetection,
                                  final String user,
                                  final String password,
                                  final boolean activated,
                                  final StorageManager storageManager,
                                  final SimpleString managementAddress,
                                  final SimpleString managementNotificationAddress,
                                  final MessageFlowRecord flowRecord,
                                  final TransportConfiguration connector) throws Exception
   {
      super(targetLocator,
            reconnectAttempts,
            retryInterval,
            retryMultiplier,
            maxRetryInterval,
            nodeUUID,
            name,
            queue,
            executor,
            filterString,
            forwardingAddress,
            scheduledExecutor,
            transformer,
            useDuplicateDetection,
            user,
            password,
            activated,
            storageManager);

      this.discoveryLocator = discoveryLocator;

      idsHeaderName = MessageImpl.HDR_ROUTE_TO_IDS.concat(name);

      this.clusterConnection = clusterConnection;

      this.targetNodeID = targetNodeID;
      this.managementAddress = managementAddress;
      this.managementNotificationAddress = managementNotificationAddress;
      this.flowRecord = flowRecord;
      this.connector = connector;

      // we need to disable DLQ check on the clustered bridges
      queue.setInternalQueue(true);
      
      if (log.isDebugEnabled())
      {
         log.debug("Setting up bridge between " + clusterConnection.getConnector() + " and " + targetLocator, new Exception ("trace"));
      }
   }
   
   @Override
   protected ServerMessage beforeForward(ServerMessage message)
   {
      // We make a copy of the message, then we strip out the unwanted routing id headers and leave
      // only
      // the one pertinent for the address node - this is important since different queues on different
      // nodes could have same queue ids
      // Note we must copy since same message may get routed to other nodes which require different headers
      message = message.copy();

      // TODO - we can optimise this

      Set<SimpleString> propNames = new HashSet<SimpleString>(message.getPropertyNames());

      byte[] queueIds = message.getBytesProperty(idsHeaderName);

      for (SimpleString propName : propNames)
      {
         if (propName.startsWith(MessageImpl.HDR_ROUTE_TO_IDS))
         {
            message.removeProperty(propName);
         }
      }

      message.putBytesProperty(MessageImpl.HDR_ROUTE_TO_IDS, queueIds);

      message = super.beforeForward(message);

      return message;
   }

   private void setupNotificationConsumer() throws Exception
   {
      if (log.isDebugEnabled())
      {
         log.debug("Setting up notificationConsumer between " + this.clusterConnection.getConnector() +
                   " and " +
                   flowRecord.getBridge().getForwardingConnection() +
                   " clusterConnection = " +
                   this.clusterConnection.getName() +
                   " on server " +
                   clusterConnection.getServer());
      }
      if (flowRecord != null)
      {
         flowRecord.reset();

         if (notifConsumer != null)
         {
            try
            {
               log.debug("Closing notification Consumer for reopening " + notifConsumer +
                         " on bridge " +
                         this.getName());
               notifConsumer.close();

               notifConsumer = null;
            }
            catch (HornetQException e)
            {
               log.error("Failed to close consumer", e);
            }
         }

         // Get the queue data

         String qName = "notif." + UUIDGenerator.getInstance().generateStringUUID() +
                        "." +
                        clusterConnection.getServer();

         SimpleString notifQueueName = new SimpleString(qName);

         SimpleString filter = new SimpleString(ManagementHelper.HDR_BINDING_TYPE + "<>" +
                                                BindingType.DIVERT.toInt() +
                                                " AND " +
                                                ManagementHelper.HDR_NOTIFICATION_TYPE +
                                                " IN ('" +
                                                NotificationType.BINDING_ADDED +
                                                "','" +
                                                NotificationType.BINDING_REMOVED +
                                                "','" +
                                                NotificationType.CONSUMER_CREATED +
                                                "','" +
                                                NotificationType.CONSUMER_CLOSED +
                                                "','" +
                                                NotificationType.PROPOSAL +
                                                "','" +
                                                NotificationType.PROPOSAL_RESPONSE +
                                                "') AND " +
                                                ManagementHelper.HDR_DISTANCE +
                                                "<" +
                                                flowRecord.getMaxHops() +
                                                " AND (" +
                                                ManagementHelper.HDR_ADDRESS +
                                                " LIKE '" +
                                                flowRecord.getAddress() +
                                                "%')");

         session.createTemporaryQueue(managementNotificationAddress, notifQueueName, filter);

         notifConsumer = session.createConsumer(notifQueueName);

         notifConsumer.setMessageHandler(flowRecord);

         session.start();

         ClientMessage message = session.createMessage(false);

         log.debug("Requesting sendQueueInfoToQueue through " + this, new Exception ("trace"));
         ManagementHelper.putOperationInvocation(message,
                                                 ResourceNames.CORE_SERVER,
                                                 "sendQueueInfoToQueue",
                                                 notifQueueName.toString(),
                                                 flowRecord.getAddress());

         ClientProducer prod = session.createProducer(managementAddress);

         if (log.isDebugEnabled())
         {
            log.debug("Cluster connetion bridge on " + clusterConnection + " requesting information on queues");
         }

         prod.send(message);
      }
   }

   @Override
   protected void afterConnect() throws Exception
   {
      super.afterConnect();
      setupNotificationConsumer();
   }

   @Override
   public void stop() throws Exception
   {
      super.stop();
   }

   protected void tryScheduleRetryReconnect(final int code)
   {
      if (code != HornetQException.DISCONNECTED)
      {
         scheduleRetryConnect();
      }
   }


   protected void fail(final boolean permanently)
   {
      log.debug("Cluster Bridge " + this.getName() + " failed, permanently=" + permanently);
      super.fail(permanently);

      if (permanently)
      {
         log.debug("cluster node for bridge " + this.getName() + " is permanently down");
         discoveryLocator.notifyNodeDown(targetNodeID);
      }

   }
}