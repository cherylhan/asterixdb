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
package edu.uci.ics.hyracks.storage.am.lsm.invertedindex.impls;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.api.io.FileReference;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.ITupleReference;
import edu.uci.ics.hyracks.storage.am.btree.frames.BTreeLeafFrameType;
import edu.uci.ics.hyracks.storage.am.btree.impls.BTree;
import edu.uci.ics.hyracks.storage.am.btree.impls.RangePredicate;
import edu.uci.ics.hyracks.storage.am.btree.util.BTreeUtils;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexAccessor;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexBulkLoadContext;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexBulkLoader;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexCursor;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexOpContext;
import edu.uci.ics.hyracks.storage.am.common.api.IModificationOperationCallback;
import edu.uci.ics.hyracks.storage.am.common.api.ISearchOperationCallback;
import edu.uci.ics.hyracks.storage.am.common.api.ISearchPredicate;
import edu.uci.ics.hyracks.storage.am.common.api.ITreeIndex;
import edu.uci.ics.hyracks.storage.am.common.api.IndexException;
import edu.uci.ics.hyracks.storage.am.common.api.IndexType;
import edu.uci.ics.hyracks.storage.am.common.dataflow.IIndex;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallback;
import edu.uci.ics.hyracks.storage.am.common.ophelpers.MultiComparator;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMComponentFinalizer;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIndexFileManager;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMFlushController;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIOOperation;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIOOperationCallback;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIOOperationScheduler;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIndex;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMMergePolicy;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMOperationTracker;
import edu.uci.ics.hyracks.storage.am.lsm.common.freepage.InMemoryBufferCache;
import edu.uci.ics.hyracks.storage.am.lsm.common.freepage.InMemoryFreePageManager;
import edu.uci.ics.hyracks.storage.am.lsm.common.impls.BTreeFactory;
import edu.uci.ics.hyracks.storage.am.lsm.common.impls.BlockingIOOperationCallback;
import edu.uci.ics.hyracks.storage.am.lsm.common.impls.LSMHarness;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.api.IInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.impls.LSMInvertedIndexFileManager.LSMInvertedIndexFileNameComponent;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.inmemory.InMemoryInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.OnDiskInvertedIndex;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.ondisk.OnDiskInvertedIndexFactory;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.tokenizers.IBinaryTokenizerFactory;
import edu.uci.ics.hyracks.storage.am.lsm.invertedindex.util.InvertedIndexUtils;
import edu.uci.ics.hyracks.storage.common.buffercache.IBufferCache;
import edu.uci.ics.hyracks.storage.common.file.IFileMapProvider;

public class LSMInvertedIndex implements ILSMIndex, IIndex {
	private final Logger LOGGER = Logger.getLogger(LSMInvertedIndex.class.getName());
	
	public class LSMInvertedIndexComponent {
        private final IInvertedIndex invIndex;
        private final BTree deleteKeysBTree;

        LSMInvertedIndexComponent(IInvertedIndex invIndex, BTree deleteKeysBTree) {
            this.invIndex = invIndex;
            this.deleteKeysBTree = deleteKeysBTree;
        }

        public IInvertedIndex getInvIndex() {
            return invIndex;
        }

        public BTree getDeletedKeysBTree() {
            return deleteKeysBTree;
        }
    }
	
    protected final LSMHarness lsmHarness;
    
    // In-memory components.
    protected final LSMInvertedIndexComponent memComponent;
    protected final IBufferCache memBufferCache;
    protected final InMemoryFreePageManager memFreePageManager;
    protected final IBinaryTokenizerFactory tokenizerFactory;
    protected FileReference memDeleteKeysBTreeFile = new FileReference(new File("membtree"));
    
    // On-disk components.
    protected final ILSMIndexFileManager fileManager;
    // For creating inverted indexes in flush and merge.
    protected final OnDiskInvertedIndexFactory diskInvIndexFactory;
    // For creating deleted-keys BTrees in flush and merge.
    protected final BTreeFactory diskBTreeFactory;
    protected final IBufferCache diskBufferCache;
    protected final IFileMapProvider diskFileMapProvider;
    // List of LSMInvertedIndexComponent instances. Using Object for better sharing via
    // ILSMIndex + LSMHarness.
    protected final LinkedList<Object> diskComponents = new LinkedList<Object>();
    // Helps to guarantees physical consistency of LSM components.
    protected final ILSMComponentFinalizer componentFinalizer;
    
