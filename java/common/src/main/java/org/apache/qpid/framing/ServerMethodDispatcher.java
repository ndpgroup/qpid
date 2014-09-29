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
  *   0-9
  *   0-91
  *   8-0
  */

package org.apache.qpid.framing;

import org.apache.qpid.AMQException;

public interface ServerMethodDispatcher
{
    boolean dispatchAccessRequest(AccessRequestBody accessRequestBody, int channelId) throws AMQException;

    public boolean dispatchBasicAck(BasicAckBody body, int channelId) throws AMQException;
    public boolean dispatchBasicCancel(BasicCancelBody body, int channelId) throws AMQException;
    public boolean dispatchBasicConsume(BasicConsumeBody body, int channelId) throws AMQException;
    public boolean dispatchBasicGet(BasicGetBody body, int channelId) throws AMQException;
    public boolean dispatchBasicPublish(BasicPublishBody body, int channelId) throws AMQException;
    public boolean dispatchBasicQos(BasicQosBody body, int channelId) throws AMQException;
    public boolean dispatchBasicRecover(BasicRecoverBody body, int channelId) throws AMQException;
    public boolean dispatchBasicReject(BasicRejectBody body, int channelId) throws AMQException;
    public boolean dispatchChannelClose(ChannelCloseBody body, int channelId) throws AMQException;
    public boolean dispatchChannelCloseOk(ChannelCloseOkBody body, int channelId) throws AMQException;
    public boolean dispatchChannelFlow(ChannelFlowBody body, int channelId) throws AMQException;
    public boolean dispatchChannelFlowOk(ChannelFlowOkBody body, int channelId) throws AMQException;
    public boolean dispatchChannelOpen(ChannelOpenBody body, int channelId) throws AMQException;
    public boolean dispatchConnectionClose(ConnectionCloseBody body, int channelId) throws AMQException;
    public boolean dispatchConnectionCloseOk(ConnectionCloseOkBody body, int channelId) throws AMQException;
    public boolean dispatchConnectionOpen(ConnectionOpenBody body, int channelId) throws AMQException;
    public boolean dispatchConnectionSecureOk(ConnectionSecureOkBody body, int channelId) throws AMQException;
    public boolean dispatchConnectionStartOk(ConnectionStartOkBody body, int channelId) throws AMQException;
    public boolean dispatchConnectionTuneOk(ConnectionTuneOkBody body, int channelId) throws AMQException;
    public boolean dispatchExchangeBound(ExchangeBoundBody body, int channelId) throws AMQException;
    public boolean dispatchExchangeDeclare(ExchangeDeclareBody body, int channelId) throws AMQException;
    public boolean dispatchExchangeDelete(ExchangeDeleteBody body, int channelId) throws AMQException;
    public boolean dispatchQueueBind(QueueBindBody body, int channelId) throws AMQException;
    public boolean dispatchQueueDeclare(QueueDeclareBody body, int channelId) throws AMQException;
    public boolean dispatchQueueDelete(QueueDeleteBody body, int channelId) throws AMQException;
    public boolean dispatchQueuePurge(QueuePurgeBody body, int channelId) throws AMQException;
    public boolean dispatchTxCommit(TxCommitBody body, int channelId) throws AMQException;
    public boolean dispatchTxRollback(TxRollbackBody body, int channelId) throws AMQException;
    public boolean dispatchTxSelect(TxSelectBody body, int channelId) throws AMQException;

    boolean dispatchQueueUnbind(QueueUnbindBody queueUnbindBody, int channelId) throws AMQException;
}
