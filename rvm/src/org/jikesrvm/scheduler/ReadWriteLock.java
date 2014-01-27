package org.jikesrvm.scheduler;

import org.jikesrvm.VM;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class ReadWriteLock {
  
  private int givenLocks;
  private int waitingWriters;
  // we have to use an RVM Monitor because this will be called from uninterruptible code
  private Monitor lock;     
  
  private boolean trace = false;
  
  /**
   * Construct a new ReadWriteLock
   */
  public ReadWriteLock() {
    lock = new Monitor();
    givenLocks = 0;
    waitingWriters = 0;
  }
  
  /**
   * Acquire the read lock.  Block if someone else currently holds
   * the write lock.
   */
  public void getReadLock() {
    if (trace) {
      VM.sysWrite("Getting read lock.  Current state: givenLocks == ");
      VM.sysWrite(givenLocks);
      VM.sysWrite(" , waitingWriters == ");
      VM.sysWriteln(waitingWriters);
    }
    lock.lockNoHandshake();
    while ((givenLocks == -1) || (waitingWriters != 0))
      lock.waitNoHandshake();
    givenLocks++;
    lock.unlock();
  }
  
  /**
   * Acquire the write lock.  Block while at least one other thread
   * holds the read lock.
   */
  public void getWriteLock() {
    if (trace) {
      VM.sysWrite("Getting write lock.  Current state: givenLocks == ");
      VM.sysWrite(givenLocks);
      VM.sysWrite(" , waitingWriters == ");
      VM.sysWriteln(waitingWriters);
    }
    lock.lockNoHandshake();
      waitingWriters++;
      while (givenLocks != 0)
        lock.waitNoHandshake();
      waitingWriters--;
      givenLocks = -1;
    lock.unlock();
  }
  
  /**
   * Release either a read or write lock.
   */
  public void releaseLock() { 
    if (trace) {
      VM.sysWrite("Releasing lock.  Current state: givenLocks == ");
      VM.sysWrite(givenLocks);
      VM.sysWrite(" , waitingWriters == ");
      VM.sysWriteln(waitingWriters);
    }
    lock.lockNoHandshake();
    if (givenLocks == 0)
      return;
    if (givenLocks == -1) {
      givenLocks = 0;
    } else {
      givenLocks--;
    }
    lock.broadcast();
    lock.unlock();
  }

}

