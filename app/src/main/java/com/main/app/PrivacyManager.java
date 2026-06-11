package com.main.app;

import android.content.Context;
import android.content.SharedPreferences;
//
/**
 * 隐私政策同意状态的统一读写入口。
 *
 * 用一个 SharedPreferences 标志位（{@link #PREFS_NAME}/{@link #KEY_AGREED}）作为「是否已同意隐私政策」
 * 的唯一事实来源，供 {@link App} 与 {@link MainActivity} 共同读取，避免重复散落的 prefs 代码。
 *
 * 约定：只有用户在隐私弹窗里明确点击「同意」后才会写入 true；在此之前任何采集设备信息的 SDK
 * （引力引擎）都不得初始化。
 */
final class PrivacyManager {
    //

    private static final String PREFS_NAME = "privacy";
    private static final String KEY_AGREED = "agreed";

    private PrivacyManager() {
    }

    /** 用户此前是否已同意隐私政策。 */
    static boolean hasAgreed(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_AGREED, false);
    }

    /** 记录用户已同意隐私政策（仅在弹窗点「同意」时调用）。 */
    static void setAgreed(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_AGREED, true).apply();
    }
}
