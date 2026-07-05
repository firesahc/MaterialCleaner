package me.gm.cleaner.model;

import android.os.Parcel;
import android.os.Parcelable;

public class LayerStatus implements Parcelable {
    public String id;
    public String state;
    public long generation;
    public long lastStartedAt;
    public long lastHeartbeatAt;
    public long lastErrorAt;
    public String lastError;
    public String[] metricKeys;
    public String[] metricValues;

    public static final Creator<LayerStatus> CREATOR = new Creator<>() {
        @Override
        public LayerStatus createFromParcel(Parcel source) {
            return new LayerStatus(source);
        }

        @Override
        public LayerStatus[] newArray(int size) {
            return new LayerStatus[size];
        }
    };

    public LayerStatus() {
        metricKeys = new String[0];
        metricValues = new String[0];
    }

    public LayerStatus(String id, String state, long generation, long lastStartedAt,
                       long lastHeartbeatAt, long lastErrorAt, String lastError,
                       String[] metricKeys, String[] metricValues) {
        this.id = id;
        this.state = state;
        this.generation = generation;
        this.lastStartedAt = lastStartedAt;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.lastErrorAt = lastErrorAt;
        this.lastError = lastError;
        this.metricKeys = metricKeys != null ? metricKeys : new String[0];
        this.metricValues = metricValues != null ? metricValues : new String[0];
    }

    private LayerStatus(Parcel source) {
        id = source.readString();
        state = source.readString();
        generation = source.readLong();
        lastStartedAt = source.readLong();
        lastHeartbeatAt = source.readLong();
        lastErrorAt = source.readLong();
        lastError = source.readString();
        metricKeys = source.createStringArray();
        metricValues = source.createStringArray();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(state);
        dest.writeLong(generation);
        dest.writeLong(lastStartedAt);
        dest.writeLong(lastHeartbeatAt);
        dest.writeLong(lastErrorAt);
        dest.writeString(lastError);
        dest.writeStringArray(metricKeys);
        dest.writeStringArray(metricValues);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
