package com.lynxeye;

import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch swMixAudio   = findViewById(R.id.swMixAudio);
        Switch swOpus       = findViewById(R.id.swOpus);
        Switch swNoise      = findViewById(R.id.swNoise);
        TextView btnChangePin = findViewById(R.id.btnChangePin);
        TextView btnBack    = findViewById(R.id.btnBack);

        swMixAudio.setChecked(AppSettings.isMixAudio(this));
        swOpus.setChecked(AppSettings.isOpusCodec(this));
        swNoise.setChecked(AppSettings.isNoiseSuppression(this));

        swMixAudio.setOnCheckedChangeListener((v, checked) -> AppSettings.setMixAudio(this, checked));
        swOpus.setOnCheckedChangeListener((v, checked) -> {
            AppSettings.setOpusCodec(this, checked);
            if (checked) Toast.makeText(this, "Opus active on next connection", Toast.LENGTH_SHORT).show();
        });
        swNoise.setOnCheckedChangeListener((v, checked) -> AppSettings.setNoiseSuppression(this, checked));

        btnChangePin.setOnClickListener(v -> {
            PinManager.clearPin(this);
            Toast.makeText(this, "PIN cleared. Set new PIN on next launch.", Toast.LENGTH_LONG).show();
        });

        btnBack.setOnClickListener(v -> finish());
    }
}
