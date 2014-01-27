import org.vmmagic.pragma.ConcurrentCheck;

import java.util.ArrayList;

public class TestReadBarriers {

  public static void main(String[] args) {
    testReadBarriers();
  }

  /**
   * This method has been annotated with the ConcurrentCheck annotation, 
   * which tells the compiler to compile it with read barriers.  We want 
   * to check that the barriers are actually inserted into the code for 
   * both objects and arrays, as well as for both reference and primitive 
   * reads.  Also we should check that both compilers (baseline and opt)
   * do this correctly.
   */
  @ConcurrentCheck
  private static void testReadBarriers() {
    ArrayList<TestObject> list = new ArrayList<TestObject>();
    for (int i=0; i<100; i++) {
      TestObject obj = new TestObject();
      if (i % 2 == 0) obj.boolF = true;
      list.add(obj);
    }

    for (int i=0; i<100; i++) {
      TestObject obj = list.get(i);
      System.out.println(obj.boolF);
    }

  }

  private static class TestObject {

    public boolean boolF;
    public byte byteF;
    public char charF;
    public short shortF;
    public int intF;
    public float floatF;
    public long longF;
    public double doubleF;
    public Object objF;

  }
}


