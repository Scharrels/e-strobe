package org.jikesrvm.cha;

import org.jikesrvm.HeapLayoutConstants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Memory;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Statics;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.ReadWriteLock;
import org.jikesrvm.scheduler.Monitor;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.CHAInterface;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.mm.mminterface.MemoryManagerConstants;
import org.jikesrvm.mm.mminterface.Selected;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.ObjectReferenceArray;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * This class provides the necessary functions for snapshotting an
 * object inside the write barrier.  We are implementing copy-on-write
 * using the write barrier here. 
 */
public class Snapshot implements HeapLayoutConstants {
  
  private static final boolean trace = false;

  // -- For experiments
  private static final boolean makeCopies = true;

  // -- Manage the epoch counter
  private static Object epochLock;
  public static int epoch = 1;   
  public static final Word HEAP_MIN = BOOT_IMAGE_DATA_START.toWord();
  
  public static int copyCount = 0;

  public static int [] dirtyCounts;
   
  /**
   * This is a bitmap indicating which probes are active.  It is 
   * protected by a read-write lock.
   */
  public static volatile int activeProbes;
  // public static ReadWriteLock activeProbeLock;
  public static Monitor activeProbeLock;
  public static Address activeProbesAddress;

  public static void init() {
    epochLock = new Object();
    // activeProbeLock = new ReadWriteLock();
    activeProbeLock = new Monitor();
    if (trace) VM.tsysWriteln("initialized snapshot");
    dirtyCounts = new int[12];
    activeProbesAddress = Statics.getSlotAddress(Entrypoints.activeProbesField.getOffset());
  }

  /**
   * Read active probes
   *
   * Use atomic read
   */
  @Inline
  public static int readActiveProbes()
  {
    return 0;
  }
  
  /**
   * A probe has been initiated.  Enable write barrier and log that this
   * probe is active.
   * 
   * @param id The id of the probe thread that has been initiated
   */
  public static void initiateProbe(int id) {
    if (trace) VM.tsysWriteln("Initiating probe ");
    
    // increment epoch while holding the lock
    synchronized(epochLock) {
      int lastEpoch = epoch;
      epoch = (lastEpoch + 1) % HEAP_MIN.toInt();
    }
    
    activeProbeLock.lockNoHandshake();
    // activeProbeLock.getWriteLock();

    // VM.tsysWriteln("  Start-1 ", (1 << id), " -> ", activeProbes);

    if (VM.VerifyAssertions) {
      // probe should be inactive
      VM._assert((1 << id & activeProbes) == 0);
    }

    activeProbes = activeProbes | (1 << id);
    Magic.sync();

    int check = activeProbesAddress.loadInt();
    // VM.tsysWriteln("Check active probes: ", activeProbes, " and ", check);

    if (VM.VerifyAssertions) {
      // probe should be active
      VM._assert((1 << id & activeProbes) != 0);
    }

    // VM.tsysWriteln("  Start-2 ", (1 << id), " -> ", activeProbes);

    int count = 0;
    for (int i=0; i<CheckingThread.MAX_CHA_THREADS; i++) {
      if ((activeProbes & (1 << i)) != 0) {
        count++;
      }
    }
    if (count > CHAInterface.maxActiveCheckers) {
      CHAInterface.maxActiveCheckers = count;
    }

    CHAInterface.curActiveCheckers++;
    dirtyCounts[id] = 0;

    activeProbeLock.unlock();
    // activeProbeLock.releaseLock();
  }
  