    // Type traits and comparators for tokens and inverted-list elements.
    protected final ITypeTraits[] invListTypeTraits;
    protected final IBinaryComparatorFactory[] invListCmpFactories;
    protected final ITypeTraits[] tokenTypeTraits;
    protected final IBinaryComparatorFactory[] tokenCmpFactories;
    
    private boolean isActivated = false;

    public LSMInvertedIndex(IBufferCache memBufferCache, InMemoryFreePageManager memFreePageManager,
            OnDiskInvertedIndexFactory diskInvIndexFactory, BTreeFactory diskBTreeFactory, ILSMIndexFileManager fileManager,
            IFileMapProvider diskFileMapProvider, ITypeTraits[] invListTypeTraits,
            IBinaryComparatorFactory[] invListCmpFactories, ITypeTraits[] tokenTypeTraits,
            IBinaryComparatorFactory[] tokenCmpFactories, IBinaryTokenizerFactory tokenizerFactory,
            ILSMFlushController flushController, ILSMMergePolicy mergePolicy, ILSMOperationTracker opTracker,
            ILSMIOOperationScheduler ioScheduler) throws IndexException {
        InMemoryInvertedIndex memInvIndex = InvertedIndexUtils.createInMemoryBTreeInvertedindex(memBufferCache,
                memFreePageManager, invListTypeTraits, invListCmpFactories, tokenTypeTraits, tokenCmpFactories,
                tokenizerFactory);
        BTree deleteKeysBTree = BTreeUtils.createBTree(memBufferCache, diskFileMapProvider, invListTypeTraits,
                invListCmpFactories, BTreeLeafFrameType.REGULAR_NSM, memDeleteKeysBTreeFile);
        memComponent = new LSMInvertedIndexComponent(memInvIndex, deleteKeysBTree);
        this.memBufferCache = memBufferCache;
        this.memFreePageManager = memFreePageManager;
        this.tokenizerFactory = tokenizerFactory;
        this.fileManager = fileManager;
        this.diskInvIndexFactory = diskInvIndexFactory;
        this.diskBTreeFactory = diskBTreeFactory;
        this.diskBufferCache = diskInvIndexFactory.getBufferCache();
        this.diskFileMapProvider = diskFileMapProvider;
        this.invListTypeTraits = invListTypeTraits;
        this.invListCmpFactories = invListCmpFactories;
        this.tokenTypeTraits = tokenTypeTraits;
        this.tokenCmpFactories = tokenCmpFactories;
        this.lsmHarness = new LSMHarness(this, flushController, mergePolicy, opTracker, ioScheduler);
        this.componentFinalizer = new LSMInvertedIndexComponentFinalizer(diskFileMapProvider);
    }

    @Override
    public synchronized void create() throws HyracksDataException {
        if (isActivated) {
            throw new HyracksDataException("Failed to create the index since it is activated.");
        }

        fileManager.deleteDirs();
        fileManager.createDirs();
    }

    @Override
    // TODO: Properly implement this one.
    public synchronized void activate() throws HyracksDataException {
        if (isActivated) {
            return;
        }

        ((InMemoryBufferCache) memComponent.getInvIndex().getBufferCache()).open();
        memComponent.getInvIndex().create();
        memComponent.getDeletedKeysBTree().create();
        List<Object> validFileNames = fileManager.cleanupAndGetValidFiles(componentFinalizer);
        for (Object o : validFileNames) {
            LSMInvertedIndexFileNameComponent component = (LSMInvertedIndexFileNameComponent) o;
            FileReference rtreeFile = new FileReference(new File(component.getRTreeFileName()));
            FileReference btreeFile = new FileReference(new File(component.getBTreeFileName()));
            RTree rtree = (RTree) createDiskTree(diskRTreeFactory, rtreeFile, false);
            BTree btree = (BTree) createDiskTree(diskBTreeFactory, btreeFile, false);
            LSMRTreeComponent diskComponent = new LSMRTreeComponent(rtree, btree);
            diskComponents.add(diskComponent);
        }
        isActivated = true;
    }

