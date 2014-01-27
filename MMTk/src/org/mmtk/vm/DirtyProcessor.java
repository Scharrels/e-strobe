package org.mmtk.vm;

import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.SimpleMutator;
import org.vmmagic.pragma.Uninterruptible;

/**
 * This class manages the dirty lists used by Strobe.
 */
@Uninterruptible
public abstract class DirtyProcessor {

  /**
   * Prepare the dirty pools
   * (Must be called before flushDirtyLists)
   * DEPRECATED
   */
  public abstract void prepare();
  
  /**
   * Fix global dirty pools
   *
   * Called by SimpleCollector
   * 
   * @param trace The trace containing liveness and forwarding info
   * @param nursery Scan only the newly created references
   */
  public abstract void fixDirtyPools(TraceLocal trace, boolean nursery);

  /**
   * Fix local dirty buffers
   *
   * Called by SimpleMutator (a superclass of RVMThread) for each mutator
   * thread.
   *
   * @param mutator The thread whose dirty buffers will be fixed
   * @param trace The trace containing liveness and forwarding info
   * @param nursery Scan only the newly created references
   */
  public abstract void fixDirtyBuffers(SimpleMutator mutator, TraceLocal trace, boolean nursery);
}
