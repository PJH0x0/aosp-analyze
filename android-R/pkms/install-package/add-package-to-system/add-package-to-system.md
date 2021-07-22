# 应用安装源码阅读指南（下）

> 应用安装源码阅读指南（上）是从PackageInstaller到PackageInstallerService，主要的作用是拷贝apk以及处理安装的参数，本篇则会讲述应用是如何安装到系统中

## UML图

![](./uml/install-package-sequence.png)

## installStage()

1. 将传入的`ActiveInstallSession`转为`InstallParams`
2. 发送一个`INIT_COPY`消息给安装线程，因为安装应用也是涉及到IO操作，这一步最后是调用到`HandlerParams.startCopy()`，这里有个小注意的点是`InstallParams extends HandlerParams`

## HandlerParams.startCopy()

调用了两个抽象方法，`handleStartCopy()`和`handleReturnCode()`，因为InstallParams是继承自HandlerParams，所以是分别调用到`InstallParams.handleStartCopy()`和`InstallParams.handleReturnCode()`

## InstallParams.handleStartCopy()

1. 调用`PackageManagerServiceUtils.getMinimalPackageInfo`获取PackageLiteInfo，主要是manifest下的一些标签
2. 如果存储空间不足，则尝试释放一些缓存尝试安装
3. 判断`origin.existing`，如果不存在则需要调用`sendIntegrityVerificationRequest()`和`sendPackageVerificationRequest()`给应用商店验证是否有异常信息，这里基本都是要检测的，除非是系统内部移动package
4. 判断是否是回滚版本，如果是要回滚版本则需要发送一个广播，这个广播用来干嘛的暂时也是未知

## InstallParams.handleReturnCode()

1. 这里有三个条件同时满足`mVerificationCompleted，mVerificationCompleted，mEnableRollbackCompleted`才可以进入安装，所以一开始是不会进入，一般调用栈是从`handleVerificationFinished()`或者是`handleIntegrityVerificationFinished()`
2. 调用`FileInstallArgs.copyApk()`这步会判断有没有之前有没有创建临时文件夹，如果没有就需要将apk拷贝到data/app目录
3. 调用`processPendingInstall()`进入下一个阶段

## processPendingInstall()

直接调用`processInstallRequestsAsync()`

## processInstallRequestsAsync()

1. 判断`success`，如果不成功则不会进行安装
2. 依次调用`FileInstallArgs.doPreInstall()`，`installPackagesTracedLI()`，`FileInstallArgs.doPostInstall()`，`FileInstallArgs.doPreInstall()`和`FileInstallArgs.doPostInstall()`并没有做什么工作，最主要的安装工作还是交给了`installPackagesTracedLI()`
3. 调用`restoreAndPostInstall()`将安装结果发送给监听者

## installPackagesTracedLI()

直接调用`installPackagesLI()`

## installPackagesLI()

这个方法是安装最主要的部分，分为主要三个阶段，准备，扫描，和提交。

1. 准备阶段就是解析Package，然后生成对应的数据结构
2. 扫描阶段是生成Package在系统中的属性，例如：uid，code位置，确定真正的包名，确定sharedUserId等等
3. 提交阶段主要就是申请权限，以及将属性缓存在xml中，方便下次开机的时候获取

接下来看一下具体的流程

1. 参数，可以看到一开始就建立了很多Map，其实这里无需关注，只有当我们要批量安装加了--multipackage参数的时候才可能会出现，这里可以假设InstallRequest列表中就一个
2. 调用`preparePackageLI()`获取解析之后的PrepareResult
3. 调用`scanPackageTracedLI()`获取扫描之后的结果ScanResult
4. 调用`reconcilePackagesLocked()`做一些一致性的处理
5. 调用`commitPackagesLocked()`将属性进行缓存到xml并申请权限
6. 调用`executePostCommitSteps()`创建app的目录以及做dex的优化工作

## preparePackageLI()

1. 确定scanFlags和parseFlags
2. 调用`PackageParser2.parsePackage()`进行扫描apk
3. 调用`ParsingPackageUtils.getSigningDetails()`获取apk的签名，当然，如果是安装应用会在之前PackageInstallerSession中获取签名
4. 判断是否存在已安装应用，如果有，则需要进行签名验证或者更新签名，更新签名需要老的package包含，首先是检测之前安装的应用是否匹配，另外还要检测sharedUserId的应用是否匹配签名，不过不知道为何要在这校验签名，因为之后在`reconcilePackagesLocked()`也会进行校验
5. 处理定义的权限，具体处理的逻辑可以不看，也不大能看懂，主要看抛出的异常是什么，基本可以确定是对重复定义的权限以及覆盖系统权限的一些权限进行检查
6. 判断`FileInstallArgs.move`，然后设置应用abi(application binary interface)，abi主要指的是二进制文件执行的架构，x86,arm这些或者32位64位系统
7. 调用`FileInstallArgs.doRename()`，这里才是真正生成应用目录的地方，在Android11中应该是类似的目录结构`/data/app/~~JfQyWVYLzBkOPnXEZp9YhQ==/com.example.anrdemo-lIG1Gajd9XweYWfdp7eYrw==/base.apk`
8. 调用`freezePackageForInstall()`停止应用，或者使用`DELETE_DONT_KILL_APP`可以不杀死进程
9. 整理`PrepareResult`的属性

## scanPackageTracedLI()

直接调用`scanPackageNewLI()`方法，`scanPackageNewLI()`方法具体见[Android 开机应用扫描指南](https://juejin.cn/post/6963828909460684830#heading-6)

## optimisticallyRegisterAppId()

使用这个方法为应用申请uid，之后一路调用到`acquireAndRegisterNewAppIdLPw()`，所有的uid都放在一个mAppIds的ArrayList下面，分配的规则如下：

1. 看一下mAppIds中有没有没被占用的，可以尽量的让空间紧凑，因为安装应用最大也就10000个
2. 如果都占满了，新增一个

## reconcilePackagesLocked()