    protected IInvertedIndex createDiskInvIndex(OnDiskInvertedIndexFactory invIndexFactory, FileReference fileRef, boolean create) throws HyracksDataException, IndexException {
        IInvertedIndex invIndex = invIndexFactory.createIndexInstance(fileRef);
        if (create) {
            invIndex.create();
        }
        // Will be closed during cleanup of merge().
        invIndex.activate();
        return invIndex;
    }
    
    protected ITreeIndex createDiskDeletedKeysBTree(BTreeFactory btreeFactory, FileReference fileRef, boolean create) throws HyracksDataException, IndexException {
        ITreeIndex btree = btreeFactory.createIndexInstance(fileRef);
        if (create) {
            btree.create();
        }
        // Will be closed during cleanup of merge().
        btree.activate();
        return btree;
    }
    
    @Override
    public void clear() throws HyracksDataException {
        if (!isActivated) {
            throw new HyracksDataException("Failed to clear the index since it is not activated.");
        }
        memComponent.getInvIndex().clear();
        memComponent.getDeletedKeysBTree().clear();
        for (Object o : diskComponents) {
            LSMInvertedIndexComponent component = (LSMInvertedIndexComponent) o;
            component.getInvIndex().deactivate();
            component.getDeletedKeysBTree().deactivate();
            component.getInvIndex().destroy();
            component.getDeletedKeysBTree().destroy();
        }
        diskComponents.clear();
    }

    @Override
    public synchronized void deactivate() throws HyracksDataException {
        if (!isActivated) {
            return;
        }

        isActivated = false;

        BlockingIOOperationCallback blockingCallBack = new BlockingIOOperationCallback();
        ILSMIndexAccessor accessor = (ILSMIndexAccessor) createAccessor(NoOpOperationCallback.INSTANCE,
                NoOpOperationCallback.INSTANCE);
        lsmHarness.getIOScheduler().scheduleOperation(accessor.createFlushOperation(blockingCallBack));
        try {
            blockingCallBack.waitForIO();
        } catch (InterruptedException e) {
            throw new HyracksDataException(e);
        }

        memComponent.getInvIndex().deactivate();
        memComponent.getDeletedKeysBTree().deactivate();
        memComponent.getInvIndex().destroy();
        memComponent.getDeletedKeysBTree().destroy();
        ((InMemoryBufferCache) memComponent.getInvIndex().getBufferCache()).close();
    }

    @Override
    public synchronized void destroy() throws HyracksDataException {
        if (isActivated) {
            throw new HyracksDataException("Failed to destroy the index since it is activated.");
        }

        memComponent.getInvIndex().destroy();
        memComponent.getDeletedKeysBTree().destroy();        
        for (Object o : diskComponents) {
            LSMInvertedIndexComponent component = (LSMInvertedIndexComponent) o;
            component.getInvIndex().destroy();
            component.getDeletedKeysBTree().destroy();
        }
        fileManager.deleteDirs();
    }

    @Override
    public IIndexAccessor createAccessor(IModificationOperationCallback modificationCallback,
            ISearchOperationCallback searchCallback) {
        // TODO: Ignore opcallbacks for now.
        return new LSMInvertedIndexAccessor(lsmHarness, createOpContext());
    }

    @Override
    public void validate() throws HyracksDataException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public long getInMemorySize() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public IIndexBulkLoader createBulkLoader(float fillFactor, boolean verifyInput) throws IndexException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ILSMIOOperation createMergeOperation(ILSMIOOperationCallback callback) throws HyracksDataException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object merge(List<Object> mergedComponents, ILSMIOOperation operation) throws HyracksDataException,
            IndexException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object flush(ILSMIOOperation operation) throws HyracksDataException, IndexException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ILSMFlushController getFlushController() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ILSMOperationTracker getOperationTracker() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ILSMIOOperationScheduler getIOScheduler() {
        // TODO Auto-generated method stub
        return null;
    }

    private LSMInvertedIndexOpContext createOpContext() {
        return new LSMInvertedIndexOpContext(memoryInvertedIndex);
    }

    @Override
    public IBufferCache getBufferCache() {
        return diskBufferCache;
    }

    @Override
    public IndexType getIndexType() {
        return IndexType.INVERTED;
    }

