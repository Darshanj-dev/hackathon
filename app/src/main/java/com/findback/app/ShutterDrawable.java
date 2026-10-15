package com.findback.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Camera-style shutter: white ring, teal disc with the mode's icon; a stop square while working. */
final class ShutterDrawable extends Drawable {
    private final Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG), disc=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ActionIcon icon;
    private final float stroke, gap;
    private final int iconSize;

    ShutterDrawable(Context c,String iconKind,boolean busy){
        stroke=Theme.dp(c,4);gap=Theme.dp(c,5);iconSize=Theme.dp(c,30);
        ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(stroke);ring.setColor(Color.WHITE);
        disc.setColor(busy?Color.WHITE:Theme.BRAND);
        icon=new ActionIcon(busy?"stop":iconKind,busy?Theme.INK:Color.WHITE,iconSize);
    }
    @Override public void draw(Canvas canvas){
        Rect b=getBounds();float cx=b.exactCenterX(),cy=b.exactCenterY(),r=Math.min(b.width(),b.height())/2f;
        canvas.drawCircle(cx,cy,r-stroke/2,ring);
        canvas.drawCircle(cx,cy,r-stroke-gap,disc);
        int left=Math.round(cx-iconSize/2f),top=Math.round(cy-iconSize/2f);
        icon.setBounds(left,top,left+iconSize,top+iconSize);icon.draw(canvas);
    }
    @Override public void setAlpha(int alpha){ring.setAlpha(alpha);disc.setAlpha(alpha);icon.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter f){ring.setColorFilter(f);disc.setColorFilter(f);icon.setColorFilter(f);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
