/*
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
 */
package org.apache.asterix.runtime.message;

import org.apache.asterix.common.messaging.api.IApplicationMessage;
import org.apache.asterix.runtime.util.AsterixClusterProperties;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.service.IControllerService;

public class TakeoverMetadataNodeResponseMessage implements IApplicationMessage {

    private static final long serialVersionUID = 1L;
    private final String nodeId;

    public TakeoverMetadataNodeResponseMessage(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }

    @Override
    public void handle(IControllerService cs) throws HyracksDataException {
        AsterixClusterProperties.INSTANCE.processMetadataNodeTakeoverResponse(this);
    }

    @Override
    public String toString() {
        return TakeoverMetadataNodeResponseMessage.class.getSimpleName();
    }
}