    public boolean insertUpdateOrDelete(ITupleReference tuple, IIndexOpContext ictx) throws HyracksDataException,
            IndexException {
        // TODO: Only insert is supported for now. Will need the context for later when update and delete 
        // are also supported.
        LSMInvertedIndexOpContext ctx = (LSMInvertedIndexOpContext) ictx;
        memAccessor.insert(tuple);
        return true;
    }

    @Override
    public void search(IIndexCursor cursor, List<Object> diskComponents, ISearchPredicate pred, IIndexOpContext ictx,
            boolean includeMemComponent, AtomicInteger searcherRefCount) throws HyracksDataException, IndexException {
        IIndexAccessor componentAccessor;

        // Over-provision by 1 if includeMemComponent == false, but that's okay!
        ArrayList<IIndexAccessor> indexAccessors = new ArrayList<IIndexAccessor>(diskComponents.size() + 1);

        if (includeMemComponent) {
            componentAccessor = memoryInvertedIndex.createAccessor();
            indexAccessors.add(componentAccessor);
        }

        for (int i = 0; i < diskComponents.size(); i++) {
            componentAccessor = ((IInvertedIndex) diskComponents.get(i)).createAccessor();
            indexAccessors.add(componentAccessor);
        }

        LSMInvertedIndexCursorInitialState initState = new LSMInvertedIndexCursorInitialState(indexAccessors, ictx,
                includeMemComponent, searcherRefCount, lsmHarness);
        LSMInvertedIndexSearchCursor lsmCursor = (LSMInvertedIndexSearchCursor) cursor;
        lsmCursor.open(initState, pred);
    }

    public void mergeSearch(IIndexCursor cursor, List<Object> diskComponents, ISearchPredicate pred,
            IIndexOpContext ictx, boolean includeMemComponent, AtomicInteger searcherRefCount)
            throws HyracksDataException, IndexException {
        IIndexAccessor componentAccessor;

        // Over-provision by 1 if includeMemComponent == false, but that's okay!
        ArrayList<IIndexAccessor> indexAccessors = new ArrayList<IIndexAccessor>(diskComponents.size() + 1);

        if (includeMemComponent) {
            componentAccessor = memoryInvertedIndex.createAccessor();
            indexAccessors.add(componentAccessor);
        }

        for (int i = 0; i < diskComponents.size(); i++) {
            componentAccessor = ((IInvertedIndex) diskComponents.get(i)).createAccessor();
            indexAccessors.add(componentAccessor);
        }

        LSMInvertedIndexCursorInitialState initState = new LSMInvertedIndexCursorInitialState(indexAccessors, ictx,
                includeMemComponent, searcherRefCount, lsmHarness);
        LSMInvertedIndexRangeSearchCursor rangeSearchCursor = (LSMInvertedIndexRangeSearchCursor) cursor;
        rangeSearchCursor.open(initState, pred);
    }

    @Override
    public Object merge(List<Object> mergedComponents) throws HyracksDataException, IndexException {
        LSMInvertedIndexOpContext ctx = createOpContext();

        IIndexCursor cursor = new LSMInvertedIndexRangeSearchCursor();
        RangePredicate mergePred = new RangePredicate(null, null, true, true, null, null);

        //Scan diskInvertedIndexes ignoring the memoryInvertedIndex.
        List<Object> mergingComponents = lsmHarness.mergeSearch(cursor, mergePred, ctx, false);
        mergedComponents.addAll(mergingComponents);

        // Nothing to merge.
        if (mergedComponents.size() <= 1) {
            cursor.close();
            return null;
        }

        // Bulk load the tuples from all diskInvertedIndexes into the new diskInvertedIndex.
        LSMInvertedFileNameComponent fNameComponent = getMergeTargetFileName(mergedComponents);
        BTree diskBTree = createDiskBTree(fileManager.createMergeFile(fNameComponent.getBTreeFileName()), true);
        //    - Create an InvertedIndex instance
        OnDiskInvertedIndex mergedDiskInvertedIndex = createDiskInvertedIndex(
                fileManager.createMergeFile(fNameComponent.getInvertedFileName()), true, diskBTree);

        IIndexBulkLoadContext bulkLoadCtx = mergedDiskInvertedIndex.beginBulkLoad(1.0f);
        try {
            while (cursor.hasNext()) {
                cursor.next();
                ITupleReference tuple = cursor.getTuple();
                mergedDiskInvertedIndex.bulkLoadAddTuple(tuple, bulkLoadCtx);
            }
        } finally {
            cursor.close();
        }
        mergedDiskInvertedIndex.endBulkLoad(bulkLoadCtx);

        return mergedDiskInvertedIndex;
    }

