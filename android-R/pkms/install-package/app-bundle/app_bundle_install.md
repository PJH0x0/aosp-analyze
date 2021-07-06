# App Bundle应用安装

> 本篇文章只是作为指南引导去看PkMS，不会贴很多代码分析，要对着源码进行看，更多是基于方法分析实现的逻辑，另外就是代码是基于Android 11，与Android 10之前代码有比较大的差别。

## 本文内容

1. 为什么要用AppBundle
2. AppBundle的使用
3. AppBundle的结构以及如何被安装到应用当中

## 为什么要使用AppBundle

原因很简单----**APK瘦身**，如果是应用开发者，应该是对于apk瘦身有相当多的痛苦之处，而AppBundle就是系统对apk瘦身的一种方式。那么AppBundle对apk是怎么瘦身的呢？原理是移除不必要的资源。如何移除？根据系统的不可变的configuration，什么是configuration？configuration就是手机或系统的一种属性，最常见的就是横竖屏，另外还有虚拟键盘，屏幕分辨率等。

configuration又分为可变与不可变的，我自己区分的，例如：虚拟键盘，横竖屏，mccmnc(SIM卡标志)就属于可变的configuration；不可变的configuration又包括如：屏幕分辨率，支持的系统语言，32位或64位系统等，这些系统固有属性就是不可变的。如果对于这些系统固有的属性进行区分，将原本的apk的资源分开存储到服务器中，安装应用的时候只导入所需要的资源，这样就可以节省很多的空间，举个例子：为了兼容Android的碎片化过程，对于不同分辨率的应用我们需要设立mdpi,hdpi,xhdpi,xxhdpi的各种资源都要设立一遍，虽然有svg格式的图片，但是复杂的图像还是需要jpeg或者png格式的图片，如果每种分辨率都加上的话，那么apk无疑会非常大，但是应用商店下发应用的时候将apk提前获取到了当前手机的分辨率，并发送给后端服务器，服务器再根据传过来的信息下发对应的apk，就会将apk的大小大大缩减。

## AppBundle的使用

所需要的工具：

