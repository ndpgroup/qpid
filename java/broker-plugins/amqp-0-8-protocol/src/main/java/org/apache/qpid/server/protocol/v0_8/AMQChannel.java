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
package org.apache.qpid.server.protocol.v0_8;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

import org.apache.log4j.Logger;
import org.apache.qpid.AMQConnectionException;
import org.apache.qpid.AMQException;
import org.apache.qpid.AMQSecurityException;
import org.apache.qpid.common.AMQPFilterTypes;
import org.apache.qpid.framing.AMQMethodBody;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.BasicContentHeaderProperties;
import org.apache.qpid.framing.ContentBody;
import org.apache.qpid.framing.ContentHeaderBody;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.framing.MethodRegistry;
import org.apache.qpid.framing.abstraction.MessagePublishInfo;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.TransactionTimeoutHelper;
import org.apache.qpid.server.TransactionTimeoutHelper.CloseAction;
import org.apache.qpid.server.configuration.BrokerProperties;
import org.apache.qpid.server.exchange.Exchange;
import org.apache.qpid.server.filter.FilterManagerFactory;
import org.apache.qpid.server.flow.FlowCreditManager;
import org.apache.qpid.server.flow.Pre0_10CreditManager;
import org.apache.qpid.server.logging.LogActor;
import org.apache.qpid.server.logging.LogMessage;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.logging.actors.AMQPChannelActor;
import org.apache.qpid.server.logging.actors.CurrentActor;
import org.apache.qpid.server.logging.messages.ChannelMessages;
import org.apache.qpid.server.logging.messages.ExchangeMessages;
import org.apache.qpid.server.logging.subjects.ChannelLogSubject;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.MessageReference;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.protocol.v0_8.output.ProtocolOutputConverter;
import org.apache.qpid.server.protocol.AMQConnectionModel;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.queue.QueueEntry;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.store.MessageStore;
import org.apache.qpid.server.store.StoreFuture;
import org.apache.qpid.server.store.StoredMessage;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.subscription.Subscription;
import org.apache.qpid.server.subscription.SubscriptionTarget;
import org.apache.qpid.server.txn.AsyncAutoCommitTransaction;
import org.apache.qpid.server.txn.LocalTransaction;
import org.apache.qpid.server.txn.LocalTransaction.ActivityTimeAccessor;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.virtualhost.VirtualHost;
import org.apache.qpid.transport.TransportException;

public class AMQChannel implements AMQSessionModel, AsyncAutoCommitTransaction.FutureRecorder
{
    public static final int DEFAULT_PREFETCH = 4096;

    private static final Logger _logger = Logger.getLogger(AMQChannel.class);

    //TODO use Broker property to configure message authorization requirements
    private boolean _messageAuthorizationRequired = Boolean.getBoolean(BrokerProperties.PROPERTY_MSG_AUTH);

    private final int _channelId;


    private final Pre0_10CreditManager _creditManager = new Pre0_10CreditManager(0l,0l);

    /**
     * The delivery tag is unique per channel. This is pre-incremented before putting into the deliver frame so that
     * value of this represents the <b>last</b> tag sent out
     */
    private long _deliveryTag = 0;

    /** A channel has a default queue (the last declared) that is used when no queue name is explicitly set */
    private AMQQueue _defaultQueue;

    /** This tag is unique per subscription to a queue. The server returns this in response to a basic.consume request. */
    private int _consumerTag;

    /**
     * The current message - which may be partial in the sense that not all frames have been received yet - which has
     * been received by this channel. As the frames are received the message gets updated and once all frames have been
     * received the message can then be routed.
     */
    private IncomingMessage _currentMessage;

    /** Maps from consumer tag to subscription instance. Allows us to unsubscribe from a queue. */
    private final Map<AMQShortString, SubscriptionTarget_0_8> _tag2SubscriptionTargetMap = new HashMap<AMQShortString, SubscriptionTarget_0_8>();

    private final MessageStore _messageStore;

    private final LinkedList<AsyncCommand> _unfinishedCommandsQueue = new LinkedList<AsyncCommand>();

    private UnacknowledgedMessageMap _unacknowledgedMessageMap = new UnacknowledgedMessageMapImpl(DEFAULT_PREFETCH);

    // Set of messages being acknowledged in the current transaction
    private SortedSet<QueueEntry> _acknowledgedMessages = new TreeSet<QueueEntry>();

    private final AtomicBoolean _suspended = new AtomicBoolean(false);

    private ServerTransaction _transaction;

    private final AtomicLong _txnStarts = new AtomicLong(0);
    private final AtomicLong _txnCommits = new AtomicLong(0);
    private final AtomicLong _txnRejects = new AtomicLong(0);
    private final AtomicLong _txnCount = new AtomicLong(0);

    private final AMQProtocolSession _session;
    private AtomicBoolean _closing = new AtomicBoolean(false);

    private final Set<Object> _blockingEntities = Collections.synchronizedSet(new HashSet<Object>());

    private final AtomicBoolean _blocking = new AtomicBoolean(false);


    private LogActor _actor;
    private LogSubject _logSubject;
    private volatile boolean _rollingBack;

    private static final Runnable NULL_TASK = new Runnable() { public void run() {} };
    private List<MessageInstance> _resendList = new ArrayList<MessageInstance>();
    private static final
    AMQShortString IMMEDIATE_DELIVERY_REPLY_TEXT = new AMQShortString("Immediate delivery is not possible.");
    private long _createTime = System.currentTimeMillis();

