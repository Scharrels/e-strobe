import org.jikesrvm.cha.*;

import java.util.*;

public class TestTransitiveClosure {

  public static void main(String[] args) {

    if (args.length != 1) {
      System.err.println("Usage: java TestTransitiveClosure <depth>");
      System.exit(1);
    }

    TreeNode<Object> root = null;
    ArrayList<TreeNode<Object>> currNodes = new ArrayList<TreeNode<Object>>();
    ArrayList<TreeNode<Object>> nextNodes;

    // build a balanced tree of depth d
    int depth = Integer.parseInt(args[0]);
    for (int i=0; i<depth; i++) {
      nextNodes = new ArrayList<TreeNode<Object>>();
      if (currNodes.isEmpty()) {
        root = new TreeNode<Object>();
        nextNodes.add(root);
      } else {
        for (TreeNode<Object> node : currNodes) {
          node.left = new TreeNode<Object>();
          node.right = new TreeNode<Object>();
          nextNodes.add(node.left);
          nextNodes.add(node.right);
        }
      }

      currNodes = nextNodes;
    }

    for (int j=0; j<1000; j++) {
      System.out.println("point 0");
      // perform a transitive closure
      CHAFuture future = new CHAFuture(new CHATask(CHATask.TRANSITIVE_CLOSURE, 
            new Object[] {root, 1000}));
      future.go();
      System.out.println("point 1");

      /**
       * Do some stuff that triggers the write barrier on 
       * the objects we are checking.
       */
      Stack<TreeNode<Object>> worklist = new Stack<TreeNode<Object>>();
      worklist.push(root);
      while (!worklist.isEmpty()) {
        TreeNode<Object> curr = worklist.pop();
        if (curr.left != null) {
          worklist.push(curr.left);
        }
        if (curr.right != null) {
          worklist.push(curr.right);
        }
        TreeNode<Object> temp = curr.left;
        curr.left = curr.right;
        curr.right = temp;
      }

      //System.out.println("future1: " + future.get());

    }

  }

}
