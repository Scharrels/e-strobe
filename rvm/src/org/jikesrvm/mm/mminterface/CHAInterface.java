package org.jikesrvm.mm.mminterface;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.cha.CheckingThread;
import org.jikesrvm.cha.CHATransitiveClosure;
import org.jikesrvm.cha.Snapshot;
import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.TIB;
import org.jikesrvm.scheduler.RVMThread;
import org.mmtk.plan.Plan;
import org.mmtk.plan.generational.Gen;
import org.mmtk.plan.TraceLocal;
import org.mmtk.utility.deque.ObjectReferenceDeque;
import org.mmtk.utility.deque.SharedDeque;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.ObjectReferenceArray;
import org.mmtk.utility.Log;
import org.jikesrvm.VM;

import org.jikesrvm.cha.ConcurrentWeakIdentityHashMap;
import java.util.Iterator;

/**
 * This class provides an interface for Concurrent Heap Assertions to interact
 * with MMTk for things like special memory allocation.  Instances of this
 * class map one-to-one with mutator threads.
 */
public class CHAInterface implements SizeConstants {
  
  /**********************************************************************
   * Static fields
   **********************************************************************/
    
  /**
   * dirtyPools is the shared portion of the ObjectReferenceDeque CHA
   * uses to keep track of dirty objects.  It is an array since we need
   * a separate dirty list for each checking thread.
   */
  public static SharedDeque[] dirtyPools;
  
  /**
   * maxActiveCheckers is used for profiling to keep track of the 
   * maximum number of checker threads active at any one time. 
   */
  public static volatile int maxActiveSnapshots = 0;
  public static volatile int curActiveSnapshots = 0;

  public static final boolean verbose = false;

  /**
   * Hashmap implementation of forwarding arrays
   */
  public static ConcurrentWeakIdentityHashMap<Object, ObjectReferenceArray> forwardingMap;

  public static final boolean useForwardingMap = false;

  /**
   * Flags to control different experiments
   */
  public static final boolean noCopies = false;
  public static final boolean noSnapshot = false;
  public static final boolean noWriteBarrier = false;
  public static final boolean noSharing = false;

  /**********************************************************************
   * Instance fields
   **********************************************************************/
  
  public ObjectReferenceDeque[] dirties;
  
  
  /**********************************************************************
   * Static methods
   **********************************************************************/
  
  /**
   * Initialize the static data structures -- dirty pools and the dirty lists
   * used during GC.
   */
  public static void init() {
    dirtyPools = new SharedDeque[Snapshot.MAX_SNAPSHOTS]; // [CheckingThread.MAX_CHA_THREADS];
    for (int i=0; i<dirtyPools.length; i++) {
      dirtyPools[i] = new SharedDeque("dirtyPool" + i, Selected.Plan.metaDataSpace, 1);
      dirtyPools[i].prepareNonBlocking();
    }
    if (useForwardingMap) {
      forwardingMap = new ConcurrentWeakIdentityHashMap<Object, ObjectReferenceArray>();
    }
  }

  /** Stats */
  
  @Uninterruptible
  public static void recordCopy(int size) {
    Plan.bytesCounter.inc(size);
  }

  public static void startWaitTimer() {
    Plan.waitTime.start();
  }

  public static void stopWaitTimer() {
    Plan.waitTime.stop();
  }
 
