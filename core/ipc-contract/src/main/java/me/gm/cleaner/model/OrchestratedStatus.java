package me.gm.cleaner.model;

import android.os.Parcel;
import android.os.Parcelable;

public class OrchestratedStatus implements Parcelable {
    public String health;
    public LayerStatus vfs;
    public LayerStatus mediaProviderJavaHook;
    public LayerStatus fuseNativeHook;
    public LayerStatus dataBus;
    public LayerStatus controlPlane;

    public static final Creator<OrchestratedStatus> CREATOR = new Creator<>() {
        @Override
        public OrchestratedStatus createFromParcel(Parcel source) {
            return new OrchestratedStatus(source);
        }

        @Override
        public OrchestratedStatus[] newArray(int size) {
            return new OrchestratedStatus[size];
        }
    };

    public OrchestratedStatus() {
    }

    public OrchestratedStatus(String health, LayerStatus vfs, LayerStatus mediaProviderJavaHook,
                              LayerStatus fuseNativeHook, LayerStatus dataBus,
                              LayerStatus controlPlane) {
        this.health = health;
        this.vfs = vfs;
        this.mediaProviderJavaHook = mediaProviderJavaHook;
        this.fuseNativeHook = fuseNativeHook;
        this.dataBus = dataBus;
        this.controlPlane = controlPlane;
    }

    private OrchestratedStatus(Parcel source) {
        health = source.readString();
        vfs = source.readParcelable(LayerStatus.class.getClassLoader());
        mediaProviderJavaHook = source.readParcelable(LayerStatus.class.getClassLoader());
        fuseNativeHook = source.readParcelable(LayerStatus.class.getClassLoader());
        dataBus = source.readParcelable(LayerStatus.class.getClassLoader());
        controlPlane = source.readParcelable(LayerStatus.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(health);
        dest.writeParcelable(vfs, flags);
        dest.writeParcelable(mediaProviderJavaHook, flags);
        dest.writeParcelable(fuseNativeHook, flags);
        dest.writeParcelable(dataBus, flags);
        dest.writeParcelable(controlPlane, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
