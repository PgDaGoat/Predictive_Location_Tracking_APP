Partners: Peter Giannetos and Sam Winn

Threshold Details:
The model uses a crossing threshold of 0.5 to determine if a pedestrian is currently crossing a road. This value was chosen as a balanced midpoint for the LSTM model's binary classification output, ensuring the app remains responsive to crossing events while minimizing false positives from sensor noise.

Crossing Detection Logic:
- Input Window: The app maintains a window of the last 80 sensor readings (QUEUE_SIZE = 80) in ModelInference.java.
- Feature Engineering: It calculates the approach angle using the cosine of the difference between the user's current heading and the road's reference angle (lrRefAngle).
- Low-Pass Filter: A Low-Pass Filter with an alpha of 0.5 is applied to linear acceleration data in MainActivity.java to smooth out noise before step detection.
- Haptic Feedback: A vibration is triggered only when the model's output transitions from below to above the 0.5 threshold, preventing continuous vibrating during a single crossing event.

UI Optimization:
- Removed high-frequency sensor data (Roll, Pitch, Yaw, Accel) from the RecyclerView to prevent UI flickering and jumping.
- Disabled RecyclerView's ItemAnimator to ensure smooth scrolling and static field updates.
- Consolidated UI refreshes to a 500ms interval for better performance.