  /** 
   * Get a list of references from an object.  Follow forwarding pointer
   * if necessary, following the correct locking discipline.
   * 
   * @param object The object whose forwarded references to get
   * @param id The id of the checking thread performing this work
   */
  /*
  public static ObjectReferenceArray getRefsForwarded(Object object) {
      
    ObjectReferenceArray result = null;
    ObjectReference objRef = ObjectReference.fromObject(object);

    // Lock the object's epoch word
    Word epoch = MiscHeader.attemptEpoch2(objRef); 
      
    // Get forwarding pointer
    ObjectReferenceArray forwardingArray = MiscHeader.getForwardingPointer(objRef);
    if (forwardingArray == null) {
      result = getRefs(objRef);
    } else {
      // TODO: why is cast to CheckingThread failing???
      // CheckingThread thisThread = (CheckingThread)RVMThread.getCurrentThread();
      RVMThread thisThread = RVMThread.getCurrentThread();
      // checker ID will be >= 0 for CheckerThreads
      if (VM.VerifyAssertions) VM._assert(thisThread.checkerId >= 0);
      ObjectReference snapshot = forwardingArray.get(thisThread.checkerId);
      if (snapshot.isNull()) {
        result = getRefs(objRef);
      } else {
        // sanity check: object and its snapshot are same type
        if (VM.VerifyAssertions) {
          VM._assert(ObjectModel.getTIB(snapshot) == ObjectModel.getTIB(objRef));
        }
        result = getRefs(snapshot); 
      }
    }      
      
    // write epoch back in, unlocking object
    MiscHeader.setEpoch(objRef, epoch);
    
    return result;
  }
  */
  
  /** 
   * Return a reference to an object.  Follow forwarding pointer if necessary.
   * Locks the object while it gets the forwarding pointer.
   *  
   * @param object The object whose forwarded references to get
   * @return A reference to either the original object or
   * the snapshot if it has been forwarded
   */
  @Uninterruptible
  public static Object getSnapshot(ObjectReference objRef) {
    return MiscHeader.getSnapshot(objRef);
  }

  
  /**
   * Get a list of references from an object.  Do not follow forwarding
   * pointer; scan *this* object.
   */
  public static ObjectReferenceArray getRefs(ObjectReference objectRef) {
    ObjectReferenceArray result = null;
    RVMType type = ObjectModel.getObjectType(objectRef.toObject());
    if (type.isClassType()) {
      RVMClass klass = type.asClass();
      int[] offsets = klass.getReferenceOffsets();
      
      result = ObjectReferenceArray.create(offsets.length);
      for(int i=0; i < offsets.length; i++) {
        result.set(i, objectRef.toAddress().plus(offsets[i]).loadObjectReference());
      }
    } else if (type.isArrayType() && type.asArray().getElementType().isReferenceType()) {
      int length = ObjectModel.getArrayLength(objectRef.toObject());
      result = ObjectReferenceArray.create(length);
      
      for(int i=0; i < length; i++) {
        result.set(i, objectRef.toAddress().plus(i << LOG_BYTES_IN_ADDRESS).loadObjectReference());
      }
    }
    return result;
  }
  
  /**
   * Allocate a forwarding array.  Will be called from the write barrier, 
   * so must be uninterruptible and cannot recursively invoke the write
   * barrier.
   */
  @Inline
  @Uninterruptible
  public static ObjectReferenceArray allocForwardingArray() {
        
    int numElements = Snapshot.MAX_SNAPSHOTS; // CheckingThread.MAX_CHA_THREADS;

    RVMArray arrayType = RVMType.ObjectReferenceArrayType;
    int headerSize = ObjectModel.computeArrayHeaderSize(arrayType);
    int align = ObjectModel.getAlignment(arrayType);
    int offset = ObjectModel.getOffsetForAlignment(arrayType, false);
    int width = arrayType.getLogElementSize();
    TIB arrayTib = arrayType.getTypeInformationBlock();
    
    int elemBytes = numElements << width;
    int size = elemBytes + headerSize;
    
    return (ObjectReferenceArray) MemoryManager.allocateArrayInternal(numElements,
        size,
        arrayTib,
        Plan.ALLOC_DEFAULT,
        align,
        offset,
        Plan.DEFAULT_SITE);

  }

  /*
  public static void sanity(boolean before)
  {
    // public static SharedDeque[] dirtyPools;
    Log.writeln("CHAInterface: start sanity check");
    // for (int i=0; i < dirtyPools.length; i++) {
      dirtyPools[0].sanity("gc", before);
      // }
    Log.writeln("CHAInterface: done sanity check");
  }
  */

