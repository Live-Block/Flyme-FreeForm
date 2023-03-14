package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.Nullable;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(PendingIntent.class)
public class PendingIntentHidden {
    public int sendAndReturnResult(
            Context context, int code, @Nullable Intent intent,
            @Nullable PendingIntent.OnFinished onFinished, @Nullable Handler handler,
            @Nullable String requiredPermission, @Nullable Bundle options) {
        throw new RuntimeException("Stub!");
    }
}
