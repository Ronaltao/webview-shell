package com.main.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;

import static android.webkit.WebSettings.LOAD_NO_CACHE;

public class MainActivity extends Activity {

    private WebView mWebView;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mWebView = findViewById(R.id.activity_main_webview);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(LOAD_NO_CACHE);
        mWebView.setWebViewClient(new MyWebViewClient());

        // 已同意过的用户直接进游戏；首次启动（未同意）先弹隐私政策，同意后才初始化 SDK 并加载。
        if (PrivacyManager.hasAgreed(this)) {
            loadGame();
        } else {
            showPrivacyDialog();
        }
    }

    /** 加载游戏 H5（远程 URL 由 flavor 的 BuildConfig 注入）。 */
    private void loadGame() {
        // REMOTE RESOURCE
        mWebView.loadUrl(BuildConfig.URL_ENTRY);

        // LOCAL RESOURCE
        // mWebView.loadUrl("file:///android_asset/index.html");
    }

    /** 首次启动的隐私政策弹窗：不同意退出、同意后初始化 SDK 再加载游戏。 */
    private void showPrivacyDialog() {
        TextView messageView = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        messageView.setPadding(pad, pad, pad, pad);
        messageView.setLineSpacing(0, 1.2f);
        messageView.setText(buildPrivacyMessage());
        // 让《隐私政策》《用户协议》两段 ClickableSpan 可点击
        messageView.setMovementMethod(LinkMovementMethod.getInstance());

        // 注意：监听器 / ClickableSpan 一律用具名内部类，切勿写成匿名内部类。AGP 7.4.1 自带的
        // R8 4.0.48 在 dex 匿名内部类(会编出 MainActivity$1.class 之类)时会触发 NullPointerException
        // 导致打包失败（dexBuilderWechatDebug 任务报错）。详见 App.java 的同类处理。
        new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_dialog_title)
                .setView(messageView)
                .setCancelable(false)
                .setPositiveButton(R.string.privacy_agree, new AgreeListener(this))
                .setNegativeButton(R.string.privacy_disagree, new DisagreeListener(this))
                .show();
    }

    /** 「同意并继续」：记录同意 -> 初始化引力引擎 SDK -> 加载游戏。静态嵌套类，规避 R8 匿名/内部类 dex bug。 */
    private static final class AgreeListener implements DialogInterface.OnClickListener {
        private final MainActivity activity;

        AgreeListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            PrivacyManager.setAgreed(activity);
            ((App) activity.getApplication()).setupGravityEngine();
            activity.loadGame();
        }
    }

    /** 「不同意并退出」：退出应用，不初始化 SDK、不加载游戏。静态嵌套类，规避 R8 匿名/内部类 dex bug。 */
    private static final class DisagreeListener implements DialogInterface.OnClickListener {
        private final MainActivity activity;

        DisagreeListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            activity.finishAffinity();
        }
    }

    /** 构造带可点击《隐私政策》《用户协议》链接的弹窗正文。 */
    private CharSequence buildPrivacyMessage() {
        String text = getString(R.string.privacy_dialog_message);
        String policyLabel = getString(R.string.privacy_link_policy);
        String agreementLabel = getString(R.string.privacy_link_agreement);

        SpannableString span = new SpannableString(text);
        applyLink(span, text, policyLabel, BuildConfig.PRIVACY_POLICY_URL);
        applyLink(span, text, agreementLabel, BuildConfig.USER_AGREEMENT_URL);
        return span;
    }

    /** 在 text 中定位 label，对其套上「点击打开 url」的 ClickableSpan。 */
    private void applyLink(SpannableString span, String text, String label, String url) {
        int start = text.indexOf(label);
        if (start < 0) {
            return;
        }
        int end = start + label.length();
        // 静态嵌套类 LinkSpan，切勿用匿名 ClickableSpan（R8 4.0.48 匿名/内部类 dex bug）。
        span.setSpan(new LinkSpan(this, url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 点击后在应用内 WebView 打开指定 url 的可点击文字。静态嵌套类，规避 R8 匿名/内部类 dex bug。 */
    private static final class LinkSpan extends ClickableSpan {
        private final MainActivity activity;
        private final String url;

        LinkSpan(MainActivity activity, String url) {
            this.activity = activity;
            this.url = url;
        }

        @Override
        public void onClick(View widget) {
            activity.openInAppWeb(url);
        }
    }

    /** 应用内 WebView 弹窗打开隐私政策/用户协议页面（不新增 Activity）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void openInAppWeb(String url) {
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new MyWebViewClient());
        webView.loadUrl(url);

        new AlertDialog.Builder(this)
                .setView(webView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if(mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
