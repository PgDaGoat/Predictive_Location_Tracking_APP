package com.example.lab3;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    private int steps = 0;
    private Handler mUiHandler = new Handler(Looper.getMainLooper());
    private Handler recyclerHandler = new Handler(Looper.getMainLooper());

    private float[] mLinearAcceleration = new float[3];
    private float[] mLPFLinearAcceleration = new float[3];
    private static final float LP_ALPHA = 0.5f; 
    private static final float STEP_ON_THRESHOLD = 3.5f;  
    private static final float STEP_OFF_THRESHOLD = 2.0f; 
    private boolean aboveThreshold = false;

    private muse_plus musePlus;
    public jdbcDatabase db;
    private OHA oha;
    private ModelInference modelInference;
    private int updateCounter = 0;

    private float[] accelerometerValues = new float[3];
    private float[] magneticValues = new float[3];
    private float[] euler_angles = new float[3];
    private float bearing_from_GPS = -1.0f;
    private float current_heading = -1.0f;
    private float approachAngle = 0.0f;
    private float nnOutput = 0.0f;
    private boolean crossingDetected = false;

    private RecyclerView mRecyclerView;
    private DataAdapter mAdapter;
    private List<itemData> mDataList;

    private static final long UPDATE_INTERVAL_MS = 500L;
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDataList != null && mAdapter != null && mDataList.size() >= 8) {
                // Sensors are no longer displayed in the recycler view
                mDataList.get(4).changeValue(String.format(Locale.getDefault(), "%.2f", current_heading));
                mDataList.get(5).changeValue(String.format(Locale.getDefault(), "%.2f", approachAngle));
                mDataList.get(6).changeValue(String.format(Locale.getDefault(), "%.4f", nnOutput));
                mDataList.get(7).changeValue(crossingDetected ? "Yes" : "No");
                
                mAdapter.notifyItemRangeChanged(4, 4);
            }
            
            updateCounter++;
            if (updateCounter >= 10) { 
                saveSensorDataToDatabase();
                updateCounter = 0;
            }
            recyclerHandler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    private SensorManager sensorManager;
    private Sensor magneticSensor;
    private Sensor accelSensor;
    private Sensor linearAccelSensor;
    private Sensor gyroSensor;
    private Sensor gravitySensor;

    private LocationManager locationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDataList = getInitData();
        mRecyclerView = findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        // Completely remove ItemAnimator to eliminate any flickering/animations during updates
        mRecyclerView.setItemAnimator(null);
        // Optimize performance since item count and sizes are generally stable
        mRecyclerView.setHasFixedSize(true);

        mAdapter = new DataAdapter(mDataList);
        mRecyclerView.setAdapter(mAdapter);

        musePlus = new muse_plus();
        db = new jdbcDatabase();
        oha = new OHA();
        modelInference = new ModelInference(this);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }

        registerSensors();
    }

    public void onStartGPSClick(View v) {
        startLocationUpdates();
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, this);
                Toast.makeText(this, "GPS Updates Started", Toast.LENGTH_SHORT).show();
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        if (location.hasBearing()) {
            bearing_from_GPS = location.getBearing();
        }
        Log.d("Location", "Lat: " + lat + ", Lon: " + lon + ", Bearing: " + bearing_from_GPS);
        
        runOnUiThread(() -> {
            if (mDataList != null && mAdapter != null) {
                if (mDataList.size() >= 3) {
                    mDataList.get(1).changeValue(String.format(Locale.getDefault(), "%.6f", lat));
                    mDataList.get(2).changeValue(String.format(Locale.getDefault(), "%.6f", lon));
                    mAdapter.notifyItemRangeChanged(1, 2);
                } else {
                    mDataList.add(new itemData("Lat/Lon", String.format(Locale.getDefault(), "%.6f, %.6f", lat, lon)));
                    mAdapter.notifyItemInserted(mDataList.size() - 1);
                }
            }
        });

        new Thread(() -> {
            if (db != null) {
                final String result = db.getNearestData(lat, lon);
                runOnUiThread(() -> {
                    if (mDataList != null && mAdapter != null) {
                        if (mDataList.size() >= 4) {
                            mDataList.get(3).changeValue(result);
                            mAdapter.notifyItemChanged(3);
                        } else {
                            mDataList.add(new itemData("Nearest Data", result));
                            mAdapter.notifyItemInserted(mDataList.size() - 1);
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerSensors();
        recyclerHandler.post(updateRunnable);
        modelInference.startInference();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterSensors();
        recyclerHandler.removeCallbacks(updateRunnable);
        modelInference.stopInference();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) {
            new Thread(() -> db.disconnect()).start();
        }
        modelInference.destroy();
    }

    private void registerSensors() {
        if (sensorManager != null) {
            if (magneticSensor != null) sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_FASTEST);
            if (accelSensor != null) sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
            if (linearAccelSensor != null) sensorManager.registerListener(this, linearAccelSensor, SensorManager.SENSOR_DELAY_GAME);
            if (gyroSensor != null) sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
            if (gravitySensor != null) sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    private void unregisterSensors() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    public void addItem(View v) {
        if (mDataList != null && mAdapter != null) {
            mDataList.add(new itemData("Achievement", (steps / 2) + " Steps!"));
            mAdapter.notifyItemInserted(mDataList.size() - 1);
        }
    }

    public void stepReset(View v) {
        steps = 0;
        euler_angles = new float[3];
        mLPFLinearAcceleration = new float[3];
        current_heading = -1.0f;
        approachAngle = 0.0f;
        nnOutput = 0.0f;
        crossingDetected = false;

        List<itemData> resetData = getInitData();
        if (mDataList != null) {
            for (int i = 0; i < resetData.size() && i < mDataList.size(); i++) {
                mDataList.get(i).changeValue(resetData.get(i).itemValue);
            }
            // Optional: trim back to initial size if extra items were added
            while (mDataList.size() > resetData.size()) {
                mDataList.remove(mDataList.size() - 1);
            }
        }

        mUiHandler.post(() -> {
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        });
        Toast.makeText(this, "All values reset", Toast.LENGTH_SHORT).show();
    }
    
    private void saveSensorDataToDatabase() {
        final int currentSteps = steps;
        final float roll = euler_angles[0];
        final float pitch = euler_angles[1];
        final float yaw = euler_angles[2];
        final float accX = mLPFLinearAcceleration[0];
        final float accY = mLPFLinearAcceleration[1];
        final float accZ = mLPFLinearAcceleration[2];

        new Thread(() -> {
            if (db != null) db.insertSensorData(currentSteps, roll, pitch, yaw, accX, accY, accZ);
        }).start();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            mLinearAcceleration[0] = event.values[0];
            mLinearAcceleration[1] = event.values[1];
            mLinearAcceleration[2] = event.values[2];
            mLPFLinearAcceleration[0] = LP_ALPHA * mLinearAcceleration[0] + (1 - LP_ALPHA) * mLPFLinearAcceleration[0];
            mLPFLinearAcceleration[1] = LP_ALPHA * mLinearAcceleration[1] + (1 - LP_ALPHA) * mLPFLinearAcceleration[1];
            mLPFLinearAcceleration[2] = LP_ALPHA * mLinearAcceleration[2] + (1 - LP_ALPHA) * mLPFLinearAcceleration[2];
            float magnitude = (float) Math.sqrt(mLPFLinearAcceleration[0] * mLPFLinearAcceleration[0] + mLPFLinearAcceleration[1] * mLPFLinearAcceleration[1] + mLPFLinearAcceleration[2] * mLPFLinearAcceleration[2]);
            if (magnitude > STEP_ON_THRESHOLD && !aboveThreshold) {
                steps++;
                aboveThreshold = true;
            } else if (magnitude < STEP_OFF_THRESHOLD && aboveThreshold) {
                aboveThreshold = false;
            }
            mUiHandler.post(() -> {
                if (mDataList != null && !mDataList.isEmpty()) {
                    mDataList.get(0).changeValue(String.valueOf(steps));
                    mAdapter.notifyItemChanged(0);
                }
            });
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accelerometerValues[0] = event.values[0]; accelerometerValues[1] = event.values[1]; accelerometerValues[2] = event.values[2];
            if (musePlus != null) musePlus.update_acc(accelerometerValues);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (musePlus != null) {
                musePlus.update_gyro(event.values, event.timestamp);
                float[] tempEuler = musePlus.get_EulerAngles();
                if (tempEuler != null) {
                    euler_angles = tempEuler;
                    if (oha != null) {
                        current_heading = oha.query_update(euler_angles, bearing_from_GPS);
                    }
                }
            }
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticValues[0] = event.values[0]; magneticValues[1] = event.values[1]; magneticValues[2] = event.values[2];
            if (musePlus != null) musePlus.update_mag(magneticValues, event.timestamp);
        } else if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
            if (musePlus != null) musePlus.update_gravity(event.values);
        }
    }

    public float getCurrentHeading() {
        return current_heading;
    }

    public void updateApproachAngleUI(float angle) {
        runOnUiThread(() -> {
            this.approachAngle = angle;
        });
    }

    public void updateNNOutputUI(float output, boolean crossingDetected) {
        runOnUiThread(() -> {
            this.nnOutput = output;
            this.crossingDetected = crossingDetected;
        });
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}

    private static class DataAdapter extends RecyclerView.Adapter<DataAdapter.ViewHolder> {
        private List<itemData> list;
        public DataAdapter(List<itemData> list) { this.list = list; }
        @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.single_unit, parent, false);
            return new ViewHolder(view);
        }
        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            itemData item = list.get(position);
            holder.itemName.setText(item.itemName);
            holder.itemValue.setText(item.itemValue);
        }
        @Override public int getItemCount() { return list.size(); }
        public static class ViewHolder extends RecyclerView.ViewHolder {
            TextView itemName; TextView itemValue;
            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                itemName = itemView.findViewById(R.id.item_name);
                itemValue = itemView.findViewById(R.id.item_value);
            }
        }
    }

    private List<itemData> getInitData() {
        List<itemData> list = new ArrayList<>();
        list.add(new itemData("Step Counter", "0"));
        list.add(new itemData("Latitude", "0.000000"));
        list.add(new itemData("Longitude", "0.000000"));
        list.add(new itemData("Distance to Road", "N/A"));
        list.add(new itemData("Current Heading", "0.00"));
        list.add(new itemData("Approach Angle", "0.00"));
        list.add(new itemData("NN Output", "0.0000"));
        list.add(new itemData("Crossing Detected", "No"));
        return list;
    }

    private static class itemData {
        String itemName; String itemValue;
        itemData(String itemName, String itemValue) { this.itemName = itemName; this.itemValue = itemValue; }
        void changeValue(String itemValue) { this.itemValue = itemValue; }
    }
}
