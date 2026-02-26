package com.example.lab3;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Pair;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.Queue;

public class ModelInference {
    private HandlerThread handlerThread;
    private Handler handler;
    private MainActivity mainActivity;
    private Queue<Pair<Float, Float>> dataQueue = new LinkedList<>();
    private static final int QUEUE_SIZE = 80;
    private Interpreter tflite;
    private static final String TAG = "ModelInference";
    private static final float CROSSING_THRESHOLD = 0.5f;
    private boolean crossingDetected = false;

    private final Runnable inferenceRunnable = new Runnable() {
        @Override
        public void run() {
            if (mainActivity != null) {
                float distance = mainActivity.db.getDistance();
                float currentHeading = mainActivity.getCurrentHeading();
                float lrRefAngle = mainActivity.db.get_lr_ref_angle();
                
                float approachAngle = (float) Math.cos(Math.toRadians(lrRefAngle - currentHeading));
                
                synchronized (dataQueue) {
                    if (dataQueue.size() >= QUEUE_SIZE) {
                        dataQueue.poll();
                    }
                    dataQueue.add(new Pair<>(distance, approachAngle));

                    if (dataQueue.size() == QUEUE_SIZE && tflite != null) {
                        float[][][] input = new float[1][80][2];
                        int i = 0;
                        for (Pair<Float, Float> pair : dataQueue) {
                            input[0][i][0] = pair.first;
                            input[0][i][1] = pair.second;
                            i++;
                        }
                        float[][] output = new float[1][1];
                        tflite.run(input, output);
                        float nnOutput = output[0][0];
                        
                        boolean prevDetected = crossingDetected;
                        crossingDetected = nnOutput > CROSSING_THRESHOLD;
                        
                        mainActivity.updateNNOutputUI(nnOutput, crossingDetected);
                        
                        if (crossingDetected && !prevDetected) {
                            vibrate();
                        }
                    }
                }

                mainActivity.updateApproachAngleUI(approachAngle);
            }
            handler.postDelayed(this, 100);
        }
    };

    public ModelInference(MainActivity activity) {
        this.mainActivity = activity;
        try {
            tflite = new Interpreter(loadModelFile());
            Log.d(TAG, "TFLite model loaded successfully");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load TFLite model", e);
        }
        handlerThread = new HandlerThread("ModelInferenceThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = mainActivity.getAssets().openFd("model_2lstm_d_cosHR.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void vibrate() {
        Vibrator v = (Vibrator) mainActivity.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(500);
            }
        }
    }

    public void startInference() {
        handler.post(inferenceRunnable);
    }

    public void stopInference() {
        handler.removeCallbacks(inferenceRunnable);
    }

    public void destroy() {
        if (tflite != null) {
            tflite.close();
        }
        handlerThread.quitSafely();
    }
}
