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

    // added on Android 15/16 (API 35/36); required to dispatch process-start events.
    // Missing override on the stub receiver causes AbstractMethodError on API 36.
    void onProcessStarted(int pid, int uid, int processType, String hostingType, String hostingName) throws RemoteException;

    abstract class Stub extends Binder implements IProcessObserver {

    }
}
