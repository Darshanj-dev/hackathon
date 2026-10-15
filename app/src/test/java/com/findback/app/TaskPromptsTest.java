package com.findback.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class TaskPromptsTest {
 @Test public void taskInstructionsSurviveRuntimePromptLimit(){for(String mode:new String[]{"find","read","place"}){String prompt=TaskPrompts.build(mode,"x".repeat(1000));assertTrue(prompt.length()<=256);assertEquals(prompt,GemmaPrompts.question(prompt));}}
 @Test public void placeInterpretationCannotClaimArrival(){String prompt=TaskPrompts.build("place","RESTROOM →");assertTrue(prompt.contains("Never confirm arrival"));assertTrue(prompt.contains("direction board"));assertTrue(prompt.contains("RESTROOM →"));}
 @Test public void missingObservationStillAllowsImageInterpretation(){assertTrue(TaskPrompts.build("read",null).contains("Read visible text using the OCR excerpt"));}
}
