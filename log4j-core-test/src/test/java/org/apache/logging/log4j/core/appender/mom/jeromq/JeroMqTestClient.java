/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.appender.mom.jeromq;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.util.Constants;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;

import static org.assertj.core.api.Assertions.assertThat;

class JeroMqTestClient {

    private final ZMQ.Context context;

    private final String endpoint;
    private final int receiveCount;
    private ZMQ.Socket subscriber;

    JeroMqTestClient(final ZMQ.Context context, final String endpoint, final int receiveCount) {
        this.context = context;
        this.endpoint = endpoint;
        this.receiveCount = receiveCount;
    }

    public void connect() {
        subscriber = context.socket(SocketType.SUB);
        assertThat(subscriber.connect(endpoint)).describedAs("Connected to %s", endpoint).isTrue();
        assertThat(subscriber.subscribe(Constants.EMPTY_BYTE_ARRAY)).isTrue();
    }

    public List<String> getMessages() {
        final List<String> messages = new ArrayList<>(receiveCount);
        for (int messageNum = 0; messageNum < receiveCount && !Thread.currentThread().isInterrupted(); messageNum++) {
            // Use trim to remove the tailing '0' character
            messages.add(subscriber.recvStr(0).trim());
        }
        return messages;
    }

    public void close() {
        if (subscriber != null) {
            subscriber.close();
        }
    }
}
