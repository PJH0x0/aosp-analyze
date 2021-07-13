# 应用安装源码阅读指南（下）

> 应用安装源码阅读指南（上）是从PackageInstaller到PackageInstallerService，主要的作用是拷贝apk以及处理安装的参数，本篇则会讲述应用是如何安装到系统中

## UML图

![](./uml/install-package-sequence.png)

## PackageManagerService.installStage()

1. 将传入的`ActiveInstallSession`转为`InstallParams`
2. 发送一个`INIT_COPY`消息给安装线程，因为安装应用也是涉及到IO操作，这一步最后是调用到`HandlerParams.startCopy()`，这里有个小注意的点是`InstallParams extends HandlerParams`

## HandlerParams.startCopy()

调用了两个抽象方法，`handleStartCopy()`和`handleReturnCode()`，因为InstallParams是继承自HandlerParams，所以是分别调用到`InstallParams.handleStartCopy()`和`InstallParams.handleReturnCode()`

## InstallParams.handleStartCopy()

1. 调用`PackageManagerServiceUtils.getMinimalPackageInfo`获取PackageLiteInfo，主要是manifest下的一些标签
2. 如果存储空间不足，则尝试释放一些缓存尝试安装
3. 确定