  /**
   * A probe has completed.  Disable the write barrier and perform cleanup.  
   * This method performs both the global and thread-local portions of the
   * cleanup.  
   */
  public static void completeProbe(int id) {
    if (trace) {
      VM.tsysWriteln("probe completed, START cleaning up");
      // VM.sysWriteln(RVMThread.getCurrentThread().getThreadSlot(), " name ", RVMThread.getCurrentThread().getName());
    }

    /* Lock the threads array here to avoid any possible races between disabling
     * the write barrier and getting the list of threads, i.e. a thread dies and
     * thus we don't get to flush its buffer.
     */

    if (trace) VM.tsysWriteln("Trying to get write barrier write lock");

    activeProbeLock.lockNoHandshake();
    // activeProbeLock.getWriteLock();

    // VM.tsysWriteln("  Clear-1 ", (1 << id), " -> ", activeProbes);

    if (trace) VM.tsysWriteln("Got write barrier write lock");

    if (VM.VerifyAssertions) {
      // probe must be active
      VM._assert((activeProbes & (1 << id)) != 0);
    }
    activeProbes = activeProbes & ~(1 << id);
    Magic.sync();

    CHAInterface.curActiveCheckers--;

    // VM.tsysWriteln("  Clear-2 ", (1 << id), " -> ", activeProbes);

    activeProbeLock.unlock();
    // activeProbeLock.releaseLock();
    
    // unmark dirty objects - only need one dirty pool
    /*
    if (trace) VM.tsysWriteln("Unmarking dirty objects");
    for (RVMThread t : threads) {
      if (t.chaInterface != null) {
        chaInterface = t.chaInterface;
        break;
      }
    }
    */

    CHAInterface chaInterface = RVMThread.getCurrentThread().chaInterface;
    if (chaInterface != null) {
      chaInterface.clearSnapshots(id, dirtyCounts[id]);
      dirtyCounts[id] = 0;
    }

    if (trace) VM.tsysWriteln("DONE cleanup");

    /*
    if (trace) {
      VM.sysWrite("probe ");
      VM.sysWrite(id);
      VM.sysWriteln(" completed, DONE cleaning up");
      VM.sysWrite(" thread ");
      VM.sysWrite(RVMThread.getCurrentThread().getThreadSlot());
      VM.sysWrite(" name ");
      VM.sysWriteln(RVMThread.getCurrentThread().getName());
    }
    */
  }
  
  /**
   * Snapshot an object
   * 
   * Object snapshots are stored as follows.  Each object has a forwarding word
   * in its header.  For an object that has been snapshotted at least once, the
   * forwarding word points to a forwarding array.  The array then points to the
   * snapshots of the object, indexed by the id of the checking thread this
   * snapshot belongs to.
   * 
   * Note that this method must be called WHILE HOLDING THE READ LOCK for the
   * write barrier.  It does not request the read lock itself.
   * 
   * We have to be careful that snapshotObject must write the epoch value into
   * the object header.
   * 
   * TODO: We should also think about ways to count the number of snapshots created 
   * while the program runs.
   */
  @Uninterruptible
  public static void snapshotObject(ObjectReference obj, CHAInterface chaInterface, int epoch) {

    // -- Experiment: no snapshot work at all
    if (CHAInterface.noSnapshot)
      return;

    RVMThread currentThread = RVMThread.getCurrentThread();

    // -- Full version: make a snapshot as necessary
    if (currentThread.makeSnapshots()) {

      currentThread.turnOffSnapshots();

      copyCount++;
      
      // alloc forwarding arr if necessary
      ObjectReferenceArray forwardingArr = MiscHeader.getOrCreateSnapshotArray(obj);

      // copy object
      ObjectReference to;
      RVMType type = Magic.getObjectType(obj.toObject());
      int size = ObjectModel.bytesRequiredWhenCopied(obj.toObject());
      if (CHAInterface.noCopies) {
        // -- Experiment: don't do the copy
        to = obj;
      } else {
        // -- Full version: snapshot object
        if (type.isArrayType()) {
          int numElems = ObjectModel.getArrayLength(obj.toObject());
          to = MemoryManager.allocateSnapshotSpaceArray(size, type, numElems);
        } else {
          to = MemoryManager.allocateSnapshotSpaceScalar(size, type);
        }
        copyNoHeaders(obj, to, size);
        CHAInterface.recordCopy(size);
      }

      if (trace) VM.tsysWriteln("Snapshot ", obj, " type ", type.getDescriptor(), " size ", size);
      // if (trace) VM.tsysWriteln("Snapshot ", obj, " to ", to, " type ", type.getDescriptor());

      // -- Must have probe lock to make sure that we don't add dirties to
      //    probes that are in the process of completing
      activeProbeLock.lockNoHandshake();
      // activeProbeLock.getWriteLock();
      Magic.sync();

      // -- Write barrier:
      //    This is a special write barrier that covers all of the writes that occur
      //    in the following loop: if the forwarding array is in the mature space
      //    and the snapshot is in the nursery, then store the address of the
      //    forwarding word in the remembered sets.
      /* THIS DOESN'T WORK
      if (Barriers.NEEDS_OBJECT_GC_WRITE_BARRIER) {
        Address forwardingWordAddress = MiscHeader.getForwardingWordAddress(obj);
        ObjectReference forwardingArrRef = ObjectReference.fromObject(forwardingArr);
        Selected.Mutator.get().forwardingArrayWriteBarrier(forwardingArrRef, forwardingWordAddress, to);
      }
      */

      // install pointers in forwarding array
      // for (int i=0; i<CheckingThread.MAX_CHA_THREADS; i++) {
      for (int i=0; i < RVMThread.numCHAThreads; i++) {
        if ((activeProbes & (1 << i)) != 0) {
          // VM.tsysWriteln("  Add ", (1 << i), " -- ", activeProbes);

          if (forwardingArr.get(i).isNull()) {

            // Store snapshot in appropriate slot
            setForwardingArray(forwardingArr, i, to);

            // log forwarding array so we can clear this snapshot later
            if ( ! CHAInterface.useForwardingMap) {
              chaInterface.dirties[i].push(ObjectReference.fromObject(forwardingArr));
              dirtyCounts[i]++;
            }
          }
        }
      }

      activeProbeLock.unlock();
      // activeProbeLock.releaseLock();

      currentThread.turnOnSnapshots();
    }
  }
  
