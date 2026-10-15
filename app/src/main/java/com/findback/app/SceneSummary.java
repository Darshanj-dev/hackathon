package com.findback.app;

import java.util.*;

/**
 * Deterministic summaries built only from detector and OCR output. Used as Gemma hints and as an
 * honest basic answer when detailed assistance is unavailable. Never claims more than was detected.
 */
public final class SceneSummary {
    private SceneSummary() {}

    public static String side(float x){return x<.33f?"left":x>.67f?"right":"centre";}

    /** Compact hint for prompts, e.g. "chair (left), 2 bottle (centre)". */
    public static String hints(List<YoloDetector.Hit> hits){
        List<String> parts=new ArrayList<>();
        for(Map.Entry<String,List<YoloDetector.Hit>> e:group(hits).entrySet()){
            int n=e.getValue().size();
            parts.add((n>1?n+" ":"")+e.getKey()+" ("+side(e.getValue().get(0).x())+")");
            if(parts.size()==6)break;
        }
        return String.join(", ",parts);
    }

    public static String describe(List<YoloDetector.Hit> hits,String text,AssistLanguage language){
        StringBuilder out=new StringBuilder(objects(hits,language));
        String t=excerpt(text);
        if(!t.isEmpty())out.append(out.length()>0?" ":"").append(textLine(t,language));
        if(out.length()==0)return nothing(language);
        return out.toString();
    }

    public static String place(List<YoloDetector.Hit> hits,String text,AssistLanguage language){
        Set<String> labels=group(hits).keySet();
        String guess=placeGuess(labels,text);
        StringBuilder out=new StringBuilder();
        if(guess!=null)out.append(language==AssistLanguage.HINDI?"यह शायद "+placeName(guess,language)+" है।":language==AssistLanguage.KANNADA?"ಇದು ಬಹುಶಃ "+placeName(guess,language)+".":"This may be a "+guess+".");
        String rest=describe(hits,text,language);
        if(guess==null&&rest.equals(nothing(language)))return language==AssistLanguage.HINDI?"मैं इस जगह को पहचान नहीं सका। कैमरा धीरे घुमाकर फिर कोशिश करें।":language==AssistLanguage.KANNADA?"ಈ ಸ್ಥಳವನ್ನು ಗುರುತಿಸಲಾಗಲಿಲ್ಲ. ಕ್ಯಾಮೆರಾವನ್ನು ನಿಧಾನವಾಗಿ ತಿರುಗಿಸಿ ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ.":"I couldn't tell what kind of place this is. Turn slowly and try again.";
        if(!rest.equals(nothing(language)))out.append(out.length()>0?" ":"").append(rest);
        return out.toString();
    }

    static String placeGuess(Set<String> labels,String text){
        String t=text==null?"":text.toLowerCase(Locale.ROOT);
        if(labels.contains("toilet")||t.matches("(?s).*\\b(washroom|restroom|toilet|wc)\\b.*"))return "washroom";
        if(labels.contains("refrigerator")||labels.contains("oven")||labels.contains("microwave")||labels.contains("toaster")||(labels.contains("sink")&&labels.contains("bowl")))return "kitchen";
        if(labels.contains("bed"))return "bedroom";
        if(labels.contains("dining table")&&(labels.contains("chair")||labels.contains("cup")||labels.contains("fork")||labels.contains("bowl")))return "dining area";
        if(labels.contains("laptop")||labels.contains("keyboard")||labels.contains("mouse"))return "work or study area";
        if(labels.contains("couch")||labels.contains("tv"))return "living room";
        if(labels.contains("car")||labels.contains("bus")||labels.contains("truck")||labels.contains("traffic light")||labels.contains("motorcycle")||labels.contains("stop sign"))return "street or road area";
        return null;
    }
    static String placeName(String guess,AssistLanguage l){
        if(l==AssistLanguage.ENGLISH)return guess;
        String[][] names={{"washroom","शौचालय","ಶೌಚಾಲಯ"},{"kitchen","रसोई","ಅಡುಗೆಮನೆ"},{"bedroom","शयनकक्ष","ಮಲಗುವ ಕೋಣೆ"},{"dining area","भोजन क्षेत्र","ಊಟದ ಸ್ಥಳ"},
            {"work or study area","काम या पढ़ाई की जगह","ಕೆಲಸ ಅಥವಾ ಓದುವ ಸ್ಥಳ"},{"living room","बैठक","ಹಾಲ್"},{"street or road area","सड़क","ರಸ್ತೆ"}};
        for(String[] n:names)if(n[0].equals(guess))return n[l==AssistLanguage.HINDI?1:2];
        return guess;
    }

