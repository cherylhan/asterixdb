/*
 * Copyright 2009-2012 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.hyracks.storage.am.lsm.invertedindex.search;

import java.io.IOException;
import java.util.ArrayList;

import edu.uci.ics.hyracks.api.context.IHyracksCommonContext;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.comm.io.ArrayTupleBuilder;
import edu.uci.ics.hyracks.dataflow.common.comm.io.ArrayTupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ITupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexOperationContext;
import edu.uci.ics.hyracks.storage.am.common.api.IndexException;
import edu.uci.ics.hyracks.storage.am.common.tuples.ConcatenatingTupleReference;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndexSearchModifier;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedListCursor;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IPartitionedInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.exceptions.OccurrenceThresholdPanicException;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.OnDiskInvertedIndexSearchCursor;

public class PartitionedTOccurrenceSearcher extends AbstractTOccurrenceSearcher {

    protected final ArrayTupleBuilder lowerBoundTupleBuilder = new ArrayTupleBuilder(1);
    protected final ArrayTupleReference lowerBoundTuple = new ArrayTupleReference();
    protected final ArrayTupleBuilder upperBoundTupleBuilder = new ArrayTupleBuilder(1);
    protected final ArrayTupleReference upperBoundTuple = new ArrayTupleReference();
    protected final ConcatenatingTupleReference fullLowSearchKey = new ConcatenatingTupleReference(2);
    protected final ConcatenatingTupleReference fullHighSearchKey = new ConcatenatingTupleReference(2);

    protected final InvertedListPartitions partitions = new InvertedListPartitions();

    public PartitionedTOccurrenceSearcher(IHyracksCommonContext ctx, IInvertedIndex invIndex) {
        super(ctx, invIndex);
        initHelperTuples();
    }

    private void initHelperTuples() {
        try {
            lowerBoundTupleBuilder.reset();
            // Write dummy value.
            lowerBoundTupleBuilder.getDataOutput().writeInt(Integer.MIN_VALUE);
            lowerBoundTupleBuilder.addFieldEndOffset();
            lowerBoundTuple.reset(lowerBoundTupleBuilder.getFieldEndOffsets(), lowerBoundTupleBuilder.getByteArray());
            // Only needed for setting the number of fields in searchKey.
            searchKey.reset(queryTokenAccessor, 0);
            fullLowSearchKey.reset();
            fullLowSearchKey.addTuple(searchKey);
            fullLowSearchKey.addTuple(lowerBoundTuple);

            upperBoundTupleBuilder.reset();
            // Write dummy value.
            upperBoundTupleBuilder.getDataOutput().writeInt(Integer.MAX_VALUE);
            upperBoundTupleBuilder.addFieldEndOffset();
            upperBoundTuple.reset(upperBoundTupleBuilder.getFieldEndOffsets(), upperBoundTupleBuilder.getByteArray());
            // Only needed for setting the number of fields in searchKey.
            searchKey.reset(queryTokenAccessor, 0);
            fullHighSearchKey.reset();
            fullHighSearchKey.addTuple(searchKey);
            fullHighSearchKey.addTuple(upperBoundTuple);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void search(OnDiskInvertedIndexSearchCursor resultCursor, InvertedIndexSearchPredicate searchPred,
            IIndexOperationContext ictx) throws HyracksDataException, IndexException {
        tokenizeQuery(searchPred);
        int numQueryTokens = queryTokenAccessor.getTupleCount();

        IInvertedIndexSearchModifier searchModifier = searchPred.getSearchModifier();
        int numTokensLowerBound = searchModifier.getNumTokensLowerBound(numQueryTokens);
        int numTokensUpperBound = searchModifier.getNumTokensUpperBound(numQueryTokens);

        IPartitionedInvertedIndex partInvIndex = (IPartitionedInvertedIndex) invIndex;
        invListCursorCache.reset();
        partitions.reset(numTokensLowerBound, numTokensUpperBound);
        for (int i = 0; i < numQueryTokens; i++) {
            searchKey.reset(queryTokenAccessor, i);
            partInvIndex.openInvertedListPartitionCursors(this, ictx, numTokensLowerBound, numTokensUpperBound,
                    partitions);
        }

        occurrenceThreshold = searchModifier.getOccurrenceThreshold(numQueryTokens);
        if (occurrenceThreshold <= 0) {
            throw new OccurrenceThresholdPanicException("Merge Threshold is <= 0. Failing Search.");
        }

        // Process the partitions one-by-one.
        ArrayList<IInvertedListCursor>[] partitionCursors = partitions.getPartitions();
        int start = partitions.getMinValidPartitionIndex();
        int end = partitions.getMaxValidPartitionIndex();
        searchResult.reset();
        for (int i = start; i <= end; i++) {
            if (partitionCursors[i] == null) {
                continue;
            }
            // Prune partition because no element in it can satisfy the occurrence threshold.
            if (partitionCursors[i].size() < occurrenceThreshold) {
                continue;
            }
            // Merge inverted lists of current partition.
            int numPrefixLists = searchModifier.getNumPrefixLists(occurrenceThreshold, partitionCursors[i].size());
            invListMerger.reset();
            invListMerger.merge(partitionCursors[i], occurrenceThreshold, numPrefixLists, searchResult);
        }

        resultCursor.open(null, searchPred);
    }

    public void setNumTokensBoundsInSearchKeys(int numTokensLowerBound, int numTokensUpperBound) {
        IntegerSerializerDeserializer.putInt(numTokensLowerBound, lowerBoundTuple.getFieldData(0),
                lowerBoundTuple.getFieldStart(0));
        IntegerSerializerDeserializer.putInt(numTokensUpperBound, upperBoundTuple.getFieldData(0),
                upperBoundTuple.getFieldStart(0));
    }

    public ITupleReference getPrefixSearchKey() {
        return searchKey;
    }

    public ITupleReference getFullLowSearchKey() {
        return fullLowSearchKey;
    }

    public ITupleReference getFullHighSearchKey() {
        return fullHighSearchKey;
    }

    public IInvertedListCursor getCachedInvertedListCursor() {
        return invListCursorCache.getNext();
    }
}