  /**
   * Prepare dirty pools
   *
   * (Must be called before flushDirtyLists below)
   */
  public static void prepareDirtyPools()
  {
    for (int i=0; i < dirtyPools.length; i++) {
      dirtyPools[i].prepare();
    }
  }

  /**
   * Fix global dirty pools
   *
   * After a collection we need to null out or forward references
   * in the dirty pools.
   *
   * Depending on the value of <code>nursery</code>, we will either
   * scan all references, or just those in the nursery.
   * 
   * Not thread safe! Must be called from a single thread.
   * 
   * @param trace The trace containing liveness and forwarding info
   * @param nursery Scan only the newly created references
   */
  public static void fixDirtyPools(TraceLocal trace, boolean nursery)
  {  
    if (verbose) {
      Log.writeln("START DirtyProcessor.fixDirtyPools()");
    }
    
    for (int i=0; i < dirtyPools.length; i++) {
      SharedDeque thisDirtyList = dirtyPools[i];
      thisDirtyList.prepareIterator();
      while (thisDirtyList.hasNext()) {
        Address slot = thisDirtyList.getNextAddress();
        fixDirtyReference(slot, trace, nursery);
      }
    }
  }

  /**
   * Fix local dirty buffers
   *
   * NOT USED: this strategy turns out to be very slow
   *
   * This method works with fixDirtyPools -- it fixes the local
   * buffers stored on each RVMThread without needing to flush them to
   * the global dirty pools.
   *
   * @param trace The trace containing liveness and forwarding info
   * @param nursery Scan only the newly created references
   */
  public void fixDirtyBuffers(TraceLocal trace, boolean nursery)
  {
    if (verbose) {
      Log.writeln("START CHAInterface.fixDirtyBuffers()");
    }

    flushDirtyBuffers();

    /*
    for (int i=0; i < dirties.length; i++) {
      ObjectReferenceDeque localbuf = dirties[i];
      localbuf.prepareIterator();
      while (localbuf.hasNext()) {
        Address slot = localbuf.getNextAddress();
        fixDirtyReference(slot, trace, nursery);
      }
    }
    */
  }

  /**
   * Flush local dirty buffers
   *
   * We flush any dirty forwarding arrays to the global
   * SharedDeques before we call fixDirtyPools (above)
   */
  public void flushDirtyBuffers()
  {
    for (int i=0; i < dirties.length; i++) {
      ObjectReferenceDeque localbuf = dirties[i];
      localbuf.flushLocal();
    }
  }

  /**
   * Update a single dirty reference
   *
   * Load the reference in the given slot and update it according
   * to the fate of that reference according to the TraceLocal
   * provided by the collector.
   */
  private static void fixDirtyReference(Address slot, TraceLocal trace, boolean nursery)
  {
    if (verbose) {
      Log.write("Slot ", slot);
      Log.write(" = ");
      Log.writeln(slot.loadWord());
    }

    if (VM.VerifyAssertions) {
       VM._assert(!slot.isZero());
    }
        
    // load reference from address
    ObjectReference ref = slot.loadObjectReference();        
    if (!ref.isNull()) {
      if (VM.VerifyAssertions) {
        VM._assert(DebugUtil.validRef(ref));
      } 
      // nonNull++;

      // skip if nursery collection but ref not in nursery
      if (nursery && !Gen.inNursery(ref)) {
        // notInNursery++;
        return;
      }

      if (!trace.isLive(ref)) {
        // if ref is not live, null out the value in slot
        if (verbose) {
          Log.write("Nulling out dirty object ");
          Log.write(ref);
          Log.writeln(" in slot ", slot);
        }
        slot.store(Address.zero());
        // nulled++;
      } else {
        // if ref is live, get forwarding address and store it in slot
        ObjectReference forwardedRef = trace.getForwardedReference(ref);
        if (VM.VerifyAssertions) {
          VM._assert(DebugUtil.validRef(forwardedRef));
        }
        if (verbose) {
          Log.write("Forwarding dirty object ");
          Log.write(ref);
          Log.write(" to ");
          Log.write(forwardedRef);
          Log.writeln(" in slot ", slot);
        }
        slot.store(forwardedRef);
      }
    } 
  }

