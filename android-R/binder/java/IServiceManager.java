//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package android.os;

import android.compat.annotation.UnsupportedAppUsage;

public interface IServiceManager extends IInterface {
    int DUMP_FLAG_PRIORITY_CRITICAL = 1;
    int DUMP_FLAG_PRIORITY_HIGH = 2;
    int DUMP_FLAG_PRIORITY_NORMAL = 4;
    int DUMP_FLAG_PRIORITY_DEFAULT = 8;
    int DUMP_FLAG_PRIORITY_ALL = 15;
    int DUMP_FLAG_PROTO = 16;

    @UnsupportedAppUsage(
        overrideSourcePosition = "frameworks/native/libs/binder/aidl/android/os/IServiceManager.aidl:61:1:61:25"
    )
    IBinder getService(String var1) throws RemoteException;

    @UnsupportedAppUsage(
        overrideSourcePosition = "frameworks/native/libs/binder/aidl/android/os/IServiceManager.aidl:69:1:69:25"
    )
    IBinder checkService(String var1) throws RemoteException;

    void addService(String var1, IBinder var2, boolean var3, int var4) throws RemoteException;

    String[] listServices(int var1) throws RemoteException;

    void registerForNotifications(String var1, IServiceCallback var2) throws RemoteException;

    void unregisterForNotifications(String var1, IServiceCallback var2) throws RemoteException;

    boolean isDeclared(String var1) throws RemoteException;

    void registerClientCallback(String var1, IBinder var2, IClientCallback var3) throws RemoteException;

    void tryUnregisterService(String var1, IBinder var2) throws RemoteException;

    public abstract static class Stub extends Binder implements IServiceManager {
        private static final String DESCRIPTOR = "android.os.IServiceManager";
        static final int TRANSACTION_getService = 1;
        static final int TRANSACTION_checkService = 2;
        static final int TRANSACTION_addService = 3;
        static final int TRANSACTION_listServices = 4;
        static final int TRANSACTION_registerForNotifications = 5;
        static final int TRANSACTION_unregisterForNotifications = 6;
        static final int TRANSACTION_isDeclared = 7;
        static final int TRANSACTION_registerClientCallback = 8;
        static final int TRANSACTION_tryUnregisterService = 9;

        public Stub() {
            this.attachInterface(this, "android.os.IServiceManager");
        }

