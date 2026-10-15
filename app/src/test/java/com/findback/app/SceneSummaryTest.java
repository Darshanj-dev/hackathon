package com.findback.app;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
public class SceneSummaryTest {
    private YoloDetector.Hit hit(String label,float x){return new YoloDetector.Hit(label,.8f,x,.5f,.1f,.1f);}
    @Test public void describesObjectsWithPositions(){
        String s=SceneSummary.describe(List.of(hit("chair",.1f),hit("bottle",.5f),hit("bottle",.6f)),"",AssistLanguage.ENGLISH);
        assertEquals("I can see a chair on the left and 2 bottles in the centre.",s);
    }
    @Test public void includesVisibleText(){assertTrue(SceneSummary.describe(List.of(),"EXIT",AssistLanguage.ENGLISH).contains("“EXIT”"));}
    @Test public void admitsWhenNothingRecognised(){assertTrue(SceneSummary.describe(List.of(),"  ",AssistLanguage.ENGLISH).startsWith("I couldn't recognise"));}
    @Test public void weakDetectionsAreIgnored(){assertTrue(SceneSummary.describe(List.of(new YoloDetector.Hit("dog",.3f,.5f,.5f,.1f,.1f)),"",AssistLanguage.ENGLISH).startsWith("I couldn't"));}
    @Test public void hindiUsesTranslatedNames(){assertTrue(SceneSummary.describe(List.of(hit("chair",.9f)),"",AssistLanguage.HINDI).contains("कुर्सी (दाईं ओर)"));}
    @Test public void placeGuessFromObjects(){
        assertTrue(SceneSummary.place(List.of(hit("refrigerator",.5f)),"",AssistLanguage.ENGLISH).startsWith("This may be a kitchen."));
        assertTrue(SceneSummary.place(List.of(),"RESTROOM",AssistLanguage.ENGLISH).startsWith("This may be a washroom."));
        assertTrue(SceneSummary.place(List.of(),"",AssistLanguage.ENGLISH).startsWith("I couldn't tell"));
    }
    @Test public void hintsAreCompact(){assertEquals("chair (left), 2 bottle (centre)",SceneSummary.hints(List.of(hit("chair",.1f),hit("bottle",.5f),hit("bottle",.4f))));}
}
