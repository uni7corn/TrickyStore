package android.system.keystore2;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

public interface IKeystoreOperation extends IInterface {
    String DESCRIPTOR = "android.system.keystore2.IKeystoreOperation";
    int VERSION = 3;
    String HASH = "4f1c704008e5687ed0d6f1590464aed39fc7f64e";

    void updateAad(byte[] aadInput) throws RemoteException;
    byte[] update(byte[] input) throws RemoteException;
    byte[] finish(byte[] input, byte[] signature) throws RemoteException;
    void abort() throws RemoteException;
    int getInterfaceVersion() throws RemoteException;
    String getInterfaceHash() throws RemoteException;

    abstract class Stub extends Binder implements IKeystoreOperation {
        public static final int TRANSACTION_updateAad = IBinder.FIRST_CALL_TRANSACTION + 0;
        public static final int TRANSACTION_update = IBinder.FIRST_CALL_TRANSACTION + 1;
        public static final int TRANSACTION_finish = IBinder.FIRST_CALL_TRANSACTION + 2;
        public static final int TRANSACTION_abort = IBinder.FIRST_CALL_TRANSACTION + 3;

        public static IKeystoreOperation asInterface(IBinder b) {
            throw new RuntimeException();
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