    private LSMInvertedFileNameComponent getMergeTargetFileName(List<Object> mergingDiskTrees)
            throws HyracksDataException {
        BTree firstTree = ((OnDiskInvertedIndex) mergingDiskTrees.get(0)).getBTree();
        BTree lastTree = ((OnDiskInvertedIndex) mergingDiskTrees.get(mergingDiskTrees.size() - 1)).getBTree();
        FileReference firstFile = diskFileMapProvider.lookupFileName(firstTree.getFileId());
        FileReference lastFile = diskFileMapProvider.lookupFileName(lastTree.getFileId());
        LSMInvertedFileNameComponent component = (LSMInvertedFileNameComponent) ((LSMInvertedIndexFileManager) fileManager)
                .getRelMergeFileName(firstFile.getFile().getName(), lastFile.getFile().getName());
        return component;
    }

    @Override
    public void addMergedComponent(Object newComponent, List<Object> mergedComponents) {
        diskComponents.removeAll(mergedComponents);
        diskComponents.addLast(newComponent);
    }

    @Override
    public void cleanUpAfterMerge(List<Object> mergedComponents) throws HyracksDataException {
        for (Object o : mergedComponents) {
            OnDiskInvertedIndex oldInvertedIndex = (OnDiskInvertedIndex) o;
            BTree oldBTree = oldInvertedIndex.getBTree();

            //delete a diskBTree file.
            FileReference fileRef = diskFileMapProvider.lookupFileName(oldBTree.getFileId());
            diskBufferCache.closeFile(oldBTree.getFileId());
            diskBufferCache.deleteFile(oldBTree.getFileId(), false);
            oldBTree.close();
            fileRef.getFile().delete();

            //delete a diskInvertedIndex file.
            fileRef = diskFileMapProvider.lookupFileName(oldInvertedIndex.getFileId());
            diskBufferCache.closeFile(oldInvertedIndex.getFileId());
            diskBufferCache.deleteFile(oldInvertedIndex.getFileId(), false);
            oldInvertedIndex.close();
            fileRef.getFile().delete();
        }
    }

    @Override
    public Object flush() throws HyracksDataException, IndexException {

        // ---------------------------------------------------
        // [Flow]
        // #. Create a scanCursor for the BTree of the memoryInvertedIndex to iterate all keys in it.
        // #. Create an diskInvertedIndex where all keys of memoryInvertedIndex will be bulkloaded.
        //    - Create a BTree instance for diskBTree 
        //    - Create an InvertedIndex instance
        // #. Begin the bulkload of the diskInvertedIndex.
        // #. While iterating the scanCursor, add each key into the diskInvertedIndex in the bulkload mode.
        // #. End the bulkload.
        // #. Return the newly created diskInvertedIndex.
        // ---------------------------------------------------

        // #. Create a scanCursor of memoryInvertedIndex to iterate all keys in it.
        BTree inMemBtree = ((InMemoryInvertedIndex) memoryInvertedIndex).getBTree();
        IIndexAccessor btreeAccessor = inMemBtree.createAccessor();
        MultiComparator btreeMultiComparator = MultiComparator.create(inMemBtree.getComparatorFactories());
        RangePredicate scanPred = new RangePredicate(null, null, true, true, btreeMultiComparator, btreeMultiComparator);

        IIndexCursor scanCursor = btreeAccessor.createSearchCursor();
        btreeAccessor.search(scanCursor, scanPred);

        // #. Create a diskInvertedIndex where all keys of memoryInvertedIndex will be bulkloaded.
        //    - Create a BTree instance for diskBTree
        LSMInvertedFileNameComponent fNameComponent = (LSMInvertedFileNameComponent) fileManager.getRelFlushFileName();
        BTree diskBTree = createDiskBTree(fileManager.createFlushFile(fNameComponent.getBTreeFileName()), true);
        //    - Create an InvertedIndex instance
        OnDiskInvertedIndex diskInvertedIndex = createDiskInvertedIndex(
                fileManager.createFlushFile(fNameComponent.getInvertedFileName()), true, diskBTree);

        // #. Begin the bulkload of the diskInvertedIndex.
        IIndexBulkLoadContext bulkLoadCtx = diskInvertedIndex.beginBulkLoad(1.0f);
        try {
            while (scanCursor.hasNext()) {
                scanCursor.next();
                diskInvertedIndex.bulkLoadAddTuple(scanCursor.getTuple(), bulkLoadCtx);
            }
        } finally {
            scanCursor.close();
        }
        diskInvertedIndex.endBulkLoad(bulkLoadCtx);

        return diskInvertedIndex;
    }

