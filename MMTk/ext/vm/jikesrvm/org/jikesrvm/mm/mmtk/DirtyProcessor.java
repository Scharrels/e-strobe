package org.jikesrvm.mm.mmtk;

import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.SimpleMutator;
import org.mmtk.plan.generational.Gen;
import org.mmtk.utility.Log;
import org.mmtk.utility.deque.SharedDeque;
import org.mmtk.utility.options.Options;
import org.mmtk.vm.VM;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.ObjectReferenceArray;
import org.jikesrvm.mm.mminterface.CHAInterface;
import org.jikesrvm.mm.mminterface.DebugUtil;
import org.jikesrvm.mm.mminterface.Selected;
import org.jikesrvm.scheduler.RVMThread;

/**
 * This class manages the dirty lists used by Strobe.  The dirty lists
 * are not scanned by the normal GC process because we want them to 
 * have the semantics of weak references, i.e., if the only reference
 * to a dirty object is from the dirty list, we want the object to
 * be collected.
 * 
 * To achieve this, we added a new phase to the GC that processes the
 * dirty lists using the same basic idea as the weak reference processor.
 * That phase needs to call into this class in order to process the
 * references in the dirty lists.
 */
@Uninterruptible
public final class DirtyProcessor extends org.mmtk.vm.DirtyProcessor {
  
  /** The DirtyProcessor singleton */
  private static final DirtyProcessor dirtyProcessor = new DirtyProcessor();

  /**
   * Prepare the dirty pools (ShareDeques) for processing. This must be called
   * before RVMThread.flushDirtyLists calls flushLocal on each local buffer
   */
  public void prepare()
  {
    CHAInterface.prepareDirtyPools();
  }

  /**
   * Fix global dirty pools
   *
   * Called by SimpleCollector, this method scans over the dirty pools
   * (SharedDeques) and either nulls out or forwards any references
   * that have changed in this collection.
   *
   * Depending on the value of <code>nursery</code>, we will either
   * scan all references, or just those in the nursery.
   * 
   * Not thread safe! Must be called from a single thread.
   * 
   * @param trace The thread local trace element
   * @param nursery Scan only the newly created references
   */
  @Override
  public void fixDirtyPools(TraceLocal trace, boolean nursery)
  {  
    CHAInterface.fixDirtyPools(trace, nursery);
  }

  /**
   * Fix local dirty buffers
   *
   * NOT USED: It turns out that this is very slow.
   *
   * Called by SimpleMutator (a superclass of RVMThread) for each mutator
   * thread. Does the same job as fixDirtyPools above, but for each of
   * the local buffers.
   *
   * @param mutator The thread whose dirty buffers will be fixed
   * @param trace The trace containing liveness and forwarding info
   * @param nursery Scan only the newly created references
   */
  @Override
  public void fixDirtyBuffers(SimpleMutator mutator, TraceLocal trace, boolean nursery)
  {
    RVMThread rvmthread = (RVMThread) mutator;
    rvmthread.chaInterface.fixDirtyBuffers(trace, nursery);
  }

  /**
   * Factory method.
   * Creates an instance of the appropriate DirtyProcessor.
   * @return the DirtyProcessor
   */
  @Interruptible
  public static DirtyProcessor get() {
    return dirtyProcessor;
  }

}