        public static IServiceManager asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            } else {
                IInterface iin = obj.queryLocalInterface("android.os.IServiceManager");
                return (IServiceManager)(iin != null && iin instanceof IServiceManager ? (IServiceManager)iin : new IServiceManager.Stub.Proxy(obj));
            }
        }

        public IBinder asBinder() {
            return this;
        }

        public static String getDefaultTransactionName(int transactionCode) {
            switch(transactionCode) {
            case 1:
                return "getService";
            case 2:
                return "checkService";
            case 3:
                return "addService";
            case 4:
                return "listServices";
            case 5:
                return "registerForNotifications";
            case 6:
                return "unregisterForNotifications";
            case 7:
                return "isDeclared";
            case 8:
                return "registerClientCallback";
            case 9:
                return "tryUnregisterService";
            default:
                return null;
            }
        }

        public String getTransactionName(int transactionCode) {
            return getDefaultTransactionName(transactionCode);
        }

        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            String descriptor = "android.os.IServiceManager";
            String _arg0;
            IBinder _arg1;
            IServiceCallback _arg1;
            switch(code) {
            case 1:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = this.getService(_arg0);
                reply.writeNoException();
                reply.writeStrongBinder(_arg1);
                return true;
            case 2:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = this.checkService(_arg0);
                reply.writeNoException();
                reply.writeStrongBinder(_arg1);
                return true;
            case 3:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = data.readStrongBinder();
                boolean _arg2 = 0 != data.readInt();
                int _arg3 = data.readInt();
                this.addService(_arg0, _arg1, _arg2, _arg3);
                reply.writeNoException();
                return true;
            case 4:
                data.enforceInterface(descriptor);
                int _arg0 = data.readInt();
                String[] _result = this.listServices(_arg0);
                reply.writeNoException();
                reply.writeStringArray(_result);
                return true;
            case 5:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = android.os.IServiceCallback.Stub.asInterface(data.readStrongBinder());
                this.registerForNotifications(_arg0, _arg1);
                reply.writeNoException();
                return true;
            case 6:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = android.os.IServiceCallback.Stub.asInterface(data.readStrongBinder());
                this.unregisterForNotifications(_arg0, _arg1);
                reply.writeNoException();
                return true;
            case 7:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                boolean _result = this.isDeclared(_arg0);
                reply.writeNoException();
                reply.writeInt(_result ? 1 : 0);
                return true;
            case 8:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = data.readStrongBinder();
                IClientCallback _arg2 = android.os.IClientCallback.Stub.asInterface(data.readStrongBinder());
                this.registerClientCallback(_arg0, _arg1, _arg2);
                reply.writeNoException();
                return true;
            case 9:
                data.enforceInterface(descriptor);
                _arg0 = data.readString();
                _arg1 = data.readStrongBinder();
                this.tryUnregisterService(_arg0, _arg1);
                reply.writeNoException();
                return true;
            case 1598968902:
                reply.writeString(descriptor);
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
            }
        }

        public static boolean setDefaultImpl(IServiceManager impl) {
            if (IServiceManager.Stub.Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            } else if (impl != null) {
                IServiceManager.Stub.Proxy.sDefaultImpl = impl;
                return true;
            } else {
                return false;
            }
        }

        public static IServiceManager getDefaultImpl() {
            return IServiceManager.Stub.Proxy.sDefaultImpl;
        }

        private static class Proxy implements IServiceManager {
            private IBinder mRemote;
            public static IServiceManager sDefaultImpl;

            Proxy(IBinder remote) {
                this.mRemote = remote;
            }

            public IBinder asBinder() {
                return this.mRemote;
            }

            public String getInterfaceDescriptor() {
                return "android.os.IServiceManager";
            }

            public IBinder getService(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                IBinder _result;
                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    boolean _status = this.mRemote.transact(1, _data, _reply, 0);
                    if (!_status && IServiceManager.Stub.getDefaultImpl() != null) {
                        IBinder var6 = IServiceManager.Stub.getDefaultImpl().getService(name);
                        return var6;
                    }

                    _reply.readException();
                    _result = _reply.readStrongBinder();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

                return _result;
            }

            public IBinder checkService(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                IBinder var6;
                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    boolean _status = this.mRemote.transact(2, _data, _reply, 0);
                    if (_status || IServiceManager.Stub.getDefaultImpl() == null) {
                        _reply.readException();
                        IBinder _result = _reply.readStrongBinder();
                        return _result;
                    }

                    var6 = IServiceManager.Stub.getDefaultImpl().checkService(name);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

                return var6;
            }

            public void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    _data.writeStrongBinder(service);
                    _data.writeInt(allowIsolated ? 1 : 0);
                    _data.writeInt(dumpPriority);
                    boolean _status = this.mRemote.transact(3, _data, _reply, 0);
                    if (!_status && IServiceManager.Stub.getDefaultImpl() != null) {
                        IServiceManager.Stub.getDefaultImpl().addService(name, service, allowIsolated, dumpPriority);
                        return;
                    }

                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

            }

            public String[] listServices(int dumpPriority) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                String[] var6;
                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeInt(dumpPriority);
                    boolean _status = this.mRemote.transact(4, _data, _reply, 0);
                    if (_status || IServiceManager.Stub.getDefaultImpl() == null) {
                        _reply.readException();
                        String[] _result = _reply.createStringArray();
                        return _result;
                    }

                    var6 = IServiceManager.Stub.getDefaultImpl().listServices(dumpPriority);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

                return var6;
            }

            public void registerForNotifications(String name, IServiceCallback callback) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    boolean _status = this.mRemote.transact(5, _data, _reply, 0);
                    if (_status || IServiceManager.Stub.getDefaultImpl() == null) {
                        _reply.readException();
                        return;
                    }

                    IServiceManager.Stub.getDefaultImpl().registerForNotifications(name, callback);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

            }

            public void unregisterForNotifications(String name, IServiceCallback callback) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    boolean _status = this.mRemote.transact(6, _data, _reply, 0);
                    if (_status || IServiceManager.Stub.getDefaultImpl() == null) {
                        _reply.readException();
                        return;
                    }

                    IServiceManager.Stub.getDefaultImpl().unregisterForNotifications(name, callback);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

            }

            public boolean isDeclared(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                boolean _result;
                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    boolean _status = this.mRemote.transact(7, _data, _reply, 0);
                    if (!_status && IServiceManager.Stub.getDefaultImpl() != null) {
                        boolean var6 = IServiceManager.Stub.getDefaultImpl().isDeclared(name);
                        return var6;
                    }

                    _reply.readException();
                    _result = 0 != _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

                return _result;
            }

            public void registerClientCallback(String name, IBinder service, IClientCallback callback) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    _data.writeStrongBinder(service);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    boolean _status = this.mRemote.transact(8, _data, _reply, 0);
                    if (!_status && IServiceManager.Stub.getDefaultImpl() != null) {
                        IServiceManager.Stub.getDefaultImpl().registerClientCallback(name, service, callback);
                        return;
                    }

                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

            }

            public void tryUnregisterService(String name, IBinder service) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();

                try {
                    _data.writeInterfaceToken("android.os.IServiceManager");
                    _data.writeString(name);
                    _data.writeStrongBinder(service);
                    boolean _status = this.mRemote.transact(9, _data, _reply, 0);
                    if (_status || IServiceManager.Stub.getDefaultImpl() == null) {
                        _reply.readException();
                        return;
                    }

                    IServiceManager.Stub.getDefaultImpl().tryUnregisterService(name, service);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }

            }
        }
    }

    public static class Default implements IServiceManager {
        public Default() {
        }

        public IBinder getService(String name) throws RemoteException {
            return null;
        }

        public IBinder checkService(String name) throws RemoteException {
            return null;
        }

        public void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) throws RemoteException {
        }

        public String[] listServices(int dumpPriority) throws RemoteException {
            return null;
        }

        public void registerForNotifications(String name, IServiceCallback callback) throws RemoteException {
        }

        public void unregisterForNotifications(String name, IServiceCallback callback) throws RemoteException {
        }

        public boolean isDeclared(String name) throws RemoteException {
            return false;
        }

        public void registerClientCallback(String name, IBinder service, IClientCallback callback) throws RemoteException {
        }

        public void tryUnregisterService(String name, IBinder service) throws RemoteException {
        }

        public IBinder asBinder() {
            return null;
        }
    }
}

