package com.example.imageanalysisapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG           = "MainActivity";
    private static final int    REQUEST_CAMERA = 42;

    private PreviewView previewView;
    private Spinner     spinnerA, spinnerB;
    private Button      applyButton;

    private Size resA, resB;
    private ExecutorService execA, execB;
    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView  = findViewById(R.id.previewView);
        spinnerA     = findViewById(R.id.spinnerA);
        spinnerB     = findViewById(R.id.spinnerB);
        applyButton  = findViewById(R.id.applyButton);

        execA = Executors.newSingleThreadExecutor();
        execB = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            populateResolutions();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA
            );
        }

        applyButton.setOnClickListener(v -> {
            resA = parse((String) spinnerA.getSelectedItem());
            resB = parse((String) spinnerB.getSelectedItem());
            startCamera();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code,
                                           @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQUEST_CAMERA
                && grants.length>0
                && grants[0]==PackageManager.PERMISSION_GRANTED) {
            populateResolutions();
        }
    }

    private void populateResolutions() {
        try {
            CameraManager cm = (CameraManager)getSystemService(CAMERA_SERVICE);
            String camId = cm.getCameraIdList()[0];
            CameraCharacteristics chars =
                    cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map.getOutputSizes(ImageReader.class);

            List<String> labels = new ArrayList<>();
            for (Size s : sizes) {
                labels.add(s.getWidth()+"×"+s.getHeight());
            }

            ArrayAdapter<String> ad = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, labels);
            ad.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            spinnerA.setAdapter(ad);
            spinnerB.setAdapter(ad);

            if (labels.size()>1) spinnerB.setSelection(1);
        } catch (CameraAccessException e) {
            Log.e(TAG, "populateResolutions failed", e);
        }
    }

    private Size parse(String txt) {
        String[] p = txt.split("×");
        return new Size(Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Display display = previewView.getDisplay();
                int rotation = display.getRotation();

                // Preview
                Preview preview = new Preview.Builder()
                        .setTargetRotation(rotation)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                /** imageAnalysisUseCaseA */
                ImageAnalysis imageAnalysisUseCaseA = new ImageAnalysis.Builder()
                        .setTargetResolution(resA)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysisUseCaseA.setAnalyzer(execA, this::logFrameA);

                /** imageAnalysisUseCaseB */
                ImageAnalysis imageAnalysisUseCaseB = new ImageAnalysis.Builder()
                        .setTargetResolution(resB)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysisUseCaseB.setAnalyzer(execB, this::logFrameB);

                // Bind all use-cases (may negotiate separate sessions)
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysisUseCaseA,
                        imageAnalysisUseCaseB
                );

            } catch (Exception e) {
                Log.e(TAG, "CameraProvider failure", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void logFrameA(ImageProxy img) {
        long ts = img.getImageInfo().getTimestamp();
        Log.d("AnalyzerA",
                "req=" + resA + ", act=" + img.getWidth()+"×"+img.getHeight() +
                        ", ts=" + ts);
        img.close();
    }

    private void logFrameB(ImageProxy img) {
        long ts = img.getImageInfo().getTimestamp();
        Log.d("AnalyzerB",
                "req=" + resB + ", act=" + img.getWidth()+"×"+img.getHeight() +
                        ", ts=" + ts);
        img.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        execA.shutdown();
        execB.shutdown();
    }
}
