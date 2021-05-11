# Binder源码阅读指南

1. 如何理解Binder传输的本质
2. 为什么可以像调用接口一样调用Binder
3. 为什么需要Parcel以及Parcelable
4. Binder调用的过程

> 阅读本文所需知识点：
>
> 1. 进程隔离的概念，需要了解为什么需要IPC
> 2. 什么是用户态和内核态，需要了解IPC实现的机制以及为什么IPC需要内存拷贝
> 3. Java的对象的本质，因为会遇到JNI创建Java对象的情况
>
> 对于1，2两点不清楚的可以阅读《OS: Three easy pieces》，是我看来最好的操作系统原理入门书籍，它厚重的原因是对于概念的解释很详细，专注于虚拟化，并发，文件系统三个方面，而不是大而全
>
> 对于第三点不清楚的可以看一下《深入理解Java虚拟机》这本书，但这一点并非强制要求，在看代码的过程中可以跳过

## Binder的使用

这里以gityuan的代码作为示例

```java
public class ClientDemo {
	public static void main(String[] args) throws RemoteException {
		System.out.println("Client start");
		IBinder binder = ServiceManager.getService("MyService"); //获取名为"MyService"的服务
		IMyService myService = new MyServiceProxy(binder); //创建MyServiceProxy对象
		myService.sayHello("binder"); //通过MyServiceProxy对象调用接口的方法
		System.out.println("Client end");
	}
}
public class ServerDemo {
	public static void main(String[] args) {
		System.out.println("MyService Start");
		Looper.prepareMainLooper(); //开启循环执行
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND); //设置为前台优先级
		ServiceManager.addService("MyService", new MyService());//注册服务
		Looper.loop();
	}
}
```

## 获取ServiceManager

在`ServerDemo`中可以看到在获取服务器之前，先要想ServiceManager添加服务`ServiceManager.addService()`，既然有添加，那必定有一个地方去存储它，存储的地方就是ServiceManager，那么要怎么获取到ServiceManager呢？先从`ServiceManager.addService()`开始，在开始之前先来看一下获取ServiceManager的UML图

### 类图

![get-servicemanager-class](./get-servicemanager-class.png)

有几个特殊的类要解释一下

1. BinderProxy，**native binder的存储类**，里面存储的是native binder的指针，并且不是由Java类初始化，是在JNI代码中加载和创建
2. IServiceManager.Stub，这个是个抽象类，没有实现类，在**平常使用Binder的时候一般都是继承它作为Binder实体类，但是IServiceManager是工作在native中的服务，所以Stub类不会被继承**，这种方式也常见于获取C++ Binder服务的时候





### ServiceManager.addService()

最终调用到`addService(String name, IBinder service, boolean allowIsolated, int dumpPriority)`，它里面的代码就一行

```java
getIServiceManager().addService(name, service, allowIsolated, dumpPriority)
```

看到正主`getIServiceManager()`了

### ServiceManager.getIServiceManager()

```java
private static IServiceManager getIServiceManager() {
    if (sServiceManager != null) {
        return sServiceManager;
    }

    // Find the service manager
    sServiceManager = ServiceManagerNative
        .asInterface(Binder.allowBlocking(BinderInternal.getContextObject()));
    return sServiceManager;
}
```

这里使用的是单例模式，没有加线程保护，因为这个接口并不给应用使用，以及应用也不能直接操作`addService()``getService()`等接口，所以`getIServiceManager()`可以是认为运行在主线程中，**没错，我们与ServiceManager的通信也是采用Binder，只是ServiceManager的Binder是有点特殊**，我们先看`ServiceManagerNative.asInterface()`这个方法

### ServiceManagerNative.asInterface()

```java
public static IServiceManager asInterface(IBinder obj) {
    if (obj == null) {
        return null;
    }

    // ServiceManager is never local
    return new ServiceManagerProxy(obj);
}
```

直接创建了一个`ServiceManagerProxy`对象

