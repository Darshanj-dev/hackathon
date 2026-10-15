package com.findback.app;
import org.junit.Test;
import static org.junit.Assert.*;
import com.findback.app.VoiceCommands.Action;
public class VoiceCommandsTest {
    private VoiceCommands.Command parse(String s,AssistMode m){return VoiceCommands.parse(s,m);}
    @Test public void naturalFindExtractsObject(){var c=parse("Find the nearest chair",AssistMode.DESCRIBE);assertEquals(Action.FIND,c.action());assertEquals("chair",c.argument());}
    @Test public void findKeepsObjectsOutsideDetectorClasses(){var c=parse("where are my car keys?",AssistMode.READ);assertEquals(Action.FIND,c.action());assertEquals("car keys",c.argument());}
    @Test public void hindiFindVerbAtEnd(){var c=parse("मेरी कुर्सी ढूंढो",AssistMode.DESCRIBE);assertEquals(Action.FIND,c.action());assertEquals("chair",AssistLanguage.voiceObject(c.argument()));}
    @Test public void sceneQuestionDescribesWithQuestion(){var c=parse("What is in front of me?",AssistMode.FIND);assertEquals(Action.DESCRIBE,c.action());assertEquals("What is in front of me?",c.argument());}
    @Test public void bareDescribeHasNoQuestion(){assertEquals("",parse("describe",AssistMode.READ).argument());}
    @Test public void readCommand(){assertEquals(Action.READ,parse("read this sign",AssistMode.FIND).action());}
    @Test public void placeQuestion(){assertEquals(Action.IDENTIFY_PLACE,parse("where am I",AssistMode.FIND).action());}
    @Test public void washroomBeatsFind(){assertEquals(Action.WASHROOM,parse("where is the washroom",AssistMode.FIND).action());}
    @Test public void stopAndRepeat(){assertEquals(Action.STOP,parse("stop",AssistMode.FIND).action());assertEquals(Action.REPEAT,parse("say that again",AssistMode.FIND).action());}
    @Test public void recallObject(){var c=parse("where did I leave my bottle",AssistMode.DESCRIBE);assertEquals(Action.RECALL,c.action());assertEquals("bottle",c.argument());}
    @Test public void bareWordUsesCurrentMode(){
        assertEquals(Action.FIND,parse("wallet",AssistMode.FIND).action());
        assertEquals("wallet",parse("wallet",AssistMode.FIND).argument());
        assertEquals(Action.READ,parse("this one",AssistMode.READ).action());
        assertEquals(Action.IDENTIFY_PLACE,parse("hello",AssistMode.PLACES).action());
        assertEquals(Action.DESCRIBE,parse("is there anyone here",AssistMode.DESCRIBE).action());
    }
    @Test public void objectPhraseStripsFiller(){assertEquals("umbrella",VoiceCommands.objectPhrase("please find my umbrella for me"));}
}
