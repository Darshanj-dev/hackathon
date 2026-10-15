package com.findback.app;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.*;

/** YOLOv8 detection export: float32 RGB NCHW [1,3,640,640], output [1,84,8400], COCO. */
public final class YoloDetector implements AutoCloseable {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final int[] pixels = new int[640*640];
    private final float[] input = new float[3*640*640];
    private final Bitmap scaled = Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
    private final android.graphics.Canvas canvas = new android.graphics.Canvas(scaled);
    private final android.graphics.Paint resizePaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    public static final String[] LABELS = ("person,bicycle,car,motorcycle,airplane,bus,train,truck,boat,traffic light,fire hydrant,stop sign,parking meter,bench,bird,cat,dog,horse,sheep,cow,elephant,bear,zebra,giraffe,backpack,umbrella,handbag,tie,suitcase,frisbee,skis,snowboard,sports ball,kite,baseball bat,baseball glove,skateboard,surfboard,tennis racket,bottle,wine glass,cup,fork,knife,spoon,bowl,banana,apple,sandwich,orange,broccoli,carrot,hot dog,pizza,donut,cake,chair,couch,potted plant,bed,dining table,toilet,tv,laptop,mouse,remote,keyboard,cell phone,microwave,oven,toaster,sink,refrigerator,book,clock,vase,scissors,teddy bear,hair drier,toothbrush").split(",");
    public record Hit(String label, float score, float x, float y, float w, float h) {}
    public YoloDetector(Context context) throws Exception {
        byte[] bytes;
        try (var in = context.getAssets().open("yolov8n.onnx")) { java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int count; while((count=in.read(chunk))!=-1)buffer.write(chunk,0,count); bytes=buffer.toByteArray(); }
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            session = env.createSession(bytes, options);
        }
    }
    public List<Hit> detect(Bitmap image) throws Exception {
        canvas.drawBitmap(image,null,new android.graphics.Rect(0,0,640,640),resizePaint);
        scaled.getPixels(pixels,0,640,0,0,640,640);
        for(int i=0;i<pixels.length;i++) { int p=pixels[i]; input[i]=((p>>16)&255)/255f; input[i+pixels.length]=((p>>8)&255)/255f; input[i+pixels.length*2]=(p&255)/255f; }
        try (OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),new long[]{1,3,640,640}); OrtSession.Result result=session.run(Collections.singletonMap(session.getInputNames().iterator().next(),tensor))) {
            float[][] out=((float[][][]) result.get(0).getValue())[0];
            if(out.length!=84) throw new IllegalStateException("Expected YOLOv8 COCO output [1,84,N]");
            List<Hit> candidates=new ArrayList<>();
            for(int i=0;i<out[0].length;i++) {
                int cls=0; float confidence=0;
                for(int c=4;c<84;c++) if(out[c][i]>confidence){confidence=out[c][i];cls=c-4;}
                if(confidence>=.45f) candidates.add(new Hit(LABELS[cls],confidence,out[0][i]/640,out[1][i]/640,out[2][i]/640,out[3][i]/640));
            }
            candidates.sort((a,b)->Float.compare(b.score(),a.score()));
            List<Hit> kept=new ArrayList<>();
            for(Hit h:candidates) { boolean overlap=false; for(Hit k:kept) if(h.label().equals(k.label())&&iou(h,k)>.45f){overlap=true;break;} if(!overlap)kept.add(h); if(kept.size()==20)break; }
            return kept;
        }
    }
    static float iou(Hit a,Hit b) {
        float w=Math.max(0,Math.min(a.x+a.w/2,b.x+b.w/2)-Math.max(a.x-a.w/2,b.x-b.w/2));
        float h=Math.max(0,Math.min(a.y+a.h/2,b.y+b.h/2)-Math.max(a.y-a.h/2,b.y-b.h/2));
        float intersection=w*h; return intersection/(a.w*a.h+b.w*b.h-intersection+1e-6f);
    }
    @Override public void close() throws OrtException {session.close();scaled.recycle();}
}
