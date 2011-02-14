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
#include "qpid/broker/QueueFlowLimit.h"
#include "qpid/broker/Queue.h"
#include "qpid/Exception.h"
#include "qpid/framing/FieldValue.h"
#include "qpid/framing/reply_exceptions.h"
#include "qpid/log/Statement.h"
#include "qpid/sys/Mutex.h"

#include "qmf/org/apache/qpid/broker/Queue.h"

#include <sstream>

using namespace qpid::broker;
using namespace qpid::framing;

namespace {
    /** ensure that the configured flow control stop and resume values are
     * valid with respect to the maximum queue capacity, and each other
     */
    template <typename T>
    void validateFlowConfig(T max, T& stop, T& resume, const std::string& type, const std::string& queue)
    {
        if (resume > stop) {
            throw InvalidArgumentException(QPID_MSG("Queue \"" << queue << "\": qpid.flow_resume_" << type
                                                    << "=" << resume
                                                    << " must be less than qpid.flow_stop_" << type
                                                    << "=" << stop));
        }
        if (resume == 0) resume = stop;
        if (max != 0 && (max < stop)) {
            throw InvalidArgumentException(QPID_MSG("Queue \"" << queue << "\": qpid.flow_stop_" << type
                                                    << "=" << stop
                                                    << " must be less than qpid.max_" << type
                                                    << "=" << max));
        }
    }

    /** extract a capacity value as passed in an argument map
     */
    uint64_t getCapacity(const FieldTable& settings, const std::string& key, uint64_t defaultValue)
    {
        FieldTable::ValuePtr v = settings.get(key);

        int64_t result = 0;

        if (!v) return defaultValue;
        if (v->getType() == 0x23) {
            QPID_LOG(debug, "Value for " << key << " specified as float: " << v->get<float>());
        } else if (v->getType() == 0x33) {
            QPID_LOG(debug, "Value for " << key << " specified as double: " << v->get<double>());
        } else if (v->convertsTo<int64_t>()) {
            result = v->get<int64_t>();
            QPID_LOG(debug, "Got integer value for " << key << ": " << result);
            if (result >= 0) return result;
        } else if (v->convertsTo<string>()) {
            string s(v->get<string>());
            QPID_LOG(debug, "Got string value for " << key << ": " << s);
            std::istringstream convert(s);
            if (convert >> result && result >= 0) return result;
        }

        QPID_LOG(warning, "Cannot convert " << key << " to unsigned integer, using default (" << defaultValue << ")");
        return defaultValue;
    }
}



QueueFlowLimit::QueueFlowLimit(Queue *_queue,
                               uint32_t _flowStopCount, uint32_t _flowResumeCount,
                               uint64_t _flowStopSize,  uint64_t _flowResumeSize)
    : queue(_queue), queueName("<unknown>"),
      flowStopCount(_flowStopCount), flowResumeCount(_flowResumeCount),
      flowStopSize(_flowStopSize), flowResumeSize(_flowResumeSize),
      flowStopped(false), count(0), size(0), queueMgmtObj(0)
{
    uint32_t maxCount(0);
    uint64_t maxSize(0);

    if (queue) {
        queueName = _queue->getName();
        if (queue->getPolicy()) {
            maxSize = _queue->getPolicy()->getMaxSize();
            maxCount = _queue->getPolicy()->getMaxCount();
        }
    }
    validateFlowConfig( maxCount, flowStopCount, flowResumeCount, "count", queueName );
    validateFlowConfig( maxSize, flowStopSize, flowResumeSize, "size", queueName );
    QPID_LOG(info, "Queue \"" << queueName << "\": Flow limit created: flowStopCount=" << flowStopCount
             << ", flowResumeCount=" << flowResumeCount
             << ", flowStopSize=" << flowStopSize << ", flowResumeSize=" << flowResumeSize );
}



