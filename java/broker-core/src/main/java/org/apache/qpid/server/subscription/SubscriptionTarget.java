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
package org.apache.qpid.server.subscription;

import org.apache.qpid.AMQException;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.util.StateChangeListener;

public interface SubscriptionTarget
{


    enum State
    {
        ACTIVE, SUSPENDED, CLOSED
    }

    State getState();

    void subscriptionRegistered(Subscription sub);

    void subscriptionRemoved(Subscription sub);

    void setStateListener(StateChangeListener<SubscriptionTarget, State> listener);

    long getUnacknowledgedBytes();

    long getUnacknowledgedMessages();

    AMQSessionModel getSessionModel();

    void send(MessageInstance entry, boolean batch) throws AMQException;

    void flushBatched();

    void queueDeleted();

    void queueEmpty() throws AMQException;

    boolean allocateCredit(ServerMessage msg);

    void restoreCredit(ServerMessage queueEntry);

    boolean isSuspended();

    boolean close();
}
