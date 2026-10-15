package com.findback.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Uses the same cropped viewport as PreviewView; coordinates are normalized to the rotated image. */
public final class DetectionOverlay extends View {
    private YoloDetector.Hit hit; private int imageWidth=1,imageHeight=1;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box=new RectF();
    public DetectionOverlay(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void update(YoloDetector.Hit h,int w,int height){hit=h;imageWidth=w;imageHeight=height;invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(hit==null)return;
        float scale=Math.max(getWidth()/(float)imageWidth,getHeight()/(float)imageHeight);
        float w=imageWidth*scale,h=imageHeight*scale,ox=(getWidth()-w)/2,oy=(getHeight()-h)/2;
        float d=getResources().getDisplayMetrics().density;
        box.set(ox+(hit.x()-hit.w()/2)*w,oy+(hit.y()-hit.h()/2)*h,ox+(hit.x()+hit.w()/2)*w,oy+(hit.y()+hit.h()/2)*h);
        paint.setStyle(Paint.Style.STROKE);
        // Dark halo under a bright stroke keeps the box visible on light and dark scenes.
        paint.setColor(0x99000000);paint.setStrokeWidth(6*d);canvas.drawRoundRect(box,12*d,12*d,paint);
        paint.setColor(Color.rgb(123,224,195));paint.setStrokeWidth(3*d);canvas.drawRoundRect(box,12*d,12*d,paint);
    }
}
