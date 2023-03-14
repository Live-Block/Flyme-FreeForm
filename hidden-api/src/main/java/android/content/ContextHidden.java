package android.content;

import android.os.UserHandle;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(Context.class)
public class ContextHidden {

    public UserHandle getUser() {
        throw new RuntimeException("Stub!");
    }

    public int getUserId() {
        throw new RuntimeException("Stub!");
    }
}