void QueueFlowLimit::enqueued(const QueuedMessage& msg)
{
    if (!msg.payload) return;

    sys::Mutex::ScopedLock l(indexLock);

    ++count;
    size += msg.payload->contentSize();

    if (!flowStopped) {
        if (flowStopCount && count > flowStopCount) {
            flowStopped = true;
            QPID_LOG(info, "Queue \"" << queueName << "\": has reached " << flowStopCount << " enqueued messages. Producer flow control activated." );
        } else if (flowStopSize && size > flowStopSize) {
            flowStopped = true;
            QPID_LOG(info, "Queue \"" << queueName << "\": has reached " << flowStopSize << " enqueued bytes. Producer flow control activated." );
        }
        if (flowStopped && queueMgmtObj)
            queueMgmtObj->set_flowStopped(true);
    }

    // KAG: test - REMOVE ONCE STABLE
    if (index.find(msg.payload) != index.end()) {
        QPID_LOG(error, "Queue \"" << queueName << "\": has enqueued a msg twice: " << msg.position);
    }

    if (flowStopped || !index.empty()) {
        msg.payload->getReceiveCompletion().startCompleter();    // don't complete until flow resumes
        index.insert(msg.payload);
    }
}



void QueueFlowLimit::dequeued(const QueuedMessage& msg)
{
    if (!msg.payload) return;

    sys::Mutex::ScopedLock l(indexLock);

    if (count > 0) {
        --count;
    } else {
        throw Exception(QPID_MSG("Flow limit count underflow on dequeue. Queue=" << queueName));
    }

    uint64_t _size = msg.payload->contentSize();
    if (_size <= size) {
        size -= _size;
    } else {
        throw Exception(QPID_MSG("Flow limit size underflow on dequeue. Queue=" << queueName));
    }

    if (flowStopped &&
        (flowResumeSize == 0 || size < flowResumeSize) &&
        (flowResumeCount == 0 || count < flowResumeCount)) {
        flowStopped = false;
        if (queueMgmtObj)
            queueMgmtObj->set_flowStopped(false);
        QPID_LOG(info, "Queue \"" << queueName << "\": has drained below the flow control resume level. Producer flow control deactivated." );
    }

    if (!index.empty()) {
        if (!flowStopped) {
            // flow enabled - release all pending msgs
            while (!index.empty()) {
                std::set< boost::intrusive_ptr<Message> >::iterator itr = index.begin();
                (*itr)->getReceiveCompletion().finishCompleter();
                index.erase(itr);
            }
        } else {
            // even if flow controlled, we must release this msg as it is being dequeued
            std::set< boost::intrusive_ptr<Message> >::iterator itr = index.find(msg.payload);
            if (itr != index.end()) {       // this msg is flow controlled, release it:
                (*itr)->getReceiveCompletion().finishCompleter();
                index.erase(itr);
            }
        }
    }
}


void QueueFlowLimit::setManagementObject(_qmfBroker::Queue *mgmtObject)
{
    queueMgmtObj = mgmtObject;
    if (queueMgmtObj) {
        queueMgmtObj->set_flowStopCount(getFlowStopCount());
        queueMgmtObj->set_flowResumeCount(getFlowResumeCount());
        queueMgmtObj->set_flowStopSize(getFlowStopSize());
        queueMgmtObj->set_flowResumeSize(getFlowResumeSize());
        queueMgmtObj->set_flowStopped(isFlowControlActive());
    }
}


void QueueFlowLimit::encode(Buffer& buffer) const
{
  buffer.putLong(flowStopCount);
  buffer.putLong(flowResumeCount);
  buffer.putLongLong(flowStopSize);
  buffer.putLongLong(flowResumeSize);
  buffer.putLong(count);
  buffer.putLongLong(size);
}


void QueueFlowLimit::decode ( Buffer& buffer )
{
  flowStopCount   = buffer.getLong();
  flowResumeCount = buffer.getLong();
  flowStopSize    = buffer.getLongLong();
  flowResumeSize  = buffer.getLongLong();
  count    = buffer.getLong();
  size     = buffer.getLongLong();
}


