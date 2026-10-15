package com.findback.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small consistent line icons; every icon is paired with a visible text label. */
final class ActionIcon extends Drawable {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ActionIcon(String kind,int color,int size){this.kind=kind;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);setBounds(0,0,size,size);}
    @Override public void draw(Canvas canvas){canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        switch(kind){
            case "Places": canvas.drawCircle(12,9,5,paint);canvas.drawCircle(12,9,1,paint);canvas.drawLine(8,13,12,21,paint);canvas.drawLine(16,13,12,21,paint);break;
            case "Find": canvas.drawCircle(10,10,6,paint);canvas.drawLine(14.5f,14.5f,21,21,paint);break;
            case "Read": canvas.drawRoundRect(4,3,20,21,2,2,paint);for(int y=8;y<18;y+=4)canvas.drawLine(8,y,16,y,paint);break;
            case "Describe": canvas.drawRoundRect(3,4,21,19,4,4,paint);canvas.drawLine(7,19,7,22,paint);canvas.drawCircle(8,11,0.8f,paint);canvas.drawCircle(12,11,0.8f,paint);canvas.drawCircle(16,11,0.8f,paint);break;
            case "Speak": canvas.drawRoundRect(9,3,15,14,3,3,paint);canvas.drawArc(6,7,18,18,0,180,false,paint);canvas.drawLine(12,18,12,22,paint);canvas.drawLine(8,22,16,22,paint);break;
            case "Replay": canvas.drawArc(4,4,20,20,-70,300,false,paint);canvas.drawLine(4,5,4,10,paint);canvas.drawLine(4,10,9,10,paint);break;
            case "Stop": canvas.drawRoundRect(5,5,19,19,2,2,paint);break;
            default: canvas.drawCircle(12,12,8,paint);canvas.drawLine(12,8,12,13,paint);canvas.drawCircle(12,17,.3f,paint);
        }canvas.restore();}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
