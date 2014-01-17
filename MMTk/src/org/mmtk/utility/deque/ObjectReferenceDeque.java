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
package org.mmtk.utility.deque;

import org.mmtk.utility.Constants;

import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This supports <i>unsynchronized</i> enqueuing and dequeuing of
 * object references
 */
@Uninterruptible public class ObjectReferenceDeque extends LocalDeque
  implements Constants {

  /****************************************************************************
   *
   * Public instance methods
   */
  public final String name;

  /**
   * Constructor
   *
   * @param queue The shared queue to which this queue will append
   * its buffers (when full or flushed) and from which it will aquire new
   * buffers when it has exhausted its own.
   */
  public ObjectReferenceDeque(String n, SharedDeque queue) {
    super(queue);
    name = n;
  }

  /**
   * Insert an object into the object queue.
   *
   * @param object the object to be inserted into the object queue
   */
  @Inline
  public final void insert(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!object.isNull());
    checkTailInsert(1);
    uncheckedTailInsert(object.toAddress());
  }

  /**
   * Push an object onto the object queue.
   *
   * @param object the object to be pushed onto the object queue
   */
  @Inline
  public final void push(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(!object.isNull());
    checkHeadInsert(1);
    uncheckedHeadInsert(object.toAddress());
  }

  /**
   * Push an object onto the object queue, force this out of line
   * ("OOL"), in some circumstnaces it is too expensive to have the
   * push inlined, so this call is made.
   *
   * @param object the object to be pushed onto the object queue
   */
  @NoInline
  public final void pushOOL(ObjectReference object) {
    push(object);
  }

  /**
   * Pop an object from the object queue, return zero if the queue
   * is empty.
   *
   * @return The next object in the object queue, or zero if the
   * queue is empty
   */
  @Inline
  public final ObjectReference pop() {
    if (checkDequeue(1)) {
      return uncheckedDequeue().toObjectReference();
    } else {
      return ObjectReference.nullReference();
    }
  }

  @Inline
  public final boolean isEmpty() {
    return !checkDequeue(1);
  }

  @Inline
  public final boolean isNonEmpty() {
    return checkDequeue(1);
  }

  /* --------------------------------------------------
       Support for Strobe: allow us to iterate over
       the ObjectReferenceDeque without removing the entries
     -------------------------------------------------- */
  
  public Address currAddr = Address.zero();
  public boolean exhausted = false;

  /**
   * Must be called before the first call to getNextAddress()
   * or hasNext(), but after the last modification to the 
   * ObjectReferenceDeque.  I.e. call this immediately before iterating
   * over the ObjectReferenceDeque.
   */
  public final void prepareIterator() {
    if (head.EQ(HEAD_INITIAL_VALUE)) {
      exhausted = true;
    } else {
      exhausted = false;
      currAddr = head;
    }
  }

  /**
   * Iterate over the addresses of each word in the ObjectReferenceDeque.
   * We assume this is being done during GC and that the ObjectReferenceDeque
   * is not being accessed by anyone else.
   * 
   * @return the address of the next word in the ObjectReferenceDeque, or null
   * if exhausted
   */
  public final Address getNextAddress() {
    if (exhausted) {
      return Address.zero();
    }
    
    currAddr = currAddr.minus(BYTES_IN_ADDRESS);
    if (bufferOffset(currAddr).isZero()) {
      exhausted = true;
    }
    
    return currAddr;
  }
  
  /**
   * Will the next call to getNextAddress() return null?
   * 
   * @return true if there are more elements, false if not
   */
  public boolean hasNext() {
    return !exhausted;
  }  
}
