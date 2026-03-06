package com.lynxeye;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class PinActivity extends AppCompatActivity {

    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvDots;
    private TextView tvAttempts;
    private StringBuilder pinInput = new StringBuilder();
    private boolean isSettingPin = false;
    private String firstPin = "";
    private int attempts = 3;
    private Handler lockHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin);

        tvTitle    = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvDots     = findViewById(R.id.tvDots);
        tvAttempts = findViewById(R.id.tvAttempts);

        isSettingPin = !PinManager.hasPin(this);

        if (isSettingPin) {
            tvTitle.setText("LYNXEYE");
            tvSubtitle.setText("CREATE PIN");
        } else {
            tvTitle.setText("LYNXEYE");
            tvSubtitle.setText("ENTER PIN");
            tvAttempts.setText(attempts + " ATTEMPTS REMAINING");
        }

        setupNumpad();
        updateDots();
    }

    private void setupNumpad() {
        int[] ids = {
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3,
            R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7,
            R.id.btn8, R.id.btn9
        };
        String[] labels = {"0","1","2","3","4","5","6","7","8","9"};

        for (int i = 0; i < ids.length; i++) {
            final String digit = labels[i];
            TextView btn = findViewById(ids[i]);
            btn.setOnClickListener(v -> addDigit(digit));
        }

        ImageButton btnDel = findViewById(R.id.btnDelete);
        btnDel.setOnClickListener(v -> deleteDigit());
    }

    private void addDigit(String digit) {
        if (pinInput.length() >= 6) return;
        pinInput.append(digit);
        updateDots();
        if (pinInput.length() == 6) {
            lockHandler.postDelayed(this::processPin, 200);
        }
    }

    private void deleteDigit() {
        if (pinInput.length() > 0) {
            pinInput.deleteCharAt(pinInput.length() - 1);
            updateDots();
        }
    }

    private void updateDots() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i < pinInput.length()) dots.append("●");
            else dots.append("○");
            if (i < 5) dots.append("  ");
        }
        tvDots.setText(dots.toString());
    }

    private void processPin() {
        String pin = pinInput.toString();
        pinInput.setLength(0);
        updateDots();

        if (isSettingPin) {
            if (firstPin.isEmpty()) {
                firstPin = pin;
                tvSubtitle.setText("CONFIRM PIN");
            } else {
                if (pin.equals(firstPin)) {
                    PinManager.savePin(this, pin);
                    openHome();
                } else {
                    firstPin = "";
                    tvSubtitle.setText("CREATE PIN");
                    showError("PINS DON'T MATCH");
                }
            }
        } else {
            if (PinManager.checkPin(this, pin)) {
                openHome();
            } else {
                attempts--;
                if (attempts <= 0) {
                    lockApp();
                } else {
                    tvAttempts.setText(attempts + " ATTEMPTS REMAINING");
                    showError("WRONG PIN");
                }
            }
        }
    }

    private void showError(String msg) {
        tvSubtitle.setText(msg);
        tvSubtitle.setTextColor(0xFFFF3D3D);
        lockHandler.postDelayed(() -> {
            tvSubtitle.setTextColor(0xFF00E676);
            tvSubtitle.setText(isSettingPin ? "CREATE PIN" : "ENTER PIN");
        }, 1000);
    }

    private void lockApp() {
        tvSubtitle.setText("LOCKED - WAIT 30s");
        tvSubtitle.setTextColor(0xFFFF3D3D);
        tvAttempts.setText("");
        for (int i = 0; i < 10; i++) {
            TextView btn = null;
            // Disable all buttons
        }
        lockHandler.postDelayed(() -> {
            attempts = 3;
            tvSubtitle.setText("ENTER PIN");
            tvSubtitle.setTextColor(0xFF00E676);
            tvAttempts.setText("3 ATTEMPTS REMAINING");
        }, 30000);
    }

    private void openHome() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
