package com.findback.app;

import java.util.Locale;
import java.util.regex.*;

/** Parses Gemma's strict scan reply: "FOUND LEFT" + a short location line, or "NOT_FOUND". */
public record FindCheck(boolean found,String side,String detail) {
    private static final Pattern HEAD=Pattern.compile("^\\W*(NOT[_ ]?FOUND|FOUND)\\W*(LEFT|RIGHT|CENTRE|CENTER|MIDDLE)?",Pattern.CASE_INSENSITIVE);

    public static FindCheck parse(String reply){
        String text=reply==null?"":reply.trim();
        Matcher m=HEAD.matcher(text);
        if(!m.find())return new FindCheck(false,"centre","");
        if(m.group(1).toUpperCase(Locale.ROOT).startsWith("NOT"))return new FindCheck(false,"centre","");
        String side=m.group(2)==null?"centre":switch(m.group(2).toUpperCase(Locale.ROOT)){case "LEFT"->"left";case "RIGHT"->"right";default->"centre";};
        String detail=text.substring(m.end()).replaceAll("^[\\s:.,-]+","").replaceAll("\\s+"," ").trim();
        if(m.group(2)==null){
            String lower=detail.toLowerCase(Locale.ROOT);
            if(lower.contains("left"))side="left";else if(lower.contains("right"))side="right";
        }
        return new FindCheck(true,side,detail);
    }
}
