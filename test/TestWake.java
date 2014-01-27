import org.jikesrvm.cha.CHAInterface;

public class TestWake {

  public static void main(String[] args) {

    CHAInterface.enableWB();
    for (int i=0; i<10; i++) {
      CHAInterface.wake();
      try {
        Thread.sleep(500);
      } catch (Exception e) {
      }
    }
    CHAInterface.disableWB();

  }

}

