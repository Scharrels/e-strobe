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
package org.mmtk.utility;

import org.mmtk.policy.RawPageSpace;
import org.mmtk.policy.Space;
import org.mmtk.vm.Lock;
import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class is the shared portion of the SimplePool data structure.
 * The SimplePool data structure provides a very basic memory pool
 * (i.e. bump pointer allocation, can deallocate only a whole pool, not
 * individual objects).
 * 
 * This class supports <i>unsynchronized</i> allocation and enqueuing of 
 * blocks for shared use.  We must keep track of these blocks so we can
 * deallocate them all at once.
 */
@Uninterruptible 
public class SharedSimplePool implements Constants {
    
  private static final Offset NEXT_OFFSET = Offset.zero();
  private static final Address HEAD_INITIAL_VALUE = Address.zero();  
  
  /** Head of the shared deque */
  private volatile Address head;

  private volatile int bufsenqueued = 0;
  private Lock lock;
  
  /** The name of this shared pool - for diagnostics */
  private final String name;

  /** Raw page space from which to allocate */
  private RawPageSpace rps;

  /** Print debug statements? */
  private boolean trace = false;
  

  /**
   * Create a new SimplePoolShared.
   *
   * @param rps The space to acquire the data structure from.
   * @param slots The number of slots in the hash table.
   * @param es The size of each entry.
   */
  public SharedSimplePool(String name, RawPageSpace rps) {
    this.rps = rps;
    this.name = name;
    lock = VM.newLock("SharedDeque");
    head = HEAD_INITIAL_VALUE;
  }
  
  /**
   * Enqueue a block on the head of the shared pool
   *
   * @param buf The address of the block to enqueue
   */
  public void enqueue(Address buf) {
    if (trace) {
      Log.write("Enqueuing block ");
      Log.writeln(buf);
    }
    lock();
    if (head.EQ(HEAD_INITIAL_VALUE)) {
      head = buf;
    } else {
      Address temp = head;
      head = buf;
      setNext(buf, temp);
    }
    bufsenqueued++;
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(checkDequeLength(bufsenqueued));
    unlock();
  }  
  
  /**
   * Allocate some number of new blocks
   * 
   * @param pages The number of pages we will need to store our data
   * @return The address of the newly-allocated block
   */
  public Address alloc(int pages) {
    Address rtn = rps.acquire(pages);
    if (rtn.isZero()) {
      Space.printUsageMB();
      VM.assertions.fail("Failed to allocate space for pool.  Is metadata virtual memory exhausted?");
    }
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(getNext(rtn).isZero());
    if (trace) {
      Log.write("Allocated block ");
      Log.write(rtn);
      Log.write("-");
      Log.writeln(rtn.plus(Conversions.pagesToBytes(pages)));
    }
    return rtn;
  }
  
  /**
   * Deallocate a block
   * 
   * @param buf The address of the block to deallocate
   */
  private void dealloc(Address buf) {
    if (trace) {
      Log.write("Deallocating block ");
      Log.writeln(buf);
    }
    rps.release(buf);
  }
  
  /**
   * Deallocate all blocks in the pool
   */
  public void release() {
    lock();
    Address curr = head;
    while (!curr.isZero()) {
      Address next = getNext(curr);
      dealloc(curr);
      curr = next;
    }
    head = HEAD_INITIAL_VALUE;
    bufsenqueued = 0;
    unlock();
  }

  /**
   * Lock this shared pool.  We use one simple low-level lock to
   * synchronize access to the shared queue of blocks.
   */
  private void lock() {
    lock.acquire();
  }

  /**
   * Release the pool.  We use one simple low-level lock to synchronize
   * access to the shared queue of blocks.
   */
  private void unlock() {
    lock.release();
  }
  
  /**
   * Check the number of blocks in the pool (for debugging
   * purposes).  Must hold lock when called.
   *
   * @param length The number of blocks believed to be in the pool.
   * @return True if the length of the pool matches length.
   */
  private boolean checkDequeLength(int length) {
    Address top = head;
    int l = 0;
    while (!top.isZero()) {
      top = getNext(top);
      l++;
    }
    if (l != length) {
      Log.write("expected length == ");
      Log.writeln(length);
      Log.write("actual length == ");
      Log.writeln(l);
    }
    return l == length;
  }
  
  /**
   * Set the "next" pointer in a block forming the linked block chain.
   *
   * @param buf The block whose next field is to be set.
   * @param next The reference to which next should point.
   */
  private static void setNext(Address buf, Address next) {
    buf.store(next, NEXT_OFFSET);
  }

  /**
   * Get the "next" pointer in a block forming the linked block chain.
   *
   * @param buf The block whose next field is to be returned.
   * @return The next field for this block.
   */
  private Address getNext(Address buf) {
    return buf.loadAddress(NEXT_OFFSET);
  }

  
}
