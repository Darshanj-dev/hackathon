package com.findback.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class AssistLanguageTest {
    @Test public void hindiVoiceFindsCanonicalObject(){assertEquals("chair",AssistLanguage.voiceObject("मेरी कुर्सी ढूंढो"));}
    @Test public void kannadaVoiceFindsCanonicalObject(){assertEquals("bottle",AssistLanguage.voiceObject("ನನ್ನ ಬಾಟಲಿ ಹುಡುಕಿ"));}
    @Test public void sceneQuestionDoesNotBecomeAnObject(){assertNull(AssistLanguage.voiceObject("What is in front of me?"));}
    @Test public void englishFindRetainsAllDetectorClasses(){assertEquals("umbrella",AssistLanguage.voiceObject("Find my umbrella"));}
    @Test public void localizedTypedObjectMapsToDetectorClass(){assertEquals("bottle",AssistLanguage.canonicalObject("ಬಾಟಲಿ"));}
}
