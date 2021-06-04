#!/bin/bash
apk_host_dir=$1
for apk in `ls $apk_host_dir | grep apk`;
do   
adb push $apk_host_dir/$apk /data/local/tmp/$apk
done
str=`adb shell pm install-create`
str1=${str#*[}
session_id=${str1%]}
echo $session_id
for apk in `adb shell ls /data/local/tmp | grep apk`;
do   
echo "commit $apk" ;  
adb shell pm install-write $session_id $apk /data/local/tmp/$apk
done
result=`adb shell pm install-commit $session_id`
echo $result
if [ "$result" = "Success" ];then
    adb shell rm -rf /data/local/tmp/*.apk
fi
