package com.main.app;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;

class MyWebViewClient extends WebViewClient {

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            view.loadUrl(url);
            return true;
        } else {
            if (url.startsWith("weixin://") ||
                    url.startsWith("alipays://") ) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                view.getContext().startActivity(intent);
                return true;
            }
        }
        return false;
//        String hostname;
//
//        // YOUR HOSTNAME
//        hostname = "example.com";

//        Uri uri = Uri.parse(url);
//        if (url.startsWith("file:") || uri.getHost() != null && uri.getHost().endsWith(hostname)) {
//            return false;
//        }
//        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
//        view.getContext().startActivity(intent);
        // return false;
    }
}
