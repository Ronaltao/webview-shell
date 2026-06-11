package com.main.app;

import android.app.Application;
import android.util.Log;

import org.json.JSONObject;

import cn.gravity.android.GEConfig;
import cn.gravity.android.GravityEngineSDK;
import cn.gravity.android.InitializeCallback;

/**
 * 应用入口 Application。
 *
 * 这里负责在进程启动时尽早配置并启动「引力引擎(Gravity Engine)」SDK。
 * AccessToken 与渠道(CHANNEL)通过各 flavor 的 BuildConfig 注入（见 app/build.gradle），
 * 不在代码里写死，保持与本项目「按渠道出包」的一致约定。
 *
 * 注：本壳应用没有隐私弹窗，故在 onCreate 中初始化即为最早可用时机。
 * 若后续增加隐私协议弹窗，应改为在用户同意之后再调用下面的 setupGravityEngine()。
 */
public class App extends Application {

    private static final String TAG = "GravityEngine";

    /** 是否开启同步获取归因信息，默认关闭。如需同步归因请参考引力文档后置为 true。 */
    private static final boolean ENABLE_SYNC_ATTRIBUTION = false;

    @Override
    public void onCreate() {
        super.onCreate();
        setupGravityEngine();
    }

    private void setupGravityEngine() {
        // 1. 配置并启动 SDK
        GEConfig config = GEConfig.getInstance(this, BuildConfig.GE_ACCESS_TOKEN);
        // 以下设备标识采集开关默认开启，引力建议不要关闭，否则会影响归因率。
        config.enableAndroidId(true);
        config.enableOAID(true);
        config.enableIMEI(true); // 实际读取 IMEI 还需 READ_PHONE_STATE 权限并在 6.0+ 动态申请；缺权限时 SDK 静默跳过
        config.enableMAC(true);

        GravityEngineSDK instance = GravityEngineSDK.setupAndStart(config);

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
}