  @Uninterruptible @Inline
  private static void setForwardingArray(ObjectReferenceArray forwardingArr,
      int i, ObjectReference to) {
    if (Barriers.NEEDS_OBJECT_GC_WRITE_BARRIER) {
      ObjectReference array = ObjectReference.fromObject(forwardingArr);
      Offset offset = Offset.fromIntZeroExtend(i << MemoryManagerConstants.LOG_BYTES_IN_ADDRESS);
      Selected.Mutator.get().objectReferenceWrite(array, 
          array.toAddress().plus(offset), 
          to, 
          offset.toWord(), 
          Word.zero(), 
          1); // org.mmtk.utility.Constants.ARRAY_ELEMENT
    } else {
      forwardingArr.set(i, to);
    }
    
  }

  /**
   * Copy an object to be pointed to by the to address.  
   *
   * @param from the address of the object to be copied
   * @param to The target location.
   */
  @Inline @Uninterruptible
  public static void copy(ObjectReference from, ObjectReference to, int bytes) {
    JavaHeader.moveObject(Address.zero(), from.toObject(), to.toObject(), bytes);
  }
  
  /**
   * Copy an object to be pointed to by the to address.  Copy only the fields
   * of the object (for a scalar) or the length and contents (for an array).
   * Do not copy the headers.
   *
   * @param from An objref to the object to be copied
   * @param to An objref to the target location
   * @param bytes The size of the from and to objects, including header
   */
  @Inline @Uninterruptible
  public static void copyNoHeaders(ObjectReference from, ObjectReference to, int bytes) {    
    /* note that for arrays we need to copy the length, so we use 
     * SCALAR_HEADER_SIZE rather than ARRAY_HEADER_SIZE since
     * ARRAY_HEADER_SIZE includes the length
     */
    int bytesToCopy = bytes - JavaHeader.SCALAR_HEADER_SIZE;
    int headerEndOffset = JavaHeader.getHeaderEndOffset();
    Address fromStart = from.toAddress().plus(headerEndOffset);
    Address toStart = to.toAddress().plus(headerEndOffset);
    if (VM.VerifyAssertions) {
      // fromStart, toStart, and bytesToCopy must be 4-byte aligned
      Word alignMask = Word.one().lsh(2).minus(Word.one());
      VM._assert(bytesToCopy % 4 == 0);
      VM._assert(fromStart.toWord().and(alignMask).EQ(Word.zero()));
      VM._assert(toStart.toWord().and(alignMask).EQ(Word.zero()));
    }
    Memory.aligned32Copy(toStart, fromStart, bytesToCopy);
     
    /*
    if (VM.VerifyAssertions) {
      // make sure the copy succeeded
      for (int i=0; i<bytesToCopy; i+=SizeConstants.BYTES_IN_WORD) {
        if (!fromStart.loadWord(Offset.fromIntSignExtend(i)).EQ(
            toStart.loadWord(Offset.fromIntSignExtend(i)))) {
          
          RVMType fromType = JavaHeader.getTIB(from.toObject()).getType();
          RVMType toType = JavaHeader.getTIB(to.toObject()).getType();
          
          VM.sysWrite("fromStart = ");
          VM.sysWrite(fromStart);
          VM.sysWrite(", toStart = ");
          VM.sysWrite(toStart);
          VM.sysWrite(", offset = ");
          VM.sysWrite(i);
          VM.sysWrite(", bytesToCopy = ");
          VM.sysWrite(bytesToCopy);
          VM.sysWrite(", from object type = ");
          VM.sysWrite(fromType.getDescriptor());
          VM.sysWriteln(", to object type = ");
          VM.sysWriteln(toType.getDescriptor());
          
          VM.sysWrite(fromStart.loadWord(Offset.fromIntSignExtend(i)));
          VM.sysWrite(" != ");
          VM.sysWriteln(toStart.loadWord(Offset.fromIntSignExtend(i)));
        }
        VM._assert(fromStart.loadWord(Offset.fromIntSignExtend(i)).EQ(
            toStart.loadWord(Offset.fromIntSignExtend(i))));
      }
    }
    */
  }
}
