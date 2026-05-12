package com.pubgrecorder;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1001;
    private static final int REQUEST_CODE_PERMISSIONS    = 1002;
    private static final int TARGET_FPS     = 120;
    private static final int VIDEO_BITRATE  = 16_000_000;
    private static final int IFRAME_INTERVAL = 1;

    private MediaProjectionManager projectionManager;
    private MediaProjection         mediaProjection;
    private VirtualDisplay          virtualDisplay;
    private MediaCodec              videoEncoder;
    private MediaMuxer              mediaMuxer;
    private Surface                 inputSurface;

    private int screenWidth, screenHeight, screenDensity;
    private boolean isRecording  = false;
    private boolean muxerStarted = false;
    private int     videoTrackIndex = -1;

    private Thread  encoderThread;
    private Handler mainHandler;

    private Button   btnRecord;
    private TextView tvStatus, tvFps, tvInfo;
    private long     recordingStartTime;
    private int      frameCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        tvStatus  = findViewById(R.id.tvStatus);
        tvFps     = findViewById(R.id.tvFps);
        tvInfo    = findViewById(R.id.tvInfo);
        btnRecord = findViewById(R.id.btnRecord);

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        getScreenMetrics();
        displayDeviceInfo();

        btnRecord.setOnClickListener(v -> {
            if (!isRecording) startRecording();
            else              stopRecording();
        });

        checkPermissions();
    }

    private void getScreenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        screenWidth   = metrics.widthPixels;
        screenHeight  = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
    }

    @SuppressLint("SetTextI18n")
    private void displayDeviceInfo() {
        tvInfo.setText(
            "Resolution: " + screenWidth + "x" + screenHeight +
            "\nTarget FPS: " + TARGET_FPS + " fps" +
            "\nBitrate: " + (VIDEO_BITRATE / 1_000_000) + " Mbps" +
            "\nMode: PUBG Low-Lag Optimized"
        );
    }

    private void checkPermissions() {
        String[] perms = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        };
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted)
            ActivityCompat.requestPermissions(this, perms, REQUEST_CODE_PERMISSIONS);
    }

    private void startRecording() {
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode != Activity.RESULT_OK) {
                Toast.makeText(this, "Permission denied!", Toast.LENGTH_SHORT).show();
                return;
            }
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            initRecorder();
        }
    }

    @SuppressLint("SetTextI18n")
    private void initRecorder() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "PUBGRecorder");
            if (!dir.exists()) dir.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File outputFile = new File(dir, "PUBG_" + timeStamp + ".mp4");

            mediaMuxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,      VIDEO_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE,    TARGET_FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LATENCY, 0);
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "PUBGRecorder",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null);

            isRecording      = true;
            muxerStarted     = false;
            videoTrackIndex  = -1;
            frameCount       = 0;
            recordingStartTime = System.currentTimeMillis();

            btnRecord.setText("Stop Recording");
            tvStatus.setText("Recording... (120 FPS)");

            startEncoderThread();
            startFpsCounter();

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startEncoderThread() {
        encoderThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (isRecording) {
                int outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        MediaFormat newFormat = videoEncoder.getOutputFormat();
                        videoTrackIndex = mediaMuxer.addTrack(newFormat);
                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                } else if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        java.nio.ByteBuffer encodedData =
                                videoEncoder.getOutputBuffer(outputBufferIndex);
                        if (encodedData != null) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);