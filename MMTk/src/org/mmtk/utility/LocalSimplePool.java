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

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * This class is the thread-local portion of the SimplePool data 
 * structure. The SimplePool data structure provides a very basic memory pool
 * (i.e. bump pointer allocation, can deallocate only a whole pool, not
 * individual objects).
 * 
 * This class receives a thread-local block and allocates from it.  When
 * the block is full, it enqueues the block into the SharedSimplePool.
 */
@Uninterruptible 
public class LocalSimplePool implements Constants {
  
  private final int ALIGNMENT = BYTES_IN_INT;
  
  private Address blockHead = Address.zero();
  private Address blockCurr = Address.zero();
  private Address blockEnd = Address.zero();  // points to the first address past the end of the block
  
  private SharedSimplePool sharedPool;
    
  public LocalSimplePool(SharedSimplePool sharedPool) {
    this.sharedPool = sharedPool;
  }
  
  /**
   * Allocate some number of bytes from the pool
   * 
   * @param bytes The size of the space to allocate, in bytes
   * @return The start of the memory region that has been allocated
   */
  public Address alloc(int bytes) {
    
    int alignedBytes = alignAllocRequest(bytes);
    
    if (VM.VERIFY_ASSERTIONS)
      VM.assertions._assert(alignedBytes >= bytes);
    
    if (blockCurr.plus(alignedBytes).toWord().GT(blockEnd.toWord())) {
      // no space in current block to allocate, need a new one
      if (!blockHead.isZero()) {
        sharedPool.enqueue(blockHead);
      }
      
      int pages = Conversions.bytesToPagesUp(alignedBytes+4);
      blockHead = sharedPool.alloc(pages);
      blockCurr = blockHead.plus(4); // TODO: this is "magic", should be encapsulated somewhere else
      blockEnd = blockHead.plus(Conversions.pagesToBytes(pages));
    }
    
    if (VM.VERIFY_ASSERTIONS) 
      VM.assertions._assert(blockCurr.plus(alignedBytes).toWord().LE(blockEnd.toWord()));
    
    Address rtn = blockCurr;
    blockCurr = blockCurr.plus(alignedBytes);
    
    if (VM.VERIFY_ASSERTIONS) 
      VM.assertions._assert(rtn.toWord().toLong() % 4 == 0);
    
    return rtn;
  }
  
  /**
   * Given an allocation request for a number of bytes, return an aligned 
   * number of bytes to allocate 
   * 
   * @param bytes The size of the allocation request in bytes
   * @return The number of bytes to actually allocate
   */
  @Inline
  private int alignAllocRequest(int bytes) {
    int ret = (bytes + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(ret % 4 == 0);
    return ret;
  }
  
  /**
   * Flush the current block back to the shared pool, reset local
   * pointers
   */
  public void flush() {
    if (!blockHead.isZero()) {
      sharedPool.enqueue(blockHead);
    }
    blockHead = Address.zero();
    blockCurr = Address.zero();
    blockEnd = Address.zero();
  }
  
}
