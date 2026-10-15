package com.findback.app;

/** What Saathi is doing right now. Every state has its own icon shape and text, never colour alone. */
public enum AssistState {
    READY("Ready to assist","dot"),
    LISTENING("Listening…","mic"),
    PROCESSING("Understanding your surroundings…","progress"),
    SPEAKING("Speaking","speaker"),
    PAUSED("Assistance paused","pause"),
    ERROR("Assistance temporarily unavailable","alert");
    public final String label,icon;
    AssistState(String label,String icon){this.label=label;this.icon=icon;}
}
