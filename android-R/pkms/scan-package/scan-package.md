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

逻辑相当的长，**有些自己也没搞懂，只挑能说的说一下**

1. 首先重命名包名的逻辑，在PkMS当中所有的package都是按照包名作为唯一的主键值，那么就可能出现应用需要更换包名的情况，PkMS给出解决方案是加上`<original-package android:name="com.android.oldpackagename" />`指明需要覆盖安装的包名，但是这样就需要考虑到诸多情况了

    1. 包名修改多次怎么办
    2. 之前有安装过老包名的应用的处理情况
    3. 之前未安装过老包名的应用的处理情况
    4. 之前安装过当前包名的应用的处理情况

    那么这些大佬是怎么做的呢？ 

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

        

