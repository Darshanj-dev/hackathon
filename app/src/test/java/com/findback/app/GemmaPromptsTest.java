package com.findback.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class GemmaPromptsTest {
    @Test public void promptsStayBounded(){
        String big="x".repeat(5000);
        for(String p:new String[]{GemmaPrompts.describe(big,big,big,big),GemmaPrompts.find(big,big),GemmaPrompts.read(big),GemmaPrompts.identifyPlace(big,big),GemmaPrompts.signs(big)})
            assertTrue(p.length()<=GemmaPrompts.MAX_PROMPT);
    }
    @Test public void signPromptCannotClaimArrival(){String p=GemmaPrompts.signs("RESTROOM →");assertTrue(p.contains("Never confirm arrival"));assertTrue(p.contains("direction board"));assertTrue(p.contains("RESTROOM →"));}
    @Test public void describeWithoutQuestionDescribes(){assertTrue(GemmaPrompts.describe("","","",null).startsWith("Describe this view"));}
    @Test public void questionIsAnsweredWithContext(){String p=GemmaPrompts.describe("Is the door open?","door (left)","","There is a door on the left.");assertTrue(p.contains("Question: Is the door open?"));assertTrue(p.contains("previous answer"));assertTrue(p.contains("door (left)"));}
    @Test public void findNamesTarget(){assertTrue(GemmaPrompts.find("car keys","").contains("looking for: car keys"));}
    @Test public void systemPromptSetsLanguageAndSafety(){String s=GemmaPrompts.system(AssistLanguage.KANNADA);assertTrue(s.contains("only in Kannada"));assertTrue(s.contains("Never say it is safe to walk"));}
    @Test public void markdownIsStripped(){assertEquals("A chair on the left.",GemmaAssistant.clean("**A chair** on the left."));}
    @Test public void repetitionLoopIsCut(){
        String kannada="ಫೈಲ್ ಆಪ್ ಅಪ್ಡೇಟ್ ಟು ಟು ಟು ಟು ಟು ಟು ಟು";
        assertEquals("ಫೈಲ್ ಆಪ್ ಅಪ್ಡೇಟ್ ಟು",kannada.substring(0,GemmaAssistant.loopStart(kannada)).trim());
        assertEquals(-1,GemmaAssistant.loopStart("A chair on the left and a bottle in the centre."));
        assertEquals(-1,GemmaAssistant.loopStart("no no no, that is fine"));
    }
}
