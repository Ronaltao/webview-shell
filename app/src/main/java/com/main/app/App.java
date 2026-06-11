package com.main.app;

import android.app.Activity;
import android.app.Application;
import android.util.Log;

import org.json.JSONObject;

import cn.gravity.android.GEConfig;
import cn.gravity.android.GravityEngineSDK;
import cn.gravity.android.InitializeCallback;

import com.bytedance.ads.convert.BDConvert;
import com.bytedance.ads.convert.event.ConvertReportHelper;

/**
 * 应用入口 Application。
 *
 * 这里负责在进程启动时尽早配置并启动「引力引擎(Gravity Engine)」SDK。
 * AccessToken 与渠道(CHANNEL)通过各 flavor 的 BuildConfig 注入（见 app/build.gradle），
 * 不在代码里写死，保持与本项目「按渠道出包」的一致约定。
 *
 * 注：引力引擎会采集 OAID/AndroidID 等设备标识，必须取得用户同意后才能初始化。
 * 因此这里 onCreate 只在「用户此前已同意隐私政策」时才初始化（老用户的最早可用时机）；
 * 首次启动尚未同意时不在此初始化，改由 {@link MainActivity} 的隐私弹窗在用户点「同意」后
 * 调用 {@link #setupGravityEngine()}。同意状态由 {@link PrivacyManager} 统一管理。
 */
public class App extends Application {

    private static final String TAG = "GravityEngine";

    /** 是否开启同步获取归因信息，默认关闭。如需同步归因请参考引力文档后置为 true。 */
    private static final boolean ENABLE_SYNC_ATTRIBUTION = false;

    /** 引力引擎实例，初始化后保存，供事件上报（付费/注册等）复用；未初始化时为 null。 */
    private GravityEngineSDK geInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        // 仅老用户（已同意过）在此尽早初始化；未同意时等隐私弹窗同意后再初始化。
        if (PrivacyManager.hasAgreed(this)) {
            setupGravityEngine();
        }
    }

    /** 初始化引力引擎 SDK。务必在用户同意隐私政策后调用（onCreate 或弹窗「同意」回调）。 */
    public void setupGravityEngine() {
        // 1. 配置并启动 SDK
        GEConfig config = GEConfig.getInstance(this, BuildConfig.GE_ACCESS_TOKEN);
        // 以下设备标识采集开关默认开启，引力建议不要关闭，否则会影响归因率。
        config.enableAndroidId(true);
        config.enableOAID(true);
        config.enableIMEI(true); // 实际读取 IMEI 还需 READ_PHONE_STATE 权限并在 6.0+ 动态申请；缺权限时 SDK 静默跳过
        config.enableMAC(true);

        GravityEngineSDK instance = GravityEngineSDK.setupAndStart(config);
        geInstance = instance; // 保存实例，供后续事件上报复用

        // 2. 初始化。client_id / client_name 传空字符串，交由 SDK 自动采集设备 ID。
        //    首次安装启动时调用，等 onSuccess 回调成功后才能继续调用其它事件上报方法。
        //    注意：回调用具名内部类 InitCallback，切勿写成匿名内部类。AGP 7.4.1 自带的
        //    R8 4.0.48 在 dex 匿名内部类(会编出 App$1.class)时会触发 NullPointerException
        //    导致打包失败（dexBuilderWechatDebug 任务报错）。
        instance.initialize(
                BuildConfig.GE_ACCESS_TOKEN,
                "",                       // USER_CLIENT_ID：空 -> SDK 自动采集(oaid>android_id>imei)
                "",                       // USER_CLIENT_NAME：空 -> SDK 按 client_id 自动生成
                BuildConfig.GE_CHANNEL,   // CHANNEL：初始化渠道
                new InitCallback(),
                ENABLE_SYNC_ATTRIBUTION);
    }

    /** 引力引擎初始化结果回调。用具名静态内部类，规避 R8 4.0.48 对匿名内部类的 dex bug。 */
    private static final class InitCallback implements InitializeCallback {
        @Override
        public void onFailed(String errorMsg, JSONObject initializeBody) {
            Log.d(TAG, "initialize failed: " + errorMsg);
        }

        @Override
        public void onSuccess(JSONObject responseJson, JSONObject initializeBody) {
            Log.d(TAG, "initialize success");
            Log.d(TAG, "initialize success" + initializeBody);
        }
    }

    /**
     * 初始化巨量引擎广告转化 SDK(BDConvert)。务必在用户同意隐私政策后调用。
     *
     * 采用官方文档「接入方式 A」：传入当前 Activity，SDK 内部不持有该引用（无内存泄漏），
     * 初始化后会自动发送启动事件，并采集 OAID/AndroidID 用于买量归因。
     * 归因应用由巨量后台「资产」按包名(applicationId)登记，因此无需在代码里注入 AppID。
     *
     * 注：与引力引擎不同，本 SDK 必须拿到 Activity，故由 {@link MainActivity}（已在同意门控内）
     * 调用，而非 App.onCreate。付费/注册等事件由 H5 经 {@link WebAppBridge} 透传后，
     * 调用本类的 {@link #trackPurchase} / {@link #trackRegister} 上报。
     */
    public void setupBytedanceConvert(Activity activity) {
        BDConvert.INSTANCE.init(this, activity);
    }

    /**
     * 上报付费事件，同时写入巨量与引力两个 SDK。由 {@link WebAppBridge} 在 H5 付费成功时调用。
     *
     * @param amountFen    付费金额，单位「分」（H5 统一传分以保精度，这里再按各 SDK 单位换算）
     * @param currency     货币类型，如 "CNY"
     * @param orderId      订单号
     * @param productName  商品名
     * @param contentType  内容类型（巨量 contentType），如 "game"
     * @param channel      渠道（巨量 paymentChannel），如 "wechat"
     * @param success      是否支付成功
     */
    public void trackPurchase(int amountFen, String currency, String orderId,
                              String productName, String contentType, String channel, boolean success) {
        // 巨量：currencyAmount 单位为「元」(int)，= 分/100（不足 1 元会被截断，是巨量 API 本身的精度限制）；
        //      contentNumber 固定 1，contentId 用订单号。
        ConvertReportHelper.onEventPurchase(
                contentType, productName, orderId, 1, channel, currency, success, amountFen / 100);
        // 引力：trackPayEvent(payAmount分, payType, orderId, payReason, payMethod)
        if (geInstance != null) {
            geInstance.trackPayEvent(amountFen, currency, orderId, productName, channel);
        }
    }

    /**
     * 上报注册事件，同时写入巨量与引力两个 SDK。由 {@link WebAppBridge} 在 H5 注册成功时调用。
     *
     * @param channel 渠道，如 "wechat"
     */
    public void trackRegister(String channel) {
        // Log.d("TestRegister", "触发了注册======================");
        ConvertReportHelper.onEventRegister(channel, true);
        if (geInstance != null) {
            geInstance.trackRegisterEvent();
        }
    }
}
