# 应用安装源码阅读指南（上）

> 前一篇[Android 开机应用扫描指南](https://juejin.cn/post/6963828909460684830)其实专注的是系统应用的安装，有一些安装的细节忽略掉了。而应用安装的部分则要更加复杂一些，由于应用安装的源码过程很长，所以分为三个部分，第一部分是从安装界面到安装服务，第二部分则是说一下AppBundle相关的逻辑，第三部分则集中在PackageManagerService中

## IntallInstalling.onCreate()

当用户点击文件夹中apk进行安装时(前提是文件管理应用已获取到安装应用的权限)会经过三个界面，分别是PackageInstallerActivity(是否确认安装)、InstallInstalling(安装进度条)、InstallSuccess(显示完成、打开)，我们着重看一下InstallInstalling的逻辑，这部分才是和安装服务有交互的

在`onCreate()`中，最重要的逻辑便是创建Session，安装服务安装应用是以Session为单位的，这样做的目的主要就是为了AppBundle服务，可以一次性安装多个apk，接下来看一下onCreate()的逻辑

1. 调用`Intent.getData()`获取应用的URI，一般都是文件管理器或者应用商店传递过来的
2. 判断scheme是否为`package`，如果是一般文件管理器传递过来的scheme都是file类型，所以这里猜测应该是为了**在某种情况下中断了安装而设计的断点安装方案**，这里一般认为是false
3. 创建安装进度条的dialog
4. 初始化创建Session的参数`SessionParams`，看一下有哪些参数，列举都是下面会用到的参数
    1. `mode = MODE_FULL_INSTALL`
    2. `installFlags = PackageManager.INSTALL_FULL_APP`
    3. `installReason = INSTALL_REASON_USER`
    4. `installLocation`由apk指定，默认是安装在应用内部，installLocation只是指定的模式，并不是可以指定路径