  /**
   * Reset dirty pools
   *
   * (Called after processing the dirty pools)
   */
  public static void resetDirtyPools()
  {
    for (int i=0; i < dirtyPools.length; i++) {
      dirtyPools[i].reset();
    }
  }

  /**********************************************************************
   * Instance methods
   **********************************************************************/
  
  public CHAInterface() {
    dirties = new ObjectReferenceDeque[Snapshot.MAX_SNAPSHOTS]; // [CheckingThread.MAX_CHA_THREADS];
    for (int i=0; i<dirties.length; i++) {
      dirties[i] = new ObjectReferenceDeque("dirty" + i, dirtyPools[i]);
    }
  }
  
  
  /**
   * Flush thread-local dirty lists to global dirtylist pools
   * DEPRECATED: 
   *    We only need to flush local buffers right before we complete the probe
  @Inline @Uninterruptible
  public void flushDirtyLists() {
    for (int i=0; i<dirties.length; i++) {
      VM.sysWriteln("  Flush ", ObjectReference.fromObject(dirties[i]));
      dirties[i].flushLocal();
    }
  }
  */
  
  /**
   * Clear snapshots for a particular checker
   */
  public void clearSnapshots(int id, int expected_count)
  {
    if (verbose)
      VM.tsysWriteln("START clearSnapshots");

    if (useForwardingMap) {
      /*
      Iterator<Object> i = forwardingMap.keySet().iterator();
      // Iterator<Object, ObjectReferenceArray> i = forwardingMap.iterator();
      while (i.hasNext()) {
        ObjectReference ref = ObjectReference.fromObject(i.next());
        ObjectReferenceArray farr = forwardingMap.get(ref);
        if (farr != null) {
          MiscHeader.clearSnapshot(ObjectReference.fromObject(farr), id);
        }
      }
      */
    } else {
      SharedDeque dirtyPool = dirtyPools[id];
      dirtyPool.prepare();

      // copy the list of threads
      RVMThread[] threads = new RVMThread[RVMThread.numThreads];
      RVMThread.acctLock.lockNoHandshake();
      for (int i=0; i<RVMThread.numThreads; i++) {
        threads[i] = RVMThread.threads[i];
      }
      RVMThread.acctLock.unlock();
      
      // if (trace) VM.tsysWriteln("Flushing chaInterface local stuff");
      for (RVMThread t : threads) {
        if (t.chaInterface != null) {
          // flush dirty list
          t.chaInterface.dirties[id].flushLocal();
        }
      }
      
      ObjectReferenceDeque dirtiesLocal = dirties[id];
      int count = 0;
      while ( ! dirtiesLocal.isEmpty()) {
        ObjectReference farr = dirtiesLocal.pop();
        count++;
        if (!farr.isNull()) {
          MiscHeader.clearSnapshot(farr, id);
        }
      }
      
      dirtyPool.reset();
      
      if (count != expected_count) {
        VM.sysWrite("PROBLEM: count ", count);
        VM.sysWriteln(" not equal to expected count ", expected_count);
      }
      // ensure shared dirty pool is exhausted
      // CHAInterface.dirtyPools[id].assertExhausted();
    }

    if (verbose)
      VM.tsysWriteln("DONE  clearSnapshots");
  }

  /* ---------------------------------------------------------
       Forwarding map implementation
     --------------------------------------------------------- */

  public static void putForwardingArray(ObjectReference obj, ObjectReferenceArray forwardingArray)
  {
    forwardingMap.put(obj.toObject(), forwardingArray);
  }

  public static ObjectReferenceArray getForwardingArray(ObjectReference obj)
  {
    ObjectReferenceArray result = forwardingMap.get(obj.toObject());
    return result;
  }
}
