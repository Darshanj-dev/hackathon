package com.findback.app;

/** Fixed cues stay independent of language-model latency. */
public enum AssistLanguage {
    ENGLISH("English", "en-IN", "English"), HINDI("हिन्दी", "hi-IN", "Hindi"), KANNADA("ಕನ್ನಡ", "kn-IN", "Kannada");
    public final String label, tag, promptName;
    AssistLanguage(String label,String tag,String promptName){this.label=label;this.tag=tag;this.promptName=promptName;}
    public String objectName(String key){
        String[] english={"bottle","chair","cup","book","cell phone","person","laptop","toilet"};
        String[] hi={"बोतल","कुर्सी","कप","किताब","फ़ोन","व्यक्ति","लैपटॉप","शौचालय"};
        String[] kn={"ಬಾಟಲಿ","ಕುರ್ಚಿ","ಕಪ್","ಪುಸ್ತಕ","ಫೋನ್","ವ್ಯಕ್ತಿ","ಲ್ಯಾಪ್‌ಟಾಪ್","ಶೌಚಾಲಯ"};
        for(int i=0;i<english.length;i++)if(english[i].equals(key))return this==HINDI?hi[i]:this==KANNADA?kn[i]:key;
        return key;
    }
    public String detected(String key,String position){
        String object=objectName(key);
        if(this==HINDI)return object+" कैमरे के "+(position.equals("left")?"बाईं ओर":position.equals("right")?"दाईं ओर":"बीच में")+" दिखाई दे रहा है।";
        if(this==KANNADA)return object+" ಕ್ಯಾಮೆರಾದ "+(position.equals("left")?"ಎಡಭಾಗದಲ್ಲಿ":position.equals("right")?"ಬಲಭಾಗದಲ್ಲಿ":"ಮಧ್ಯದಲ್ಲಿ")+" ಕಾಣುತ್ತಿದೆ.";
        return key+" detected in the "+position+" of the camera view.";
    }
    public String notFound(String key){return this==HINDI?objectName(key)+" इस दृश्य में नहीं मिला। कैमरा धीरे घुमाएँ।":this==KANNADA?objectName(key)+" ಈ ದೃಶ್ಯದಲ್ಲಿ ಕಂಡುಬಂದಿಲ್ಲ. ಕ್ಯಾಮೆರಾವನ್ನು ನಿಧಾನವಾಗಿ ತಿರುಗಿಸಿ.":"No "+key+" detected in this view. Try scanning slowly.";}
    public static String voiceObject(String phrase){
        String s=phrase.toLowerCase(java.util.Locale.ROOT);
        for(String key:new String[]{"cell phone","bottle","chair","cup","book","person","laptop","toilet"})for(AssistLanguage l:values())if(s.contains(l.objectName(key)))return key;
        if(s.contains("phone"))return "cell phone";
        for(String key:YoloDetector.LABELS)if(s.contains(key))return key;
        return null;
    }
    public static String canonicalObject(String value){
        String s=value.toLowerCase(java.util.Locale.ROOT).trim();
        for(AssistLanguage language:values())for(String key:new String[]{"bottle","chair","cup","book","cell phone","person","laptop","toilet"})if(s.equals(language.objectName(key)))return key;
        if(s.equals("phone"))return "cell phone";if(s.equals("sofa"))return "couch";return s;
    }
}
