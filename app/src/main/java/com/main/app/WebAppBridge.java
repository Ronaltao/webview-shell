package com.main.app;

import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * H5 ↔ 原生 JS bridge：把 H5 的付费/注册事件透传到巨量 + 引力两个 SDK。
 *
 * 由 {@link MainActivity} 通过 addJavascriptInterface(this, "AndroidBridge") 暴露，
 * H5 端调用 {@code window.AndroidBridge.reportPurchase(json)} / {@code reportRegister(json)}，
 * 参数统一为单个 JSON 字符串（加字段不破坏签名）。
 *
 * 注意：
 * - {@link JavascriptInterface} 方法运行在 WebView 的 JS binder 线程（非 UI 线程）；巨量/引力
 *   的事件方法均内部排队/异步，后台线程调用安全，这里不转主线程（也避免引入匿名 Runnable）。
 * - 双保险校验同意状态；JSON 解析失败仅记日志，绝不把异常抛回 WebView。
 * - 本类为顶层具名类，规避 AGP 7.4.1 自带 R8 4.0.48 对匿名内部类的 dex bug。
 */
public class WebAppBridge {

    private static final String TAG = "WebAppBridge";

    private final MainActivity activity;

    WebAppBridge(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * H5 付费成功时调用。JSON 字段：
     * {@code amount}(必填,单位「分」)、{@code currency}(默认 CNY)、{@code orderId}、
     * {@code productName}、{@code contentType}(默认 game)、{@code channel}(默认渠道)、
     * {@code success}(默认 true)。
     */
    @JavascriptInterface
    public void reportPurchase(String json) {
        if (!PrivacyManager.hasAgreed(activity)) {
            return;
        }
        try {
            JSONObject o = new JSONObject(emptyIfBlank(json));
            int amountFen = o.optInt("amount", 0);
            String currency = o.optString("currency", "CNY");
            String orderId = o.optString("orderId", "");
            String productName = o.optString("productName", "");
            String contentType = o.optString("contentType", "game");
            String channel = o.optString("channel", BuildConfig.GE_CHANNEL);
            boolean success = o.optBoolean("success", true);
            ((App) activity.getApplication()).trackPurchase(
                    amountFen, currency, orderId, productName, contentType, channel, success);
        } catch (JSONException e) {
            Log.w(TAG, "reportPurchase bad json: " + json, e);
        }
    }

    /**
     * H5 注册成功时调用。JSON 字段：{@code channel}(默认渠道)。
     */
    @JavascriptInterface
    public void reportRegister(String json) {
        if (!PrivacyManager.hasAgreed(activity)) {
            return;
        }
        try {
            JSONObject o = new JSONObject(emptyIfBlank(json));
            String channel = o.optString("channel", BuildConfig.GE_CHANNEL);
            ((App) activity.getApplication()).trackRegister(channel);
        } catch (JSONException e) {
            Log.w(TAG, "reportRegister bad json: " + json, e);
        }
    }

    /** H5 可能不传参（null/空），统一兜底成空 JSON，让各字段走默认值。 */
    private static String emptyIfBlank(String json) {
        return (json == null || json.trim().isEmpty()) ? "{}" : json;
    }
}
