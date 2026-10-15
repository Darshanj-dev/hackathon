package com.findback.app;

import java.util.Locale;
import java.util.regex.*;

/** Turns a spoken request into an action. Without an explicit verb, the current mode decides. */
public final class VoiceCommands {
    private VoiceCommands() {}
    public enum Action { STOP, REPEAT, READ, WASHROOM, IDENTIFY_PLACE, FIND, RECALL, DESCRIBE }
    public record Command(Action action,String argument) {}

    private static final Pattern STOP=Pattern.compile("^(stop|cancel|quiet|be quiet|रुको|बंद करो|ನಿಲ್ಲಿಸು)\\b.*");
    private static final Pattern REPEAT=Pattern.compile(".*\\b(repeat|say (that|it) again|again)\\b.*|.*(दोबारा|फिर से बोलो|ಮತ್ತೆ ಹೇಳು|ಮತ್ತೆ).*");
    private static final Pattern RECALL=Pattern.compile(".*\\b(where did i (leave|put|last see)|last seen)\\b.*");
    private static final Pattern WASHROOM=Pattern.compile(".*\\b(washroom|restroom|toilet|bathroom)\\b.*|.*(शौचालय|ಶೌಚಾಲಯ).*");
    private static final Pattern READ=Pattern.compile("^(please )?(read|what does (it|this|that) say)\\b.*|.*\\bread (this|it|the|that|aloud|out)\\b.*|.*(पढ़|ಓದ).*");
    private static final Pattern PLACE=Pattern.compile(".*\\b(where am i|what (kind of )?place|which place|identify (this|the) (place|room)|what room)\\b.*|.*(मैं कहाँ हूँ|यह कौन सी जगह|ನಾನು ಎಲ್ಲಿದ್ದೇನೆ).*");
    private static final Pattern FIND=Pattern.compile("^(please )?(can you )?(help me )?(find|locate|search for|look for|where is|where's|where are)\\s+(.+)$|^(.+?)\\s*(ढूंढो|ढूँढो|ढूंढें|खोजो|ಹುಡುಕಿ|ಹುಡುಕು)$");
    private static final Pattern DESCRIBE=Pattern.compile(".*\\b(describe|what('s| is) (around|in front|here|this)|what do you see|what can you see|surroundings|look around)\\b.*|.*(क्या दिख|आसपास|ವಿವರಿಸು|ಏನು ಕಾಣ).*");

    public static Command parse(String spoken,AssistMode current){
        String s=spoken==null?"":spoken.toLowerCase(Locale.ROOT).replaceAll("[?.!,]"," ").replaceAll("\\s+"," ").trim();
        if(s.isEmpty())return new Command(current==AssistMode.FIND?Action.FIND:Action.DESCRIBE,"");
        if(STOP.matcher(s).matches())return new Command(Action.STOP,"");
        if(REPEAT.matcher(s).matches())return new Command(Action.REPEAT,"");
        Matcher recall=Pattern.compile("^.*\\b(?:where did i (?:leave|put|last see)|last seen)\\s+(.+)$").matcher(s);
        if(RECALL.matcher(s).matches())return new Command(Action.RECALL,recall.matches()?objectPhrase(recall.group(1)):"");
        if(WASHROOM.matcher(s).matches())return new Command(Action.WASHROOM,"");
        if(PLACE.matcher(s).matches())return new Command(Action.IDENTIFY_PLACE,"");
        if(READ.matcher(s).matches())return new Command(Action.READ,"");
        Matcher find=FIND.matcher(s);
        if(find.matches())return new Command(Action.FIND,objectPhrase(find.group(5)!=null?find.group(5):find.group(6)));
        if(DESCRIBE.matcher(s).matches())return new Command(Action.DESCRIBE,isBareDescribe(s)?"":spoken.trim());
        return switch(current){
            case FIND -> new Command(Action.FIND,objectPhrase(s));
            case READ -> new Command(Action.READ,"");
            case PLACES -> new Command(Action.IDENTIFY_PLACE,"");
            case DESCRIBE -> AssistLanguage.voiceObject(s)!=null&&s.split(" ").length<=2?new Command(Action.FIND,objectPhrase(s)):new Command(Action.DESCRIBE,spoken.trim());
        };
    }

    private static boolean isBareDescribe(String s){return s.matches("(please )?(describe( (this|it|the scene|my surroundings|surroundings|what you see))?|what do you see|what can you see|look around)");}

    /** "the nearest chair" → "chair"; "my car keys" → "car keys". */
    public static String objectPhrase(String raw){
        if(raw==null)return "";
        String s=raw.toLowerCase(Locale.ROOT).replaceAll("[?.!,]"," ").trim();
        s=s.replaceAll("\\b(please|for me|in this room|around here|here|nearby)\\b","").replaceAll("\\s+"," ").trim();
        s=s.replaceAll("^(can you |help me )*(find|locate|search for|look for|where is|where's|where are)\\s+","");
        s=s.replaceAll("^((the|a|an|my|some|any|nearest|closest|nearby)\\s+)+","");
        return s.replaceAll("\\s+"," ").trim();
    }
}
