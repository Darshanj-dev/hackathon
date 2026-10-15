package com.findback.app;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

/** Answer area that grows with content up to a cap, then scrolls. */
final class BoundedScrollView extends ScrollView {
    private int maxHeight;
    BoundedScrollView(Context c,int maxHeight){super(c);this.maxHeight=maxHeight;setVerticalScrollBarEnabled(true);setNestedScrollingEnabled(true);}
    void setMaxHeight(int value){maxHeight=value;requestLayout();}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int size=View.MeasureSpec.getSize(heightSpec),mode=View.MeasureSpec.getMode(heightSpec);
        int limit=mode==View.MeasureSpec.UNSPECIFIED?maxHeight:Math.min(size,maxHeight);
        super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
    }
}
