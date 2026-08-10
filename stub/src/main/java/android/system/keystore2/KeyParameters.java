package android.system.keystore2;

import android.hardware.security.keymint.KeyParameter;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class KeyParameters implements Parcelable {
    public KeyParameter[] keyParameter;

    public static final Creator<KeyParameters> CREATOR = new Creator<KeyParameters>() {
        @Override
        public KeyParameters createFromParcel(Parcel in) {
            throw new RuntimeException();
        }

        @Override
        public KeyParameters[] newArray(int size) {
            throw new RuntimeException();
        }
    };

    @Override
    public int describeContents() {
        throw new RuntimeException();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        throw new RuntimeException();
    }
}

