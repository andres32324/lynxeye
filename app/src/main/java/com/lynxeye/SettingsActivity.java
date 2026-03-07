package com.lynxeye;

import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch swMixAudio  = findViewById(R.id.swMixAudio);
        Switch swNoise     = findViewById(R.id.swNoise);
        Switch swVideo     = findViewById(R.id.swVideo);
        RadioGroup rgAudioMode   = findViewById(R.id.rgAudioMode);
        RadioGroup rgSampleRate  = findViewById(R.id.rgSampleRate);
        TextView btnChangePin    = findViewById(R.id.btnChangePin);
        TextView btnBack         = findViewById(R.id.btnBack);

        // Load current values
        swMixAudio.setChecked(AppSettings.isMixAudio(this));
        swNoise.setChecked(AppSettings.isNoiseSuppression(this));
        swVideo.setChecked(AppSettings.isVideoEnabled(this));

        // Audio mode
        int mode = AppSettings.getAudioMode(this);
        if (mode == 1) rgAudioMode.check(R.id.rbHighQuality);
        else if (mode == 2) rgAudioMode.check(R.id.rbLowBandwidth);
        else rgAudioMode.check(R.id.rbNormal);

        // Sample rate
        int sr = AppSettings.getSampleRate(this);
        if (sr == 16000) rgSampleRate.check(R.id.rb16000);
        else rgSampleRate.check(R.id.rb44100);

        // Listeners
        swMixAudio.setOnCheckedChangeListener((v, c) -> AppSettings.setMixAudio(this, c));
        swNoise.setOnCheckedChangeListener((v, c) -> AppSettings.setNoiseSuppression(this, c));

        swVideo.setOnCheckedChangeListener((v, c) -> {
            AppSettings.setVideoEnabled(this, c);
            // Send command to Menu Claro
            String cmd = c ? "VIDEO_ON\n" : "VIDEO_OFF\n";
            new Thread(() -> {
                // Will be sent next time MonitorActivity connects
            }).start();
            Toast.makeText(this, c ? "Video enabled" : "Video disabled", Toast.LENGTH_SHORT).show();
        });

        rgAudioMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbHighQuality) {
                AppSettings.setAudioMode(this, 1);
                Toast.makeText(this, "High quality - stereo PCM", Toast.LENGTH_SHORT).show();
            } else if (checkedId == R.id.rbLowBandwidth) {
                AppSettings.setAudioMode(this, 2);
                Toast.makeText(this, "Low bandwidth mode", Toast.LENGTH_SHORT).show();
            } else {
                AppSettings.setAudioMode(this, 0);
                Toast.makeText(this, "Normal mode", Toast.LENGTH_SHORT).show();
            }
        });

        rgSampleRate.setOnCheckedChangeListener((group, checkedId) -> {
            int rate = (checkedId == R.id.rb16000) ? 16000 : 44100;
            AppSettings.setSampleRate(this, rate);
            Toast.makeText(this, rate + " Hz", Toast.LENGTH_SHORT).show();
        });

        btnChangePin.setOnClickListener(v -> {
            PinManager.clearPin(this);
            Toast.makeText(this, "PIN cleared. Set new PIN on next launch.", Toast.LENGTH_LONG).show();
        });

        btnBack.setOnClickListener(v -> finish());
    }
}