    static String objects(List<YoloDetector.Hit> hits,AssistLanguage language){
        Map<String,List<YoloDetector.Hit>> groups=group(hits);
        if(groups.isEmpty())return "";
        List<String> parts=new ArrayList<>();
        for(Map.Entry<String,List<YoloDetector.Hit>> e:groups.entrySet()){
            int n=e.getValue().size();String name=language.objectName(e.getKey());String where=side(e.getValue().get(0).x());
            if(language==AssistLanguage.HINDI)parts.add((n>1?n+" ":"")+name+" ("+(where.equals("left")?"बाईं ओर":where.equals("right")?"दाईं ओर":"बीच में")+")");
            else if(language==AssistLanguage.KANNADA)parts.add((n>1?n+" ":"")+name+" ("+(where.equals("left")?"ಎಡಭಾಗದಲ್ಲಿ":where.equals("right")?"ಬಲಭಾಗದಲ್ಲಿ":"ಮಧ್ಯದಲ್ಲಿ")+")");
            else parts.add((n>1?n+" "+plural(name):"a "+name)+(where.equals("centre")?" in the centre":" on the "+where));
            if(parts.size()==5)break;
        }
        if(language==AssistLanguage.HINDI)return "मुझे दिख रहा है: "+String.join(", ",parts)+"।";
        if(language==AssistLanguage.KANNADA)return "ನನಗೆ ಕಾಣುತ್ತಿದೆ: "+String.join(", ",parts)+".";
        if(parts.size()==1)return "I can see "+parts.get(0)+".";
        return "I can see "+String.join(", ",parts.subList(0,parts.size()-1))+" and "+parts.get(parts.size()-1)+".";
    }
    private static String plural(String name){return name.endsWith("s")||name.endsWith("sh")||name.endsWith("ch")?name+"es":name.equals("person")?"people":name+"s";}
    private static String textLine(String t,AssistLanguage l){return l==AssistLanguage.HINDI?"लिखा है: “"+t+"”।":l==AssistLanguage.KANNADA?"ಬರಹ: “"+t+"”.":"Visible text reads: “"+t+"”.";}
    static String nothing(AssistLanguage l){return l==AssistLanguage.HINDI?"मैं यहाँ कोई जानी-पहचानी चीज़ नहीं पहचान सका। पास जाएँ या रोशनी बढ़ाएँ।":l==AssistLanguage.KANNADA?"ಇಲ್ಲಿ ಪರಿಚಿತ ವಸ್ತುಗಳನ್ನು ಗುರುತಿಸಲಾಗಲಿಲ್ಲ. ಹತ್ತಿರ ಹೋಗಿ ಅಥವಾ ಬೆಳಕು ಹೆಚ್ಚಿಸಿ.":"I couldn't recognise specific objects here. Try moving closer or adding light.";}
    static String excerpt(String text){
        if(text==null)return "";
        String s=text.replaceAll("\\s+"," ").trim();
        return s.length()<=120?s:s.substring(0,120)+"…";
    }

    /** Groups by label, ordered by best confidence, ignoring weak detections. */
    static Map<String,List<YoloDetector.Hit>> group(List<YoloDetector.Hit> hits){
        Map<String,List<YoloDetector.Hit>> out=new LinkedHashMap<>();
        if(hits==null)return out;
        List<YoloDetector.Hit> sorted=new ArrayList<>(hits);sorted.sort((a,b)->Float.compare(b.score(),a.score()));
        for(YoloDetector.Hit h:sorted)if(h.score()>=.5f)out.computeIfAbsent(h.label(),k->new ArrayList<>()).add(h);
        return out;
    }
}
