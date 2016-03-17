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
package org.apache.asterix.runtime.operators.joins;

import org.apache.asterix.runtime.evaluators.functions.temporal.IntervalLogic;
import org.apache.asterix.runtime.evaluators.functions.temporal.IntervalPartitionLogic;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.std.buffermanager.ITupleAccessor;

public class EndsIntervalMergeJoinChecker extends AbstractIntervalMergeJoinChecker {
    private static final long serialVersionUID = 1L;

    public EndsIntervalMergeJoinChecker(int[] keysLeft, int[] keysRight) {
        super(keysLeft[0], keysRight[0]);
    }

    @Override
    public boolean checkToSaveInMemory(ITupleAccessor accessorLeft, ITupleAccessor accessorRight)
            throws HyracksDataException {
        long end0 = IntervalJoinUtil.getIntervalEnd(accessorLeft, idLeft);
        long end1 = IntervalJoinUtil.getIntervalEnd(accessorRight, idRight);
        return (end0 <= end1);
    }

    @Override
    public boolean checkToRemoveInMemory(ITupleAccessor accessorLeft, ITupleAccessor accessorRight)
            throws HyracksDataException {
        return !checkToSaveInMemory(accessorLeft, accessorRight);
    }

    public <T extends Comparable<T>> boolean compareInterval(T start0, T end0, T start1, T end1) {
        return IntervalLogic.ends(start0, end0, start1, end1);
    }

    public <T extends Comparable<T>> boolean compareIntervalPartition(T start0, T end0, T start1, T end1) {
        return IntervalPartitionLogic.ends(start0, end0, start1, end1);
    }

}