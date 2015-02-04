/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.objectmodel;

import org.jikesrvm.VM;
import org.jikesrvm.Constants;
import org.jikesrvm.cha.Snapshot;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.DebugUtil;
import org.jikesrvm.mm.mminterface.MemoryManagerConstants;
import org.jikesrvm.mm.mminterface.Selected;
import org.jikesrvm.mm.mminterface.CHAInterface;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.ObjectReferenceArray;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

import org.jikesrvm.scheduler.RVMThread;

/**
 * Defines other header words not used for
 * core Java language support of memory allocation.
 * Typically these are extra header words used for various
 * kinds of instrumentation or profiling.
 * 
 * For our concurrent heap assertions system, we add a forwarding
 * word to each object to keep track of an object's snapshot.
 *
 * @see ObjectModel
 */
@Uninterruptible
public final class MiscHeader implements Constants, MiscHeaderConstants {

  private static final Offset MISC_HEADER_START = JavaHeaderConstants.MISC_HEADER_OFFSET;

  /*
   * Forwarding word has a bunch of uses:
   * 1) keeps track of the epoch during which this object was allocated
   * 2) serves as a lock while forwarding array is allocated and pointer set
   * 3) pointer to forwarding array
   * 
   * NOT_FORWARDED means the forwarding word contains the epoch during which
   * this object was allocated.
   * 
   * BEING_FORWARDED means the forwarding word contains the value 0.
   * 
   * FORWARDED means the forwarding word contains a pointer to the forwarding
   * array.
   */
  
  /* offset from object ref to forwarding word, in bytes */
  public static final Offset FORWARDING_WORD_OFFSET = MISC_HEADER_START;
  /* offset from object ref to epoch, in bytes */
  public static final Offset EPOCH_OFFSET = FORWARDING_WORD_OFFSET.plus(BYTES_IN_ADDRESS);
  
  /* offset from object ref to .oid field, in bytes */
  static final Offset OBJECT_OID_OFFSET = MISC_HEADER_START;
  /* offset from object ref to OBJECT_DEATH field, in bytes */
  static final Offset OBJECT_DEATH_OFFSET = OBJECT_OID_OFFSET.plus(BYTES_IN_ADDRESS);
  /* offset from object ref to .link field, in bytes */
  static final Offset OBJECT_LINK_OFFSET = OBJECT_DEATH_OFFSET.plus(BYTES_IN_ADDRESS);
  
  
  
  /** Forwarding of this object is incomplete */
  private static final Word BEING_FORWARDED = Word.zero(); // ...00
  
  /** This object is being copied */
  private static final Word BEING_COPIED = Word.zero();
  
  
  
  /////////////////////////
  // Support for YYY (an example of how to add a word to all objects)
  /////////////////////////
  // offset from object ref to yet-to-be-defined instrumentation word
  // static final int YYY_DATA_OFFSET_1 = (VM.YYY ? MISC_HEADER_START + GC_TRACING_HEADER_WORDS : 0);
  // static final int YYY_DATA_OFFSET_2 = (VM.YYY ? MISC_HEADER_START + GC_TRACING_HEADER_WORDS + 4 : 0);
  // static final int YYY_HEADER_BYTES = (VM.YYY ? 8 : 0);

  /**
   * How many available bits does the misc header want to use?
   */
  static final int REQUESTED_BITS = 0;

  /**
   * The next object ID to be used.
   */
  @Entrypoint
  private static Word oid;
  /**
   * The current "time" for the trace being generated.
   */
  private static Word time;
  /**
   * The address of the last object allocated into the header.
   */
  @Entrypoint
  private static Word prevAddress;

  static {
    oid = Word.fromIntSignExtend(4);
    time = Word.fromIntSignExtend(4);
    prevAddress = Word.zero();
  }

