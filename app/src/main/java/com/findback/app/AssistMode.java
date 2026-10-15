package com.findback.app;

/** The four assistance modes. {@code key} is the stable identifier used by prompts and saved state. */
public enum AssistMode {
    PLACES("place","Places","place","Identify this place"),
    FIND("find","Find","find","Start finding"),
    READ("read","Read","read","Read visible text"),
    DESCRIBE("describe","Describe","describe","Describe surroundings");
    public final String key,label,icon,action;
    AssistMode(String key,String label,String icon,String action){this.key=key;this.label=label;this.icon=icon;this.action=action;}
    public static AssistMode fromKey(String key){for(AssistMode m:values())if(m.key.equals(key))return m;return DESCRIBE;}
}
