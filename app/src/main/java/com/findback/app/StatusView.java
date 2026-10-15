package com.findback.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.*;

/** One-line status: a distinct indicator shape per state plus plain words. */
final class StatusView extends LinearLayout {
    private final ImageView icon;
    private final ProgressBar spinner;
    private final TextView label;
    private final boolean onDark;

    StatusView(Context c){this(c,false);}
    StatusView(Context c,boolean onDark){
        super(c);
        this.onDark=onDark;
        setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(Theme.dp(c,48));
        FrameLayout indicator=new FrameLayout(c);
        icon=new ImageView(c);indicator.addView(icon,new FrameLayout.LayoutParams(Theme.dp(c,18),Theme.dp(c,18),Gravity.CENTER));
        spinner=new ProgressBar(c,null,android.R.attr.progressBarStyleSmall);spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(onDark?Theme.MINT:Theme.BRAND));
        indicator.addView(spinner,new FrameLayout.LayoutParams(Theme.dp(c,18),Theme.dp(c,18),Gravity.CENTER));
        addView(indicator,new LayoutParams(Theme.dp(c,20),Theme.dp(c,20)));
        label=Theme.text(c,"",14,Theme.INK,false);label.setTypeface(Theme.medium());
        label.setPadding(Theme.dp(c,8),0,0,0);label.setMaxLines(2);
        addView(label,new LayoutParams(0,-2,1));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        set(AssistState.READY,null);
    }

    void set(AssistState state,String detail){
        String text=detail==null||detail.isBlank()?state.label:detail;
        boolean error=state==AssistState.ERROR, quiet=state==AssistState.PAUSED;
        int color=onDark?(error?Theme.ERROR_ON_DARK:quiet?Theme.ON_DARK_MUTED:Theme.MINT):(error?Theme.ERROR:quiet?Theme.MUTED:Theme.BRAND);
        boolean progress=state==AssistState.PROCESSING;
        spinner.setVisibility(progress?VISIBLE:GONE);icon.setVisibility(progress?GONE:VISIBLE);
        if(!progress)icon.setImageDrawable(new ActionIcon(state.icon,color,Theme.dp(getContext(),18)));
        label.setText(text);label.setTextColor(onDark?(error?Theme.ERROR_ON_DARK:Theme.ON_DARK):(error?Theme.ERROR:Theme.INK));
        setContentDescription("Status: "+text);
    }
}
