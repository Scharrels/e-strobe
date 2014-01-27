package org.jikesrvm.cha;

/**
 * This is a simple thread latch that allows a set of threads to block until
 * some event has occurred.  Once that event has occurred, the threads may
 * continue.
 */
public class SimpleLatch {
  
  private Object lock;
  private volatile boolean release;
  
  public SimpleLatch() {
    lock = new Object();
    release = false;
  }
  
  public void arrive() {
    synchronized(lock) {
  	  try {
  	    while (!release) {
  	      lock.wait();
  	    }
  	  } catch (InterruptedException e) {}
    }
  }
  
  public void release() {
    synchronized(lock) {
	release = true; 
	lock.notifyAll();
    }
  }
  
  public void reset() {
    release = false;
  }

}