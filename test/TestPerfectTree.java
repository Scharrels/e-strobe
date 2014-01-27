import org.jikesrvm.cha.CHATask;
import org.jikesrvm.cha.CHAFuture;
import org.vmmagic.pragma.ConcurrentCheck;

import java.util.*;
import java.lang.Integer;

public class TestPerfectTree {

  public static void main(String[] args) {

    if (args.length != 1) {
      System.err.println("Usage: java TestTransitiveClosure <depth>");
      System.exit(1);
    }

    TreeNode<Integer> root = null;
    ArrayList<TreeNode<Integer>> currNodes = new ArrayList<TreeNode<Integer>>();
    ArrayList<TreeNode<Integer>> nextNodes;

    // build a balanced tree of depth d
    int depth = Integer.parseInt(args[0]);
    int count = 0;
    for (int i=0; i<depth; i++) {
      nextNodes = new ArrayList<TreeNode<Integer>>();
      if (currNodes.isEmpty()) {
        root = new TreeNode<Integer>();
        root.value = Integer.valueOf(count);
        count++;
        nextNodes.add(root);
      } else {
        for (TreeNode<Integer> node : currNodes) {
          node.left = new TreeNode<Integer>();
          node.left.value = Integer.valueOf(count);
          count++;
          node.right = new TreeNode<Integer>();
          node.right.value = Integer.valueOf(count);
          count++;
          nextNodes.add(node.left);
          nextNodes.add(node.right);
        }
      }
      currNodes = nextNodes;
    }

    for (int j=0; j<1; j++) {

      // perform check
      CHATask task = new PerfectTreeCheckTask(root); 
      CHAFuture future1 = new CHAFuture(task);
      future1.go();

      /**
       * Do some stuff that triggers the write barrier on 
       * the objects we are checking.
       */
      /*
      Stack<TreeNode<Integer>> worklist = new Stack<TreeNode<Integer>>();
      worklist.push(root);
      while (!worklist.isEmpty()) {
        TreeNode<Integer> curr = worklist.pop();
        if (curr.left != null) {
          worklist.push(curr.left);
        }
        if (curr.right != null) {
          worklist.push(curr.right);
        }
        TreeNode<Integer> temp = curr.left;
        curr.left = curr.right;
        curr.right = temp;
      }
      */

      System.out.println("This node is the one we write to: " + root.right.value);
      System.out.println("This node is the one we cut off: " + root.right.right.value);
      TreeNode<Integer> temp = root.right.right;
      root.right.right = null;

      // perform another check
      task = new PerfectTreeCheckTask(root); 
      CHAFuture future2 = new CHAFuture(task);
      future2.go();

      root.right.right = temp;

      // perform another check
      task = new PerfectTreeCheckTask(root); 
      CHAFuture future3 = new CHAFuture(task);
      future3.go();

      System.out.println("future1: " + future1.get());
      System.out.println("future2: " + future2.get());
      System.out.println("future3: " + future3.get());

    }

  }

  private static class PerfectTreeCheckTask implements CHATask {

    TreeNode<Integer> root;

    public PerfectTreeCheckTask(TreeNode<Integer> root) {
      this.root = root;
    }

    @ConcurrentCheck
    public boolean compute() {

      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {}

      // compute depth
      int depth = -1;
      TreeNode<Integer> currNode = root;
      while (currNode != null) {
        currNode = currNode.left;
        depth++;
      }

      // count nodes in tree
      int count = 0;
      Stack<TreeNode<Integer>> worklist = new Stack<TreeNode<Integer>>();
      if (root != null) {
        worklist.push(root);
      }
      while (!worklist.isEmpty()) {
        TreeNode<Integer> curr = worklist.pop();
        //System.out.println(curr.value);
        count++;
        if (curr.left != null) {
          worklist.push(curr.left);
        }
        if (curr.right != null) {
          worklist.push(curr.right);
        }
      }

      return count == (int)Math.pow(2, depth+1) - 1;
    }
  }

}
