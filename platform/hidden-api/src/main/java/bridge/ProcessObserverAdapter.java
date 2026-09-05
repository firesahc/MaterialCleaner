package hidden;

import android.app.IProcessObserver;
import android.os.RemoteException;

public class ProcessObserverAdapter extends IProcessObserver.Stub {

    @Override
    public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
    }

    @Override
    public void onProcessDied(int pid, int uid) throws RemoteException {
    }

    @Override
    public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
        // no longer exists from API 26
    }

    @Override
    public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) throws RemoteException {
        // from Q beta 3
    }

    // from Android 14 r50 (API 35), dispatched from API 36: empty default keeps stub
    // receivers crash-free when system_server dispatches the new process-start callback.
    @Override
    public void onProcessStarted(int pid, int processUid, int packageUid, String packageName, String processName) throws RemoteException {
    }
}
