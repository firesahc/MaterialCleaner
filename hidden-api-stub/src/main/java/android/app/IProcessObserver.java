package android.app;

import android.os.Binder;
import android.os.RemoteException;

public interface IProcessObserver {

    void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException;

    void onProcessDied(int pid, int uid) throws RemoteException;

    // no longer exists from API 26
    void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException;

    // from Q beta 3
    void onForegroundServicesChanged(int pid, int uid, int serviceTypes) throws RemoteException;

    // from Android 14 r50 (API 35), dispatched by system_server from API 36.
    // Missing override on the stub receiver causes AbstractMethodError on API 36.
    // Param names follow AOSP IProcessObserver.aidl: processUid is the uid used for lookup.
    void onProcessStarted(int pid, int processUid, int packageUid, String packageName, String processName) throws RemoteException;

    abstract class Stub extends Binder implements IProcessObserver {

    }
}
