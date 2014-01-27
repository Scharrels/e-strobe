import org.mmtk.plan.heapassertions.spec.*;

public class Test {

  public static void main(String[] args) {

    CHAFuture future = new CHAFuture(new CHATask(CHATask.IS_SHARED, null));
    future.go();

    try {
    Thread.sleep(3000);
    } catch (InterruptedException e) {
    }

  }

}
