package com.voicestudio.phone;

interface IVirtualMicService {
    void destroy() = 16777114;
    String startMic(String targetPackage, int seconds, boolean otherProfile, boolean allApps, boolean cellular, IBinder owner) = 1;
    String snapshot() = 2;
    void stopMic(boolean explicitStop) = 3;
    // WAV 文件注入（复用 dev.virtualmic.Main --wav，不经过实时 socket）
    String startWav(String targetPackage, String wavPath, int seconds, IBinder owner) = 4;
}
