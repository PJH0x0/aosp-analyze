# Android 开机应用扫描指南

> 本篇文章只是作为指南引导去看PkMS，不会贴大段代码进行分析，更多是基于方法分析实现的逻辑，另外就是代码是基于Android 11，与Android 10之前代码有比较大的差别。

## 本文的内容

1. PkMS是怎么知道apk的位置
2. 系统应用和普通应用的区别
3. 应用扫描的过程以及应用信息的保存

## PkMS怎么知道apk的位置

答案是按照路径，对于手机用户安装应用都是放在`/data/app`，对于系统应用则是分布各个分区中，可以简单的认为是目录,`/data`就是data分区，以下的分区都会被扫描，并且只会扫描priv-app和app目录：

1. system分区下的priv-app，app目录`/system/priv-app`, `/system/app`
2. product分区
3. odm分区
4. oem分区
5. vendor分区
6. system_ext分区

不过这些分区都执行不是特别严格，没涉及到gms测试的基本都不怎么管，那么apk是存在什么地方呢？例如：Settings就放在`/system_ext/priv-app/Settings/Settings.apk`，其他分区都是类似的，下面分析应用扫描过程还会说到。这里解释一下，为什么要分priv-app和app两个目录进行扫描? priv-app意思是这个目录里面都是privileged应用，可以获取privilege权限，权限的定义`frameworks/base/core/res/AndroidManifest.xml`中找到

## 系统应用和普通应用的区别

答案还是路径，data分区内的应用是普通应用，其他分区安装的应用都是系统应用，**系统应用均不可卸载**

## 应用扫描过程

### scanDirLI()

scanDirLI的几个参数说明一下，

1. scanDir，应用目录的集合，以上面Settings为例，目录是`/system_ext/priv-app`
2. scanFlags，扫描的标志，当为系统应用时，会加上`SCAN_AS_SYSTEM`标志，如果是priv-app目录还要加上`SCAN_AS_PRIVILGED`
3. packageParser，这个就是解析AndroidManifest.xml主要工具类
4. executorService，生产者线程

scanDirLI的逻辑比较简单，