    private final ClientDeliveryMethod _clientDeliveryMethod;

    private final TransactionTimeoutHelper _transactionTimeoutHelper;
    private final UUID _id = UUID.randomUUID();


    private final CapacityCheckAction _capacityCheckAction = new CapacityCheckAction();
    private final ImmediateAction _immediateAction = new ImmediateAction();


    public AMQChannel(AMQProtocolSession session, int channelId, MessageStore messageStore)
            throws AMQException
    {
        _session = session;
        _channelId = channelId;

        _actor = new AMQPChannelActor(this, session.getLogActor().getRootMessageLogger());
        _logSubject = new ChannelLogSubject(this);
        _actor.message(ChannelMessages.CREATE());

        _messageStore = messageStore;

        // by default the session is non-transactional
        _transaction = new AsyncAutoCommitTransaction(_messageStore, this);

        _clientDeliveryMethod = session.createDeliveryMethod(_channelId);

        _transactionTimeoutHelper = new TransactionTimeoutHelper(_logSubject, new CloseAction()
        {
            @Override
            public void doTimeoutAction(String reason) throws AMQException
            {
                closeConnection(reason);
            }
        });
    }

    /** Sets this channel to be part of a local transaction */
    public void setLocalTransactional()
    {
        _transaction = new LocalTransaction(_messageStore, new ActivityTimeAccessor()
        {
            @Override
            public long getActivityTime()
            {
                return _session.getLastReceivedTime();
            }
        });
        _txnStarts.incrementAndGet();
    }

    public boolean isTransactional()
    {
        return _transaction.isTransactional();
    }

    public void receivedComplete()
    {
        sync();
    }

    private void incrementOutstandingTxnsIfNecessary()
    {
        if(isTransactional())
        {
            //There can currently only be at most one outstanding transaction
            //due to only having LocalTransaction support. Set value to 1 if 0.
            _txnCount.compareAndSet(0,1);
        }
    }

    private void decrementOutstandingTxnsIfNecessary()
    {
        if(isTransactional())
        {
            //There can currently only be at most one outstanding transaction
            //due to only having LocalTransaction support. Set value to 0 if 1.
            _txnCount.compareAndSet(1,0);
        }
    }

    public Long getTxnCommits()
    {
        return _txnCommits.get();
    }

    public Long getTxnRejects()
    {
        return _txnRejects.get();
    }

    public Long getTxnCount()
    {
        return _txnCount.get();
    }

    public Long getTxnStart()
    {
        return _txnStarts.get();
    }

    public int getChannelId()
    {
        return _channelId;
    }

    public void setPublishFrame(MessagePublishInfo info, final Exchange e) throws AMQSecurityException
    {
        String routingKey = info.getRoutingKey() == null ? null : info.getRoutingKey().asString();
        SecurityManager securityManager = getVirtualHost().getSecurityManager();
        if (!securityManager.authorisePublish(info.isImmediate(), routingKey, e.getName()))
        {
            throw new AMQSecurityException("Permission denied: " + e.getName());
        }
        _currentMessage = new IncomingMessage(info);
        _currentMessage.setExchange(e);
    }

    public void publishContentHeader(ContentHeaderBody contentHeaderBody)
            throws AMQException
    {
        if (_currentMessage == null)
        {
            throw new AMQException("Received content header without previously receiving a BasicPublish frame");
        }
        else
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("Content header received on channel " + _channelId);
            }

            _currentMessage.setContentHeaderBody(contentHeaderBody);

