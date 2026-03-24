Xiexie VPN – https://xiexievpn.com

Android client

Build steps on Linux (Ubuntu/Debian):

1. Install JDK 21 and Android SDK, set environment variables:

export ANDROID_HOME=~/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

2. In the source code directory, run:

cd V2rayNG && chmod +x gradlew && ./gradlew assemblePlaystoreRelease

The APK files will be generated at: V2rayNG/app/build/outputs/apk/playstore/release/