package com.ijk.media.player.lib;

import android.util.Log;

/**
 * Created by Administrator on 2017/2/21.
 */

public class MyLog {

    private static boolean isDebug = true;

    public static void d(String tag, String msg) {
        if (isDebug) {
            Log.d(tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        if (isDebug) {
            Log.i(tag, msg);
        }
    }
}
