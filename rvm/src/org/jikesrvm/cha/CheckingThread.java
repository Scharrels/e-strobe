package org.jikesrvm.cha;

import org.jikesrvm.VM;
import org.jikesrvm.scheduler.Monitor;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.NonMoving;

import java.util.concurrent.ArrayBlockingQueue;

@NonMoving public class CheckingThread extends RVMThread {
  
  private static boolean trace = false;
  public static final int MAX_CHA_THREADS = 12;
  private static int nextUnusedId = 0;  // TODO: shared -- need to protect with lock?
  public static Monitor schedLock;

  public static int numActiveCheckers = 0;
  public static Monitor numLock;

  private int snapshotId;

  // Task and associated monitor
  //public static CHAFuture newTask = null;
  //public static NoYieldpointsMonitor taskLock = null;
      
  // -- Queue of idle checking threads
  //    In this model idle checking threads put themselves on this queue. To start a
  //    an assertion, the application does a take() on this queue to get a free
  //    checking thread. If the queue is empty the take() will block until a checker
  //    enqueues itself (which is exactly what we want)
  public static ArrayBlockingQueue<CheckingThread> checkerQueue = null;

  // -- Wait lock
  //    Each checker thread uses this lock to wait for an assertion. The checkerWaiting
  //    boolean makes sure that when the system is saturated the checker thread
  //    doesn't miss a wake-up (broadcast) from the application
  public Monitor waitLock = null;
  public boolean checkerWaiting = false;

  // -- Go lock
  //    The application uses this lock to wait for the checker thread to turn on the
  //    write barrier before continuing. Again, the boolean ensures that we do not
  //    miss the wake-up
  public Monitor goLock = null;
  public boolean applicationWaiting = false;

  // -- The check to perform
  private CHAFuture checkToDo = null;

  //TODO: do we need this?
  public static void boot() {
    schedLock = new Monitor();
    numLock = new Monitor();
    //taskLock = new NoYieldpointsMonitor();
    checkerQueue = new ArrayBlockingQueue<CheckingThread>(MAX_CHA_THREADS);
  }
  
  /**
   * constructor
   */
  public CheckingThread() {
    super("Checker-"+nextUnusedId);
    checkerId = nextUnusedId;
    nextUnusedId++;
    makeDaemon(true);

    waitLock = new Monitor();
    goLock = new Monitor();
    turnOffSnapshots();

    // -- Put myself on the queue
    try {
      checkerQueue.put(this);
    } catch (InterruptedException e) {
      VM.sysWriteln("INTERRUPTED");
    }
  }

  /**
   * This is the main loop of the checking thread. 
   */
  public void run() {
    
    while (true) {

      if (trace) VM.tsysWriteln("Checker: Wait for assertion...");

      // -- Wait for an assertion request
      waitLock.lockNoHandshake();
      checkerWaiting = true;
      waitLock.waitWithHandshake();
      checkerWaiting = false;
      waitLock.unlock();

      // -- Enable write barrier
      /* SZG: Make the application thread do this...
      if (trace) VM.tsysWriteln("Checker: ...got one, enabling write barrier");
      Snapshot.initiateProbe(checkerId);
      */

      // -- When we get here, the application has called startMe and passed
      //    a CHAFuture to compute

      /*
      // -- Let application thread continue
      while (true) {
        goLock.lockNoHandshake();
        if (applicationWaiting) {
          goLock.broadcast();
          goLock.unlock();
          break;
        } else {
          goLock.unlock();
        }
      }
      */

      numLock.lockNoHandshake();
      numActiveCheckers++;
      numLock.unlock();

      // -- Perform computation
      if (trace) VM.tsysWriteln("Checker: Start check...");
      switchToSnapshot(snapshotId);
      checkToDo.compute();
      switchToLive();

      // -- Clean up, disabling write barrier
      if (trace) VM.tsysWriteln("Checker: ...check complete");
      Snapshot.completeProbe(snapshotId);
      snapshotId = -1;
      checkToDo = null;

      numLock.lockNoHandshake();
      numActiveCheckers--;
      numLock.unlock();

      // -- Put myself back in the queue
      if (trace) VM.tsysWriteln("Checker: Go back on queue ");
      try {
        checkerQueue.put(this);
      } catch (InterruptedException e) {
        VM.sysWriteln("INTERRUPTED");
      }
    }

    /*
    while (true) {
      
      // suspend this thread: it will resume when it is notified
      // that there is work to do.
      if (trace) {
        VM.sysWrite("Checking thread ");
        VM.sysWrite(checkerId);
        VM.sysWriteln(" wait");
      }
      schedLock.lockNoHandshake();
      schedLock.waitWithHandshake();
      schedLock.unlock();

      if (trace) {
        VM.sysWrite("Checking thread ",  checkerId);
        VM.sysWriteln(" notified -- look for task");
      }

      while (true) {
        
        if (trace) {
          VM.sysWrite("Checking thread taking (", checkerId);
          VM.sysWriteln(") size = ", CHAFuture.futureQueue.size());
        }
        
        // blocks until a task becomes available
        CHAFuture future = null;
        try {
          future = CHAFuture.futureQueue.take();
        } catch (InterruptedException e) {
          VM.sysWriteln("INTERRUPTED");
        }
        
        if (trace) {
          VM.sysWrite("Checking thread released (", checkerId);
          VM.sysWriteln(") from take, size = ", CHAFuture.futureQueue.size());
        }

        if (VM.VerifyAssertions) {
          VM._assert(future != null);
        }
                
        // enable write barrier
        if (trace) {
          VM.sysWrite("Checking thread started (", checkerId);
          VM.sysWriteln(") enabling write barrier");
        }
        Snapshot.initiateProbe(checkerId);

        // tell mutator to continue
        if (trace) {
          VM.sysWrite("Checking thread (", checkerId);
          VM.sysWriteln(") releasing latch");
        }
        // future.latch.release();

        // perform computation
        if (trace) {
          VM.sysWrite("Checking thread (", checkerId);
          VM.sysWriteln(") executing check");
        }
        future.compute();

        // clean up, disabling write barrier
        if (trace) {
          VM.sysWrite("Checking thread (", checkerId);
          VM.sysWriteln(") completed, cleaning up");
        }
        Snapshot.completeProbe(checkerId); 
      }
    }
    */
  }

  /**
   * Wake this thread
   * Called by application thread
   */
  public void startMe(CHAFuture future)
  {
    while (true) {
      waitLock.lockNoHandshake();
      if (checkerWaiting) {
        checkToDo = future;
        waitLock.broadcast();
        snapshotId = Snapshot.initiateProbe();
        waitLock.unlock();
        break;
      } else {
        waitLock.unlock();
      }
    }

    /*
    goLock.lockNoHandshake();
    applicationWaiting = true;
    goLock.waitWithHandshake();
    applicationWaiting = false;
    goLock.unlock();
    */
  }

  /**
   * Wait for all checkers to complete
   * We need this for the test harness
   */
  public static void waitAllDone()
  {
    VM.tsysWriteln("Wait for checkers to complete...");
    boolean done = false;
    while ( ! done) {
      numLock.lockNoHandshake();
      if (numActiveCheckers == 0)
        done = true;
      numLock.unlock();
    }
    VM.tsysWriteln("...continue");
  }

  /**
   * Wake concurrent checking threads
   */
  public static void wake() {
    schedLock.lockNoHandshake();
    schedLock.broadcast();
    schedLock.unlock();
  }
  
}