package com.findback.app;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** One outline icon family on a 24-unit grid with 2-unit rounded strokes. */
final class ActionIcon extends Drawable {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();

    ActionIcon(String kind,int color,int size){
        this.kind=kind;
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);fill.setStyle(Paint.Style.FILL);
        setBounds(0,0,size,size);
    }

    @Override public void draw(Canvas canvas){
        canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        path.reset();
        switch(kind){
            case "place" -> {path.moveTo(12,21.5f);path.cubicTo(7,16,5,12.8f,5,9.8f);path.cubicTo(5,5.8f,8.1f,3,12,3);path.cubicTo(15.9f,3,19,5.8f,19,9.8f);path.cubicTo(19,12.8f,17,16,12,21.5f);path.close();canvas.drawPath(path,paint);canvas.drawCircle(12,10,2.6f,paint);}
            case "find" -> {canvas.drawCircle(10.5f,10.5f,6.5f,paint);canvas.drawLine(15.3f,15.3f,20.5f,20.5f,paint);}
            case "read" -> {canvas.drawRoundRect(5,3,19,21,2.5f,2.5f,paint);canvas.drawLine(8.5f,8,15.5f,8,paint);canvas.drawLine(8.5f,12,15.5f,12,paint);canvas.drawLine(8.5f,16,13,16,paint);}
            case "describe" -> {path.moveTo(2.5f,12);path.quadTo(12,2.5f,21.5f,12);path.quadTo(12,21.5f,2.5f,12);path.close();canvas.drawPath(path,paint);canvas.drawCircle(12,12,3,paint);}
            case "mic" -> {canvas.drawRoundRect(9,3,15,14,3,3,paint);canvas.drawArc(5.5f,5.5f,18.5f,17.5f,10,160,false,paint);canvas.drawLine(12,17.5f,12,21,paint);}
            case "replay" -> {canvas.drawArc(4.5f,4.5f,19.5f,19.5f,-60,290,false,paint);canvas.drawLine(5.2f,4.2f,5.2f,9,paint);canvas.drawLine(5.2f,9,10,9,paint);}
            case "stop" -> canvas.drawRoundRect(6.5f,6.5f,17.5f,17.5f,2,2,fill);
            case "switch" -> {canvas.drawRoundRect(3,6.5f,21,19,3,3,paint);canvas.drawLine(8.5f,6.5f,10,4,paint);canvas.drawLine(10,4,14,4,paint);canvas.drawLine(14,4,15.5f,6.5f,paint);canvas.drawArc(8,9,16,17,200,120,false,paint);canvas.drawArc(8,9,16,17,20,120,false,paint);canvas.drawLine(8.4f,10.5f,8.4f,12.6f,paint);canvas.drawLine(15.6f,15.5f,15.6f,13.4f,paint);}
            case "help" -> {canvas.drawCircle(12,12,9,paint);path.moveTo(9.5f,9.5f);path.quadTo(9.5f,7,12,7);path.quadTo(14.5f,7,14.5f,9.3f);path.quadTo(14.5f,11,12,12);path.lineTo(12,13.5f);canvas.drawPath(path,paint);canvas.drawCircle(12,16.8f,.9f,fill);}
            case "globe" -> {canvas.drawCircle(12,12,9,paint);canvas.drawLine(3,12,21,12,paint);canvas.drawOval(8,3,16,21,paint);}
            case "camera_off" -> {canvas.drawRoundRect(3,6.5f,21,19,3,3,paint);canvas.drawCircle(12,12.8f,3.4f,paint);canvas.drawLine(3,3,21,21,paint);}
            case "speaker" -> {path.moveTo(4,9.5f);path.lineTo(8,9.5f);path.lineTo(12.5f,5.5f);path.lineTo(12.5f,18.5f);path.lineTo(8,14.5f);path.lineTo(4,14.5f);path.close();canvas.drawPath(path,paint);canvas.drawArc(11,8,18,16,-50,100,false,paint);}
            case "pause" -> {canvas.drawLine(9,6.5f,9,17.5f,paint);canvas.drawLine(15,6.5f,15,17.5f,paint);}
            case "alert" -> {canvas.drawCircle(12,12,9,paint);canvas.drawLine(12,7.5f,12,13,paint);canvas.drawCircle(12,16.5f,.9f,fill);}
            case "chat" -> {path.moveTo(4,5);path.lineTo(20,5);path.lineTo(20,16);path.lineTo(10,16);path.lineTo(6,19.5f);path.lineTo(6,16);path.lineTo(4,16);path.close();canvas.drawPath(path,paint);}
            case "door" -> {canvas.drawRoundRect(6,3,18,21,1.5f,1.5f,paint);canvas.drawCircle(14.5f,12.5f,.9f,fill);}
            case "dot" -> canvas.drawCircle(12,12,5,fill);
            case "more" -> {canvas.drawCircle(12,5.5f,1.8f,fill);canvas.drawCircle(12,12,1.8f,fill);canvas.drawCircle(12,18.5f,1.8f,fill);}
            case "gallery" -> {canvas.drawRoundRect(3.5f,4.5f,20.5f,19.5f,3,3,paint);canvas.drawCircle(9,9.5f,1.8f,paint);path.moveTo(3.8f,17);path.lineTo(9.5f,12.5f);path.lineTo(13,15.5f);path.lineTo(16,13);path.lineTo(20.2f,16.5f);canvas.drawPath(path,paint);}
            case "settings" -> {canvas.drawCircle(12,12,3.2f,paint);for(int i=0;i<8;i++){double a=Math.PI/4*i;canvas.drawLine((float)(12+6*Math.cos(a)),(float)(12+6*Math.sin(a)),(float)(12+9*Math.cos(a)),(float)(12+9*Math.sin(a)),paint);}canvas.drawCircle(12,12,6,paint);}
            case "check" -> {path.moveTo(5,12.5f);path.lineTo(10,17.5f);path.lineTo(19.5f,7);canvas.drawPath(path,paint);}
            case "chevron_up" -> {path.moveTo(6,15);path.lineTo(12,9);path.lineTo(18,15);canvas.drawPath(path,paint);}
            case "chevron_down" -> {path.moveTo(6,9);path.lineTo(12,15);path.lineTo(18,9);canvas.drawPath(path,paint);}
            case "close" -> {canvas.drawLine(6.5f,6.5f,17.5f,17.5f,paint);canvas.drawLine(17.5f,6.5f,6.5f,17.5f,paint);}
            default -> canvas.drawCircle(12,12,8,paint);
        }
        canvas.restore();
    }
    // Without an intrinsic size, ImageView stretches the icon to the button's full bounds.
    @Override public int getIntrinsicWidth(){return getBounds().width();}
    @Override public int getIntrinsicHeight(){return getBounds().height();}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);fill.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);fill.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
