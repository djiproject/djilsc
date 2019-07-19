package com.example.modeling3d.singleuavsystem;


import android.Manifest;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.log.DJILog;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.FetchMediaTask;
import dji.sdk.media.FetchMediaTaskContent;
import dji.sdk.media.FetchMediaTaskScheduler;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;
import dji.thirdparty.afinal.core.AsyncTask;

import com.example.modeling3d.singleuavsystem.media.DJIVideoStreamDecoder;

import com.example.modeling3d.singleuavsystem.customview.OverlayView;
import com.example.modeling3d.singleuavsystem.customview.OverlayView.DrawCallback;
import com.example.modeling3d.singleuavsystem.media.NativeHelper;
import com.example.modeling3d.singleuavsystem.tflite.Classifier;
import com.example.modeling3d.singleuavsystem.tflite.TFLiteObjectDetectionAPIModel;
import com.example.modeling3d.singleuavsystem.tracking.MultiBoxTracker;
import com.example.modeling3d.singleuavsystem.env.Logger;
import com.example.modeling3d.singleuavsystem.env.ImageUtils;

public class MyActivity extends Application {

    private SingleApplication singleApplication;

    @Override
    protected void attachBaseContext(Context paramContext) {
        super.attachBaseContext(paramContext);
        Helper.install(MApplication.this);
        if (singleApplication == null) {
            singleApplication = new SingleApplication();
            singleApplication.setContext(this);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        //MultiDex.install(this);
        singleApplication.onCreate();
    }

    mReceivedVideoDataListener =new VideoFeeder.VideoDataListener()

    {

        @Override
        public void onReceive ( byte[] videoBuffer, int size){
        if (System.currentTimeMillis() - lastupdate > 1000) {
            Log.d(TAG, "camera recv video data size: " + size);
            lastupdate = System.currentTimeMillis();
        }
        switch (demoType) {
            case USE_SURFACE_VIEW:
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
                break;
            case USE_SURFACE_VIEW_DEMO_DECODER:
                /**
                 we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                break;

            case USE_TEXTURE_VIEW:
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
                break;
        }

    }
    }

    ;

    public void onDataRecv(byte[] data, int size, int frameNum, boolean isKeyFrame, int width, int height) {
        if (dataHandler == null || dataHandlerThread == null || !dataHandlerThread.isAlive()) {
            return;
        }
        if (data.length != size) {
            loge("recv data size: " + size + ", data lenght: " + data.length);
        } else {
            logd("recv data size: " + size + ", frameNum: " + frameNum + ", isKeyframe: " + isKeyFrame + "," +
                    " width: " + width + ", height: " + height);
            currentTime = System.currentTimeMillis();
            frameIndex++;
            DJIFrame newFrame = new DJIFrame(data, size, currentTime, currentTime, isKeyFrame,
                    frameNum, frameIndex, width, height);
            dataHandler.obtainMessage(MSG_FRAME_QUEUE_IN, newFrame).sendToTarget();

        }
    }

    private void onFrameQueueIn(Message msg) {
        DJIFrame inputFrame = (DJIFrame) msg.obj;
        if (inputFrame == null) {
            return;
        }
        if (!hasIFrameInQueue) { // check the I frame flag
            if (inputFrame.frameNum != 1 && !inputFrame.isKeyFrame) {
                loge("the timing for setting iframe has not yet come.");
                return;
            }
            byte[] defaultKeyFrame = null;
            try {
                defaultKeyFrame = getDefaultKeyFrame(inputFrame.width); // Get I frame data
            } catch (IOException e) {
                loge("get default key frame error: " + e.getMessage());
            }
            if (defaultKeyFrame != null) {
                DJIFrame iFrame = new DJIFrame(
                        defaultKeyFrame,
                        defaultKeyFrame.length,
                        inputFrame.pts,
                        System.currentTimeMillis(),
                        inputFrame.isKeyFrame,
                        0,
                        inputFrame.frameIndex - 1,
                        inputFrame.width,
                        inputFrame.height
                );
                frameQueue.clear();
                frameQueue.offer(iFrame); // Queue in the I frame.
                logd("add iframe success!!!!");
                hasIFrameInQueue = true;
            } else if (inputFrame.isKeyFrame) {
                logd("onFrameQueueIn no need add i frame!!!!");
                hasIFrameInQueue = true;
            } else {
                loge("input key frame failed");
            }
        }
        if (inputFrame.width != 0 && inputFrame.height != 0 &&
                (inputFrame.width != this.width ||
                        inputFrame.height != this.height)) {
            this.width = inputFrame.width;
            this.height = inputFrame.height;
            /*
             * On some devices, the codec supports changing of resolution during the fly
             * However, on some devices, that is not the case.
             * So, reset the codec in order to fix this issue.
             */
            loge("init decoder for the 1st time or when resolution changes");
            if (dataHandler != null && !dataHandler.hasMessages(MSG_INIT_CODEC)) {
                dataHandler.sendEmptyMessage(MSG_INIT_CODEC);
            }
        }
        // Queue in the input frame.
        if (this.frameQueue.offer(inputFrame)) {
            logd("put a frame into the Extended-Queue with index=" + inputFrame.frameIndex);
        } else {
            // If the queue is full, drop a frame.
            DJIFrame dropFrame = frameQueue.poll();
            this.frameQueue.offer(inputFrame);
            loge("Drop a frame with index=" + dropFrame.frameIndex + " and append a frame with index=" + inputFrame.frameIndex);
        }
    }

    private void initCodec() {
        if (width == 0 || height == 0) {
            return;
        }
        if (codec != null) {
            releaseCodec();
        }
        loge("initVideoDecoder----------------------------------------------------------");
        loge("initVideoDecoder video width = " + width + "  height = " + height);
        // create the media format
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_ENCODING_FORMAT, width, height);
        if (surface == null) {
            logd("initVideoDecoder: yuv output");
            // The surface is null, which means that the yuv data is needed, so the color format should
            // be set to YUV420.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        } else {
            logd("initVideoDecoder: display");
            // The surface is set, so the color format should be set to format surface.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        }
        try {
            // Create the codec instance.
            codec = MediaCodec.createDecoderByType(VIDEO_ENCODING_FORMAT);
            logd("initVideoDecoder create: " + (codec == null));
            // Configure the codec. What should be noted here is that the hardware decoder would not output
            // any yuv data if a surface is configured into, which mean that if you want the yuv frames, you
            // should set "null" surface when calling the "configure" method of MediaCodec.
            codec.configure(format, surface, null, 0);
            logd("initVideoDecoder configure");
            //            codec.configure(format, null, null, 0);
            if (codec == null) {
                loge("Can't find video info!");
                return;
            }
            // Start the codec
            codec.start();
        } catch (Exception e) {
            loge("init codec failed, do it again: " + e);
            e.printStackTrace();
        }
    }

    private void decodeFrame() throws Exception {
        DJIFrame inputFrame = frameQueue.poll();
        if (inputFrame == null) {
            return;
        }
        if (codec == null) {
            if (dataHandler != null && !dataHandler.hasMessages(MSG_INIT_CODEC)) {
                dataHandler.sendEmptyMessage(MSG_INIT_CODEC);
            }
            return;
        }

        int inIndex = codec.dequeueInputBuffer(0);

        // Decode the frame using MediaCodec
        if (inIndex >= 0) {
            //Log.d(TAG, "decodeFrame: index=" + inIndex);
            ByteBuffer buffer = codec.getInputBuffer(inIndex);
            buffer.put(inputFrame.videoBuffer);
            inputFrame.fedIntoCodecTime = System.currentTimeMillis();
            long queueingDelay = inputFrame.getQueueDelay();
            // Feed the frame data to the decoder.
            codec.queueInputBuffer(inIndex, 0, inputFrame.size, inputFrame.pts, 0);

            // Get the output data from the decoder.
            int outIndex = codec.dequeueOutputBuffer(bufferInfo, 0);

            if (outIndex >= 0) {
                //Log.d(TAG, "decodeFrame: outIndex: " + outIndex);
                if (surface == null && yuvDataListener != null) {
                    // If the surface is null, the yuv data should be get from the buffer and invoke the callback.
                    logd("decodeFrame: need callback");
                    ByteBuffer yuvDataBuf = codec.getOutputBuffer(outIndex);
                    yuvDataBuf.position(bufferInfo.offset);
                    yuvDataBuf.limit(bufferInfo.size - bufferInfo.offset);
                    if (yuvDataListener != null) {
                        yuvDataListener.onYuvDataReceived(yuvDataBuf, bufferInfo.size - bufferInfo.offset, width, height);
                    }
                }
                // All the output buffer must be release no matter whether the yuv data is output or
                // not, so that the codec can reuse the buffer.
                codec.releaseOutputBuffer(outIndex, true);
            } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // The output buffer set is changed. So the decoder should be reinitialized and the
                // output buffers should be retrieved.
                long curTime = System.currentTimeMillis();
                bufferChangedQueue.addLast(curTime);
                if (bufferChangedQueue.size() >= 10) {
                    long headTime = bufferChangedQueue.pollFirst();
                    if (curTime - headTime < 1000) {
                        // reset decoder
                        loge("Reset decoder. Get INFO_OUTPUT_BUFFERS_CHANGED more than 10 times within a second.");
                        bufferChangedQueue.clear();
                        dataHandler.removeCallbacksAndMessages(null);
                        dataHandler.sendEmptyMessage(MSG_INIT_CODEC);
                        return;
                    }
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                loge("format changed, color: " + codec.getOutputFormat().getInteger(MediaFormat.KEY_COLOR_FORMAT));
            }
        } else {
            codec.flush();
        }
    }

    public void onYuvDataReceived(final ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
        if (isProcessingFrame) {
            LOGGER.w("Dropping frame!");
            return;
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                previewHeight = height;
                previewWidth = width;
                rgbBytes = new int[previewWidth * previewHeight];
                initDetection(new Size(width, height), 90);
            }
        } catch (final Exception e) {
            LOGGER.e(e, "Exception!");
            return;
        }

        final byte[] bytes = new byte[dataSize];
        yuvFrame.get(bytes);

        isProcessingFrame = true;
        yuvBytes[0] = bytes;

        imageConverter =
                new Runnable() {
                    @Override
                    public void run() {
                        ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
                    }
                };

        postInferenceCallback =
                new Runnable() {
                    @Override
                    public void run() {
                        //camera.addCallbackBuffer(bytes);
                        isProcessingFrame = false;
                    }
                };
        processImage();
    }

    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        LOGGER.i("Running detection on image " + currTimestamp);
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                        switch (MODE) {
                            case TF_OD_API:
                                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                break;
                        }

                        final List<Classifier.Recognition> mappedRecognitions =
                                new LinkedList<Classifier.Recognition>();

                        for (final Classifier.Recognition result : results) {
                            final RectF location = result.getLocation();
                            if (location != null && result.getConfidence() >= minimumConfidence) {
                                canvas.drawRect(location, paint);

                                cropToFrameTransform.mapRect(location);

                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            }
                        }

                        tracker.trackResults(mappedRecognitions, currTimestamp);
                        trackingOverlay.postInvalidate();

                        computingDetection = false;
                    }
                });
    }

}