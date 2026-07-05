package me.gm.cleaner.server;

import me.gm.cleaner.server.ICleanerServerCallback;

interface IMediaProviderHooksService {

    int getVersion() = 0;

    void setCleanerServerBinder(in ICleanerServerCallback iinterface) = 1;

    void refreshPolicyFromDataBus() = 13;

    long getNativeMountPointsGeneration() = 14;
}