### ServiceManagerProxy初始化

```java
public ServiceManagerProxy(IBinder remote) {
    mRemote = remote;
    mServiceManager = IServiceManager.Stub.asInterface(remote);
}
```

1. `mRemote = remote;`这个是老的方式，gityuan的[Binder系列7-framework层分析](http://gityuan.com/2015/11/21/binder-framework/#34-smpaddservice)，就是使用这种方
2. `mServiceManager = IServiceManager.Stub.asInterface(remote);`是使用的新的，通过AIDL方式进行通信，显得更加简洁一点

这里说明一点**AIDL并不等于Binder通信，它只是让Binder通信变得更加简单，就如同Retrofit和okhttp的关系**，AIDL生成的java代码在out目录下，所以想要分析的话得要先编译过Android源码才行，具体的路径是`out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/javac/shard30/classes/android/os/IServiceManager.class`使用AndroidStudio，IDEA或者反编译工具可以打开查看，如果想直接看的话我把它拷贝了一份[IServiceManager.java](https://github.com/TeenagerPeng/aosp-analyze/blob/main/android-R/binder/java/IServiceManager.java)

### IServiceManager.Stub.asInterface()

```java
public static IServiceManager asInterface(IBinder obj) {
    if (obj == null) {
        return null;
    } else {
        IInterface iin = obj.queryLocalInterface("android.os.IServiceManager");
        return (IServiceManager)(iin != null && iin instanceof IServiceManager ? (IServiceManager)iin : new IServiceManager.Stub.Proxy(obj));
    }
}
```

逻辑很简单

1. 如果是相同进程，直接返回Binder对象，由于ServiceManager是单独处于一个进程，这里不会是相同进程，至于本地Service是怎么连接到的，我们稍后再讨论
2. 如果不是相同进程，则创建`IServiceManager.Stub.Proxy`

### IServiceManager.Stub.Proxy初始化

```java
Proxy(IBinder remote) {
    this.mRemote = remote;
}
```

这个和ServiceManagerProxy老的初始化方式是不是一模一样，非常的像，再来看看`IServiceManager.Stub.Proxy.addService()`

```java
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
```

和老方式也基本一样，所以新方式只是自动生成了这部分代码，减少了代码量，让IPC看起来就是一个方法调用，但最终都是调用`IBinder.transact()`方法进行IPC，接下来回到`ServiceManager.getIServiceManager()`方法中，上面的只能算作是一些代码技巧而已，接下来就是Binder的核心，获取ServiceManager的`IBinder`对象。

### BinderInternal.getContextObject()

这是一个native方法，对应到`android_util_Binder.cpp`的`android_os_BinderInternal_getContextObject`

### android_os_BinderInternal_getContextObject()

这里做了两件事情：

1. 通过调用`ProcessState.getContextObject(NULL)`获取sp\<IBinder>，注意这里传递的参数NULL，即要获取的是IServiceManager的Binder，这一步留作native Binder解析流程中详细阐述
2. 调用`javaObjectForIBinder()`将IBinder转为java对象，即BinderProxy对象，转换的过程就是将IBinder的指针(long类型)存储在BinderProxy的mNativeData中

### javaObjectForIBinder()

1. 检查是不是JavaBBinder，这一步的目的还没弄清楚
2. 创建BinderProxyNativeData类型的指针
3. 将其IBinder的智能指针引用放到BinderProxyNativeData.mObject当中
4. 通过`CallStaticObjectMethod()`调用`BinderProxy.getInstance()`并传递对应的参数，这里又引出了一个问题，**我们都知道调用一个类的静态方法是会触发类的加载，可以看到这里直接传的是`gBinderProxyOffsets.mClass`，说明BinderProxy.class对象已经加载完成了，那么这个`gBinderProxyOffsets`又是怎么初始化的呢？**答案是在虚拟机启动的时候就加载完成了，具体的调用栈大致如下``
5. 下面的代码没有看懂，但是关系不是很大