package com.openclaw.app;

public interface StreamCallback {
    void onToken(String token);
    void onComplete();
    void onError(String error);
}
