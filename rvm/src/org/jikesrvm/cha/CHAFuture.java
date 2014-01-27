/**
 * This class encapsulates a concurrent heap assertion and allows the program
 * to check whether the result is ready.
 */

package org.jikesrvm.cha;

import java.util.concurrent.ArrayBlockingQueue;

import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;

import org.jikesrvm.mm.mminterface.CHAInterface;

public class CHAFuture {
  
  private static boolean trace = false;
  
  // outstanding future
  public static ArrayBlockingQueue<CHAFuture> futureQueue = null;
  
  public volatile boolean done = false;
  public volatile boolean result;
  public CHATask task;
  public SimpleLatch latch;
  private Object lock = new Object();
    
  public boolean isDone() { 
    return done;
  }
  
  /**
   * Returns the result of the task when it completes.  If not complete, waits
   * until complete.
   */
  public boolean get() {
    synchronized(lock) {
      while (!done) {
        try {
          lock.wait();
        } catch (InterruptedException e) {}
      }
    }
    return result;
  }
  
  /**
   * Start the concurrent heap assertion and return
   * 
   * We had a long discussion on 9/28 re: the semantics of the heap snapshot.
   * We decided that our snapshot should represent _one of_ the possible states
   * of the heap at the program point where the assertion occurred.  Note that 
   * this does not mean the snapshot can represent any possible state of the heap;
   * there may be some heap states that can never be in a snapshot.  But that's OK
   * because this is a runtime tool, and as long as we never show a bogus state to 
   * the checking thread we are OK.
   * 
   * Given this, I think we don't need to stop the mutator threads when a checking
   * thread starts.  Suppose we have n threads, and thread 0 issues the assertion.
   * Thread 0 issues the assertion, it does some stuff, then turns on the write
   * barrier atomically.  We can consider the snapshot to have captured the 
   * heap state at the moment when the write barrier was turned on.  This will
   * capture an equivalent state to if thread 0 did not have the assertion 
   * but was preempted at the assertion point and not scheduled again until
   * the write barrier would have been enabled. 
   *
   * Process:
   * 1) atomically put task into static field
   * 2) enable write barrier
   * 3) notify checking threads
   * 4) one checking thread should "win" and atomically pull task from field
   *    and set it to null -- atomic compare and swap 
   * 
   * TODO: behavior if all checking threads are busy? all mutator threads have to wait
   * until task queue is empty?
   */
  public void go() {

    RVMThread.getCurrentThread().turnOffSnapshots();

    CHAInterface.startWaitTimer();

    // -- Remove next free checker thread from queue (if there is one)
    CheckingThread myChecker = null;

    if (trace) VM.tsysWriteln("Get checker from queue, size = ", CheckingThread.checkerQueue.size());

    try {
      myChecker = CheckingThread.checkerQueue.take();
    } catch (InterruptedException e) {
      VM.sysWriteln("INTERRUPTED");
    }

    if (trace) VM.tsysWriteln("Got one -- start checker " + myChecker.getName());

    // -- Give it the assertion to compute and wake it up
    myChecker.startMe(this);

    if (trace) VM.tsysWriteln("Continue application");

    CHAInterface.stopWaitTimer();

    RVMThread.getCurrentThread().turnOnSnapshots();

    /*
    // lazy initialization
    if (futureQueue == null) {
      futureQueue = new ArrayBlockingQueue<CHAFuture>(1);
      
      // release checking threads from first wait
      CheckingThread.wake();
    }
    
    if (VM.VerifyAssertions) {
      VM._assert(task != null);
    }

    // put future to be consumed by checking threads
    if (trace) VM.tsysWriteln("About to put task on queue, size = ", futureQueue.size());
    try {
      futureQueue.put(this);
    } catch (InterruptedException e) {
      VM.sysWriteln("INTERRUPTED");
    }
    if (trace) VM.sysWriteln("mutator: Task on queue, size = ", futureQueue.size());

    if (trace) VM.tsysWriteln("mutator: about to arrive at latch");
    // wait here until a checking thread has awoken and enabled the write barrier
    latch.arrive();
    if (trace) VM.tsysWriteln("mutator: released from latch");

    // RVMThread.getCurrentThread().turnOnSnapshots();
    */
  }
  
  /**
   * Perform the task for this future and store result in variable
   * 
   * @param The id of the checking thread performing this check
   */
  public void compute() {
    // if (trace) VM.tsysWriteln("Checker: about to compute");
    result = task.compute();
    // if (trace) VM.tsysWriteln("Checker: done with compute");
    synchronized(lock) {
      done = true;
      lock.notifyAll();
    }
    if (trace) VM.tsysWriteln("checker: notified all");
  }
  
  public CHAFuture(CHATask task) {
    this.task = task;
    this.latch = new SimpleLatch();
  }

  /**
   * Evaluate this assertion synchronously
   */
  public boolean checkSync()
  {
    return task.compute();
  }
}

