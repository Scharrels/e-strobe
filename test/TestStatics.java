import org.jikesrvm.cha.*;

import java.util.LinkedList;

public class TestStatics {

  public static final int ARRAY_SIZE = 5;
  public static Container container = new Container();

  public static void main(String[] args) {

    Object[] objArr1 = new Object[ARRAY_SIZE];

    for (int i=0; i<objArr1.length; i++) {
      objArr1[i] = new Object();
    }

    Object testObj = new Object();
    objArr1[2] = testObj;
    container.value = testObj;

    System.out.println("point 0");
    CHAFuture future = new CHAFuture(new CHATask(CHATask.SHARED_CHILDREN, 
          new Object[] {objArr1, container}));
    future.go();
    container.value = null;
    System.out.println("point 1");

    CHAFuture future2 = new CHAFuture(new CHATask(CHATask.SHARED_CHILDREN, 
          new Object[] {objArr1, container}));
    future2.go();
    container.value = testObj;
    System.out.println("point 2");

    CHAFuture future3 = new CHAFuture(new CHATask(CHATask.SHARED_CHILDREN, 
          new Object[] {objArr1, container}));
    future3.go();
    container.value = testObj;
    System.out.println("point 3");

    LinkedList<Object> list = new LinkedList<Object>();
    for (int j=0; j<1000; j++) {
      list.add(new Object());
    }

    System.out.println("future1: " + future.get());
    System.out.println("future2: " + future2.get());
    System.out.println("future3: " + future3.get());

    try {
    Thread.sleep(1000);
    } catch (InterruptedException e) {
    }

  }

}