1. 以上面`/system_ext/priv-app`为例，遍历目录下的每个目录和apk
2. 使用ExecutorService(线程池)对所有目录和apk进行解析，详细看[PkMS解析package指南](https://juejin.cn/post/6941298492174729224)
3. 获取ParsedPackage并进行调用`addForInitLI()`

### addForInitLI()

逻辑相当的复杂，考虑情况也要很多，**有些自己也没搞懂，只挑能说的说一下**

1. 首先重命名包名的逻辑，在PkMS当中所有的package都是按照包名作为唯一的主键值，那么就可能出现应用需要更换包名的情况，PkMS给出解决方案是加上`<original-package android:name="com.android.oldpackagename" />`指明需要覆盖安装的包名，但是这样就需要考虑到诸多情况了

    1. 包名修改多次怎么办
    2. 之前有安装过老包名的应用的处理情况
    3. 之前未安装过老包名的应用的处理情况
    4. 之前安装过当前包名的应用的处理情况

    那么PkMS是怎么做的呢？ 

    1. 设计了一个`originalPackages`表示之前原始package，当遇到`original-package`时，就将包名加入到originalPackages当中，代码如下

        ```java
        //ParsingPackageUtils.parseOriginalPackage()
        String orig = sa.getNonConfigurationString(
            R.styleable.AndroidManifestOriginalPackage_name,
            0);
        if (!pkg.getPackageName().equals(orig)) {
            if (pkg.getOriginalPackages().isEmpty()) {
                pkg.setRealPackage(pkg.getPackageName());
            }
            pkg.addOriginalPackage(orig);
        }
        
        ```

    2. 然后在`addForInitLI()`中根据realPkgName获取originalPkgSetting
    3. 接下来判断是否要覆盖安装应用

    不过我感觉里面还是有些bug，似乎只是为了用gms应用替换AOSP里面的应用(如：PackageInstaller和PermissionController)专门设计的，并不具备普遍性，安装的应用及时加了标签也还是会被认为是两个应用

2. 对于覆盖安装系统应用的data区应用进行处理，什么是覆盖安装系统应用？即系统应用有更新，可以通过应用商店下载覆盖安装，但是并不会去卸载系统应用，而是disable系统应用，例如:Google的gms应用都是如此

    1. 如果安装的应用出现问题了，重新enable被disabled应用
    2. 如果是系统更新的应用，则需要扫描disabled系统应用，因为可能会有OTA升级导致系统应用更新apk的情况，调用`scanPackageOnlyLI()`，进行扫描，后面还会分析
    3. 接下来是根据disabled系统应用和安装应用的版本号，判断是否需要重新使用系统应用

    

3. 处理签名，对于安装的应用，必须要签名

    1. 确认是否需要重新签名，对于非系统应用来说，无需重新收集，这部分AOSP压根没写，注释里面的第二条还没实现

    ```java
    // Verify certificates against what was last scanned. Force re-collecting certificate in two
    // special cases:
    // 1) when scanning system, force re-collect only if system is upgrading.
    // 2) when scannning /data, force re-collect only if the app is privileged (updated from
    // preinstall, or treated as privileged, e.g. due to shared user ID).
    final boolean forceCollect = scanSystemPartition ? mIsUpgrade
        : PackageManagerServiceUtils.isApkVerificationForced(pkgSetting);
    ```

    2. 是否跳过签名验证，这个是覆盖安装时候使用

    ```java
    // Full APK verification can be skipped during certificate collection, only if the file is
    // in verified partition, or can be verified on access (when apk verity is enabled). In both
    // cases, only data in Signing Block is verified instead of the whole file.
    // TODO(b/136132412): skip for Incremental installation
    final boolean skipVerify = scanSystemPartition
        || (forceCollect && canSkipForcedPackageVerification(parsedPackage));
    collectCertificatesLI(pkgSetting, parsedPackage, forceCollect, skipVerify);
    
    ```

    

4. 还要处理一下第三步的反方向，即一开始有个安装的应用(data分区)，而后OTA升级有添加了该系统应用(system或其他分区)，分为三种情况进行讨论

    1. 签名不相同，则卸载data分区的应用
    2. 如果系统应用的版本号更高，则移除data分区的应用，但保留应用的数据
    3. 如果系统应用的版本号更低，则将`shouldHideSystemApp`设置为true，也就是将在下面disable系统应用

5. 调用`scanPackageNewLI()`继续扫描

6. 调用`reconcilePackagesLocked()`进行一致化处理

7. 调用`commitReconciledScanResultLocked()`

### scanPackageNewLI()

需要注意的是**这个方法不仅仅是开机扫描的时候用，在安装应用的时候也是执行的**

1. 再次处理重命名包名，这个应该和安装有关系，我测试的时候是覆盖安装系统应用有效，普通应用无法生效，会被认为是两个应用
2. 调用`adjustFlags()`重新调整`scanFlags`，分为以下几种情况
    1. 重命名包名应用或者覆盖安装的应用是系统应用，scanFlags添加上`SCAN_AS_SYSTEM`以及可能的其他flags
    2. 如果sharedUserId是包含了privilege应用，则正在扫描的应用也要加上`SCAN_AS_PRIVILEGED`
3. 获取`SharedUserSetting`，这个在`addForInitLI()`获取过一次，是为了扫描disabledPkgSetting获取的
4. 构建`ScanRequest`并且调用`scanPackageOnlyLI()`获取`ScanResult`

### scanPackageOnlyLI()

这部分主要的逻辑是更新packageSetting和nativelibrary的解析

1. 确定是否需要获取abi，如果packageSetting，如果应用已安装，则无需更改cpuAbi，如果未安装，则需要解析native库
2. 获取\<uses-static-library>标签，这个我不知道是干嘛的，目前还没遇到这种标签
3. 更新或创建新的`PackageSetting`

