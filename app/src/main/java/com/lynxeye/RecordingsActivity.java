package com.lynxeye;

import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingsActivity extends AppCompatActivity {

    private ListView listView;
    private TextView tvNowPlaying, tvCurrentTime, tvTotalTime, tvEmpty;
    private ImageButton btnPlayPause, btnStop;
    private SeekBar seekPlayback;

    private MediaPlayer mediaPlayer;
    private File currentFile;
    private List<File> recordings = new ArrayList<>();
    private Handler handler = new Handler();
    private Runnable seekUpdater;
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);

        listView     = findViewById(R.id.listRecordings);
        tvNowPlaying = findViewById(R.id.tvNowPlaying);
        tvCurrentTime= findViewById(R.id.tvCurrentTime);
        tvTotalTime  = findViewById(R.id.tvTotalTime);
        tvEmpty      = findViewById(R.id.tvEmpty);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnStop      = findViewById(R.id.btnStop);
        seekPlayback = findViewById(R.id.seekPlayback);

        loadRecordings();
        setupPlayer();
    }

    private File getRecordingsDir() {
        File dir = new File(getExternalFilesDir(null), "Recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void loadRecordings() {
        recordings.clear();
        File dir = getRecordingsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".wav") || name.endsWith(".m4a"));
        if (files != null && files.length > 0) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            recordings.addAll(Arrays.asList(files));
            tvEmpty.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.VISIBLE);
        }

        ArrayAdapter<File> adapter = new ArrayAdapter<File>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, recordings) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                view.setBackgroundColor(0xFF111111);

                File f = recordings.get(position);
                TextView text1 = view.findViewById(android.R.id.text1);
                TextView text2 = view.findViewById(android.R.id.text2);

                text1.setText("🎙 " + f.getName().replace("LynxEye_", "").replace(".wav", "").replace(".m4a", ""));
                text1.setTextColor(0xFF00E676);
                text1.setTextSize(14);

                // Get duration
                String duration = getDuration(f);
                String size = String.format(Locale.getDefault(), "%.1f MB", f.length() / 1048576.0);
                text2.setText(duration + "  •  " + size);
                text2.setTextColor(0xFF888888);

                return view;
            }
        };

        listView.setAdapter(adapter);

        // Tap to play
        listView.setOnItemClickListener((parent, view, position, id) -> {
            playFile(recordings.get(position));
        });

        // Long press to delete
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            File f = recordings.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("Borrar grabación")
                    .setMessage("¿Borrar " + f.getName() + "?")
                    .setPositiveButton("Borrar", (d, w) -> {
                        if (currentFile != null && currentFile.equals(f)) stopPlayback();
                        f.delete();
                        loadRecordings();
                        Toast.makeText(this, "Borrado", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return true;
        });
    }

    private String getDuration(File f) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(f.getAbsolutePath());
            String ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            mmr.release();
            if (ms != null) {
                long secs = Long.parseLong(ms) / 1000;
                return String.format(Locale.getDefault(), "%02d:%02d", secs / 60, secs % 60);
            }
        } catch (Exception ignored) {}
        return "--:--";
    }

    private void setupPlayer() {
        btnPlayPause.setOnClickListener(v -> {
            if (mediaPlayer == null) return;
            if (isPlaying) {
                mediaPlayer.pause();
                isPlaying = false;
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            } else {
                mediaPlayer.start();
                isPlaying = true;
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                startSeekUpdater();
            }
        });

        btnStop.setOnClickListener(v -> stopPlayback());

        seekPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(p);
                    tvCurrentTime.setText(formatTime(p));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void playFile(File f) {
        stopPlayback();
        currentFile = f;

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(f.getAbsolutePath());
            mediaPlayer.prepare();

            int duration = mediaPlayer.getDuration();
            seekPlayback.setMax(duration);
            tvTotalTime.setText(formatTime(duration));
            tvCurrentTime.setText("00:00");
            tvNowPlaying.setText("▶  " + f.getName().replace("LynxEye_", "").replace(".wav","").replace(".m4a",""));

            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                seekPlayback.setProgress(0);
                tvCurrentTime.setText("00:00");
                handler.removeCallbacks(seekUpdater);
            });

            mediaPlayer.start();
            isPlaying = true;
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            startSeekUpdater();

        } catch (Exception e) {
            Toast.makeText(this, "Error al reproducir: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startSeekUpdater() {
        seekUpdater = new Runnable() {
            @Override public void run() {
                if (mediaPlayer != null && isPlaying) {
                    int pos = mediaPlayer.getCurrentPosition();
                    seekPlayback.setProgress(pos);
                    tvCurrentTime.setText(formatTime(pos));
                    handler.postDelayed(this, 500);
                }
            }
        };
        handler.post(seekUpdater);
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isPlaying = false;
        handler.removeCallbacks(seekUpdater);
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        seekPlayback.setProgress(0);
        tvCurrentTime.setText("00:00");
        tvTotalTime.setText("00:00");
        tvNowPlaying.setText("SIN REPRODUCCIÓN");
    }

    private String formatTime(int ms) {
        long s = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}
