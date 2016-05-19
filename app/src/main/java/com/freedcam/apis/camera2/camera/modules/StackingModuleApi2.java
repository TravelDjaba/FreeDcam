package com.freedcam.apis.camera2.camera.modules;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.view.Surface;

import com.freedcam.apis.basecamera.camera.Size;
import com.freedcam.apis.basecamera.camera.modules.ModuleEventHandler;
import com.freedcam.apis.camera1.camera.modules.ModuleHandler;
import com.freedcam.apis.camera1.camera.modules.StackingModule;
import com.freedcam.apis.camera1.camera.parameters.modes.StackModeParameter;
import com.freedcam.apis.camera2.camera.CameraHolderApi2;
import com.freedcam.ui.handler.MediaScannerManager;
import com.freedcam.utils.AppSettingsManager;
import com.freedcam.utils.FreeDPool;
import com.freedcam.utils.Logger;
import com.freedcam.utils.StringUtils;
import com.imageconverter.ScriptC_imagestack;
import com.imageconverter.ScriptField_MinMaxPixel;

import java.io.File;
import java.util.Date;

/**
 * Created by troop on 16.05.2016.
 */
@TargetApi(Build.VERSION_CODES.M)
public class StackingModuleApi2 extends AbstractModuleApi2
{
    private final String TAG = StackingModuleApi2.class.getSimpleName();
    private Size previewSize;
    private Surface previewsurface;
    private Surface camerasurface;
    private int mHeight;
    private int mWidth;
    private RenderScript mRS;
    private ScriptC_imagestack imagestack;
    private Allocation mOutputAllocation;
    private Allocation mInputAllocation;
    private ScriptField_MinMaxPixel medianMinMax;
    private ProcessingTask mProcessingTask;
    private HandlerThread mProcessingThread;
    private Handler mProcessingHandler;
    private boolean keepstacking = false;
    private boolean afterFilesave = false;

    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;

    public StackingModuleApi2(CameraHolderApi2 cameraHandler, ModuleEventHandler eventHandler, Context context, AppSettingsManager appSettingsManager) {
        super(cameraHandler, eventHandler, context, appSettingsManager);
        this.name = ModuleHandler.MODULE_STACKING;
    }

    @Override
    public String ShortName() {
        return "Stack";
    }

