package me.gm.cleaner.server;

import me.gm.cleaner.server.ICleanerServerCallback;
import me.gm.cleaner.server.IMediaProviderHooksService;

interface ICleanerHooksService {

    void setCleanerServerBinder(in ICleanerServerCallback iinterface) = 1;

    void setMediaProviderBinder(in IMediaProviderHooksService iinterface) = 2;

    void setReadOnlyPaths(in Map<String, List> packageNameToReadOnlyPaths) = 30;

    void setMountPoint(in List<String> value) = 31;

    void setRecordExternalAppSpecificStorage(boolean value) = 32;
}
