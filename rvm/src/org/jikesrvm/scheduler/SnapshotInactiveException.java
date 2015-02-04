package org.jikesrvm.scheduler;

/**
 * Created by Jorne Kandziora on 14-1-15.
 */
public class SnapshotInactiveException extends RuntimeException {
    public SnapshotInactiveException(int snapshot){
        super("Snapshot " + snapshot + "is not active.");
    }
}
