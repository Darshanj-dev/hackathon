package com.findback.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.util.StateSet;
import android.widget.TextView;

/** Design tokens: warm neutral surfaces, near-black type, one forest-teal brand colour, 8dp rhythm. */
final class Theme {
    private Theme() {}
    static final int BACKGROUND=0xFFF7F7F2, SURFACE=0xFFFFFFFF, SURFACE_ALT=0xFFEEEEE9, CAMERA=0xFF0E1110;
    static final int INK=0xFF171918, MUTED=0xFF565B58, BORDER=0xFFD3D5CF, FOCUS=0xFF171918;
    static final int BRAND=0xFF145B52, BRAND_PRESSED=0xFF0E413A, BRAND_SOFT=0xFFE1EDEA, ON_BRAND=0xFFFFFFFF, MINT=0xFFBFE7DD;
    /** Translucent charcoal surfaces over the camera; text on them is white or MINT (≥ 7:1 over a white scene). */
    static final int OVERLAY=0xB8121615, OVERLAY_STRONG=0xEB121615, MENU=0xF5161A19, ON_DARK=0xFFFFFFFF, ON_DARK_MUTED=0xFFC2CBC8, ERROR_ON_DARK=0xFFFFB4A6, DIVIDER_ON_DARK=0x29FFFFFF;
    static final int ERROR=0xFFA3341F, ERROR_SOFT=0xFFFBEAE6, CAUTION=0xFF7A5300;
    static final int RADIUS_CONTROL=12, RADIUS_CARD=16, RADIUS_CAMERA=20;

    static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}

    static GradientDrawable rounded(Context c,int color,int radiusDp,int strokeColor,int strokeDp){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radiusDp));
        if(strokeDp>0)d.setStroke(dp(c,strokeDp),strokeColor);
        return d;
    }

    /** Ripple feedback plus a visible focus ring for keyboard, switch access and TalkBack focus. */
    static Drawable control(Context c,int fill,int radiusDp,int strokeColor,int strokeDp){return control(c,fill,radiusDp,strokeColor,strokeDp,FOCUS);}

    /** Same, for controls drawn over the camera: the focus ring is mint so it stays visible on dark surfaces. */
    static Drawable controlOnDark(Context c,int fill,int radiusDp,int strokeColor,int strokeDp){return control(c,fill,radiusDp,strokeColor,strokeDp,MINT);}

    static Drawable control(Context c,int fill,int radiusDp,int strokeColor,int strokeDp,int focus){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},rounded(c,fill,radiusDp,focus,3));
        states.addState(StateSet.WILD_CARD,rounded(c,fill,radiusDp,strokeColor,strokeDp));
        int ripple=fill==BRAND||Color.alpha(fill)<0xFF||Color.luminance(fill)<.2f?0x33FFFFFF:0x1F171918;
        return new RippleDrawable(ColorStateList.valueOf(ripple),states,rounded(c,0xFF000000,radiusDp,0,0));
    }

    static TextView text(Context c,String value,float sp,int color,boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        t.setIncludeFontPadding(true);
        return t;
    }

    static Typeface medium(){return Typeface.create("sans-serif-medium",Typeface.NORMAL);}

    static boolean reducedMotion(Context c){
        try{return android.provider.Settings.Global.getFloat(c.getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f;}
        catch(Exception e){return false;}
    }
}
