package com.openclaw.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logoContainer = findViewById(R.id.logoContainer);
        TextView tagline   = findViewById(R.id.tagline);
        View dotsContainer = findViewById(R.id.dotsContainer);

        logoContainer.setScaleX(0.3f);
        logoContainer.setScaleY(0.3f);
        logoContainer.setAlpha(0f);
        tagline.setAlpha(0f);
        tagline.setTranslationY(30f);
        dotsContainer.setAlpha(0f);

        AnimatorSet anim = new AnimatorSet();
        anim.playTogether(
            ObjectAnimator.ofFloat(logoContainer, "scaleX", 0.3f, 1f),
            ObjectAnimator.ofFloat(logoContainer, "scaleY", 0.3f, 1f),
            ObjectAnimator.ofFloat(logoContainer, "alpha",  0f,   1f)
        );
        anim.setDuration(600);
        anim.setInterpolator(new OvershootInterpolator(1.2f));
        anim.start();

        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> {
            ObjectAnimator.ofFloat(tagline, "alpha", 0f, 1f).setDuration(400).start();
            ObjectAnimator.ofFloat(tagline, "translationY", 30f, 0f).setDuration(400).start();
        }, 400);
        h.postDelayed(() ->
            ObjectAnimator.ofFloat(dotsContainer, "alpha", 0f, 1f).setDuration(300).start(),
            700);

        h.postDelayed(this::proceed, 1800);
    }

    private void proceed() {
        SharedPreferences p = getSharedPreferences("openclaw_prefs", MODE_PRIVATE);
        if (p.getBoolean("biometric_enabled", false) && canBiometric()) {
            showBiometric();
        } else {
            goNext();
        }
    }

    private boolean canBiometric() {
        return BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void showBiometric() {
        new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            new BiometricPrompt.AuthenticationCallback() {
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { goNext(); }
                public void onAuthenticationFailed() {}
                public void onAuthenticationError(int code, CharSequence err) { goNext(); }
            }
        ).authenticate(new BiometricPrompt.PromptInfo.Builder()
            .setTitle("验证身份")
            .setSubtitle("使用指纹或面部识别进入 OpenClaw")
            .setNegativeButtonText("跳过")
            .build());
    }

    private void goNext() {
        SharedPreferences p = getSharedPreferences("openclaw_prefs", MODE_PRIVATE);
        Class<?> target = p.getString("server_url", "").isEmpty()
            ? SettingsActivity.class : MainActivity.class;
        Intent i = new Intent(this, target);
        if (target == SettingsActivity.class) i.putExtra("first_launch", true);
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}