    private BTree createBTreeFlushTarget() throws HyracksDataException {
        LSMInvertedFileNameComponent fNameComponent = (LSMInvertedFileNameComponent) fileManager.getRelFlushFileName();
        FileReference fileRef = fileManager.createFlushFile(fNameComponent.getBTreeFileName());
        return createDiskBTree(fileRef, true);
    }

    private BTree createDiskBTree(FileReference fileRef, boolean createBTree) throws HyracksDataException {
        // File will be deleted during cleanup of merge().
        diskBufferCache.createFile(fileRef);
        int diskBTreeFileId = diskFileMapProvider.lookupFileId(fileRef);
        // File will be closed during cleanup of merge().
        diskBufferCache.openFile(diskBTreeFileId);
        // Create new BTree instance.
        BTree diskBTree = diskBTreeFactory.createIndexInstance();
        if (createBTree) {
            diskBTree.create(diskBTreeFileId);
        }
        // BTree will be closed during cleanup of merge().
        diskBTree.open(diskBTreeFileId);
        return diskBTree;
    }

    private OnDiskInvertedIndex createInvertedIndexFlushTarget(BTree diskBTree) throws HyracksDataException {
        FileReference fileRef = fileManager.createFlushFile((String) fileManager.getRelFlushFileName());
        return createDiskInvertedIndex(fileRef, true, diskBTree);
    }

    private OnDiskInvertedIndex createDiskInvertedIndex(FileReference fileRef, boolean createInvertedIndex, BTree diskBTree)
            throws HyracksDataException {
        // File will be deleted during cleanup of merge().
        diskBufferCache.createFile(fileRef);
        int diskInvertedIndexFileId = diskFileMapProvider.lookupFileId(fileRef);
        // File will be closed during cleanup of merge().
        diskBufferCache.openFile(diskInvertedIndexFileId);
        // Create new InvertedIndex instance.
        OnDiskInvertedIndex diskInvertedIndex = (OnDiskInvertedIndex) diskInvertedIndexFactory.createIndexInstance(diskBTree);
        if (createInvertedIndex) {
            diskInvertedIndex.create(diskInvertedIndexFileId);
        }
        // InvertedIndex will be closed during cleanup of merge().
        diskInvertedIndex.open(diskInvertedIndexFileId);
        return diskInvertedIndex;
    }

    public void addFlushedComponent(Object index) {
        diskInvertedIndexList.addFirst(index);
    }

    @Override
    public InMemoryFreePageManager getInMemoryFreePageManager() {
        // TODO This code should be changed more generally if IInMemoryInvertedIndex interface is defined and
        //      InMemoryBtreeInvertedIndex implements IInMemoryInvertedIndex
        InMemoryInvertedIndex memoryBTreeInvertedIndex = (InMemoryInvertedIndex) memoryInvertedIndex;
        return (InMemoryFreePageManager) memoryBTreeInvertedIndex.getBTree().getFreePageManager();
    }

    @Override
    public void resetInMemoryComponent() throws HyracksDataException {
        // TODO This code should be changed more generally if IInMemoryInvertedIndex interface is defined and
        //      InMemoryBtreeInvertedIndex implements IInMemoryInvertedIndex
        InMemoryInvertedIndex memoryBTreeInvertedIndex = (InMemoryInvertedIndex) memoryInvertedIndex;
        BTree memBTree = memoryBTreeInvertedIndex.getBTree();
        InMemoryFreePageManager memFreePageManager = (InMemoryFreePageManager) memBTree.getFreePageManager();
        memFreePageManager.reset();
        memBTree.create(memBTree.getFileId());
    }

    @Override
    public List<Object> getDiskComponents() {
        return diskComponents;
    }

    @Override
    public ILSMComponentFinalizer getComponentFinalizer() {
        return componentFinalizer;
    }
}