            deliverCurrentMessageIfComplete();
        }
    }

    private void deliverCurrentMessageIfComplete()
            throws AMQException
    {
        // check and deliver if header says body length is zero
        if (_currentMessage.allContentReceived())
        {
            try
            {

                final MessageMetaData messageMetaData =
                        new MessageMetaData(_currentMessage.getMessagePublishInfo(),
                                            _currentMessage.getContentHeader(),
                                            getProtocolSession().getLastReceivedTime());

                final StoredMessage<MessageMetaData> handle = _messageStore.addMessage(messageMetaData);
                final AMQMessage amqMessage = createAMQMessage(_currentMessage, handle);
                MessageReference reference = amqMessage.newReference();
                try
                {
                    int bodyCount = _currentMessage.getBodyCount();
                    if(bodyCount > 0)
                    {
                        long bodyLengthReceived = 0;
                        for(int i = 0 ; i < bodyCount ; i++)
                        {
                            ContentBody contentChunk = _currentMessage.getContentChunk(i);
                            handle.addContent((int)bodyLengthReceived, ByteBuffer.wrap(contentChunk.getPayload()));
                            bodyLengthReceived += contentChunk.getSize();
                        }
                    }

                    if(!checkMessageUserId(_currentMessage.getContentHeader()))
                    {
                        _transaction.addPostTransactionAction(new WriteReturnAction(AMQConstant.ACCESS_REFUSED, "Access Refused", amqMessage));
                    }
                    else
                    {
                        final boolean immediate = _currentMessage.getMessagePublishInfo().isImmediate();

                        final InstanceProperties instanceProperties =
                                new InstanceProperties()
                                {
                                    @Override
                                    public Object getProperty(final Property prop)
                                    {
                                        switch(prop)
                                        {
                                            case EXPIRATION:
                                                return amqMessage.getExpiration();
                                            case IMMEDIATE:
                                                return immediate;
                                            case PERSISTENT:
                                                return amqMessage.isPersistent();
                                            case MANDATORY:
                                                return _currentMessage.getMessagePublishInfo().isMandatory();
                                            case REDELIVERED:
                                                return false;
                                        }
                                        return null;
                                    }
                                };

                        int enqueues = _currentMessage.getExchange().send(amqMessage, instanceProperties, _transaction,
                                                                          immediate ? _immediateAction : _capacityCheckAction);
                        if(enqueues == 0)
                        {
                            handleUnroutableMessage(amqMessage);
                        }
                        else
                        {
                            incrementOutstandingTxnsIfNecessary();
                            handle.flushToStore();
                        }
                    }
                }
                finally
                {
                    reference.release();
                }

            }
            finally
            {
                long bodySize = _currentMessage.getSize();
                long timestamp = _currentMessage.getContentHeader().getProperties().getTimestamp();
                _session.registerMessageReceived(bodySize, timestamp);
                _currentMessage = null;
            }
        }

    }

    /**
     * Either throws a {@link AMQConnectionException} or returns the message
     *
     * Pre-requisite: the current message is judged to have no destination queues.
     *
     * @throws AMQConnectionException if the message is mandatory close-on-no-route
     * @see AMQProtocolSession#isCloseWhenNoRoute()
     */
    private void handleUnroutableMessage(AMQMessage message) throws AMQConnectionException
    {
        boolean mandatory = message.isMandatory();
        String description = currentMessageDescription();
        boolean closeOnNoRoute = _session.isCloseWhenNoRoute();

        if(_logger.isDebugEnabled())
        {
            _logger.debug(String.format(
                    "Unroutable message %s, mandatory=%s, transactionalSession=%s, closeOnNoRoute=%s",
                    description, mandatory, isTransactional(), closeOnNoRoute));
        }

        if (mandatory && isTransactional() && _session.isCloseWhenNoRoute())
        {
            throw new AMQConnectionException(
                    AMQConstant.NO_ROUTE,
                    "No route for message " + currentMessageDescription(),
                    0, 0, // default class and method ids
                    getProtocolSession().getProtocolVersion().getMajorVersion(),
                    getProtocolSession().getProtocolVersion().getMinorVersion(),
                    (Throwable) null);
        }

        if (mandatory || message.isImmediate())
        {
            _transaction.addPostTransactionAction(new WriteReturnAction(AMQConstant.NO_ROUTE, "No Route for message " + currentMessageDescription(), message));
        }
        else
        {
            _actor.message(ExchangeMessages.DISCARDMSG(_currentMessage.getExchangeName().asString(),
                                                       _currentMessage.getMessagePublishInfo().getRoutingKey() == null
                                                               ? null
                                                               : _currentMessage.getMessagePublishInfo()
                                                                       .getRoutingKey()
                                                                       .toString()));
        }
    }

    private String currentMessageDescription()
    {
        if(_currentMessage == null || !_currentMessage.allContentReceived())
        {
            throw new IllegalStateException("Cannot create message description for message: " + _currentMessage);
        }

        return String.format(
                "[Exchange: %s, Routing key: %s]",
                _currentMessage.getExchangeName(),
                _currentMessage.getMessagePublishInfo().getRoutingKey() == null
                        ? null
                        : _currentMessage.getMessagePublishInfo().getRoutingKey().toString());
    }

    public void publishContentBody(ContentBody contentBody) throws AMQException
    {
        if (_currentMessage == null)
        {
            throw new AMQException("Received content body without previously receiving a Content Header");
        }

        if (_logger.isDebugEnabled())
        {
            _logger.debug(debugIdentity() + " content body received on channel " + _channelId);
        }

        try
        {
            _currentMessage.addContentBodyFrame(contentBody);

            deliverCurrentMessageIfComplete();
        }
        catch (AMQException e)
        {
            // we want to make sure we don't keep a reference to the message in the
            // event of an error
            _currentMessage = null;
            throw e;
        }
        catch (RuntimeException e)
        {
            // we want to make sure we don't keep a reference to the message in the
            // event of an error
            _currentMessage = null;
            throw e;
        }
    }

    public long getNextDeliveryTag()
    {
        return ++_deliveryTag;
    }

    public int getNextConsumerTag()
    {
        return ++_consumerTag;
    }


    public Subscription getSubscription(AMQShortString tag)
    {
        final SubscriptionTarget_0_8 target = _tag2SubscriptionTargetMap.get(tag);
        return target == null ? null : target.getSubscription();
    }

    /**
     * Subscribe to a queue. We register all subscriptions in the channel so that if the channel is closed we can clean
     * up all subscriptions, even if the client does not explicitly unsubscribe from all queues.
     *
     * @param tag       the tag chosen by the client (if null, server will generate one)
     * @param queue     the queue to subscribe to
     * @param acks      Are acks enabled for this subscriber
     * @param filters   Filters to apply to this subscriber
     *
     * @param noLocal   Flag stopping own messages being received.
     * @param exclusive Flag requesting exclusive access to the queue
     * @return the consumer tag. This is returned to the subscriber and used in subsequent unsubscribe requests
     *
     * @throws AMQException                  if something goes wrong
     */
    public AMQShortString subscribeToQueue(AMQShortString tag, AMQQueue queue, boolean acks,
                                           FieldTable filters, boolean noLocal, boolean exclusive) throws AMQException
    {
        if (tag == null)
        {
            tag = new AMQShortString("sgen_" + getNextConsumerTag());
        }

        if (_tag2SubscriptionTargetMap.containsKey(tag))
        {
            throw new AMQException("Consumer already exists with same tag: " + tag);
        }

        SubscriptionTarget_0_8 target;
        EnumSet<Subscription.Option> options = EnumSet.noneOf(Subscription.Option.class);

        if(filters != null && Boolean.TRUE.equals(filters.get(AMQPFilterTypes.NO_CONSUME.getValue())))
        {
            target = SubscriptionTarget_0_8.createBrowserTarget(this, tag, filters, _creditManager);
            options.add(Subscription.Option.TRANSIENT);
        }
        else if(acks)
        {
            target = SubscriptionTarget_0_8.createAckTarget(this, tag, filters, _creditManager);
            options.add(Subscription.Option.ACQUIRES);
            options.add(Subscription.Option.SEES_REQUEUES);
        }
        else
        {
            target = SubscriptionTarget_0_8.createNoAckTarget(this, tag, filters, _creditManager);
            options.add(Subscription.Option.ACQUIRES);
            options.add(Subscription.Option.SEES_REQUEUES);
        }

        if(exclusive)
        {
            options.add(Subscription.Option.EXCLUSIVE);
        }

        // So to keep things straight we put before the call and catch all exceptions from the register and tidy up.
        // We add before we register as the Async Delivery process may AutoClose the subscriber
        // so calling _cT2QM.remove before we have done put which was after the register succeeded.
        // So to keep things straight we put before the call and catch all exceptions from the register and tidy up.

        _tag2SubscriptionTargetMap.put(tag, target);

        try
        {
            Subscription sub =
                    queue.registerSubscription(target, FilterManagerFactory.createManager(FieldTable.convertToMap(filters)), AMQMessage.class, AMQShortString.toString(tag), options);
        }
        catch (AMQException e)
        {
            _tag2SubscriptionTargetMap.remove(tag);
            throw e;
        }
        catch (RuntimeException e)
        {
            _tag2SubscriptionTargetMap.remove(tag);
            throw e;
        }
        return tag;
    }

    /**
     * Unsubscribe a consumer from a queue.
     * @param consumerTag
     * @return true if the consumerTag had a mapped queue that could be unregistered.
     * @throws AMQException
     */
    public boolean unsubscribeConsumer(AMQShortString consumerTag) throws AMQException
    {

        SubscriptionTarget_0_8 target = _tag2SubscriptionTargetMap.remove(consumerTag);
        Subscription sub = target == null ? null : target.getSubscription();
        if (sub != null)
        {
            sub.close();
            return true;
        }
        else
        {
            _logger.warn("Attempt to unsubscribe consumer with tag '"+consumerTag+"' which is not registered.");
        }
        return false;
    }

    /**
     * Called from the protocol session to close this channel and clean up. T
     *
     * @throws AMQException if there is an error during closure
     */
    @Override
    public void close() throws AMQException
    {
        close(null, null);
    }

    public void close(AMQConstant cause, String message) throws AMQException
    {
        if(!_closing.compareAndSet(false, true))
        {
            //Channel is already closing
            return;
        }

        LogMessage operationalLogMessage = cause == null ?
                ChannelMessages.CLOSE() :
                ChannelMessages.CLOSE_FORCED(cause.getCode(), message);
        CurrentActor.get().message(_logSubject, operationalLogMessage);

        unsubscribeAllConsumers();
        _transaction.rollback();

        try
        {
            requeue();
        }
        catch (AMQException e)
        {
            _logger.error("Caught AMQException whilst attempting to requeue:" + e);
        }
        catch (TransportException e)
        {
            _logger.error("Caught TransportException whilst attempting to requeue:" + e);
        }
    }

    private void unsubscribeAllConsumers() throws AMQException
    {
        if (_logger.isInfoEnabled())
        {
            if (!_tag2SubscriptionTargetMap.isEmpty())
            {
                _logger.info("Unsubscribing all consumers on channel " + toString());
            }
            else
            {
                _logger.info("No consumers to unsubscribe on channel " + toString());
            }
        }

        for (Map.Entry<AMQShortString, SubscriptionTarget_0_8> me : _tag2SubscriptionTargetMap.entrySet())
        {
            if (_logger.isInfoEnabled())
            {
                _logger.info("Unsubscribing consumer '" + me.getKey() + "' on channel " + toString());
            }

            Subscription sub = me.getValue().getSubscription();


            sub.close();

        }

        _tag2SubscriptionTargetMap.clear();
    }

    /**
     * Add a message to the channel-based list of unacknowledged messages
     *
     * @param entry       the record of the message on the queue that was delivered
     * @param deliveryTag the delivery tag used when delivering the message (see protocol spec for description of the
     *                    delivery tag)
     * @param subscription The consumer that is to acknowledge this message.
     */
    public void addUnacknowledgedMessage(MessageInstance entry, long deliveryTag, Subscription subscription)
    {
        if (_logger.isDebugEnabled())
        {
                    _logger.debug(debugIdentity() + " Adding unacked message(" + entry.getMessage().toString() + " DT:" + deliveryTag
                               + ") for " + subscription);

        }

        _unacknowledgedMessageMap.add(deliveryTag, entry);

    }

    private final String id = "(" + System.identityHashCode(this) + ")";

    public String debugIdentity()
    {
        return _channelId + id;
    }

    /**
     * Called to attempt re-delivery all outstanding unacknowledged messages on the channel. May result in delivery to
     * this same channel or to other subscribers.
     *
     * @throws org.apache.qpid.AMQException if the requeue fails
     */
    public void requeue() throws AMQException
    {
        // we must create a new map since all the messages will get a new delivery tag when they are redelivered
        Collection<MessageInstance> messagesToBeDelivered = _unacknowledgedMessageMap.cancelAllMessages();

        if (!messagesToBeDelivered.isEmpty())
        {
            if (_logger.isInfoEnabled())
            {
                _logger.info("Requeuing " + messagesToBeDelivered.size() + " unacked messages. for " + toString());
            }

        }

        for (MessageInstance unacked : messagesToBeDelivered)
        {
            // Mark message redelivered
            unacked.setRedelivered();

            // Ensure message is released for redelivery
            unacked.release();
        }

    }

    /**
     * Requeue a single message
     *
     * @param deliveryTag The message to requeue
     *
     * @throws AMQException If something goes wrong.
     */
    public void requeue(long deliveryTag) throws AMQException
    {
        MessageInstance unacked = _unacknowledgedMessageMap.remove(deliveryTag);

        if (unacked != null)
        {
            // Mark message redelivered
            unacked.setRedelivered();

            // Ensure message is released for redelivery
            unacked.release();

        }
        else
        {
            _logger.warn("Requested requeue of message:" + deliveryTag + " but no such delivery tag exists."
                      + _unacknowledgedMessageMap.size());

        }

    }

    public boolean isMaxDeliveryCountEnabled(final long deliveryTag)
    {
        final MessageInstance queueEntry = _unacknowledgedMessageMap.get(deliveryTag);
        if (queueEntry != null)
        {
            final int maximumDeliveryCount = queueEntry.getMaximumDeliveryCount();
            return maximumDeliveryCount > 0;
        }

        return false;
    }

    public boolean isDeliveredTooManyTimes(final long deliveryTag)
    {
        final MessageInstance queueEntry = _unacknowledgedMessageMap.get(deliveryTag);
        if (queueEntry != null)
        {
            final int maximumDeliveryCount = queueEntry.getMaximumDeliveryCount();
            final int numDeliveries = queueEntry.getDeliveryCount();
            return maximumDeliveryCount != 0 && numDeliveries >= maximumDeliveryCount;
        }

        return false;
    }

    /**
     * Called to resend all outstanding unacknowledged messages to this same channel.
     *
     * @param requeue Are the messages to be requeued or dropped.
     *
     * @throws AMQException When something goes wrong.
     */
    public void resend(final boolean requeue) throws AMQException
    {


        final Map<Long, MessageInstance> msgToRequeue = new LinkedHashMap<Long, MessageInstance>();
        final Map<Long, MessageInstance> msgToResend = new LinkedHashMap<Long, MessageInstance>();

        if (_logger.isDebugEnabled())
        {
            _logger.debug("unacked map Size:" + _unacknowledgedMessageMap.size());
        }

        // Process the Unacked-Map.
        // Marking messages who still have a consumer for to be resent
        // and those that don't to be requeued.
        _unacknowledgedMessageMap.visit(new ExtractResendAndRequeue(_unacknowledgedMessageMap,
                                                                    msgToRequeue,
                                                                    msgToResend
        ));


        // Process Messages to Resend
        if (_logger.isDebugEnabled())
        {
            if (!msgToResend.isEmpty())
            {
                _logger.debug("Preparing (" + msgToResend.size() + ") message to resend.");
            }
            else
            {
                _logger.debug("No message to resend.");
            }
        }

        for (Map.Entry<Long, MessageInstance> entry : msgToResend.entrySet())
        {
            MessageInstance message = entry.getValue();
            long deliveryTag = entry.getKey();

            //Amend the delivery counter as the client hasn't seen these messages yet.
            message.decrementDeliveryCount();

            // Without any details from the client about what has been processed we have to mark
            // all messages in the unacked map as redelivered.
            message.setRedelivered();

            if (!message.resend())
            {
                msgToRequeue.put(deliveryTag, message);
            }
        } // for all messages
        // } else !isSuspend

        if (_logger.isInfoEnabled())
        {
            if (!msgToRequeue.isEmpty())
            {
                _logger.info("Preparing (" + msgToRequeue.size() + ") message to requeue to.");
            }
        }

        // Process Messages to Requeue at the front of the queue
        for (Map.Entry<Long, MessageInstance> entry : msgToRequeue.entrySet())
        {
            MessageInstance message = entry.getValue();
            long deliveryTag = entry.getKey();

            //Amend the delivery counter as the client hasn't seen these messages yet.
            message.decrementDeliveryCount();

            _unacknowledgedMessageMap.remove(deliveryTag);

            message.setRedelivered();
            message.release();

        }
    }


    /**
     * Acknowledge one or more messages.
     *
     * @param deliveryTag the last delivery tag
     * @param multiple    if true will acknowledge all messages up to an including the delivery tag. if false only
     *                    acknowledges the single message specified by the delivery tag
     *
     * @throws AMQException if the delivery tag is unknown (e.g. not outstanding) on this channel
     */
    public void acknowledgeMessage(long deliveryTag, boolean multiple) throws AMQException
    {
        Collection<MessageInstance> ackedMessages = getAckedMessages(deliveryTag, multiple);
        _transaction.dequeue(ackedMessages, new MessageAcknowledgeAction(ackedMessages));
    }

    private Collection<MessageInstance> getAckedMessages(long deliveryTag, boolean multiple)
    {

        return _unacknowledgedMessageMap.acknowledge(deliveryTag, multiple);

    }

    /**
     * Used only for testing purposes.
     *
     * @return the map of unacknowledged messages
     */
    public UnacknowledgedMessageMap getUnacknowledgedMessageMap()
    {
        return _unacknowledgedMessageMap;
    }

    /**
     * Called from the ChannelFlowHandler to suspend this Channel
     * @param suspended boolean, should this Channel be suspended
     */
    public void setSuspended(boolean suspended)
    {
        boolean wasSuspended = _suspended.getAndSet(suspended);
        if (wasSuspended != suspended)
        {
            // Log Flow Started before we start the subscriptions
            if (!suspended)
            {
                _actor.message(_logSubject, ChannelMessages.FLOW("Started"));
            }


            // This section takes two different approaches to perform to perform
            // the same function. Ensuring that the Subscription has taken note
            // of the change in Channel State

            // Here we have become unsuspended and so we ask each the queue to
            // perform an Async delivery for each of the subscriptions in this
            // Channel. The alternative would be to ensure that the subscription
            // had received the change in suspension state. That way the logic
            // behind deciding to start an async delivery was located with the
            // Subscription.
            if (wasSuspended)
            {
                // may need to deliver queued messages
                for (SubscriptionTarget_0_8 s : _tag2SubscriptionTargetMap.values())
                {
                    s.getSubscription().externalStateChange();
                }
            }


            // Here we have become suspended so we need to ensure that each of
            // the Subscriptions has noticed this change so that we can be sure
            // they are not still sending messages. Again the code here is a
            // very simplistic approach to ensure that the change of suspension
            // has been noticed by each of the Subscriptions. Unlike the above
            // case we don't actually need to do anything else.
            if (!wasSuspended)
            {
                // may need to deliver queued messages
                for (SubscriptionTarget_0_8 s : _tag2SubscriptionTargetMap.values())
                {
                    try
                    {
                        s.getSubscription().getSendLock();
                    }
                    finally
                    {
                        s.getSubscription().releaseSendLock();
                    }
                }
            }


            // Log Suspension only after we have confirmed all suspensions are
            // stopped.
            if (suspended)
            {
                _actor.message(_logSubject, ChannelMessages.FLOW("Stopped"));
            }

        }
    }

    public boolean isSuspended()
    {
        return _suspended.get()  || _closing.get() || _session.isClosing();
    }

    public void commit() throws AMQException
    {
        commit(null, false);
    }


    public void commit(final Runnable immediateAction, boolean async) throws AMQException
    {

        if (!isTransactional())
        {
            throw new AMQException("Fatal error: commit called on non-transactional channel");
        }

        if(async && _transaction instanceof LocalTransaction)
        {

            ((LocalTransaction)_transaction).commitAsync(new Runnable()
            {
                @Override
                public void run()
                {
                    immediateAction.run();
                    _txnCommits.incrementAndGet();
                    _txnStarts.incrementAndGet();
                    decrementOutstandingTxnsIfNecessary();
                }
            });
        }
        else
        {
            _transaction.commit(immediateAction);

            _txnCommits.incrementAndGet();
            _txnStarts.incrementAndGet();
            decrementOutstandingTxnsIfNecessary();
        }
    }

    public void rollback() throws AMQException
    {
        rollback(NULL_TASK);
    }

    public void rollback(Runnable postRollbackTask) throws AMQException
    {
        if (!isTransactional())
        {
            throw new AMQException("Fatal error: commit called on non-transactional channel");
        }

        // stop all subscriptions
        _rollingBack = true;
        boolean requiresSuspend = _suspended.compareAndSet(false,true);

        // ensure all subscriptions have seen the change to the channel state
        for(SubscriptionTarget_0_8 sub : _tag2SubscriptionTargetMap.values())
        {
            sub.getSubscription().getSendLock();
            sub.getSubscription().releaseSendLock();
        }

        try
        {
            _transaction.rollback();
        }
        finally
        {
            _rollingBack = false;

            _txnRejects.incrementAndGet();
            _txnStarts.incrementAndGet();
            decrementOutstandingTxnsIfNecessary();
        }

        postRollbackTask.run();

        for(MessageInstance entry : _resendList)
        {
            Subscription sub = entry.getDeliveredSubscription();
            if(sub == null || sub.isClosed())
            {
                entry.release();
            }
            else
            {
                entry.resend();
            }
        }
        _resendList.clear();

        if(requiresSuspend)
        {
            _suspended.set(false);
            for(SubscriptionTarget_0_8 sub : _tag2SubscriptionTargetMap.values())
            {
                sub.getSubscription().externalStateChange();
            }

        }
    }

    public String toString()
    {
        return "["+_session.toString()+":"+_channelId+"]";
    }

    public void setDefaultQueue(AMQQueue queue)
    {
        _defaultQueue = queue;
    }

    public AMQQueue getDefaultQueue()
    {
        return _defaultQueue;
    }


    public boolean isClosing()
    {
        return _closing.get();
    }

    public AMQProtocolSession getProtocolSession()
    {
        return _session;
    }

    public FlowCreditManager getCreditManager()
    {
        return _creditManager;
    }

    public void setCredit(final long prefetchSize, final int prefetchCount)
    {
        _actor.message(ChannelMessages.PREFETCH_SIZE(prefetchSize, prefetchCount));
        _creditManager.setCreditLimits(prefetchSize, prefetchCount);
    }

    public MessageStore getMessageStore()
    {
        return _messageStore;
    }

    public ClientDeliveryMethod getClientDeliveryMethod()
    {
        return _clientDeliveryMethod;
    }

    private final RecordDeliveryMethod _recordDeliveryMethod = new RecordDeliveryMethod()
        {

            public void recordMessageDelivery(final Subscription sub, final MessageInstance entry, final long deliveryTag)
            {
                addUnacknowledgedMessage(entry, deliveryTag, sub);
            }
        };

    public RecordDeliveryMethod getRecordDeliveryMethod()
    {
        return _recordDeliveryMethod;
    }


    private AMQMessage createAMQMessage(IncomingMessage incomingMessage, StoredMessage<MessageMetaData> handle)
            throws AMQException
    {

        AMQMessage message = new AMQMessage(handle, _session.getReference());

        final BasicContentHeaderProperties properties =
                  incomingMessage.getContentHeader().getProperties();

        long expiration = properties.getExpiration();
        message.setExpiration(expiration);
        return message;
    }

    private boolean checkMessageUserId(ContentHeaderBody header)
    {
        AMQShortString userID = header.getProperties().getUserId();
        return (!_messageAuthorizationRequired || _session.getAuthorizedPrincipal().getName().equals(userID == null? "" : userID.toString()));

    }

    @Override
    public UUID getId()
    {
        return _id;
    }

    public AMQConnectionModel getConnectionModel()
    {
        return _session;
    }

    public String getClientID()
    {
        return String.valueOf(_session.getContextKey());
    }

    public LogSubject getLogSubject()
    {
        return _logSubject;
    }

    @Override
    public int compareTo(AMQSessionModel o)
    {
        return getId().compareTo(o.getId());
    }


    private class ImmediateAction implements Action<QueueEntry>
    {

        public ImmediateAction()
        {
        }

        public void performAction(QueueEntry entry)
        {
            AMQQueue queue = entry.getQueue();

            if (!entry.getDeliveredToConsumer() && entry.acquire())
            {

                ServerTransaction txn = new LocalTransaction(_messageStore);
                final AMQMessage message = (AMQMessage) entry.getMessage();
                MessageReference ref = message.newReference();
                entry.delete();
                txn.dequeue(queue, message,
                            new ServerTransaction.Action()
                            {
                                @Override
                                public void postCommit()
                                {
                                    try
                                    {
                                        final
                                        ProtocolOutputConverter outputConverter =
                                                _session.getProtocolOutputConverter();

                                        outputConverter.writeReturn(message.getMessagePublishInfo(),
                                                                    message.getContentHeaderBody(),
                                                                    message,
                                                                    _channelId,
                                                                    AMQConstant.NO_CONSUMERS.getCode(),
                                                                    IMMEDIATE_DELIVERY_REPLY_TEXT);
                                    }
                                    catch (AMQException e)
                                    {
                                        throw new RuntimeException(e);
                                    }
                                }

                                @Override
                                public void onRollback()
                                {

                                }
                            }
                           );
                txn.commit();
                ref.release();


            }
            else
            {
                queue.checkCapacity(AMQChannel.this);
            }

        }
    }

    private final class CapacityCheckAction implements Action<QueueEntry>
    {
        @Override
        public void performAction(final QueueEntry entry)
        {
            AMQQueue queue = entry.getQueue();
            queue.checkCapacity(AMQChannel.this);
        }
    }

    private class MessageAcknowledgeAction implements ServerTransaction.Action
    {
        private final Collection<MessageInstance> _ackedMessages;

        public MessageAcknowledgeAction(Collection<MessageInstance> ackedMessages)
        {
            _ackedMessages = ackedMessages;
        }

        public void postCommit()
        {
            try
            {
                for(MessageInstance entry : _ackedMessages)
                {
                    entry.delete();
                }
            }
            finally
            {
                _acknowledgedMessages.clear();
            }

        }

        public void onRollback()
        {
            // explicit rollbacks resend the message after the rollback-ok is sent
            if(_rollingBack)
            {
                 _resendList.addAll(_ackedMessages);
            }
            else
            {
                try
                {
                    for(MessageInstance entry : _ackedMessages)
                    {
                        entry.release();
                    }
                }
                finally
                {
                    _acknowledgedMessages.clear();
                }
            }

        }
    }

    private class WriteReturnAction implements ServerTransaction.Action
    {
        private final AMQConstant _errorCode;
        private final String _description;
        private final MessageReference<AMQMessage> _reference;

        public WriteReturnAction(AMQConstant errorCode,
                                 String description,
                                 AMQMessage message)
        {
            _errorCode = errorCode;
            _description = description;
            _reference = message.newReference();
        }

        public void postCommit()
        {
            try
            {
                AMQMessage message = _reference.getMessage();
                _session.getProtocolOutputConverter().writeReturn(message.getMessagePublishInfo(),
                                                              message.getContentHeaderBody(),
                                                              message,
                                                              _channelId,
                                                              _errorCode.getCode(),
                                                              AMQShortString.validValueOf(_description));
                _reference.release();
            }
            catch (AMQException e)
            {
                //TODO
                throw new RuntimeException(e);
            }

        }

        public void onRollback()
        {
            _reference.release();
        }
    }


    public LogActor getLogActor()
    {
        return _actor;
    }

    public synchronized void block()
    {
        if(_blockingEntities.add(this))
        {
            if(_blocking.compareAndSet(false,true))
            {
                _actor.message(_logSubject, ChannelMessages.FLOW_ENFORCED("** All Queues **"));
                flow(false);
            }
        }
    }

    public synchronized void unblock()
    {
        if(_blockingEntities.remove(this))
        {
            if(_blockingEntities.isEmpty() && _blocking.compareAndSet(true,false))
            {
                _actor.message(_logSubject, ChannelMessages.FLOW_REMOVED());

                flow(true);
            }
        }
    }

    public synchronized void block(AMQQueue queue)
    {
        if(_blockingEntities.add(queue))
        {

            if(_blocking.compareAndSet(false,true))
            {
                _actor.message(_logSubject, ChannelMessages.FLOW_ENFORCED(queue.getName()));
                flow(false);
            }
        }
    }

    public synchronized void unblock(AMQQueue queue)
    {
        if(_blockingEntities.remove(queue))
        {
            if(_blockingEntities.isEmpty() && _blocking.compareAndSet(true,false) && !isClosing())
            {
                _actor.message(_logSubject, ChannelMessages.FLOW_REMOVED());

                flow(true);
            }
        }
    }

    @Override
    public Object getConnectionReference()
    {
        return getProtocolSession().getReference();
    }

    public int getUnacknowledgedMessageCount()
    {
        return getUnacknowledgedMessageMap().size();
    }

    private void flow(boolean flow)
    {
        MethodRegistry methodRegistry = _session.getMethodRegistry();
        AMQMethodBody responseBody = methodRegistry.createChannelFlowBody(flow);
        _session.writeFrame(responseBody.generateFrame(_channelId));
    }

    @Override
    public boolean getBlocking()
    {
        return _blocking.get();
    }

    public VirtualHost getVirtualHost()
    {
        return getProtocolSession().getVirtualHost();
    }

    public void checkTransactionStatus(long openWarn, long openClose, long idleWarn, long idleClose) throws AMQException
    {
        _transactionTimeoutHelper.checkIdleOrOpenTimes(_transaction, openWarn, openClose, idleWarn, idleClose);
    }

    /**
     * Typically called from the HouseKeepingThread instead of the main receiver thread,
     * therefore uses a lock to close the connection in a thread-safe manner.
     */
    private void closeConnection(String reason) throws AMQException
    {
        Lock receivedLock = _session.getReceivedLock();
        receivedLock.lock();
        try
        {
            _session.close(AMQConstant.RESOURCE_ERROR, reason);
        }
        finally
        {
            receivedLock.unlock();
        }
    }

    public void deadLetter(long deliveryTag) throws AMQException
    {
        final UnacknowledgedMessageMap unackedMap = getUnacknowledgedMessageMap();
        final MessageInstance rejectedQueueEntry = unackedMap.remove(deliveryTag);

        if (rejectedQueueEntry == null)
        {
            _logger.warn("No message found, unable to DLQ delivery tag: " + deliveryTag);
        }
        else
        {
            final ServerMessage msg = rejectedQueueEntry.getMessage();
            final Subscription sub = rejectedQueueEntry.getDeliveredSubscription();

            int requeues = rejectedQueueEntry.routeToAlternate(new Action<QueueEntry>()
                {
                    @Override
                    public void performAction(final QueueEntry requeueEntry)
                    {
                        _actor.message( _logSubject, ChannelMessages.DEADLETTERMSG(msg.getMessageNumber(),
                                                                                   requeueEntry.getQueue().getName()));
                    }
                }, null);

            if(requeues == 0)
            {

                final TransactionLogResource owningResource = rejectedQueueEntry.getOwningResource();
                if(owningResource instanceof AMQQueue)
                {
                    final AMQQueue queue = (AMQQueue) owningResource;

                    final Exchange altExchange = queue.getAlternateExchange();

                    if (altExchange == null)
                    {
                        _logger.debug("No alternate exchange configured for queue, must discard the message as unable to DLQ: delivery tag: " + deliveryTag);
                        _actor.message(_logSubject, ChannelMessages.DISCARDMSG_NOALTEXCH(msg.getMessageNumber(), queue.getName(), msg.getRoutingKey()));

                    }
                    else
                    {
                        _logger.debug(
                                "Routing process provided no queues to enqueue the message on, must discard message as unable to DLQ: delivery tag: "
                                + deliveryTag);
                        _actor.message(_logSubject,
                                       ChannelMessages.DISCARDMSG_NOROUTE(msg.getMessageNumber(), altExchange.getName()));
                    }
                }
            }

        }
    }

    public void recordFuture(final StoreFuture future, final ServerTransaction.Action action)
    {
        _unfinishedCommandsQueue.add(new AsyncCommand(future, action));
    }

    public void sync()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("sync() called on channel " + debugIdentity());
        }

        AsyncCommand cmd;
        while((cmd = _unfinishedCommandsQueue.poll()) != null)
        {
            cmd.awaitReadyForCompletion();
            cmd.complete();
        }
        if(_transaction instanceof LocalTransaction)
        {
            ((LocalTransaction)_transaction).sync();
        }
    }

    private static class AsyncCommand
    {
        private final StoreFuture _future;
        private ServerTransaction.Action _action;

        public AsyncCommand(final StoreFuture future, final ServerTransaction.Action action)
        {
            _future = future;
            _action = action;
        }

        void awaitReadyForCompletion()
        {
            _future.waitForCompletion();
        }

        void complete()
        {
            if(!_future.isComplete())
            {
                _future.waitForCompletion();
            }
            _action.postCommit();
            _action = null;
        }
    }

    @Override
    public int getConsumerCount()
    {
        return _tag2SubscriptionTargetMap.size();
    }
}
