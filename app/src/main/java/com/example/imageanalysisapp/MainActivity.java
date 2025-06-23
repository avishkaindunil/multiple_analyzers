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

        // 1) Camera permission → populate spinners
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            loadSupportedSizes();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, PERM_REQ);
        }

        // 2) On Apply → read selections & bind
        applyBtn.setOnClickListener(v -> {
            resA = toSize((String)spinnerA.getSelectedItem());
            resB = toSize((String)spinnerB.getSelectedItem());
            Log.d(TAG, "Chosen: A=" + resA + ", B=" + resB);
            bindCameraX();
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req==PERM_REQ && grants[0]==PackageManager.PERMISSION_GRANTED) {
            loadSupportedSizes();
        }
    }

    private void loadSupportedSizes() {
        try {
            CameraManager cm = (CameraManager)getSystemService(CAMERA_SERVICE);
            String camId = cm.getCameraIdList()[0];
            CameraCharacteristics chars = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

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
            Log.e(TAG, "Failed to load sizes", e);
        }
    }

    private Size toSize(String txt) {
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

                int rotation = previewView.getDisplay().getRotation();

                /** Preview use case */
                Preview preview = new Preview.Builder()
                        .setTargetRotation(rotation)
                        .build();
                preview.setSurfaceProvider(
                        previewView.getSurfaceProvider()
                );

                /** imageAnalysisUseCaseA */
                ResolutionSelector selA = new ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new ResolutionStrategy(
                                        resA,
                                        ResolutionStrategy.FALLBACK_RULE_NONE))
                        .build();
                ImageAnalysis imageAnalysisUseCaseA = new ImageAnalysis.Builder()
                        .setResolutionSelector(selA)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysisUseCaseA.setAnalyzer(execA, this::logFrame);

                /** imageAnalysisUseCaseB */
                ResolutionSelector selB = new ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new ResolutionStrategy(
                                        resB,
                                        ResolutionStrategy.FALLBACK_RULE_NONE))
                        .build();
                ImageAnalysis imageAnalysisUseCaseB = new ImageAnalysis.Builder()
                        .setResolutionSelector(selB)
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysisUseCaseB.setAnalyzer(execB, this::logFrame);

                /** Bind all use cases */
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

    private void logFrame(ImageProxy img) {
        long ts = img.getImageInfo().getTimestamp();
        String tag = img.getWidth()==resA.getWidth() &&
                img.getHeight()==resA.getHeight()
                ? "AnalyzerA" : "AnalyzerB";
        Log.d(tag,
                "requested=" + (tag.equals("AnalyzerA")?resA:resB) +
                        ", actual=" + img.getWidth()+"×"+img.getHeight() +
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
