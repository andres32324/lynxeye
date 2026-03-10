package com.lynxeye;

import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/* JADX INFO: loaded from: classes3.dex */
public class SettingsActivity extends AppCompatActivity {
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Switch swMixAudio = (Switch) findViewById(R.id.swMixAudio);
        Switch swNoise = (Switch) findViewById(R.id.swNoise);
        Switch swVideo = (Switch) findViewById(R.id.swVideo);
        Switch swVisualizer = (Switch) findViewById(R.id.swVisualizer);
        RadioGroup rgAudioMode = (RadioGroup) findViewById(R.id.rgAudioMode);
        RadioGroup rgSampleRate = (RadioGroup) findViewById(R.id.rgSampleRate);
        TextView btnChangePin = (TextView) findViewById(R.id.btnChangePin);
        TextView btnBack = (TextView) findViewById(R.id.btnBack);
        swMixAudio.setChecked(AppSettings.isMixAudio(this));
        swNoise.setChecked(AppSettings.isNoiseSuppression(this));
        swVideo.setChecked(AppSettings.isVideoEnabled(this));
        swVisualizer.setChecked(AppSettings.isVisualizerEnabled(this));
        int mode = AppSettings.getAudioMode(this);
        if (mode == 1) {
            rgAudioMode.check(R.id.rbHighQuality);
        } else if (mode == 2) {
            rgAudioMode.check(R.id.rbLowBandwidth);
        } else {
            rgAudioMode.check(R.id.rbNormal);
        }
        int sr = AppSettings.getSampleRate(this);
        if (sr == 16000) {
            rgSampleRate.check(R.id.rb16000);
        } else {
            rgSampleRate.check(R.id.rb44100);
        }
        swMixAudio.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda0
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                this.f$0.m180lambda$onCreate$0$comlynxeyeSettingsActivity(compoundButton, z);
            }
        });
        swNoise.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda1
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                this.f$0.m181lambda$onCreate$1$comlynxeyeSettingsActivity(compoundButton, z);
            }
        });
        swVideo.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda2
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                this.f$0.m182lambda$onCreate$2$comlynxeyeSettingsActivity(compoundButton, z);
            }
        });
        swVisualizer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda3
            @Override // android.widget.CompoundButton.OnCheckedChangeListener
            public final void onCheckedChanged(CompoundButton compoundButton, boolean z) {
                this.f$0.m183lambda$onCreate$3$comlynxeyeSettingsActivity(compoundButton, z);
            }
        });
        rgAudioMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda4
            @Override // android.widget.RadioGroup.OnCheckedChangeListener
            public final void onCheckedChanged(RadioGroup radioGroup, int i) {
                this.f$0.m184lambda$onCreate$4$comlynxeyeSettingsActivity(radioGroup, i);
            }
        });
        rgSampleRate.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda5
            @Override // android.widget.RadioGroup.OnCheckedChangeListener
            public final void onCheckedChanged(RadioGroup radioGroup, int i) {
                this.f$0.m185lambda$onCreate$5$comlynxeyeSettingsActivity(radioGroup, i);
            }
        });
        btnChangePin.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m186lambda$onCreate$6$comlynxeyeSettingsActivity(view);
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.SettingsActivity$$ExternalSyntheticLambda7
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m187lambda$onCreate$7$comlynxeyeSettingsActivity(view);
            }
        });
    }

    /* JADX INFO: renamed from: lambda$onCreate$0$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m180lambda$onCreate$0$comlynxeyeSettingsActivity(CompoundButton v, boolean c) {
        AppSettings.setMixAudio(this, c);
    }

    /* JADX INFO: renamed from: lambda$onCreate$1$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m181lambda$onCreate$1$comlynxeyeSettingsActivity(CompoundButton v, boolean c) {
        AppSettings.setNoiseSuppression(this, c);
    }

    /* JADX INFO: renamed from: lambda$onCreate$2$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m182lambda$onCreate$2$comlynxeyeSettingsActivity(CompoundButton v, boolean c) {
        AppSettings.setVideoEnabled(this, c);
        Toast.makeText(this, c ? "Video enabled" : "Video disabled", 0).show();
    }

    /* JADX INFO: renamed from: lambda$onCreate$3$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m183lambda$onCreate$3$comlynxeyeSettingsActivity(CompoundButton v, boolean c) {
        AppSettings.setVisualizerEnabled(this, c);
        Toast.makeText(this, c ? "Visualizer enabled" : "Visualizer disabled", 0).show();
    }

    /* JADX INFO: renamed from: lambda$onCreate$4$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m184lambda$onCreate$4$comlynxeyeSettingsActivity(RadioGroup group, int checkedId) {
        if (checkedId != R.id.rbHighQuality) {
            if (checkedId != R.id.rbLowBandwidth) {
                AppSettings.setAudioMode(this, 0);
                Toast.makeText(this, "Normal mode", 0).show();
                return;
            } else {
                AppSettings.setAudioMode(this, 2);
                Toast.makeText(this, "Low bandwidth mode", 0).show();
                return;
            }
        }
        AppSettings.setAudioMode(this, 1);
        Toast.makeText(this, "High quality - stereo PCM", 0).show();
    }

    /* JADX INFO: renamed from: lambda$onCreate$5$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m185lambda$onCreate$5$comlynxeyeSettingsActivity(RadioGroup group, int checkedId) {
        int rate = checkedId == R.id.rb16000 ? 16000 : 44100;
        AppSettings.setSampleRate(this, rate);
        Toast.makeText(this, rate + " Hz", 0).show();
    }

    /* JADX INFO: renamed from: lambda$onCreate$6$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m186lambda$onCreate$6$comlynxeyeSettingsActivity(View v) {
        PinManager.clearPin(this);
        Toast.makeText(this, "PIN cleared. Set new PIN on next launch.", 1).show();
    }

    /* JADX INFO: renamed from: lambda$onCreate$7$com-lynxeye-SettingsActivity, reason: not valid java name */
    /* synthetic */ void m187lambda$onCreate$7$comlynxeyeSettingsActivity(View v) {
        finish();
    }
}
