package android.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IActivityTaskManager extends IInterface {
    abstract class Stub extends Binder implements IServiceConnection {
        public static IActivityTaskManager asInterface(IBinder obj)
        {
            throw new RuntimeException("Stub!");
        }
    }

    void moveRootTaskToDisplay(int taskId, int displayId);

    void moveStackToDisplay(int stackId, int displayId);

    void registerTaskStackListener(ITaskStackListener iTaskStackListener);

    void unregisterTaskStackListener(ITaskStackListener iTaskStackListener);
}
