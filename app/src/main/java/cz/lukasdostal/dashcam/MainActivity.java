package cz.lukasdostal.dashcam;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private TextureView preview;
    private CountDownTimer countDownTimer;
    private long timeInMiliseconds = 180000; //3minuty
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            setupCamera();
            transformImage(i, i1);
            connectCamera();

        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            Toast.makeText(getApplicationContext(), "TextureView size is changed", Toast.LENGTH_SHORT).show();

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };
    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            if (mIsRecording) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                startRecord();
                mMediaRecorder.start();
            } else {

            startPreview();
            }
            Toast.makeText(getApplicationContext(), "fotoapar??t p??ipojen", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;

        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            mCameraDevice = null;

        }
    };

    private MediaRecorder mMediaRecorder;
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private Size mVideoSize;
    private int mTotalRotation = 0;
    private int mBitrate = 2000000;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private ImageButton mRecordImageButton;
    private boolean mIsRecording = false;
    private File mVideoFolder;
    private String mVideoFileName;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size size, Size t1) {
            return Long.signum((long) size.getWidth() * size.getHeight() / (long) t1.getWidth() + t1.getHeight());
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        createVideoFolder();

        mMediaRecorder = new MediaRecorder();
        preview = (TextureView) findViewById(R.id.videoPreview);
        mRecordImageButton = (ImageButton) findViewById(R.id.recordVideoButton);
        mRecordImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(mIsRecording) {
                    mIsRecording = false;
                    mRecordImageButton.setImageResource(R.drawable.record);
                    stopTimer();
                } else {
                    checkWriteStoragePermission();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();


        startBackgroundThread();


    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQEST_CAMERA_PERMISSION_RESULT) {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(getApplicationContext(), "Aplikace Dashcam pot??ebuje slu??by fotoapar??tu", Toast.LENGTH_SHORT).show();
            }
        }
        if(requestCode == REQEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mIsRecording = true;
                mRecordImageButton.setImageResource(R.drawable.recording);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(this, "[DEBUG]povolen?? ud??leno", Toast.LENGTH_SHORT).show();
            } else{
                Toast.makeText(this, "Aplikace Dashcam pot??ebuje opr??vn??n?? ke sv??mu fungov??n??", Toast.LENGTH_SHORT).show();
            }
        }
    }


    @Override
    protected void onPause() {
        closeCamera();

        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if(hasFocus) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }

        if(preview.isAvailable()) {
            setupCamera();
            transformImage(getScreenWidth(), getScreenHeight());
            connectCamera();
        } else {
            preview.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        startBackgroundThread();
    }

    private void setupCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cameraManager.getCameraIdList()){
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG));
                mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder.class));
                mCameraId = cameraId;
                int videoHeight = mVideoSize.getHeight();
                mBitrate = chooseOptimalBitrate(videoHeight);
                return;
            };
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
                } else {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this, "Aplikace Dashcam pot??ebuje p????stup k fotoapar??tu", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[] {Manifest.permission.CAMERA}, REQEST_CAMERA_PERMISSION_RESULT);
                }
            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startRecord() {
        try {
            setupMediaRecorder();
            SurfaceTexture surfaceTexture = preview.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recordSurface = mMediaRecorder.getSurface();
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recordSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(
                                        mCaptureRequestBuilder.build(), null, null
                                );
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                        }
                    }, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = preview.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            try {
                                cameraCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getApplicationContext(), "nelze zobrazit n??hled fotoapar??tu", Toast.LENGTH_SHORT).show();

                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;

        }
    }
    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("DashCam");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }
    private static Size chooseOptimalSize(Size[] choices) {
        List<Size> validOptions = new ArrayList<Size>();
        for(Size option : choices) {
            if(option.getHeight() == 2160 & option.getWidth() == 3840) {
                validOptions.add(option);
            }
            if(option.getHeight() == 1440 & option.getWidth() == 2560) {
                validOptions.add(option);
            }
            if(option.getHeight() == 1080 & option.getWidth() == 1920) {
                validOptions.add(option);
            }
            if(option.getHeight() == 720 & option.getWidth() == 1280) {
                validOptions.add(option);
            }
            if(option.getHeight() == 480 & option.getWidth() == 640) {
                validOptions.add(option);
            }
        }
        if(validOptions.size() > 0) {
            return Collections.min(validOptions, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }
    public static int getScreenWidth() {
        return Resources.getSystem().getDisplayMetrics().widthPixels;
    }

    public static int getScreenHeight() {
        return Resources.getSystem().getDisplayMetrics().heightPixels;
    }
    private void transformImage(int width, int height) {
        if(preview == null || mPreviewSize == null) {
            return;
        }
        Matrix matrix = new Matrix();
        RectF prevRectF = new RectF(0, 0, width, height);
        RectF prevSizeRectF = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = prevRectF.centerX();
        float centerY = prevRectF.centerY();
        prevSizeRectF.offset(centerX - prevSizeRectF.centerX(), centerY - prevSizeRectF.centerY());
        matrix.setRectToRect(prevRectF, prevSizeRectF, Matrix.ScaleToFit.FILL);
        float scale = Math.max((float)width / mPreviewSize.getWidth(), (float)height / mPreviewSize.getHeight());
        matrix.postScale(scale, scale, centerX, centerY);
        matrix.postRotate(270, centerX, centerY);
        preview.setTransform(matrix);
    }
    private void createVideoFolder() {
        File movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        mVideoFolder = new File(movieFile, "dashcam");
        if(!mVideoFolder.exists()) {
            mVideoFolder.mkdirs();
        }
    }
    private File createVideoFileName() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "Z??znam" + timestamp;
        File videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder);
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }



    private void checkWriteStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)  {
                mIsRecording = true;
                mRecordImageButton.setImageResource(R.drawable.recording);
                startTimer();
            } else {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        Toast.makeText(this, "Dashcam pot??ebuje p????stup k ulo??i??ti pro ukl??d??n?? nahr??vek", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
                }
        } else {
            mIsRecording = true;
            mRecordImageButton.setImageResource(R.drawable.recording);
            startTimer();
        }
    }

    private void setupMediaRecorder() throws IOException {
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(mVideoFileName);
        mMediaRecorder.setVideoEncodingBitRate(mBitrate);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setOrientationHint(mTotalRotation);
        mMediaRecorder.prepare();
    }

    public int chooseOptimalBitrate(int video) {
        int optimalBitrate = 3000000;
        if(video == 2160) {
            optimalBitrate = 15000000;
        }
        if(video == 1440) {
            optimalBitrate = 11000000;
        }
        if(video == 1080) {
            optimalBitrate = 8000000;
        }
        if(video == 720) {
            optimalBitrate = 4000000;
        }
        if(video == 480) {
            optimalBitrate = 1000000;
        }
        return optimalBitrate;
    }
    public void startTimer() {
        try {
            createVideoFileName();
        } catch (IOException e) {
            e.printStackTrace();
        }
        startRecord();
        mMediaRecorder.start();
        countDownTimer = new CountDownTimer(timeInMiliseconds, 1000) {

            @Override
            public void onTick(long l) {


            }

            @Override
            public void onFinish() {
                mMediaRecorder.stop();
                mMediaRecorder.reset();
                startTimer();
            }
        }.start();

    }
        public void stopTimer() {
        countDownTimer.cancel();
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        startPreview();
    }
}