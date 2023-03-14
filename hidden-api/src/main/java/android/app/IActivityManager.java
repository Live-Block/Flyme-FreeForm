package android.app;

import android.content.Intent;
import android.content.IIntentSender;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.util.List;

public interface IActivityManager extends IInterface {
    abstract class Stub extends Binder implements IServiceConnection {
        public static IActivityManager asInterface(IBinder obj)
        {
            throw new RuntimeException("Stub!");
        }
    }

    void forceStopPackage(String packageName, int userId);
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);
    Intent getIntentForIntentSender(IIntentSender sender);

    int startActivityAsUserWithFeature(
            IApplicationThread caller,
            String callingPackage,
            String callingFeatureId,
            Intent intent,
            String resolvedType,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            int flags,
            ProfilerInfo profilerInfo,
            Bundle options,
            int userId);
}
