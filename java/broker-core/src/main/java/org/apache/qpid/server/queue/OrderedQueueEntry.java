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
package org.apache.qpid.server.queue;

import org.apache.qpid.server.message.ServerMessage;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public abstract class OrderedQueueEntry<E extends OrderedQueueEntry<E,Q,L>, Q extends AbstractQueue<E,Q,L>, L extends OrderedQueueEntryList<E,Q,L>> extends QueueEntryImpl<E,Q,L>
{
    static final AtomicReferenceFieldUpdater<OrderedQueueEntry, OrderedQueueEntry>
                _nextUpdater =
            AtomicReferenceFieldUpdater.newUpdater
            (OrderedQueueEntry.class, OrderedQueueEntry.class, "_next");

    private volatile E _next;

    public OrderedQueueEntry(L queueEntryList)
    {
        super(queueEntryList);
    }

    public OrderedQueueEntry(L queueEntryList, ServerMessage message, final long entryId)
    {
        super(queueEntryList, message, entryId);
    }

    public OrderedQueueEntry(L queueEntryList, ServerMessage message)
    {
        super(queueEntryList, message);
    }

    public E getNextNode()
    {
        return _next;
    }

    public E getNextValidEntry()
    {

        E next = getNextNode();
        while(next != null && next.isDeleted())
        {

            final E newNext = next.getNextNode();
            if(newNext != null)
            {
                OrderedQueueEntryList._nextUpdater.compareAndSet(this,next, newNext);
                next = getNextNode();
            }
            else
            {
                next = null;
            }

        }
        return next;
    }

}