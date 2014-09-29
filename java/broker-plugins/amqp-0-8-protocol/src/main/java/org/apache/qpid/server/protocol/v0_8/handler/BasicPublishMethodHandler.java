/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.protocol.v0_8.handler;

import java.security.AccessControlException;

import org.apache.log4j.Logger;

import org.apache.qpid.AMQException;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.BasicPublishBody;
import org.apache.qpid.framing.abstraction.MessagePublishInfo;
import org.apache.qpid.framing.abstraction.MessagePublishInfoImpl;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.message.MessageDestination;
import org.apache.qpid.server.protocol.v0_8.AMQChannel;
import org.apache.qpid.server.protocol.v0_8.AMQProtocolSession;
import org.apache.qpid.server.protocol.v0_8.state.StateAwareMethodListener;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;

public class BasicPublishMethodHandler implements StateAwareMethodListener<BasicPublishBody>
{
    private static final Logger _logger = Logger.getLogger(BasicPublishMethodHandler.class);

    private static final BasicPublishMethodHandler _instance = new BasicPublishMethodHandler();


    public static BasicPublishMethodHandler getInstance()
    {
        return _instance;
    }

    private BasicPublishMethodHandler()
    {
    }

    public void methodReceived(final AMQProtocolSession<?> connection,
                               BasicPublishBody body,
                               int channelId) throws AMQException
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug("Publish received on channel " + channelId);
        }

        AMQShortString exchangeName = body.getExchange();
        VirtualHostImpl vHost = connection.getVirtualHost();

        // TODO: check the delivery tag field details - is it unique across the broker or per subscriber?

        MessageDestination destination;

        if (exchangeName == null || AMQShortString.EMPTY_STRING.equals(exchangeName))
        {
            destination = vHost.getDefaultDestination();
        }
        else
        {
            destination = vHost.getMessageDestination(exchangeName.toString());
        }

        // if the exchange does not exist we raise a channel exception
        if (destination == null)
        {
            throw body.getChannelException(AMQConstant.NOT_FOUND, "Unknown exchange name",
                                           connection.getMethodRegistry());
        }
        else
        {
            // The partially populated BasicDeliver frame plus the received route body
            // is stored in the channel. Once the final body frame has been received
            // it is routed to the exchange.
            AMQChannel channel = connection.getChannel(channelId);

            if (channel == null)
            {
                throw body.getChannelNotFoundException(channelId, connection.getMethodRegistry());
            }

            MessagePublishInfo info = new MessagePublishInfoImpl(body.getExchange(),
                                                                 body.getImmediate(),
                                                                 body.getMandatory(),
                                                                 body.getRoutingKey());
            info.setExchange(exchangeName);
            try
            {
                channel.setPublishFrame(info, destination);
            }
            catch (AccessControlException e)
            {
                throw body.getConnectionException(AMQConstant.ACCESS_REFUSED, e.getMessage(), connection.getMethodRegistry());
            }
        }
    }

}



