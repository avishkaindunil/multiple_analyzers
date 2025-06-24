package com.example.imageanalysisapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.Preview.SurfaceProvider;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
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
    private static final String TAG      = "MainActivity";
    private static final int    PERM_REQ = 42;

    private PreviewView previewView;
    private Spinner     spinnerA, spinnerB;
    private Button      applyBtn;

    private Size resA, resB;
    private ExecutorService execA, execB;
    private ProcessCameraProvider cameraProvider;
    private final Handler handler = new Handler(Looper.getMainLooper());

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            loadSupportedSizes();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, PERM_REQ);
        }

        applyBtn.setOnClickListener(v -> {
            resA = parseSize((String) spinnerA.getSelectedItem());
            resB = parseSize((String) spinnerB.getSelectedItem());
            Log.d(TAG, "Picked resA=" + resA + ", resB=" + resB);
            bindCameraX();
        });
    }

    @Override
    public void onRequestPermissionsResult(int req,
                                           @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == PERM_REQ && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            loadSupportedSizes();
        }
    }

    private void loadSupportedSizes() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String camId = cm.getCameraIdList()[0];
            CameraCharacteristics chars = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;

            Size[] sizes = map.getOutputSizes(ImageReader.class);
            List<String> labels = new ArrayList<>();
            for (Size s : sizes) {
                labels.add(s.getWidth() + "×" + s.getHeight());
            }

            ArrayAdapter<String> ad = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, labels);
            ad.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            spinnerA.setAdapter(ad);
            spinnerB.setAdapter(ad);
            if (labels.size() > 1) spinnerB.setSelection(1);

        } catch (CameraAccessException e) {
            Log.e(TAG, "loadSupportedSizes failed", e);
        }
    }

    private Size parseSize(String txt) {
        String[] p = txt.split("×");
        return new Size(
                Integer.parseInt(p[0].trim()),
                Integer.parseInt(p[1].trim())
        );
    }

    private void bindCameraX() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();

                Display display = previewView.getDisplay();
                int rotation = display.getRotation();

                /** Force aspect ratio to the Preview */
                AspectRatioStrategy ar16_9 = new AspectRatioStrategy(
                        AspectRatio.RATIO_16_9,
                        AspectRatioStrategy.FALLBACK_RULE_NONE
                );

                ResolutionSelector previewSel = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ar16_9)
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(previewSel)
                        .setTargetRotation(rotation)
                        .build();

                /** Hook up viewfinder */
                SurfaceProvider orig = previewView.getSurfaceProvider();
                preview.setSurfaceProvider(
                        ContextCompat.getMainExecutor(this),
                        request -> {
                            Size d = request.getResolution();
                            Log.d(TAG, "SurfaceRequest preview (16:9): "
                                    + d.getWidth() + "×" + d.getHeight());
                            orig.onSurfaceRequested(request);
                        }
                );

                /** ImageAnalysisUseCaseA */
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
                imageAnalysisUseCaseA.setAnalyzer(execA, img -> logFrame("AnalyzerA", resA, img));


                /** ImageAnalysisUseCaseA */
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
                imageAnalysisUseCaseA.setAnalyzer(execB, img -> logFrame("AnalyzerB", resB, img));

                /** Bind to all useCases */
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysisUseCaseA,
                        imageAnalysisUseCaseB
                );

                /** Log final preview resolution & aspect */
                ResolutionInfo info = preview.getResolutionInfo();
                if (info != null) {
                    Size actual = info.getResolution();
                    String ratio = getAspectRatio(
                            actual.getWidth(), actual.getHeight());
                    Log.d(TAG, "getResolutionInfo(): "
                            + actual.getWidth() + "×" + actual.getHeight()
                            + "  (Aspect Ratio: " + ratio + ")");
                } else {
                    Log.w(TAG, "ResolutionInfo is null");
                }

            } catch (Exception e) {
                Log.e(TAG, "bindCameraX failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void logFrame(String tag, Size requested, ImageProxy img) {
        long ts = img.getImageInfo().getTimestamp();
        String msg = "req=" + requested.getWidth() + "×" + requested.getHeight()
                + ", act=" + img.getWidth() + "×" + img.getHeight()
                + ", ts=" + ts;
        handler.postDelayed(() -> {
            Log.d(tag, msg);
            img.close();
        }, 10000);
    }

    private String getAspectRatio(int w, int h) {
        int gcd = gcd(w, h);
        return (w / gcd) + ":" + (h / gcd);
    }

    private int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        execA.shutdown();
        execB.shutdown();
    }
}
