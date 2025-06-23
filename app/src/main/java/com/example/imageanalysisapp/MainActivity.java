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
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
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
    private static final String TAG = "MainActivity";
    private static final int    PERM_REQ = 42;

    private PreviewView previewView;
    private Spinner     spinnerA, spinnerB;
    private Button      applyBtn;

    private Size resA, resB;
    private ExecutorService execA, execB;
    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        spinnerA    = findViewById(R.id.spinnerA);
        spinnerB    = findViewById(R.id.spinnerB);
        applyBtn    = findViewById(R.id.applyButton);

        execA = Executors.newSingleThreadExecutor();
        execB = Executors.newSingleThreadExecutor();

        // 1) Ask permission, then load sizes
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            loadSizes();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, PERM_REQ);
        }

        // 2) On Apply, remember selections & start
        applyBtn.setOnClickListener(v -> {
            resA = parseSize((String)spinnerA.getSelectedItem());
            resB = parseSize((String)spinnerB.getSelectedItem());
            Log.d(TAG, "Picked resA=" + resA + " resB=" + resB);
            bindCameraX();
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req==PERM_REQ && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) {
            loadSizes();
        } else {
            Log.e(TAG, "Camera denied");
        }
    }

    private void loadSizes() {
        try {
            CameraManager cm = (CameraManager)getSystemService(CAMERA_SERVICE);
            String camId = cm.getCameraIdList()[0];
            CameraCharacteristics chars = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map==null) return;

            Size[] sizes = map.getOutputSizes(ImageReader.class);
            List<String> labels = new ArrayList<>();
            for (Size s: sizes) labels.add(s.getWidth()+"×"+s.getHeight());

            ArrayAdapter<String> ad = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, labels);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerA.setAdapter(ad);
            spinnerB.setAdapter(ad);

            if (labels.size()>1) spinnerB.setSelection(1);

        } catch (CameraAccessException e) {
            Log.e(TAG, "loadSizes failed", e);
        }
    }

    private Size parseSize(String txt) {
        String[] p = txt.split("×");
        return new Size(Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim()));
    }

    private void bindCameraX() {
        ListenableFuture<ProcessCameraProvider> f =
                ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                cameraProvider = f.get();
                cameraProvider.unbindAll();

                int rot = previewView.getDisplay().getRotation();

                // — Preview (no forced size) —
                Preview preview = new Preview.Builder()
                        .setTargetRotation(rot)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // — Analyzer A (exact resA) —
                ResolutionSelector selA = new ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new ResolutionStrategy(resA,
                                        ResolutionStrategy.FALLBACK_RULE_NONE))
                        .build();
                ImageAnalysis imageAnalysisUseCaseA = new ImageAnalysis.Builder()
                        .setResolutionSelector(selA)
                        .setTargetRotation(rot)
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysisUseCaseA.setAnalyzer(execA, this::logA);

                // — Analyzer B (exact resB) —
                ResolutionSelector selB = new ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new ResolutionStrategy(resB,
                                        ResolutionStrategy.FALLBACK_RULE_NONE))
                        .build();
                ImageAnalysis imageAnalysisUseCaseB = new ImageAnalysis.Builder()
                        .setResolutionSelector(selB)
                        .setTargetRotation(rot)
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysisUseCaseB.setAnalyzer(execB, this::logB);

                // — Bind all at once —
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysisUseCaseA,
                        imageAnalysisUseCaseB
                );

            } catch (Exception e) {
                Log.e(TAG, "bindCameraX failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void logA(ImageProxy img) {
        long ts = img.getImageInfo().getTimestamp();
        Log.d("AnalyzerA",
                "req=" + resA + ", act=" + img.getWidth()+"×"+img.getHeight() +
                        ", ts=" + ts);
        img.close();
    }
    private void logB(ImageProxy img) {
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
