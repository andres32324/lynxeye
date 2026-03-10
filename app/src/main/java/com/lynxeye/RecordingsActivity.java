package com.lynxeye;

import android.content.DialogInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes3.dex */
public class RecordingsActivity extends AppCompatActivity {
    private ImageButton btnPlayPause;
    private ImageButton btnStop;
    private File currentFile;
    private ListView listView;
    private MediaPlayer mediaPlayer;
    private SeekBar seekPlayback;
    private Runnable seekUpdater;
    private TextView tvCurrentTime;
    private TextView tvEmpty;
    private TextView tvNowPlaying;
    private TextView tvTotalTime;
    private List<File> recordings = new ArrayList();
    private Handler handler = new Handler();
    private boolean isPlaying = false;

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);
        this.listView = (ListView) findViewById(R.id.listRecordings);
        this.tvNowPlaying = (TextView) findViewById(R.id.tvNowPlaying);
        this.tvCurrentTime = (TextView) findViewById(R.id.tvCurrentTime);
        this.tvTotalTime = (TextView) findViewById(R.id.tvTotalTime);
        this.tvEmpty = (TextView) findViewById(R.id.tvEmpty);
        this.btnPlayPause = (ImageButton) findViewById(R.id.btnPlayPause);
        this.btnStop = (ImageButton) findViewById(R.id.btnStop);
        this.seekPlayback = (SeekBar) findViewById(R.id.seekPlayback);
        loadRecordings();
        setupPlayer();
    }

    private File getRecordingsDir() {
        File dir = new File(getExternalFilesDir(null), "Recordings");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void loadRecordings() {
        this.recordings.clear();
        File dir = getRecordingsDir();
        File[] files = dir.listFiles(new FilenameFilter() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda2
            @Override // java.io.FilenameFilter
            public final boolean accept(File file, String str) {
                return RecordingsActivity.lambda$loadRecordings$0(file, str);
            }
        });
        if (files != null && files.length > 0) {
            Arrays.sort(files, new Comparator() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda3
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return Long.compare(((File) obj2).lastModified(), ((File) obj).lastModified());
                }
            });
            this.recordings.addAll(Arrays.asList(files));
            this.tvEmpty.setVisibility(8);
        } else {
            this.tvEmpty.setVisibility(0);
        }
        ArrayAdapter<File> adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_2, android.R.id.text1, this.recordings) { // from class: com.lynxeye.RecordingsActivity.1
            @Override // android.widget.ArrayAdapter, android.widget.Adapter
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                view.setBackgroundColor(-15658735);
                File f = (File) RecordingsActivity.this.recordings.get(position);
                TextView text1 = (TextView) view.findViewById(android.R.id.text1);
                TextView text2 = (TextView) view.findViewById(android.R.id.text2);
                text1.setText("🎙 " + f.getName().replace("LynxEye_", "").replace(".wav", "").replace(".m4a", ""));
                text1.setTextColor(-16718218);
                text1.setTextSize(14.0f);
                String duration = RecordingsActivity.this.getDuration(f);
                String size = String.format(Locale.getDefault(), "%.1f MB", Double.valueOf(f.length() / 1048576.0d));
                text2.setText(duration + "  •  " + size);
                text2.setTextColor(-7829368);
                return view;
            }
        };
        this.listView.setAdapter((ListAdapter) adapter);
        this.listView.setOnItemClickListener(new AdapterView.OnItemClickListener() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda4
            @Override // android.widget.AdapterView.OnItemClickListener
            public final void onItemClick(AdapterView adapterView, View view, int i, long j) {
                this.f$0.m174lambda$loadRecordings$2$comlynxeyeRecordingsActivity(adapterView, view, i, j);
            }
        });
        this.listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda5
            @Override // android.widget.AdapterView.OnItemLongClickListener
            public final boolean onItemLongClick(AdapterView adapterView, View view, int i, long j) {
                return this.f$0.m176lambda$loadRecordings$4$comlynxeyeRecordingsActivity(adapterView, view, i, j);
            }
        });
    }

    static /* synthetic */ boolean lambda$loadRecordings$0(File d, String name) {
        return name.endsWith(".wav") || name.endsWith(".m4a");
    }

    /* JADX INFO: renamed from: lambda$loadRecordings$2$com-lynxeye-RecordingsActivity, reason: not valid java name */
    /* synthetic */ void m174lambda$loadRecordings$2$comlynxeyeRecordingsActivity(AdapterView parent, View view, int position, long id) {
        playFile(this.recordings.get(position));
    }

    /* JADX INFO: renamed from: lambda$loadRecordings$4$com-lynxeye-RecordingsActivity, reason: not valid java name */
    /* synthetic */ boolean m176lambda$loadRecordings$4$comlynxeyeRecordingsActivity(AdapterView parent, View view, int position, long id) {
        final File f = this.recordings.get(position);
        new AlertDialog.Builder(this).setTitle("Borrar grabación").setMessage("¿Borrar " + f.getName() + "?").setPositiveButton("Borrar", new DialogInterface.OnClickListener() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda1
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m175lambda$loadRecordings$3$comlynxeyeRecordingsActivity(f, dialogInterface, i);
            }
        }).setNegativeButton("Cancelar", (DialogInterface.OnClickListener) null).show();
        return true;
    }

    /* JADX INFO: renamed from: lambda$loadRecordings$3$com-lynxeye-RecordingsActivity, reason: not valid java name */
    /* synthetic */ void m175lambda$loadRecordings$3$comlynxeyeRecordingsActivity(File f, DialogInterface d, int w) {
        File file = this.currentFile;
        if (file != null && file.equals(f)) {
            stopPlayback();
        }
        f.delete();
        loadRecordings();
        Toast.makeText(this, "Borrado", 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getDuration(File f) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(f.getAbsolutePath());
            String ms = mmr.extractMetadata(9);
            mmr.release();
            if (ms != null) {
                long secs = Long.parseLong(ms) / 1000;
                return String.format(Locale.getDefault(), "%02d:%02d", Long.valueOf(secs / 60), Long.valueOf(secs % 60));
            }
            return "--:--";
        } catch (Exception e) {
            return "--:--";
        }
    }

    private void setupPlayer() {
        this.btnPlayPause.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m178lambda$setupPlayer$5$comlynxeyeRecordingsActivity(view);
            }
        });
        this.btnStop.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda7
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m179lambda$setupPlayer$6$comlynxeyeRecordingsActivity(view);
            }
        });
        this.seekPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.lynxeye.RecordingsActivity.2
            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && RecordingsActivity.this.mediaPlayer != null) {
                    RecordingsActivity.this.mediaPlayer.seekTo(p);
                    RecordingsActivity.this.tvCurrentTime.setText(RecordingsActivity.this.formatTime(p));
                }
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override // android.widget.SeekBar.OnSeekBarChangeListener
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    /* JADX INFO: renamed from: lambda$setupPlayer$5$com-lynxeye-RecordingsActivity, reason: not valid java name */
    /* synthetic */ void m178lambda$setupPlayer$5$comlynxeyeRecordingsActivity(View v) {
        MediaPlayer mediaPlayer = this.mediaPlayer;
        if (mediaPlayer == null) {
            return;
        }
        if (this.isPlaying) {
            mediaPlayer.pause();
            this.isPlaying = false;
            this.btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        } else {
            mediaPlayer.start();
            this.isPlaying = true;
            this.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            startSeekUpdater();
        }
    }

    /* JADX INFO: renamed from: lambda$setupPlayer$6$com-lynxeye-RecordingsActivity, reason: not valid java name */
    /* synthetic */ void m179lambda$setupPlayer$6$comlynxeyeRecordingsActivity(View v) {
        stopPlayback();
    }

    private void playFile(File f) {
        stopPlayback();
        this.currentFile = f;
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            this.mediaPlayer = mediaPlayer;
            mediaPlayer.setDataSource(f.getAbsolutePath());
            this.mediaPlayer.prepare();
            int duration = this.mediaPlayer.getDuration();
            this.seekPlayback.setMax(duration);
            this.tvTotalTime.setText(formatTime(duration));
            this.tvCurrentTime.setText("00:00");
            this.tvNowPlaying.setText("▶  " + f.getName().replace("LynxEye_", "").replace(".wav", "").replace(".m4a", ""));
            this.mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { // from class: com.lynxeye.RecordingsActivity$$ExternalSyntheticLambda0
                @Override // android.media.MediaPlayer.OnCompletionListener
                public final void onCompletion(MediaPlayer mediaPlayer2) {
                    this.f$0.m177lambda$playFile$7$comlynxeyeRecordingsActivity(mediaPlayer2);
                }
            });
            this.mediaPlayer.start();
            this.isPlaying = true;
            this.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            startSeekUpdater();
        } catch (Exception e) {
            Toast.makeText(this, "Error al reproducir: " + e.getMessage(), 1).show();
        }
    }

    /* JADX INFO: renamed from: lambda$playFile$7$com-lynxeye-RecordingsActivity, reason: not valid java name */
    /* synthetic */ void m177lambda$playFile$7$comlynxeyeRecordingsActivity(MediaPlayer mp) {
        this.isPlaying = false;
        this.btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        this.seekPlayback.setProgress(0);
        this.tvCurrentTime.setText("00:00");
        this.handler.removeCallbacks(this.seekUpdater);
    }

    private void startSeekUpdater() {
        Runnable runnable = new Runnable() { // from class: com.lynxeye.RecordingsActivity.3
            @Override // java.lang.Runnable
            public void run() {
                if (RecordingsActivity.this.mediaPlayer != null && RecordingsActivity.this.isPlaying) {
                    int pos = RecordingsActivity.this.mediaPlayer.getCurrentPosition();
                    RecordingsActivity.this.seekPlayback.setProgress(pos);
                    RecordingsActivity.this.tvCurrentTime.setText(RecordingsActivity.this.formatTime(pos));
                    RecordingsActivity.this.handler.postDelayed(this, 500L);
                }
            }
        };
        this.seekUpdater = runnable;
        this.handler.post(runnable);
    }

    private void stopPlayback() {
        MediaPlayer mediaPlayer = this.mediaPlayer;
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            this.mediaPlayer.release();
            this.mediaPlayer = null;
        }
        this.isPlaying = false;
        this.handler.removeCallbacks(this.seekUpdater);
        this.btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        this.seekPlayback.setProgress(0);
        this.tvCurrentTime.setText("00:00");
        this.tvTotalTime.setText("00:00");
        this.tvNowPlaying.setText("SIN REPRODUCCIÓN");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String formatTime(int ms) {
        long s = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", Long.valueOf(s / 60), Long.valueOf(s % 60));
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}
