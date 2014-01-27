package org.jikesrvm.cha;

import java.util.HashSet;
import java.util.ArrayList;

import org.jikesrvm.mm.mminterface.CHAInterface;
import org.vmmagic.unboxed.ObjectReferenceArray;
import org.vmmagic.unboxed.ObjectReference;

import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.TIB;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.unboxed.Word;
import org.jikesrvm.SizeConstants;

import org.jikesrvm.VM;

public class CHATransitiveClosure implements CHATask {
  
  Object obj;
  int expectedCount;
  // Object [] bfs = new Object[expectedCount];
  
  public CHATransitiveClosure(Object obj, int expectedCount) {
    this.obj = obj;
    this.expectedCount = expectedCount;
  }
  
  public boolean compute() {
    return computeBFS();
  }

  /*
  public boolean computeDFS() {
      // int count_notforwarded = 0;
      // int count_forwardednull = 0;
      // int count_forwarded = 0;
    // VM.sysWriteln("P1");
    HashSet<Object> visited = new HashSet<Object>();
    ArrayList<Object> worklist = new ArrayList<Object>(100);
    ObjectReferenceArray refs;
    worklist.add(obj);
    int count = 0;
    while (!worklist.isEmpty() && count < expectedCount) {
      Object currObj = worklist.remove(worklist.size()-1);
      visited.add(currObj);
      count++;
      
      ObjectReference objRef = ObjectReference.fromObject(currObj);
      
      // Lock the object's epoch word
      Word epoch = MiscHeader.attemptEpoch2(objRef); 
      
      ObjectReference getrefsfrom;
      
      // Get forwarding pointer
     ObjectReferenceArray forwardingArray = MiscHeader.getForwardingPointer(objRef);
      if (forwardingArray == null) {
        getrefsfrom = objRef;
        // count_notforwarded++;
      } else {
        // CheckingThread thisThread = (CheckingThread)RVMThread.getCurrentThread();
        RVMThread thisThread = RVMThread.getCurrentThread();
        ObjectReference snapshot = forwardingArray.get(thisThread.checkerId);
        if (snapshot.isNull()) {
          getrefsfrom = objRef;
          // count_forwardednull++;
        } else {
          // sanity check: object and its snapshot are same type
          if (VM.VerifyAssertions) {
            VM._assert(ObjectModel.getTIB(snapshot) == ObjectModel.getTIB(objRef));
          }
          getrefsfrom = snapshot;
          // count_forwarded++;
        }
      }
      
      // write epoch back in, unlocking object
      MiscHeader.setEpoch(objRef, epoch);
      
      RVMType type = ObjectModel.getObjectType(getrefsfrom.toObject());
      if (type.isClassType()) {
        RVMClass klass = type.asClass();
        int[] offsets = klass.getReferenceOffsets();
        for(int i=0; i < offsets.length; i++) {
          ObjectReference target = getrefsfrom.toAddress().plus(offsets[i]).loadObjectReference();
          if (!target.isNull() && !visited.contains(target.toObject())) {
            worklist.add(target);
          }
        }
      } else if (type.isArrayType() && type.asArray().getElementType().isReferenceType()) {
        int length = ObjectModel.getArrayLength(getrefsfrom.toObject());
        for(int i=0; i < length; i++) {
          ObjectReference target = getrefsfrom.toAddress().plus(i << SizeConstants.LOG_BYTES_IN_ADDRESS).loadObjectReference();
          if (!target.isNull() && !visited.contains(target.toObject())) {
            worklist.add(target);
          }
        }
      }
      
      // refs = CHAInterface.getRefsForwarded(currObj);
      // visited.add(currObj);
      // count++;
      // if (refs != null) {
      //   for (int j=0; j<refs.length(); j++) {
      //     ObjectReference thisRef = refs.get(j);
      //     if (!thisRef.isNull() && !visited.contains(thisRef.toObject())) {
      //       worklist.push(thisRef.toObject());
      //     }
      //   }
      // }
    }
    // VM.sysWriteln("PX");
    // VM.sysWrite("Stats: nf ");
    // VM.sysWrite(count_notforwarded);
    // VM.sysWrite(" f0 ");
    // VM.sysWrite(count_forwardednull);
    // VM.sysWrite(" f ");
    // VM.sysWriteln(count_forwarded);
    return count == expectedCount;
  }
  */

  public boolean computeBFS() {
    int count = 0;
    int end = 0;

    // Object [] bfs = new Object[expectedCount];
    RVMThread thisThread = RVMThread.getCurrentThread();
    Object [] bfs = thisThread.getBuffer(expectedCount);
    bfs[end] = obj;
    end++;

    while ((count < expectedCount) && (count < end)) {
      Object currObj = bfs[count];
      bfs[count] = null;
      count++;

      ObjectReference objRef = ObjectReference.fromObject(currObj);
      RVMType otype = ObjectModel.getObjectType(objRef.toObject());
      ObjectReference getrefsfrom = MiscHeader.getSnapshot(objRef);

      // VM.tsysWriteln("Follow ", objRef, " --> ", getrefsfrom, " type ", otype.getDescriptor().toString());

      RVMType type = ObjectModel.getObjectType(getrefsfrom.toObject());
      if (type != otype) {
        VM.tsysWriteln("THIS IS BAD");
        VM.tsysWriteln("Original ", objRef, " type ", otype.getDescriptor());
        VM.tsysWriteln("Snapshot ", getrefsfrom, " type ", type.getDescriptor());
      }

      // VM.sysWriteln(" type ", type.getDescriptor().toString());

      if (type.isClassType()) {
        RVMClass klass = type.asClass();
        int[] offsets = klass.getReferenceOffsets();
        for(int i=0; i < offsets.length; i++) {
          ObjectReference target = getrefsfrom.toAddress().plus(offsets[i]).loadObjectReference();
          if (!target.isNull() && (end < bfs.length)) {
            bfs[end] = target.toObject();
            end++;
          }
        }
      } else if (type.isArrayType() && type.asArray().getElementType().isReferenceType()) {
        int length = ObjectModel.getArrayLength(getrefsfrom.toObject());
        for(int i=0; i < length; i++) {
          ObjectReference target = getrefsfrom.toAddress().plus(i << SizeConstants.LOG_BYTES_IN_ADDRESS).loadObjectReference();
          if (!target.isNull() && (end < bfs.length)) {
            bfs[end] = target.toObject();
            end++;
          }
        }
      }
    }

    // -- Clear out the rest of the references
    while (count < expectedCount) {
      VM.tsysWriteln("SHORT: expected ", expectedCount, " but got ", count);
      bfs[count] = null;
      count++;
    }

    // VM.sysWriteln("PX");
    /*
    VM.sysWrite("Stats: nf ");
    VM.sysWrite(count_notforwarded);
    VM.sysWrite(" f0 ");
    VM.sysWrite(count_forwardednull);
    VM.sysWrite(" f ");
    VM.sysWriteln(count_forwarded);
    */

    // bfs = null;

    return count == expectedCount;
  }
}
