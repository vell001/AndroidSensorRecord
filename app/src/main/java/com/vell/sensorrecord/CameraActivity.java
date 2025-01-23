package com.vell.sensorrecord;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CameraActivity extends Activity implements SurfaceHolder.Callback, SensorEventListener, LocationListener {

    private static final String TAG = "CameraActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private double fps = 12;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread imageReadThread;
    private Handler imageReadHandler;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private CaptureRequest.Builder captureRequestBuilder;

    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private Sensor gyroscopeSensor;
    private LocationManager locationManager;
    private Location lastLocation;
    private TextView tvInfo;

    private List<String> permissions = new ArrayList<>();
    private Gson gson = new Gson();

    private File baseDir;
    private File recordDir;
    private File imageDir;
    private File frameFile;
    private File imuFile;
    private File gpsFile;
    private FileOutputStream frameOut;
    private FileOutputStream imuOut;
    private FileOutputStream gpsOut;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private boolean startRecord = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.camera_act);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // 初始化SurfaceView
        surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        tvInfo = findViewById(R.id.tv_info);

        // 初始化传感器管理器
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // 初始化位置管理器
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_CODE);
        } else {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
        }

        // 检查权限
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!checkPermissions()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
        imageReadThread = new HandlerThread("image_read");
        imageReadThread.start();
        imageReadHandler = new Handler(imageReadThread.getLooper());
        imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 10);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            double lastTimeSec = 0;

            @Override
            public void onImageAvailable(ImageReader imageReader) {
                Image image = imageReader.acquireLatestImage();
                if (image == null) {
                    return;
                }
                if (!startRecord) {
                    image.close();
                    return;
                }
                double timeSec = image.getTimestamp() / 1e9;
                if (timeSec - lastTimeSec < 1 / fps) {
                    image.close();
                    return;
                }
                updateInfoView(String.format(Locale.CHINA, "fps: %d", (int) (1 / (timeSec - lastTimeSec))));
                lastTimeSec = timeSec;
                Log.i(TAG, String.format("image_time %d real %d", image.getTimestamp(), SystemClock.elapsedRealtime()));
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                String filename = String.format(Locale.CHINA, "%.9f.jpg", timeSec);
                File imageFile = new File(imageDir, filename);

                try (RandomAccessFile file = new RandomAccessFile(imageFile, "rw"); FileChannel channel = file.getChannel()) {
                    // 分批次写入
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "写入文件时发生错误: " + e.getMessage(), e);
                    Toast.makeText(CameraActivity.this, "write frame error", Toast.LENGTH_SHORT).show();
                }
                FrameInfo frameInfo = new FrameInfo();
                frameInfo.name = filename;
                frameInfo.ts = timeSec;
                try {
                    frameOut.write(gson.toJson(frameInfo).getBytes());
                    frameOut.write('\n');
                    frameOut.flush();
                } catch (Exception e) {
                    Log.e(TAG, "write json error", e);
                    Toast.makeText(CameraActivity.this, "write frame error", Toast.LENGTH_SHORT).show();
                }
                image.close();
            }
        }, imageReadHandler);
    }

    private void updateInfoView(String info) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvInfo.setText(info);
            }
        });

    }

    private boolean checkPermissions() {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (!granted) {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                if (surfaceHolder.getSurface() != null) {
                    openCamera();
                }
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Surface changed
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        closeCamera();
    }

    private void openCamera() {
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreviewSession() {
        try {
            Surface surface = surfaceHolder.getSurface();
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.addTarget(imageReader.getSurface());

            // 禁用自动对焦
//            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            OutputConfiguration outputConfiguration = new OutputConfiguration(surface);
            OutputConfiguration imageReaderOutputConfiguration = new OutputConfiguration(imageReader.getSurface());
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(outputConfiguration);
            outputs.add(imageReaderOutputConfiguration);

            cameraDevice.createCaptureSessionByOutputConfigurations(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure capture session");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    SensorEvent lastImuSensor = null;

    private void Sensor2ImuInfo(SensorEvent event, ImuInfo imuInfo) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            imuInfo.ax = event.values[0];
            imuInfo.ay = event.values[1];
            imuInfo.az = event.values[2];
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            imuInfo.gx = event.values[0];
            imuInfo.gy = event.values[1];
            imuInfo.gz = event.values[2];
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!startRecord) {
            return;
        }
        if (lastImuSensor != null && lastImuSensor.sensor.getType() != event.sensor.getType()) {
            ImuInfo imuInfo = new ImuInfo();
            imuInfo.ts = event.timestamp / 1e9;
            Sensor2ImuInfo(event, imuInfo);
            Sensor2ImuInfo(lastImuSensor, imuInfo);

//            Log.i(TAG, "imu " + event.sensor.getType() + " " + event.timestamp + " ds " + Math.abs(event.timestamp-lastImuSensor.timestamp));
            try {
                imuOut.write(gson.toJson(imuInfo).getBytes());
                imuOut.write('\n');
                if (System.currentTimeMillis() % 1000 == 0) {
                    imuOut.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "write json error", e);
                Toast.makeText(CameraActivity.this, "write imu error", Toast.LENGTH_SHORT).show();
            }
            lastImuSensor = null;
        } else {
            lastImuSensor = event;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (!startRecord) {
            return;
        }
        Log.i(TAG, String.format("gps %d", location.getElapsedRealtimeNanos()));
        GpsInfo gpsInfo = new GpsInfo();
        gpsInfo.acc = location.getAccuracy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gpsInfo.acc_yaw = location.getBearingAccuracyDegrees();
            gpsInfo.acc_v = location.getVerticalAccuracyMeters();
            gpsInfo.acc_sp = location.getSpeedAccuracyMetersPerSecond();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            gpsInfo.acc_h = location.getMslAltitudeAccuracyMeters();
        }
        gpsInfo.alt = location.getAltitude();
        gpsInfo.lat = location.getLatitude();
        gpsInfo.lon = location.getLongitude();
        gpsInfo.sp = location.getSpeed();
        gpsInfo.yaw = location.getBearing();
        gpsInfo.provider = location.getProvider();
        gpsInfo.ts = location.getElapsedRealtimeNanos() / 1e9;
        try {
            gpsOut.write(gson.toJson(gpsInfo).getBytes());
            gpsOut.write('\n');
            gpsOut.flush();
        } catch (Exception e) {
            Log.e(TAG, "write json error", e);
            Toast.makeText(CameraActivity.this, "write gps error", Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);
    }

    public synchronized void stopRecord(View view) {
        startRecord = false;
        try {
            frameOut.close();
            imuOut.close();
            gpsOut.close();
        } catch (Exception e) {
            Log.e(TAG, "file close error", e);
        }
        updateInfoView("closed");
    }

    public synchronized void startRecord(View view) {
        baseDir = this.getExternalFilesDir("sensor_record");
        recordDir = new File(baseDir, dateFormat.format(new Date()));
        imageDir = new File(recordDir, "image");
        boolean created = imageDir.mkdirs();
        if (!created) {
            Log.e(TAG, "not create dir " + imageDir.getAbsolutePath());
            Toast.makeText(this, "文件夹创建失败 " + imageDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            return;
        }
        frameFile = new File(recordDir, "frame.txt");
        imuFile = new File(recordDir, "imu.txt");
        gpsFile = new File(recordDir, "gps.txt");

        try {
            frameOut = new FileOutputStream(frameFile);
            imuOut = new FileOutputStream(imuFile);
            gpsOut = new FileOutputStream(gpsFile);
        } catch (Exception e) {
            Log.e(TAG, "file open error", e);
            return;
        }
        startRecord = true;
    }
}