package com.ijk.media.player.lib;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import tv.danmaku.ijk.media.ijkplayerlib.BuildConfig;

/**
 * Created by Administrator on 2017/2/21.
 */

public class DebugHelp {
    public static Boolean sDebug = null;

    public DebugHelp() {
    }

    public static boolean isDebugBuild() {
        if(sDebug == null) {
            try {
                Class t = Class.forName("android.app.ActivityThread");
                Method message1 = t.getMethod("currentPackageName", new Class[0]);
                String packageName = (String)message1.invoke((Object)null, (Object[])null);
                Class buildConfig = Class.forName(packageName + ".BuildConfig");
                Field DEBUG = buildConfig.getField("DEBUG");
                DEBUG.setAccessible(true);
                sDebug = Boolean.valueOf(DEBUG.getBoolean((Object)null));
            } catch (Throwable var5) {
                String message = var5.getMessage();
                sDebug = Boolean.valueOf((message == null || !message.contains("BuildConfig")) && BuildConfig.DEBUG);
            }
        }

        return sDebug.booleanValue();
    }
}
