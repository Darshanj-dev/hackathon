package com.findback.app;

import android.graphics.Bitmap;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;

/** CameraX RGBA output uses one plane; row padding must never become image pixels. */
final class CameraFrames {
    static Bitmap rgba(ImageProxy image) {
        ImageProxy.PlaneProxy plane=image.getPlanes()[0];
        int[] pixels=decode(plane.getBuffer(),image.getWidth(),image.getHeight(),plane.getRowStride(),plane.getPixelStride());
        return Bitmap.createBitmap(pixels,image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
    }
    static int[] decode(ByteBuffer source,int width,int height,int rowStride,int pixelStride) {
        if(width<=0||height<=0||pixelStride<4||rowStride<(width-1)*pixelStride+4)throw new IllegalArgumentException("Invalid RGBA frame layout");
        ByteBuffer buffer=source.duplicate();int offset=buffer.position();
        if((long)offset+(long)(height-1)*rowStride+(long)(width-1)*pixelStride+4>buffer.limit())throw new IllegalArgumentException("Incomplete camera frame");
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int at=offset+y*rowStride+x*pixelStride;
            int r=buffer.get(at)&255,g=buffer.get(at+1)&255,b=buffer.get(at+2)&255,a=buffer.get(at+3)&255;
            pixels[y*width+x]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        return pixels;
    }
}
