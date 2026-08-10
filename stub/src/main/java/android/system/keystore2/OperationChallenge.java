package android.system.keystore2;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class OperationChallenge implements Parcelable {
    public long challenge = 0;

    public static final Creator<OperationChallenge> CREATOR = new Creator<OperationChallenge>() {
        @Override
        public OperationChallenge createFromParcel(Parcel in) {
            throw new RuntimeException();
        }

        @Override
        public OperationChallenge[] newArray(int size) {
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