    @Override
    public String LongName() {
        return "Stacking";
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void startPreview()
    {
        if (afterFilesave)
        {
            afterFilesave =false;
            baseCameraHolder.CaptureSessionH.CreateCaptureSession();
        }
        else {
            previewSize = new Size(ParameterHandler.PictureSize.GetValue());
            mHeight = previewSize.height;
            mWidth = previewSize.width;
            Type.Builder yuvTypeBuilder = new Type.Builder(mRS, Element.YUV(mRS));
            yuvTypeBuilder.setX(mWidth);
            yuvTypeBuilder.setY(mHeight);
            yuvTypeBuilder.setYuvFormat(ImageFormat.YUV_420_888);
            mInputAllocation = Allocation.createTyped(mRS, yuvTypeBuilder.create(),
                    Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);
            Type.Builder rgbTypeBuilder = new Type.Builder(mRS, Element.RGBA_8888(mRS));
            rgbTypeBuilder.setX(mWidth);
            rgbTypeBuilder.setY(mHeight);
            mOutputAllocation = Allocation.createTyped(mRS, rgbTypeBuilder.create(),
                    Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);
            medianMinMax = new ScriptField_MinMaxPixel(mRS, mWidth * mHeight);

            SurfaceTexture texture = baseCameraHolder.textureView.getSurfaceTexture();

            texture.setDefaultBufferSize(previewSize.width, previewSize.height);
            previewsurface = new Surface(texture);
            mOutputAllocation.setSurface(previewsurface);
            //baseCameraHolder.CaptureSessionH.AddSurface(previewsurface,true);
            baseCameraHolder.CaptureSessionH.SetTextureViewSize(mWidth, mHeight, 0, 180, false);
            camerasurface = mInputAllocation.getSurface();
            baseCameraHolder.CaptureSessionH.AddSurface(camerasurface, true);


            baseCameraHolder.CaptureSessionH.CreateCaptureSession();

            if (mProcessingTask != null) {

                while (mProcessingTask.working) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Logger.exception(e);
                    }
                }
                mProcessingTask = null;
            }
            mProcessingTask = new ProcessingTask(mInputAllocation);
        }
    }

    @Override
    public void stopPreview()
    {
        baseCameraHolder.CaptureSessionH.StopRepeatingCaptureSession();
    }

    @Override
    public boolean DoWork()
    {
        if (!keepstacking) {
            workstarted();
            keepstacking = true;
        }
        else
        {
            baseCameraHolder.StopPreview();
            saveImageToFile();
            afterFilesave = true;
            startPreview();
        }

        return super.DoWork();
    }

    private void saveImageToFile() {
        final Bitmap outputBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
        mOutputAllocation.copyTo(outputBitmap);
        FreeDPool.Execute(new Runnable() {
            @Override
            public void run() {
                File stackedImg = new File(StringUtils.getFilePath(appSettingsManager.GetWriteExternal(), "_stack.jpg"));
                SaveBitmapToFile(outputBitmap,stackedImg);
                workfinished(true);
                MediaScannerManager.ScanMedia(context, stackedImg);
                eventHandler.WorkFinished(stackedImg);
            }
        });

        keepstacking =false;
    }

    @Override
    public void LoadNeededParameters() {
        baseCameraHolder.ModulePreview = this;
        mProcessingThread = new HandlerThread("StackingModuleApi2");
        mProcessingThread.start();
        mProcessingHandler = new Handler(mProcessingThread.getLooper());
        if (mRS == null)
            mRS = RenderScript.create(context);
        if (yuvToRgbIntrinsic == null)
            yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(mRS, Element.U8_4(mRS));
        if(imagestack == null);
            imagestack = new ScriptC_imagestack(mRS);
        imagestack.set_yuvinput(true);

        baseCameraHolder.StartPreview();
    }

    @Override
    public void UnloadNeededParameters()
    {
        if (keepstacking)
            keepstacking = false;
        baseCameraHolder.StopPreview();
        Logger.d(TAG, "UnloadNeededParameters");
        if (mProcessingTask != null) {

            while (mProcessingTask.working)
            {
                saveImageToFile();
                keepstacking = false;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Logger.exception(e);
                }
            }
            mProcessingTask = null;
        }
        baseCameraHolder.CaptureSessionH.CloseCaptureSession();
        mOutputAllocation = null;
        mInputAllocation = null;

        previewsurface = null;
        camerasurface = null;

    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void initRs()
    {


    }

    /**
     * Class to process buffer from camera and output to buffer to screen
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    class ProcessingTask implements Runnable, Allocation.OnBufferAvailableListener {
        private int mPendingFrames = 0;
        private Allocation mInputAllocation;
        private boolean working = false;
        public ProcessingTask(Allocation input) {
            mInputAllocation = input;
            mInputAllocation.setOnBufferAvailableListener(this);
        }
        @Override
        public void onBufferAvailable(Allocation a) {
            synchronized (this) {
                mPendingFrames++;
                mProcessingHandler.post(this);
            }
        }
        @Override
        public void run()
        {
            working = true;
            // Find out how many frames have arrived
            int pendingFrames;
            synchronized (this) {
                pendingFrames = mPendingFrames;
                mPendingFrames = 0;
                // Discard extra messages in case processing is slower than frame rate
                mProcessingHandler.removeCallbacks(this);
            }
            // Get to newest input
            for (int i = 0; i < pendingFrames; i++)
            {
                mInputAllocation.ioReceive();
            }
            if (mOutputAllocation == null)
                return;
            if (keepstacking)
            {
                imagestack.set_gCurrentFrame(mInputAllocation);
                imagestack.set_gLastFrame(mOutputAllocation);
                // Run processing pass
                if (ParameterHandler.imageStackMode.GetValue().equals(StackModeParameter.AVARAGE))
                    imagestack.forEach_stackimage_avarage(mOutputAllocation);
                else if (ParameterHandler.imageStackMode.GetValue().equals(StackModeParameter.AVARAGE1x2))
                    imagestack.forEach_stackimage_avarage1x2(mOutputAllocation);
                else if (ParameterHandler.imageStackMode.GetValue().equals(StackModeParameter.AVARAGE1x3))
                    imagestack.forEach_stackimage_avarage1x3(mOutputAllocation);
                else if (ParameterHandler.imageStackMode.GetValue().equals(StackModeParameter.AVARAGE3x3))
                    imagestack.forEach_stackimage_avarage3x3(mOutputAllocation);
                else if(ParameterHandler.imageStackMode.GetValue().equals(StackModeParameter.LIGHTEN))
                    imagestack.forEach_stackimage_lighten(mOutputAllocation);
                else if (ParameterHandler.imageStackMode.GetValue().equals(StackModeParameter.MEDIAN))
                {
                    imagestack.bind_medianMinMaxPixel(medianMinMax);
                    imagestack.forEach_stackimage_median(mOutputAllocation);
                }
            }
            else
            {
                yuvToRgbIntrinsic.setInput(mInputAllocation);
                yuvToRgbIntrinsic.forEach(mOutputAllocation);
            }
            mOutputAllocation.ioSend();
            working = false;
        }
    }


}
