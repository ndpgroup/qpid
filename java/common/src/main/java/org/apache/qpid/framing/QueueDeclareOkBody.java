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

/*
 * This file is auto-generated by Qpid Gentools v.0.1 - do not modify.
 * Supported AMQP version:
 *   8-0
 */

package org.apache.qpid.framing;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.qpid.AMQException;
import org.apache.qpid.codec.MarkableDataInput;

public class QueueDeclareOkBody extends AMQMethodBodyImpl implements EncodableAMQDataBlock, AMQMethodBody
{

    public static final int CLASS_ID =  50;
    public static final int METHOD_ID = 11;

    // Fields declared in specification
    private final AMQShortString _queue; // [queue]
    private final long _messageCount; // [messageCount]
    private final long _consumerCount; // [consumerCount]

    // Constructor
    public QueueDeclareOkBody(MarkableDataInput buffer) throws AMQFrameDecodingException, IOException
    {
        _queue = readAMQShortString( buffer );
        _messageCount = readUnsignedInteger( buffer );
        _consumerCount = readUnsignedInteger( buffer );
    }

    public QueueDeclareOkBody(
            AMQShortString queue,
            long messageCount,
            long consumerCount
                             )
    {
        _queue = queue;
        _messageCount = messageCount;
        _consumerCount = consumerCount;
    }

    public int getClazz()
    {
        return CLASS_ID;
    }

    public int getMethod()
    {
        return METHOD_ID;
    }

    public final AMQShortString getQueue()
    {
        return _queue;
    }
    public final long getMessageCount()
    {
        return _messageCount;
    }
    public final long getConsumerCount()
    {
        return _consumerCount;
    }

    protected int getBodySize()
    {
        int size = 8;
        size += getSizeOf( _queue );
        return size;
    }

    public void writeMethodPayload(DataOutput buffer) throws IOException
    {
        writeAMQShortString( buffer, _queue );
        writeUnsignedInteger( buffer, _messageCount );
        writeUnsignedInteger( buffer, _consumerCount );
    }

    public boolean execute(MethodDispatcher dispatcher, int channelId) throws AMQException
	{
        return dispatcher.dispatchQueueDeclareOk(this, channelId);
	}

    public String toString()
    {
        StringBuilder buf = new StringBuilder("[QueueDeclareOkBodyImpl: ");
        buf.append( "queue=" );
        buf.append(  getQueue() );
        buf.append( ", " );
        buf.append( "messageCount=" );
        buf.append(  getMessageCount() );
        buf.append( ", " );
        buf.append( "consumerCount=" );
        buf.append(  getConsumerCount() );
        buf.append("]");
        return buf.toString();
    }

}
