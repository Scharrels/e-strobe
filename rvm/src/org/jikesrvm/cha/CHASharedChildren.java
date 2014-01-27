package org.jikesrvm.cha;

import org.jikesrvm.mm.mminterface.CHAInterface;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.ObjectReferenceArray;

public class CHASharedChildren implements CHATask {
  
  Object obj1, obj2;
  
  CHASharedChildren(Object obj1, Object obj2) {
    this.obj1 = obj1;
    this.obj2 = obj2;
  }
  
  /**
   * Compute the result for a task
   * 
   * @return The result
   */
  public boolean compute() { 
    // INTENTIONALLY BROKEN (SZG)
    ObjectReferenceArray refs1 = null; // CHAInterface.getRefsForwarded(obj1);
    ObjectReferenceArray refs2 = null; // CHAInterface.getRefsForwarded(obj2);
    if (refs1 != null && refs2 != null) {
      for (int i=0; i<refs1.length(); i++) {
        ObjectReference thisRef = refs1.get(i);
        for (int j=0; j<refs2.length(); j++) {
          if (thisRef != null && thisRef == refs2.get(j)) {
            return true;
          }
        }
      }
    }
    return false;
  }

}