1. Android Studio，确保Android Studio版本大于3.2，且gradle版本不小于3.2.1
2. bundletool，用来格式转换，需要到github上[下载](https://github.com/google/bundletool/releases)

### 生成aab格式

通过Build->Generate Signed Bundle/Apk->Android App Bundle，然后设置签名，以及要build的版本，等待一会就可以生成一个xxx.aab文件，它是一个压缩包，但还不能直接安装，要转为apk形式

### 用bundletool转为apks压缩包

bundletool的[官网使用方式](https://developer.android.google.cn/studio/command-line/bundletool)，这里官网有个错误就是下载下来的bundletool并不是一个脚本，而是一个jar包，所以并不能使用`bundletool`命令，而是要使用`java -jar bundletool_version.jar`，至于后面的参数则都是相同的，使用之后则可以得到一个新的压缩包，打开之后有一个splits目录，则是所有分开的apk，这里后面称之为split apk，这里面只有两类apk

1. base.apk，这种apk的名字可能叫做base-master.apk，无所谓，重要的是它没有split标志，并且有dex文件
2. split.apk，这种apk的名字就会根据具体的configuration来区分，例如:base-zh.apk说明它是中文语言的资源apk，base-xxhdpi.apk，则说明它是xxhdpi资源的apk，这类apk最大的特点就是它们没有dex文件，并且在manifest中有唯一的split属性

### adb 安装split apk

解压出这两类apk之后，就可以通过adb安装，具体的命令如下

```shel
adb push xxx.apk /data/local/tmp/ # 这步要将所有的apk都push到这个目录下
adb shell pm install-create # 这一步可以得到一个sessionId，接下来所有的操作都要用到这个sessionId
adb shell pm install-write {sessionId} base-zh.apk /data/local/tmp/base-zh.apk
adb shell pm install-commit {sessionId}
```

下面有一个完整的例子

```shell
adb shell pm install-create
# Success：created install session [1237281889]
adb shell pm install-write  1237281889 base.apk /data/local/tmp/FeatureSplitBase.apk
adb shell pm install-write  1237281889 base1.apk /data/local/tmp/FeatureSplit1.apk
adb shell pm install-write  1237281889 base2.apk /data/local/tmp/FeatureSplit2.apk
adb shell pm install-commit 1237281889
```

当然我也写了一个脚本，可以安装某个目录下的split apk，可以通过这个地方[下载](https://github.com/TeenagerPeng/aosp-analyze/blob/main/android-R/pkms/install-package/app-bundle/app_bundle_install.sh)，使用方式是`./app_bundle_install.sh 目录`，后面接上需要安装的apk所在的目录即可，注意：**它会安装目录里面所有的apk，不要弄混了，否则可能安装不成功**

## Split apk结构与安装过程

Split apk的结构很简单，通常是由一个base apk以及一个或多个split apk组成，base.apk没有split属性，而split apk则有split属性，这是唯一区分base和split的方式，接下来看一下安装过程，从`adb shell pm install-create`开始

### adb shell pm install-create

这一步对应的就是install-package中的Session创建，`adb shell pm`最终都是发送给了`PackageManagerShellCommand.onCommand()`方法中处理，大致的流程是`adb ->binder->PackageManagerShellCommand`，对于adb不是特别熟悉，这边不再仔细研究了。

#### PackageManagerShellCommand.runInstallCreate()

1. 获取输出到adb shell的输出流，这个输出流可以通过adb直接显示控制台中
2. 调用`makeInstallParams()`创建SessionParams，
3. 调用`doCreateSession()`创建Session
4. 将sessionId输出到控制台中

#### PackageManagerShellCommand.makeInstallParams()

1. 创建SessionParams对象
2. 创建InstallParams对象
3. 根据`adb shell pm install-create`的参数指定SessionParams的flag，例如:`adb shell pm install-create -R`可以覆盖安装已存在的应用
4. 返回InstallParams对象

#### PackageManagerShellCommand.doCreateSession()

1. 判断是为谁安装应用，默认是给所有用户安装
2. 通过PkMS获取PackageInstallerService并调用其createSession()，详情见
3. 返回sessionId

### adb shell pm install-write

这一步对应的是安装应用中Session操作，

#### PackageManagerShellCommand.runInstallWrite()

1. 获取shell命令的后续参数，包括文件大小(这个参数最好不要乱用)，sessionId，安装到系统(data/data/app)下的文件名(别乱填，要是.apk后缀)，路径(最好是/data/local/tmp下的路径，因为拥有者必须要是shell和system权限都可以访问)
2. 调用`doWriteSplit()`进行拷贝apk

#### PackageManagerShellCommand.doWriteSplit()

1. 打开之前创建的session，这里有个注意的点是每调用一次`adb shell pm install-write`都会调用`session.open()`方法，所以为了避免多次创建stageDir，所以这边有个`mPrepared`属性，**详情见**
2. 重新封装了一个PackageInstaller.Session，但是看起来没有必要
3. 判断是不是流安装，这里的流应该是指`adb install`这种方式，很显然这里并非这种方式安装
4. 调用`openFileForSystem()`打开文件并获取文件描述符
5. 调用`PackageInstaller.Session.write()`,**详情见**，注意这里的`inComingFD`就是打开的`/data/local/tmp`的apk文件

### adb shell pm install-commit

#### PackageManagerShellCommand.runInstallCommit()

1. 获取sessionId参数
2. 调用`doCommitSession()`发送安装请求
3. 调用`doWaitForStagedSessionReady()`处理一些错误和等待session完成

#### PackageManagerShellCommand.doCommitSession()

1. 调用`PackageInstallerService.openSession()`以获取session
2. 判断.dm和.apk的名字是否匹配，这里.dm是dex metadata的意思，但是具体是干嘛的还不知道，感觉上是不是和插件化有关
3. 创建LocalIntenReceiver，用于获取安装的结果
4. 调用`PackageInstallerSession.commit()`进行提交，详情见
5. 调用`LocalIntenReceiver.getResult()`获取安装结果，注意，这里是一个阻塞队列，会阻塞至应用安装完成
6. 根据结果打印安装成功，或者安装错误的原因

# 总结

adb安装应用和点击安装应用是一样的，但是adb安装的可以使用各种flag进行调试，另外就是app bundle，app bundle安装的原理就是分为资源apk和代码apk，资源apk按需下发安装，代码apk一定要安装，安装过程其实是和普通apk相类似，只是parse apk的时候有所区别，一般的apk只需parse base.apk即可，而app bundle则需要parse所有的apk并将属性整合