  /**
   * Perform any required initialization of the MISC portion of the header.
   * @param obj the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  @Uninterruptible
  public static void initializeHeader(Object obj, TIB tib, int size, boolean isScalar) {
    if (VM.VerifyAssertions) {
      VM._assert(!MemoryManagerConstants.GENERATE_GC_TRACE);
    }
    Address ref = Magic.objectAsAddress(obj);
    //ref.store(Snapshot.epoch, FORWARDING_WORD_OFFSET);
    ref.store(Snapshot.epoch, EPOCH_OFFSET);
    ref.store(Word.zero(), FORWARDING_WORD_OFFSET);
    /* Only perform initialization when it is required */
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      ref.store(oid, OBJECT_OID_OFFSET);
      ref.store(time, OBJECT_DEATH_OFFSET);
      oid = oid.plus(Word.fromIntSignExtend((size - GC_TRACING_HEADER_BYTES) >> LOG_BYTES_IN_ADDRESS));
    }
  }

  /**
   * Perform any required initialization of the MISC portion of the header.
   * @param bootImage the bootimage being written
   * @param ref the object ref to the storage to be initialized
   * @param tib the TIB of the instance being created
   * @param size the number of bytes allocated by the GC system for this object.
   * @param isScalar are we initializing a scalar (true) or array (false) object?
   */
  @Interruptible("Only called during boot iamge creation")
  public static void initializeHeader(BootImageInterface bootImage, Address ref, TIB tib, int size,
                                      boolean isScalar) {
    if (VM.VerifyAssertions) VM._assert(!MemoryManagerConstants.GENERATE_GC_TRACE);
    
    // have to do this so GC is aware of snapshots
    bootImage.setAddressWord(ref.plus(FORWARDING_WORD_OFFSET), Word.zero(), true, true);
    bootImage.setFullWord(ref.plus(EPOCH_OFFSET), Snapshot.epoch);
    
    /* Only perform initialization when it is required */
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      bootImage.setAddressWord(ref.plus(OBJECT_OID_OFFSET), oid, false, false);
      bootImage.setAddressWord(ref.plus(OBJECT_DEATH_OFFSET), time, false, false);
      bootImage.setAddressWord(ref.plus(OBJECT_LINK_OFFSET), prevAddress, false, false);
      prevAddress = ref.toWord();
      oid = oid.plus(Word.fromIntSignExtend((size - GC_TRACING_HEADER_BYTES) >> LOG_BYTES_IN_ADDRESS));
    }
  }

  
  /***********************************************************************************/
  
  public static void updateDeathTime(Object object) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      Magic.objectAsAddress(object).store(time, OBJECT_DEATH_OFFSET);
    }
  }

  public static void setDeathTime(Object object, Word time_) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      Magic.objectAsAddress(object).store(time_, OBJECT_DEATH_OFFSET);
    }
  }

  public static void setLink(Object object, ObjectReference link) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      Magic.objectAsAddress(object).store(link, OBJECT_LINK_OFFSET);
    }
  }

  public static void updateTime(Word time_) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    time = time_;
  }

  public static Word getOID(Object object) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      return Magic.objectAsAddress(object).plus(OBJECT_OID_OFFSET).loadWord();
    } else {
      return Word.zero();
    }
  }

  public static Word getDeathTime(Object object) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      return Magic.objectAsAddress(object).plus(OBJECT_DEATH_OFFSET).loadWord();
    } else {
      return Word.zero();
    }
  }

  public static ObjectReference getLink(Object ref) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      return ObjectReference.fromObject(Magic.getObjectAtOffset(ref, OBJECT_LINK_OFFSET));
    } else {
      return ObjectReference.nullReference();
    }
  }

  public static Address getBootImageLink() {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      return prevAddress.toAddress();
    } else {
      return Address.zero();
    }
  }

  public static Word getOID() {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      return oid;
    } else {
      return Word.zero();
    }
  }

  public static void setOID(Word oid_) {
    if (VM.VerifyAssertions) VM._assert(MemoryManagerConstants.GENERATE_GC_TRACE);
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      oid = oid_;
    }
  }

  public static int getHeaderSize() {
    return NUM_BYTES_HEADER;
  }

  /**
   * For low level debugging of GC subsystem.
   * Dump the header word(s) of the given object reference.
   * @param ref the object reference whose header should be dumped
   */
  public static void dumpHeader(Object ref) {
    if (VM.VerifyAssertions) VM._assert(!MemoryManagerConstants.GENERATE_GC_TRACE);
    VM.sysWrite(" FORWARDING_WORD=", getForwardingWord(ObjectReference.fromObject(ref)));
    VM.sysWrite(" EPOCH=", getEpoch(ObjectReference.fromObject(ref)));
    // by default nothing to do, unless the misc header is required
    if (MemoryManagerConstants.GENERATE_GC_TRACE) {
      VM.sysWrite(" OID=", getOID(ref));
      VM.sysWrite(" LINK=", getLink(ref));
      VM.sysWrite(" DEATH=", getDeathTime(ref));
    }
  }
  
  
  /**********************************************************
   * CHA stuff
   *********************************************************/
  
  /**
   * Clear the forwarding word for an object.
   *
   * @param object the object ref to the storage to be initialized
   */
  @Inline
  private static void clearForwardingWord(ObjectReference object) {
    object.toAddress().store(Word.zero(), FORWARDING_WORD_OFFSET);
  }

  /**
   * Has an object been forwarded?
   *
   * @param object The object to be checked
   * @return True if the object has been forwarded
   */
  @Inline
  private static boolean isForwarded(ObjectReference object) {
    if (CHAInterface.useForwardingMap) {
      ObjectReferenceArray arr = CHAInterface.getForwardingArray(object);
      return arr != null;
    } else {
      return stateIsForwarded(getForwardingWord(object));
    }
  }

  /**
   * Has an object been forwarded or being forwarded?
   *
   * @param object The object to be checked
   * @return True if the object has been forwarded or is being forwarded
   */
  @Inline
  private static boolean isForwardedOrBeingForwarded(ObjectReference object) {
    return stateIsForwardedOrBeingForwarded(getForwardingWord(object));
  }

  /**
   * Non-atomic read of forwarding pointer word
   *
   * @param object The object whose forwarding word is to be read
   * @return The forwarding word stored in <code>object</code>'s
   * header.
   */
  @Inline
  private static Word getForwardingWord(ObjectReference object) {
    return object.toAddress().loadWord(FORWARDING_WORD_OFFSET);
  }

  /**
   * Get forwarding work address
   */
  @Inline
    public static Address getForwardingWordAddress(ObjectReference object) {
    return object.toAddress().plus(FORWARDING_WORD_OFFSET);
  }
  
  /**
   * Non-atomic read of forwarding pointer word
   *
   * @param object The object whose forwarding word is to be read
   * @return The forwarding word stored in <code>object</code>'s
   * header.
   */
  @Inline
  @Uninterruptible
  public static ObjectReferenceArray getSnapshotArray(ObjectReference object) {
    if (CHAInterface.useForwardingMap) {
      ObjectReferenceArray arr = CHAInterface.getForwardingArray(object);
      return arr;
    } else {
      Object result = object.toAddress().loadObjectReference(FORWARDING_WORD_OFFSET).toObject();
      return (ObjectReferenceArray) result;
    }
  }

  /**
   * Get or create snapshot array
   *
   * NOTE: Header must already be locked by attemptEpoch
   */
  @Inline
  @Uninterruptible
  public static ObjectReferenceArray getOrCreateSnapshotArray(ObjectReference obj) {
    ObjectReferenceArray snapshotArr = getSnapshotArray(obj);
    if (snapshotArr == null) {
      snapshotArr = CHAInterface.allocForwardingArray();
      if (CHAInterface.useForwardingMap) {
        CHAInterface.putForwardingArray(obj, snapshotArr);
      } else {
        setForwardingPointer(obj, snapshotArr);
      }
    }
    return snapshotArr;
  }

  /**
   * Return a reference to an object.  Follow forwarding pointer if necessary.
   * Locks the object while it gets the forwarding pointer.
   * 
   * @param object The object whose forwarded references to get
   * @return A reference to either the original object or
   * the snapshot if it has been forwarded
   */
  @Inline
  @Uninterruptible
  public static ObjectReference getSnapshot(ObjectReference objRef)
  {
    if(RVMThread.getCurrentThread().getSnapshotId() == -1){
        return objRef;
    }
    // Lock the object's epoch word
    Word epoch = MiscHeader.attemptEpoch2(objRef); 
    
    ObjectReference getRefsFrom;
    // Get forwarding pointer
    ObjectReferenceArray snapshotArray = getSnapshotArray(objRef);
    RVMThread thisThread = RVMThread.getCurrentThread();
    if (snapshotArray == null) {
      getRefsFrom = objRef;
      // count_notforwarded++;
    } else {
      // CheckingThread thisThread = (CheckingThread)RVMThread.getCurrentThread();
      ObjectReference snapshot = snapshotArray.get(thisThread.getSnapshotId());
      if (snapshot.isNull()) {
        getRefsFrom = objRef;
        // count_forwardednull++;
      } else {
        // sanity check: object and its snapshot are same type
        if (VM.VerifyAssertions) {
          VM._assert(ObjectModel.getTIB(snapshot) == ObjectModel.getTIB(objRef));
        }
        getRefsFrom = snapshot;
        // count_forwarded++;
      }
    }
    // write epoch back in, unlocking object
    MiscHeader.setEpoch(objRef, epoch);
    
    return getRefsFrom;    
  }

  /**
   * Clear snapshot from a forwarding array 
   * 
   * @param objRef The forwarding array whose snapshot to clear
   * @param id The id of the check whose snapshot to clear
   */
  @Inline
  @Uninterruptible
    public static void clearSnapshot(ObjectReference farr, int id)
  {
    if (VM.VerifyAssertions) {
      VM._assert(DebugUtil.validRef(farr));
    }
    
    ObjectReferenceArray snapshotArray = (ObjectReferenceArray)farr.toObject();
    
    // forwarding array, snapshot should not be null  
    if (VM.VerifyAssertions) {
      VM._assert(snapshotArray != null);
      ObjectReference snapshot = snapshotArray.get(id);
      VM._assert(!snapshot.isNull());
    }

    // set snapshot to null
    snapshotArray.set(id, ObjectReference.nullReference());
  }

  /**
   * Is the state of the forwarding word being forwarded?
   *
   * @param fword A forwarding word.
   * @return True if the forwarding word's state is being forwarded.
   */
  @Inline
  private static boolean stateIsBeingForwarded(Word fword) {
    return fword.EQ(BEING_FORWARDED);
  }

  /**
   * Is the state of the forwarding word forwarded?
   *
   * @param fword A forwarding word.
   * @return True if the forwarding word's state is forwarded.
   */
  @Inline
  private static boolean stateIsForwarded(Word fword) {
    /**
     *  true if forwarding word is >= the HEAP_MIN value, which is
     *  the lowest possible heap address.
     */
    return fword.GE(Snapshot.HEAP_MIN);
  }

  /**
   * Is the state of the forwarding word forwarded or being forwarded?
   *
   * @param fword A forwarding word.
   * @return True if the forwarding word's state is forwarded or being
   *         forwarded.
   */
  @Inline
  private static boolean stateIsForwardedOrBeingForwarded(Word fword) {
    /**
     * true if forwarding word is either >= HEAP_MIN or 0
     */
    return fword.GE(Snapshot.HEAP_MIN) || fword.EQ(Word.zero());
  }
  
  /**
   * Is the state of the forwarding word not forwarded?
   *
   * @param fword A forwarding word.
   * @return True if the forwarding word's state is forwarded or being
   *         forwarded.
   */
  @Inline
  private static boolean stateIsNotForwarded(Word fword) {
    /**
     * true if forwarding word is < HEAP_MIN && != 0
     */
    return fword.LT(Snapshot.HEAP_MIN) && !fword.EQ(Word.zero());
  }
  
  /**
   * Non-atomic write of forwarding pointer word (assumption, thread
   * doing the set has done attempt to forward and owns the right to
   * copy the object)
   * 
   * TODO: If we write in the address of a forwarding array,
   * we may need to log that for the gc write barrier.
   *
   * @param object The object whose forwarding pointer is to be set
   * @param ptr The forwarding pointer to be stored in the object's
   * forwarding word
   */
  @Inline
  private static void setForwardingPointer(ObjectReference object,
                                           ObjectReferenceArray ptr) {
    /**
     *  if there is a GC write barrier, we have to make sure to
     *  log this reference
     */
    if (Barriers.NEEDS_OBJECT_GC_WRITE_BARRIER) {
      Selected.Mutator.get().objectReferenceWrite(object, 
          object.toAddress().plus(FORWARDING_WORD_OFFSET), 
          ObjectReference.fromObject(ptr), 
          FORWARDING_WORD_OFFSET.toWord(), 
          Word.zero(), 
          0); // org.mmtk.utility.Constants.INSTANCE_FIELD
    } else {
      ObjectReference value = ObjectReference.fromObject((Object)ptr);
      object.toAddress().store(value, FORWARDING_WORD_OFFSET);
    }
  }  
  
  /**
   * Non-atomic read of epoch
   *
   * @param object The object whose epoch is to be read
   * @return The epoch stored in <code>object</code>'s
   * header.
   */
  @Inline
  public static Word getEpoch(ObjectReference object) {
    return object.toAddress().loadWord(EPOCH_OFFSET);
  }
    
  /**
   * If the epoch of the object is the same as the current epoch,
   * returns immediately with false.  Otherwise locks the epoch word
   * and returns true.
   *
   * @param object The object to be forwarded
   * @return True if this write barrier is going to do the copy 
   */
  @Inline
  public static boolean attemptEpoch(ObjectReference object, 
                                     Word currEpoch) {
    Word oldValue;
    do {
      oldValue = object.toAddress().prepareWord(EPOCH_OFFSET);
        if (oldValue.EQ(currEpoch)) return false;
    } while (oldValue.EQ(BEING_COPIED) || !object.toAddress().attempt(oldValue, BEING_COPIED, EPOCH_OFFSET));
    return true;
  }
  
  /**
   * Leaves the epoch word in the state BEING_COPIED.  Must not return
   * BEING_COPIED.
   *
   * @param object The object to be forwarded
   * @return True is this write barrier is going to do the copy 
   */
  @Inline
  public static Word attemptEpoch2(ObjectReference object) {
    Word oldValue;
    do {
      oldValue = object.toAddress().prepareWord(EPOCH_OFFSET);
    } while (oldValue.EQ(BEING_COPIED) || !object.toAddress().attempt(oldValue, BEING_COPIED, EPOCH_OFFSET));
    if (VM.VerifyAssertions) VM._assert(!oldValue.EQ(BEING_COPIED));
    return oldValue;
  }
  
  /**
   * Non-atomic write of epoch 
   *
   * @param object The object whose epoch is to be set
   * @param ptr The epoch value to be stored in the object's headaer
   */
  @Inline
  public static void setEpoch(ObjectReference object,
                              Word value) {
    object.toAddress().store(value, EPOCH_OFFSET);
  }    
  
  
  
}
