package me.gm.cleaner.server;

import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.server.IMediaProviderHooksService;

interface ICleanerHooksService {

    void setCleanerServerBinder(in ICleanerServerCallback iinterface) = 1;

    void setMediaProviderBinder(in IMediaProviderHooksService iinterface) = 2;

    void refreshPolicyFromDataBus() = 33;

    long getNativeMountPointsGeneration() = 34;
}
