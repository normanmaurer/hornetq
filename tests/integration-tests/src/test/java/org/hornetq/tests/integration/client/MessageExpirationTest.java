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
package org.hornetq.tests.integration.client;
import org.junit.Before;

import org.junit.Test;

import org.junit.Assert;

import org.hornetq.api.core.Message;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.ServerLocator;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.Queue;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.tests.util.RandomUtil;
import org.hornetq.tests.util.ServiceTestBase;

/**
 * A MessageExpirationTest
 *
 * @author <a href="mailto:jmesnil@redhat.com">Jeff Mesnil</a>
 *
 *
 */
public class MessageExpirationTest extends ServiceTestBase
{

   private static final int EXPIRATION = 1000;

   private HornetQServer server;

   private ClientSession session;

   private ClientSessionFactory sf;

   private ServerLocator locator;

   @Test
   public void testMessageExpiredWithoutExpiryAddress() throws Exception
   {
      SimpleString address = RandomUtil.randomSimpleString();
      SimpleString queue = RandomUtil.randomSimpleString();

      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createMessage(false);
      message.setExpiration(System.currentTimeMillis() + MessageExpirationTest.EXPIRATION);
      producer.send(message);

      Thread.sleep(MessageExpirationTest.EXPIRATION * 2);

      session.start();

      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message2 = consumer.receiveImmediate();
      Assert.assertNull(message2);

      consumer.close();
      session.deleteQueue(queue);
   }

   @Test
   public void testMessageExpiredWithoutExpiryAddressWithExpiryDelayOverride() throws Exception
   {
      SimpleString address = RandomUtil.randomSimpleString();
      SimpleString queue = RandomUtil.randomSimpleString();

      session.close();

      session =  addClientSession(sf.createSession(false, false, false));
      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createMessage(false);

      AddressSettings addressSettings = new AddressSettings();
      addressSettings.setExpiryDelay((long) MessageExpirationTest.EXPIRATION);
      server.getAddressSettingsRepository().addMatch(address.toString(), addressSettings);

      producer.send(message);

      // second message, this message shouldn't be overriden
      message = session.createMessage(false);
      message.setExpiration(System.currentTimeMillis() + EXPIRATION * 3);
      producer.send(message);
      session.commit();

      session.start();
      ClientConsumer consumer = session.createConsumer(queue);
      // non expired.. should receive both
      assertNotNull(consumer.receiveImmediate());
      assertNotNull(consumer.receiveImmediate());

      // stopping the consumer to cleanup the client's buffer
      session.stop();

      // we recieve the message and then rollback...   then we wait some time > expiration, the message must be gone
      session.rollback();



      Thread.sleep(MessageExpirationTest.EXPIRATION * 2);
      session.start();

      // one expired as we changed the expiry in one of the messages... should receive just one
      assertNotNull(consumer.receiveImmediate());
      assertNull(consumer.receiveImmediate());

      session.stop();
      session.rollback();

      Thread.sleep(MessageExpirationTest.EXPIRATION * 2);
      session.start();

      // both expired... nothing should be received
      assertNull(consumer.receiveImmediate());

      consumer.close();
      session.deleteQueue(queue);
   }

   @Test
   public void testMessageExpirationOnServer() throws Exception
   {
      SimpleString address = RandomUtil.randomSimpleString();
      SimpleString queue = RandomUtil.randomSimpleString();

      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message = session.createMessage(false);
      message.setExpiration(System.currentTimeMillis() + MessageExpirationTest.EXPIRATION);
      producer.send(message);

      Thread.sleep(MessageExpirationTest.EXPIRATION * 2);

      session.start();

      Thread.sleep(500);

      Assert.assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getDeliveringCount());
      Assert.assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getMessageCount());

      ClientMessage message2 = consumer.receiveImmediate();
      Assert.assertNull(message2);

      consumer.close();
      session.deleteQueue(queue);
   }

   @Test
   public void testMessageExpirationOnClient() throws Exception
   {
      SimpleString address = RandomUtil.randomSimpleString();
      SimpleString queue = RandomUtil.randomSimpleString();

      session.createQueue(address, queue, false);

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createMessage(false);
      message.setExpiration(System.currentTimeMillis() + MessageExpirationTest.EXPIRATION);
      producer.send(message);

      session.start();

      Thread.sleep(MessageExpirationTest.EXPIRATION * 2);

      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message2 = consumer.receiveImmediate();
      Assert.assertNull(message2);

      Assert.assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getDeliveringCount());
      Assert.assertEquals(0, ((Queue)server.getPostOffice().getBinding(queue).getBindable()).getMessageCount());

      consumer.close();
      session.deleteQueue(queue);
   }

   @Test
   public void testMessageExpiredWithExpiryAddress() throws Exception
   {
      SimpleString address = RandomUtil.randomSimpleString();
      SimpleString queue = RandomUtil.randomSimpleString();
      final SimpleString expiryAddress = RandomUtil.randomSimpleString();
      SimpleString expiryQueue = RandomUtil.randomSimpleString();

      server.getAddressSettingsRepository().addMatch(address.toString(), new AddressSettings()
      {
         private static final long serialVersionUID = -6476053400596299130L;

         @Override
         public SimpleString getExpiryAddress()
         {
            return expiryAddress;
         }
      });

      session.createQueue(address, queue, false);
      session.createQueue(expiryAddress, expiryQueue, false);

      ClientProducer producer = session.createProducer(address);
      ClientMessage message = session.createMessage(false);
      message.setExpiration(System.currentTimeMillis() + MessageExpirationTest.EXPIRATION);
      producer.send(message);

      Thread.sleep(MessageExpirationTest.EXPIRATION * 2);

      session.start();

      ClientConsumer consumer = session.createConsumer(queue);
      ClientMessage message2 = consumer.receiveImmediate();
      Assert.assertNull(message2);

      ClientConsumer expiryConsumer = session.createConsumer(expiryQueue);
      ClientMessage expiredMessage = expiryConsumer.receive(500);
      Assert.assertNotNull(expiredMessage);
      Assert.assertNotNull(expiredMessage.getObjectProperty(Message.HDR_ACTUAL_EXPIRY_TIME));
      Assert.assertEquals(address, expiredMessage.getObjectProperty(Message.HDR_ORIGINAL_ADDRESS));
      Assert.assertEquals(queue, expiredMessage.getObjectProperty(Message.HDR_ORIGINAL_QUEUE));
      consumer.close();
      expiryConsumer.close();
      session.deleteQueue(queue);
      session.deleteQueue(expiryQueue);
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   @Before
   public void setUp() throws Exception
   {
      super.setUp();

      server = createServer(false);
      server.start();
      locator = createInVMNonHALocator();
      sf = createSessionFactory(locator);
      session = addClientSession(sf.createSession(false, true, true));
   }
}