uint32_t QueueFlowLimit::encodedSize() const {
  return sizeof(uint32_t) +  // flowStopCount
         sizeof(uint32_t) +  // flowResumecount
         sizeof(uint64_t) +  // flowStopSize
         sizeof(uint64_t) +  // flowResumeSize
         sizeof(uint32_t) +  // count
         sizeof(uint64_t);   // size
}


const std::string QueueFlowLimit::flowStopCountKey("qpid.flow_stop_count");
const std::string QueueFlowLimit::flowResumeCountKey("qpid.flow_resume_count");
const std::string QueueFlowLimit::flowStopSizeKey("qpid.flow_stop_size");
const std::string QueueFlowLimit::flowResumeSizeKey("qpid.flow_resume_size");
uint64_t QueueFlowLimit::defaultMaxSize;
uint QueueFlowLimit::defaultFlowStopRatio;
uint QueueFlowLimit::defaultFlowResumeRatio;


void QueueFlowLimit::setDefaults(uint64_t maxQueueSize, uint flowStopRatio, uint flowResumeRatio)
{
    defaultMaxSize = maxQueueSize;
    defaultFlowStopRatio = flowStopRatio;
    defaultFlowResumeRatio = flowResumeRatio;

    /** @todo Verify valid range on Broker::Options instead of here */
    if (flowStopRatio > 100 || flowResumeRatio > 100)
        throw InvalidArgumentException(QPID_MSG("Default queue flow ratios must be between 0 and 100, inclusive:"
                                                << " flowStopRatio=" << flowStopRatio
                                                << " flowResumeRatio=" << flowResumeRatio));
    if (flowResumeRatio > flowStopRatio)
        throw InvalidArgumentException(QPID_MSG("Default queue flow stop ratio must be >= flow resume ratio:"
                                                << " flowStopRatio=" << flowStopRatio
                                                << " flowResumeRatio=" << flowResumeRatio));
}


std::auto_ptr<QueueFlowLimit> QueueFlowLimit::createQueueFlowLimit(Queue *queue, const qpid::framing::FieldTable& settings)
{
    std::string type(QueuePolicy::getType(settings));

    if (type == QueuePolicy::RING || type == QueuePolicy::RING_STRICT) {
        // The size of a RING queue is limited by design - no need for flow control.
        return std::auto_ptr<QueueFlowLimit>();
    }

    if (settings.get(flowStopCountKey) || settings.get(flowStopSizeKey)) {
        uint32_t flowStopCount = getCapacity(settings, flowStopCountKey, 0);
        uint32_t flowResumeCount = getCapacity(settings, flowResumeCountKey, 0);
        uint64_t flowStopSize = getCapacity(settings, flowStopSizeKey, 0);
        uint64_t flowResumeSize = getCapacity(settings, flowResumeSizeKey, 0);
        if (flowStopCount == 0 && flowStopSize == 0) {   // disable flow control
            return std::auto_ptr<QueueFlowLimit>();
        }
        return std::auto_ptr<QueueFlowLimit>(new QueueFlowLimit(queue, flowStopCount, flowResumeCount,
                                                                flowStopSize, flowResumeSize));
    }

    if (defaultFlowStopRatio) {
        uint64_t maxByteCount = getCapacity(settings, QueuePolicy::maxSizeKey, defaultMaxSize);
        uint64_t flowStopSize = (uint64_t)(maxByteCount * (defaultFlowStopRatio/100.0) + 0.5);
        uint64_t flowResumeSize = (uint64_t)(maxByteCount * (defaultFlowResumeRatio/100.0));

        return std::auto_ptr<QueueFlowLimit>(new QueueFlowLimit(queue, 0, 0, flowStopSize, flowResumeSize));
    }
    return std::auto_ptr<QueueFlowLimit>();
}


namespace qpid {
    namespace broker {

std::ostream& operator<<(std::ostream& out, const QueueFlowLimit& f)
{
    out << "; flowStopCount=" << f.flowStopCount << ", flowResumeCount=" << f.flowResumeCount;
    out << "; flowStopSize=" << f.flowStopSize << ", flowResumeSize=" << f.flowResumeSize;
    return out;
}

    }
}

