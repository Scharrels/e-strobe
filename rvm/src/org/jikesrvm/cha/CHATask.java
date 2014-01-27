/**
 * An interface for a ConcurrentHeapAssertions task. 
 *  
 */

package org.jikesrvm.cha;

public interface CHATask {
  
  /**
   * Compute the result for a task
   * 
   * @return The result
   */
  public boolean compute();
  
}
