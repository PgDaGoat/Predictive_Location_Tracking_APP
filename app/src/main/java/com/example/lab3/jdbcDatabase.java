package com.example.lab3;

import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class jdbcDatabase {

    private Connection connection;

    private final String host = "127.0.0.1";
    private final String database = "mkelane";
    private final int port = 5432;
    private final String user = "postgres";
    private final String pass = "password"; 
    private String url = "jdbc:postgresql://%s:%d/%s?sslmode=disable";
    private boolean status;

    private float st_d = 0;
    private float lr_ref_angle = 0;

    private static final String TAG = "jdbcDatabase";

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "PostgreSQL JDBC Driver not found.", e);
        }
    }

    public jdbcDatabase() {
        this.url = String.format(this.url, this.host, this.port, this.database);
    }

    public boolean checkConnection() {
        connect();
        return status;
    }

    private synchronized void connect() {
        try {
            if (connection != null && !connection.isClosed()) {
                return;
            }
            connection = DriverManager.getConnection(url, user, pass);
            status = true;
            Log.d(TAG, "connected:" + status);
            createTableIfNotExist();
        } catch (Exception e) {
            status = false;
            Log.e(TAG, "Connection failed", e);
        }
    }

    private void createTableIfNotExist() {
        String sql = "CREATE TABLE IF NOT EXISTS sensor_data (" +
                "id SERIAL PRIMARY KEY, " +
                "steps INTEGER, " +
                "roll FLOAT, " +
                "pitch FLOAT, " +
                "yaw FLOAT, " +
                "acc_x FLOAT, " +
                "acc_y FLOAT, " +
                "acc_z FLOAT, " +
                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ");";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            Log.d(TAG, "Table 'sensor_data' ensured.");
        } catch (SQLException e) {
            Log.e(TAG, "Failed to create table", e);
        }
    }

    public void insertSensorData(int steps, float roll, float pitch, float yaw, float accX, float accY, float accZ) {
        try {
            connect();
            if (status) {
                String sql = "INSERT INTO sensor_data (steps, roll, pitch, yaw, acc_x, acc_y, acc_z, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setInt(1, steps);
                statement.setFloat(2, roll);
                statement.setFloat(3, pitch);
                statement.setFloat(4, yaw);
                statement.setFloat(5, accX);
                statement.setFloat(6, accY);
                statement.setFloat(7, accZ);
                statement.executeUpdate();
                Log.d(TAG, "Data inserted successfully");
            }
        } catch (SQLException e) {
            Log.e(TAG, "Insert failed", e);
        }
    }

    public String getNearestData(double testLat, double testLon) {
        String result = "No data found";
        try {
            connect();
            if (status) {
                // Get nearest road point and distance. 
                // We ensure everything is compared in SRID 3857.
                String sql = "SELECT name, " +
                             "ST_X(ST_ClosestPoint(ST_Transform(way, 3857), ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 3857))) AS road_x, " +
                             "ST_Y(ST_ClosestPoint(ST_Transform(way, 3857), ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 3857))) AS road_y, " +
                             "ST_Distance(ST_Transform(way, 3857), ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 3857)) AS dist_meters " +
                             "FROM planet_osm_roads " +
                             "WHERE name IS NOT NULL " +
                             "ORDER BY way <-> ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), ST_SRID(way)) " +
                             "LIMIT 1";

                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setDouble(1, testLon);
                statement.setDouble(2, testLat);
                statement.setDouble(3, testLon);
                statement.setDouble(4, testLat);
                statement.setDouble(5, testLon);
                statement.setDouble(6, testLat);
                statement.setDouble(7, testLon);
                statement.setDouble(8, testLat);

                ResultSet rs = statement.executeQuery();
                if (rs.next()) {
                    String roadName = rs.getString("name");
                    double roadX = rs.getDouble("road_x");
                    double roadY = rs.getDouble("road_y");
                    this.st_d = (float) rs.getDouble("dist_meters");

                    // Calculate Azimuth. 
                    // ST_MakePoint creates SRID 0 by default, so we wrap it with ST_SetSRID to match.
                    String azimuthSql = "SELECT degrees(ST_Azimuth(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 3857), ST_SetSRID(ST_MakePoint(?, ?), 3857))) AS degA_B";
                    PreparedStatement azStmt = connection.prepareStatement(azimuthSql);
                    azStmt.setDouble(1, testLon);
                    azStmt.setDouble(2, testLat);
                    azStmt.setDouble(3, roadX);
                    azStmt.setDouble(4, roadY);
                    ResultSet azRs = azStmt.executeQuery();
                    
                    if (azRs.next()) {
                        float degA_B = azRs.getFloat("degA_B");
                        degA_B = (degA_B + 90 + 360) % 360;
                        degA_B = degA_B % 180;

                        boolean isPedonRoadsNorth = testLat > roadY;
                        boolean isPedonRoadEast = testLon > roadX;

                        int left_or_right = 0;
                        if (degA_B >= 0 && degA_B < 45){
                            left_or_right = isPedonRoadEast ? 1 : -1;
                        } else if (degA_B >= 45 && degA_B < 135){
                            left_or_right = isPedonRoadsNorth ? -1 : 1;
                        } else if (degA_B >= 135 && degA_B < 180){
                            left_or_right = isPedonRoadEast ? -1 : 1;
                        }
                        
                        this.lr_ref_angle = (degA_B - 90 * left_or_right + 360) % 360;
                    }

                    result = roadName + " (" + String.format("%.1f", this.st_d) + "m)";
                    Log.d(TAG, "Nearest road found: " + result + ", lr_ref_angle: " + this.lr_ref_angle);
                } else {
                    result = "No roads found nearby";
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Query failed", e);
            result = "Error: " + e.getMessage();
        }
        return result;
    }

    public float getDistance() {
        return st_d;
    }

    public float get_lr_ref_angle() {
        return lr_ref_angle;
    }

    public synchronized void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ex) {
            Log.e(TAG, "Disconnect failed", ex);
        } finally {
            status = false;
        }
    }
}
